package com.rewardsiq.pricing.grpc;

import com.rewardsiq.grpc.ServiceAuthInterceptor;
import com.rewardsiq.grpc.pricing.v1.Offer;
import com.rewardsiq.grpc.pricing.v1.OfferPoints;
import com.rewardsiq.grpc.pricing.v1.PriceOffersRequest;
import com.rewardsiq.grpc.pricing.v1.PriceOffersResponse;
import com.rewardsiq.grpc.pricing.v1.RedemptionPricingGrpc;
import com.rewardsiq.grpc.publish.v1.Cabin;
import com.rewardsiq.pricing.OfferQuery;
import com.rewardsiq.pricing.PointsResult;
import com.rewardsiq.pricing.PricingService;
import com.rewardsiq.pricing.RulesCache.CacheNotReadyException;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * UC-08 / INT-2: DPE asks for points prices during shopping. Answers from memory; respects the
 * caller's deadline. The member balance is never logged (NFR-S6).
 */
@Component
public class RedemptionPricingGrpcService extends RedemptionPricingGrpc.RedemptionPricingImplBase {

    private static final Logger log = LoggerFactory.getLogger(RedemptionPricingGrpcService.class);
    static final int MAX_OFFERS = 50;
    private static final Pattern AIRLINE = Pattern.compile("[A-Z0-9]{2}");
    private static final Pattern AIRPORT = Pattern.compile("[A-Z]{3}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private final PricingService pricing;

    public RedemptionPricingGrpcService(PricingService pricing) {
        this.pricing = pricing;
    }

    @Override
    public void priceOffers(PriceOffersRequest request, StreamObserver<PriceOffersResponse> response) {
        MDC.put("correlationId", ServiceAuthInterceptor.CORRELATION_ID.get());
        try {
            List<OfferQuery> offers = validate(request);
            if (Context.current().isCancelled()) {
                return; // deadline passed while queued; DPE has already fallen back to cash-only
            }
            OptionalLong balance = request.hasMemberBalance()
                    ? OptionalLong.of(request.getMemberBalance()) : OptionalLong.empty();
            List<PointsResult> results = pricing.price(request.getAirline(), offers, balance);
            response.onNext(PriceOffersResponse.newBuilder()
                    .addAllResults(results.stream().map(RedemptionPricingGrpcService::toProto).toList())
                    .build());
            response.onCompleted();
        } catch (IllegalArgumentException ex) {
            response.onError(Status.INVALID_ARGUMENT.withDescription(ex.getMessage()).asRuntimeException());
        } catch (CacheNotReadyException ex) {
            response.onError(Status.UNAVAILABLE.withDescription(ex.getMessage()).asRuntimeException());
        } catch (RuntimeException ex) {
            log.error("PriceOffers failed for {} offer(s)", request.getOffersCount(), ex);
            response.onError(Status.INTERNAL.withDescription("internal error").asRuntimeException());
        } finally {
            MDC.remove("correlationId");
        }
    }

    static List<OfferQuery> validate(PriceOffersRequest request) {
        if (!AIRLINE.matcher(request.getAirline()).matches()) {
            throw new IllegalArgumentException("airline must be a 2-character IATA code");
        }
        if (request.getOffersCount() == 0 || request.getOffersCount() > MAX_OFFERS) {
            throw new IllegalArgumentException("1 to " + MAX_OFFERS + " offers per request");
        }
        if (request.hasMemberBalance() && request.getMemberBalance() < 0) {
            throw new IllegalArgumentException("member_balance must not be negative");
        }
        return request.getOffersList().stream().map(RedemptionPricingGrpcService::toQuery).toList();
    }

    private static OfferQuery toQuery(Offer o) {
        String id = o.getOfferId();
        if (id.isBlank()) {
            throw new IllegalArgumentException("offer_id is required");
        }
        if (!AIRPORT.matcher(o.getOrigin()).matches() || !AIRPORT.matcher(o.getDestination()).matches()) {
            throw new IllegalArgumentException(id + ": origin and destination must be 3-letter IATA codes");
        }
        if (o.getCabin() == Cabin.CABIN_UNSPECIFIED || o.getCabin() == Cabin.UNRECOGNIZED) {
            throw new IllegalArgumentException(id + ": cabin is required");
        }
        if (!CURRENCY.matcher(o.getFare().getCurrency()).matches()) {
            throw new IllegalArgumentException(id + ": fare.currency must be ISO 4217");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(o.getFare().getAmount());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(id + ": fare.amount must be a decimal string");
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException(id + ": fare.amount must be positive");
        }
        return new OfferQuery(id, o.getOrigin(), o.getDestination(),
                com.rewardsiq.rule.Cabin.valueOf(o.getCabin().name()),
                o.getFareBrand(), o.getBundle(), o.getPointOfSale(), amount, o.getFare().getCurrency());
    }

    static OfferPoints toProto(PointsResult r) {
        OfferPoints.Builder b = OfferPoints.newBuilder().setOfferId(r.offerId()).setCashOnly(r.cashOnly());
        if (!r.cashOnly()) {
            b.setPointsRequired(r.pointsRequired())
                    .setPointsUsable(r.pointsUsable())
                    .setRuleId(Objects.requireNonNull(r.ruleId()))
                    .setRuleVersion(r.ruleVersion());
        }
        return b.build();
    }
}
