package com.rewardsiq.security;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The signed-in user. The airline (tenant) always comes from the token, never from the request body,
 * so one airline's analysts can never read or change another airline's rules.
 */
public record CurrentUser(String subject, String airline, Set<Role> roles) {

    public static final String AIRLINE_CLAIM = "airline";
    public static final String ROLES_CLAIM = "roles";

    public static CurrentUser from(Jwt jwt) {
        String airline = jwt.getClaimAsString(AIRLINE_CLAIM);
        if (airline == null || !airline.matches("[A-Z0-9]{2}")) {
            throw new IllegalArgumentException("Token has no valid airline claim");
        }
        List<String> raw = jwt.getClaimAsStringList(ROLES_CLAIM);
        Set<Role> roles = raw == null ? Set.of() : raw.stream()
                .filter(r -> Set.of("VIEWER", "ANALYST", "APPROVER", "ADMIN").contains(r))
                .map(Role::valueOf)
                .collect(Collectors.toUnmodifiableSet());
        return new CurrentUser(jwt.getSubject(), airline, roles);
    }
}
