package com.rewardsiq.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.rewardsiq.common.config.RewardsIqProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * LOCAL DEVELOPMENT ONLY (profile "dev"): mints tokens signed with the dev secret so you can call
 * the API from Bruno/Postman without an identity provider. Never enable in a shared environment.
 */
@RestController
@Profile("dev")
public class DevTokenController {

    private final NimbusJwtEncoder encoder;
    private final String audience;
    private final Clock clock;

    public DevTokenController(RewardsIqProperties props, Clock clock) {
        byte[] key = Base64.getDecoder().decode(props.security().devSecret());
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(key, "HmacSHA256")));
        this.audience = props.security().audience();
        this.clock = clock;
    }

    @PostMapping("/dev/token")
    public Map<String, Object> token(@Valid @RequestBody TokenRequest request) {
        Instant now = clock.instant();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("rewards-iq-dev")
                .subject(request.subject())
                .audience(List.of(audience))
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofHours(8)));
        if (request.airline() != null) {
            claims.claim(CurrentUser.AIRLINE_CLAIM, request.airline());
        }
        if (request.roles() != null) {
            claims.claim(CurrentUser.ROLES_CLAIM, request.roles());
        }
        if (request.scope() != null) {
            claims.claim("scope", request.scope());
        }
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
        return Map.of("accessToken", token, "tokenType", "Bearer", "expiresIn", 28800);
    }

    public record TokenRequest(@NotBlank String subject, @Pattern(regexp = "[A-Z0-9]{2}") String airline,
                               List<String> roles, String scope) {
    }
}
