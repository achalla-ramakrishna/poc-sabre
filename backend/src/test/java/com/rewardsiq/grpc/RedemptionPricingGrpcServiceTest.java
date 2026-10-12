package com.rewardsiq.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.rewardsiq.TestRules;
import com.rewardsiq.common.config.RewardsIqProperties;
import com.rewardsiq.grpc.pricing.v1.Money;
import com.rewardsiq.grpc.pricing.v1.Offer;
import com.rewardsiq.grpc.pricing.v1.PriceOffersRequest;
import com.rewardsiq.grpc.pricing.v1.PriceOffersResponse;
import com.rewardsiq.grpc.pricing.v1.RedemptionPricingGrpc;
import com.rewardsiq.grpc.publish.v1.Cabin;
import com.rewardsiq.pricing.PointsCalculator;
import com.rewardsiq.pricing.PricingService;
import com.rewardsiq.pricing.RuleIndex;
import com.rewardsiq.pricing.RulesCache;
import com.rewardsiq.pricing.grpc.RedemptionPricingGrpcService;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** INT-2 end to end over a real gRPC stack (in-process): auth interceptor, mapping, BR results. */
class RedemptionPricingGrpcServiceTest {

    private static final byte[] KEY = Base64.getDecoder().decode("cmV3YXJkcy1pcS1sb2NhbC1kZXYtb25seS1zZWNyZXQtMzI=");
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void start() throws Exception {
        RulesCache cache = mock(RulesCache.class);
        when(cache.current()).thenReturn(RuleIndex.of(List.of(
                TestRules.published("rul_01JA3K7Q2M", TestRules.sydPerEconomyChoice()))));
        var pricing = new PricingService(cache, new PointsCalculator(),
                Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC));
        var props = new RewardsIqProperties(null, null, null, null, null,
                new RewardsIqProperties.Security(null, "rewards-iq", "rewardsiq.pricing"));
        var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(KEY, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(new RedemptionPricingGrpcService(pricing),
                        new ServiceAuthInterceptor(decoder, props)))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private static String token(String scope) {
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(KEY, "HmacSHA256")));
        var claims = JwtClaimsSet.builder().subject("dpe-service").claim("scope", scope)
                .expiresAt(Instant.now().plusSeconds(300)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private RedemptionPricingGrpc.RedemptionPricingBlockingStub stub(String bearer) {
        Metadata h = new Metadata();
        if (bearer != null) {
            h.put(ServiceAuthInterceptor.AUTHORIZATION, "Bearer " + bearer);
        }
        return RedemptionPricingGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(h));
    }

    private static Offer offer(String id, Cabin cabin, String amount) {
        return Offer.newBuilder().setOfferId(id).setOrigin("SYD").setDestination("PER").setTravelDate("2026-11-14")
                .setCabin(cabin).setFareBrand(cabin == Cabin.ECONOMY ? "CHOICE" : "BUSINESS").setPointOfSale("AU")
                .setFare(Money.newBuilder().setAmount(amount).setCurrency("AUD")).build();
    }

    @Test
    void prices_the_spec_example_offers() {
        PriceOffersResponse response = stub(token("rewardsiq.pricing")).priceOffers(PriceOffersRequest.newBuilder()
                .setAirline("VA").setMemberBalance(100_000)
                .addOffers(offer("of-1", Cabin.ECONOMY, "900.00"))
                .addOffers(offer("of-2", Cabin.ECONOMY, "1800.00"))
                .addOffers(offer("of-3", Cabin.BUSINESS, "2450.00"))
                .build());

        assertThat(response.getResults(0).getPointsRequired()).isEqualTo(43_100);
        assertThat(response.getResults(0).getPointsUsable()).isEqualTo(43_100);
        assertThat(response.getResults(0).getRuleId()).isEqualTo("rul_01JA3K7Q2M");
        assertThat(response.getResults(1).getPointsRequired()).isEqualTo(86_100);
        assertThat(response.getResults(1).getPointsUsable()).isEqualTo(70_000);
        assertThat(response.getResults(2).getCashOnly()).isTrue();
    }

    @Test
    void missing_token_is_unauthenticated_and_wrong_scope_is_denied() {
        var request = PriceOffersRequest.newBuilder().setAirline("VA").addOffers(offer("of-1", Cabin.ECONOMY, "1")).build();

        assertThatThrownBy(() -> stub(null).priceOffers(request))
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThatThrownBy(() -> stub(token("something.else")).priceOffers(request))
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    void malformed_offers_are_invalid_argument() {
        var request = PriceOffersRequest.newBuilder().setAirline("VA")
                .addOffers(offer("of-1", Cabin.ECONOMY, "nine hundred")).build();

        assertThatThrownBy(() -> stub(token("rewardsiq.pricing")).priceOffers(request))
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
}
