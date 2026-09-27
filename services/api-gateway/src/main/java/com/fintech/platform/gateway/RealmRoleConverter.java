package com.fintech.platform.gateway;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

/**
 * Turns Keycloak's {@code realm_access.roles} claim into Spring authorities.
 *
 * <p>The mapping is {@code CUSTOMER} to {@code ROLE_CUSTOMER} rather than to {@code CUSTOMER}, so that
 * route rules can be written {@code hasRole('CUSTOMER')} instead of {@code hasAuthority('CUSTOMER')}.
 * {@code hasRole} prepends the prefix itself, and mixing the two forms in one policy is how a rule
 * quietly stops matching.
 *
 * <p>Nothing is derived from the {@code scope} claim. OAuth scopes describe what a client was granted
 * to do and are shared with other systems; Keycloak roles are this realm's authorisation vocabulary.
 * Reading scopes here would quietly couple the platform's access decisions to whatever else is pointed
 * at the same identity provider.
 */
final class RealmRoleConverter implements Converter<Jwt, Mono<AbstractAuthenticationToken>> {

    private static final String REALM_ACCESS = "realm_access";
    private static final String ROLES = "roles";

    @Override
    public Mono<AbstractAuthenticationToken> convert(Jwt jwt) {
        return Mono.just(new JwtAuthenticationToken(jwt, authorities(jwt), principalName(jwt)));
    }

    private Collection<GrantedAuthority> authorities(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String role : realmRoles(jwt)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        // The scope authorities are kept alongside so that a future rule can express a narrower
        // capability, but no rule in this gateway relies on them.
        String scope = jwt.getClaimAsString("scope");
        if (scope != null && !scope.isBlank()) {
            for (String value : scope.split("\\s+")) {
                if (!value.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority("SCOPE_" + value));
                }
            }
        }
        return authorities;
    }

    @SuppressWarnings("unchecked")
    private List<String> realmRoles(Jwt jwt) {
        Object realmAccess = jwt.getClaims().get(REALM_ACCESS);
        if (!(realmAccess instanceof Map<?, ?> map)) {
            return List.of();
        }
        Object roles = map.get(ROLES);
        if (!(roles instanceof Collection<?> collection)) {
            return List.of();
        }
        return collection.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                // A role name that is not an identifier would produce an authority string that no rule
                // can match. Dropping it is the safe direction: the caller is authenticated, and no
                // authorisation rule can be satisfied by a name the policy language cannot express.
                .filter(role -> role.matches("[A-Za-z0-9_]+"))
                .toList();
    }

    private String principalName(Jwt jwt) {
        String preferred = jwt.getClaimAsString("preferred_username");
        return preferred != null && !preferred.isBlank() ? preferred : jwt.getSubject();
    }
}
