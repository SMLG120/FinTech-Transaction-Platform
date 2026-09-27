package com.fintech.platform.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * How a Keycloak claim becomes an authority the route policy can match.
 *
 * <p>Getting this wrong fails in one of two ways, and both are quiet. If roles are not mapped, every
 * request is refused and the cause is a missing header somewhere. If roles are mapped with the wrong
 * prefix, a rule written {@code hasRole("CUSTOMER")} matches nothing and the refusal looks identical.
 */
class RealmRoleConverterTest {

    private final RealmRoleConverter converter = new RealmRoleConverter();

    private JwtAuthenticationToken convert(Jwt jwt) {
        return (JwtAuthenticationToken) converter.convert(jwt).block();
    }

    private static Jwt jwtWith(Map<String, Object> claims) {
        return new Jwt(
                "token-value",
                java.time.Instant.now(),
                java.time.Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"),
                claims);
    }

    private static Jwt jwtForRoles(String... roles) {
        return jwtWith(Map.of(
                "sub", "8f14e45f-ea0c-4f2b-9a1d-1234567890ab",
                "preferred_username", "customer@fintech.test",
                "realm_access", Map.of("roles", List.of(roles))));
    }

    @Test
    @DisplayName("maps every realm role to a ROLE_ authority")
    void maps_realm_roles_to_authorities() {
        var authorities = convert(jwtForRoles("CUSTOMER", "SUPPORT_AGENT")).getAuthorities();

        assertThat(authorities)
                .extracting(org.springframework.security.core.GrantedAuthority::getAuthority)
                .contains("ROLE_CUSTOMER", "ROLE_SUPPORT_AGENT");
    }

    @Test
    @DisplayName("uses the username as the principal so audit rows are readable")
    void prefers_the_username_over_the_opaque_subject() {
        var token = convert(jwtForRoles("CUSTOMER"));

        assertThat(token.getName()).isEqualTo("customer@fintech.test");
    }

    @Test
    @DisplayName("falls back to the subject when there is no username claim")
    void falls_back_to_the_subject() {
        Jwt jwt = jwtWith(Map.of("sub", "8f14e45f-ea0c-4f2b-9a1d-1234567890ab"));

        assertThat(convert(jwt).getName()).isEqualTo("8f14e45f-ea0c-4f2b-9a1d-1234567890ab");
    }

    @Test
    @DisplayName("a token with no realm_access yields no role authorities")
    void tolerates_a_token_without_realm_access() {
        // This is the shape the realm produced when its default client scopes were misconfigured, and
        // the gateway must authenticate such a token while authorising nothing on it.
        Jwt jwt = jwtWith(Map.of("sub", "sub", "preferred_username", "someone@example.test"));

        assertThat(convert(jwt).getAuthorities()).isEmpty();
    }

    @Test
    @DisplayName("a role name the policy language cannot express is dropped, not passed through")
    void drops_a_role_that_cannot_become_an_authority() {
        Jwt jwt = jwtWith(
                Map.of("sub", "sub", "realm_access", Map.of("roles", List.of("CUSTOMER", "has space", "semi;colon"))));

        assertThat(convert(jwt).getAuthorities())
                .extracting(org.springframework.security.core.GrantedAuthority::getAuthority)
                .containsExactly("ROLE_CUSTOMER");
    }

    @Test
    @DisplayName("realm_access of an unexpected type yields no roles instead of throwing")
    void tolerates_a_malformed_realm_access_claim() {
        // An issuer is a third party. A claim that is a string where an object was expected must
        // produce a caller with no roles, not a 500 that tells them to retry.
        Jwt jwt = jwtWith(Map.of("sub", "sub", "realm_access", "CUSTOMER"));

        assertThat(convert(jwt).getAuthorities()).isEmpty();
    }

    @Test
    @DisplayName("scope is not turned into a role")
    void does_not_promote_scopes_to_roles() {
        // A token whose scope says admin must not become an administrator here. Coupling authorisation
        // to scopes would let any client in the realm grant itself a role by asking for a scope.
        Jwt jwt = jwtWith(Map.of(
                "sub", "sub",
                "scope", "email profile admin",
                "realm_access", Map.of("roles", List.of("CUSTOMER"))));

        var authorities = convert(jwt).getAuthorities();

        assertThat(authorities)
                .extracting(org.springframework.security.core.GrantedAuthority::getAuthority)
                .contains("ROLE_CUSTOMER", "SCOPE_admin")
                .doesNotContain("ROLE_admin");
    }

    @ParameterizedTest(name = "{0} becomes ROLE_{0}")
    @CsvSource({"PLATFORM_ADMIN", "CUSTOMER", "SUPPORT_AGENT", "COMPLIANCE_OFFICER", "AUDITOR"})
    @DisplayName("every role the realm defines is expressible as an authority")
    void maps_the_platform_role_vocabulary(String role) {
        assertThat(convert(jwtForRoles(role)).getAuthorities())
                .extracting(org.springframework.security.core.GrantedAuthority::getAuthority)
                .containsExactly("ROLE_" + role);
    }

    @Test
    @DisplayName("a converted token is authenticated, which is what the forwarding filter checks")
    void produces_an_authenticated_token() {
        JwtAuthenticationToken token = convert(jwtForRoles("CUSTOMER"));

        assertThat(token.isAuthenticated()).isTrue();
        assertThat(token.getToken().getSubject()).isEqualTo("8f14e45f-ea0c-4f2b-9a1d-1234567890ab");
    }

    @Test
    @DisplayName("an empty role list is a valid authenticated token")
    void tolerates_an_empty_role_list() {
        Jwt jwt = jwtWith(Map.of("sub", "sub", "realm_access", Map.of("roles", List.of())));

        var token = convert(jwt);
        assertThat(token.isAuthenticated()).isTrue();
        assertThat(token.getAuthorities()).isEmpty();
    }

    @Test
    @DisplayName("the header names a downstream service looks for are the ones the platform defines")
    void header_names_are_stable() {
        // A rename here without a matching change in every service would turn every authorised request
        // into an unauthenticated one, so the contract is asserted rather than left to convention.
        assertThat(headersOf(com.fintech.platform.common.identity.InternalIdentityCodec.class))
                .containsExactlyInAnyOrder(
                        "X-Internal-Identity-Subject",
                        "X-Internal-Identity-Username",
                        "X-Internal-Identity-Roles",
                        "X-Internal-Identity-Correlation-Id",
                        "X-Internal-Identity-Issued-At",
                        "X-Internal-Identity-Signature");
    }

    private static List<String> headersOf(Class<?> type) {
        return java.util.Arrays.stream(type.getDeclaredFields())
                .filter(field -> field.getName().startsWith("HEADER_"))
                .map(field -> {
                    try {
                        return (String) field.get(null);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toList();
    }
}
