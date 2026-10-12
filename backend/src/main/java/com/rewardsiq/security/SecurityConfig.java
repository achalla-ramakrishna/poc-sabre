package com.rewardsiq.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewardsiq.common.config.RewardsIqProperties;
import com.rewardsiq.common.error.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

/**
 * Stateless resource server: every request carries a JWT from the identity provider.
 * Coarse URL rules here; role checks per endpoint with @PreAuthorize.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, ObjectMapper mapper) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable) // bearer tokens in headers, no cookies
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/dev/token", "/error").permitAll()
                        // Coarse role gates, checked before the body is read; @PreAuthorize refines per action.
                        .requestMatchers(HttpMethod.POST, "/api/v1/rules/*/approve", "/api/v1/rules/*/reject",
                                "/api/v1/rules/*/retire").hasRole("APPROVER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/rules", "/api/v1/rules/**").hasRole("ANALYST")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/rules/**").hasRole("ANALYST")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(authenticationConverter()))
                        .authenticationEntryPoint((req, res, ex) -> write(res, mapper, HttpStatus.UNAUTHORIZED,
                                "UNAUTHORIZED", "A valid bearer token is required"))
                        .accessDeniedHandler((req, res, ex) -> write(res, mapper, HttpStatus.FORBIDDEN,
                                "FORBIDDEN", "You are not allowed to perform this action")))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> write(res, mapper, HttpStatus.UNAUTHORIZED,
                                "UNAUTHORIZED", "A valid bearer token is required"))
                        .accessDeniedHandler((req, res, ex) -> write(res, mapper, HttpStatus.FORBIDDEN,
                                "FORBIDDEN", "You are not allowed to perform this action")))
                .build();
    }

    /** "roles": ["ANALYST"] becomes ROLE_ANALYST; "scope" keeps the default SCOPE_ prefix. */
    static JwtAuthenticationConverter authenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
            List<String> roles = jwt.getClaimAsStringList(CurrentUser.ROLES_CLAIM);
            if (roles != null) {
                roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
            }
            return authorities;
        });
        return converter;
    }

    /**
     * Dev: HMAC key from rewards.security.dev-secret. Real environments: the IdP's issuer URI
     * (keys fetched from its JWKS endpoint). Both check expiry, issuer (if set) and audience.
     */
    @Bean
    JwtDecoder jwtDecoder(RewardsIqProperties props,
                          @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuer) {
        NimbusJwtDecoder decoder;
        OAuth2TokenValidator<Jwt> base;
        String devSecret = props.security().devSecret();
        if (StringUtils.hasText(devSecret)) {
            byte[] key = java.util.Base64.getDecoder().decode(devSecret);
            decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                    .macAlgorithm(MacAlgorithm.HS256).build();
            base = JwtValidators.createDefault();
        } else if (StringUtils.hasText(issuer)) {
            decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuer);
            base = JwtValidators.createDefaultWithIssuer(issuer);
        } else {
            throw new IllegalStateException(
                    "Configure rewards.security.dev-secret (local only) or spring.security.oauth2.resourceserver.jwt.issuer-uri");
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(base, audience(props.security().audience())));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> audience(String expected) {
        OAuth2Error error = new OAuth2Error("invalid_token", "Token audience must include " + expected, null);
        return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(expected)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(error);
    }

    private static void write(HttpServletResponse res, ObjectMapper mapper, HttpStatus status, String code,
                              String detail) throws IOException {
        ProblemDetail pd = GlobalExceptionHandler.problem(status, code, detail);
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), pd);
    }
}
