package com.fintech.platform.gateway;

import com.fintech.platform.common.identity.InternalIdentityProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import reactor.core.publisher.Mono;

/**
 * The platform's authentication and authorisation boundary.
 *
 * <p>Phase 2 replaces the {@code denyAll()} of Phase 1 with real token verification. Four decisions
 * here are the ones worth arguing about:
 *
 * <ol>
 *   <li><b>Issuer and JWKS address are configured separately.</b> The issuer in a token is the URL the
 *       browser used, {@code http://localhost:8180/realms/fintech}, and that is what gets validated.
 *       Inside the compose network the gateway cannot reach that URL by name, so keys are fetched from
 *       {@code http://keycloak:8180}. Using {@code issuer-uri} alone would derive both from one value
 *       and fail at startup inside the container.
 *   <li><b>RS256 only.</b> A JWT's {@code alg} header is attacker-controlled, and the classic
 *       {@code none} and RS256-to-HS256 confusion attacks both work if the decoder is allowed to choose.
 *       Pinning the algorithm means the key type is not negotiable.
 *   <li><b>Audience is required.</b> Without it, a token minted for any other client in the same realm
 *       is accepted here, because a valid signature only proves who signed it, not what it was for.
 *   <li><b>Authorisation is by path and role, and denies by default.</b> See {@link #apiIngressChain}.
 * </ol>
 */
@Configuration
@EnableWebFluxSecurity
// KeycloakProperties is also reachable through @ConfigurationPropertiesScan on the application class;
// both are listed so that adding a second @Configuration class here cannot silently unbind it.
@EnableConfigurationProperties({KeycloakProperties.class, InternalIdentityProperties.class})
class GatewaySecurityConfiguration {

    /**
     * Injected rather than calling {@code Instant.now()} so the identity lifetime and the verification
     * window are testable at all; the signing side and the verifying side share one clock definition.
     */
    @Bean
    Clock platformClock() {
        return Clock.systemUTC();
    }

    /** Unauthenticated because Prometheus and Docker cannot present a token. */
    private static final String[] OPERATIONAL_ENDPOINTS = {
        "/actuator/health", "/actuator/health/**", "/actuator/prometheus"
    };

    /** Path prefixes and the roles that may call them. Deliberately the whole of the policy. */
    private static final String PLATFORM_ADMIN_PATH = "/api/admin/**";

    private static final String SUPPORT_PATH = "/api/support/**";
    private static final String COMPLIANCE_PATH = "/api/compliance/**";
    private static final String AUDIT_PATH = "/api/audit/**";
    private static final String FRAUD_PATH = "/api/v1/fraud/**";
    private static final String SETTLEMENT_PATH = "/api/v1/settlement/**";
    private static final String NOTIFICATION_PATH = "/api/v1/notifications/**";
    private static final String DISPUTE_PATH = "/api/v1/disputes/**";
    private static final String ANY_AUTHENTICATED_PATH = "/api/**";

    private static final String ROLE_PLATFORM_ADMIN = "PLATFORM_ADMIN";
    private static final String ROLE_SUPPORT_AGENT = "SUPPORT_AGENT";
    private static final String ROLE_COMPLIANCE_OFFICER = "COMPLIANCE_OFFICER";
    private static final String ROLE_AUDITOR = "AUDITOR";
    private static final String ROLE_FRAUD_ANALYST = "FRAUD_ANALYST";
    private static final String ROLE_SETTLEMENT_OPERATOR = "SETTLEMENT_OPERATOR";
    private static final String ROLE_CUSTOMER = "CUSTOMER";

    private static final String UNAUTHORIZED_BODY = """
      {"error":{"code":"AUTHENTICATION_REQUIRED","message":"A valid bearer token is required."}}""";

    private static final String FORBIDDEN_BODY = """
      {"error":{"code":"INSUFFICIENT_ROLE","message":"Your roles do not permit this operation."}}""";

    /**
     * Builds the decoder by hand rather than letting {@code issuer-uri} do it, because the two
     * addresses genuinely differ inside Compose: the issuer is what a client's token says, while the
     * JWKS endpoint is where this container can actually reach Keycloak.
     */
    @Bean
    ReactiveJwtDecoder jwtDecoder(KeycloakProperties properties) {
        // Reactive, not blocking: the gateway runs on Netty event loops, and the blocking JwtDecoder
        // would park an event-loop thread on a JWKS fetch. Under a burst of uncached tokens that is the
        // difference between degrading and refusing connections.
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();

        decoder.setJwtValidator(jwtValidators(properties));
        return decoder;
    }

    /**
     * Everything a token must satisfy, assembled in one place.
     *
     * <p>Package-private rather than private so the test can apply this exact validator set to a
     * decoder built from a local key pair. The seam is only the source of the keys, never the checks:
     * a test that assembled its own validators would pass against a chain that had quietly stopped
     * validating the audience, which is exactly the regression worth catching.
     */
    static DelegatingOAuth2TokenValidator<Jwt> jwtValidators(KeycloakProperties properties) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()), audienceValidator(properties));
    }

    /**
     * Checks {@code aud}, which is a list in Keycloak even when only one client is registered, and
     * which some issuers send as a bare string. Accepting both is necessary; accepting an absent
     * audience is not.
     */
    private static OAuth2TokenValidator<Jwt> audienceValidator(KeycloakProperties properties) {
        return jwt -> {
            List<String> audiences = jwt.getAudience();
            if (audiences == null || audiences.isEmpty()) {
                return OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "The token carries no audience.", null));
            }
            boolean expected = audiences.stream().anyMatch(properties.audiences()::contains);
            return expected
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                            "invalid_token",
                            "The token audience " + audiences + " does not include " + properties.audience() + ".",
                            null));
        };
    }

    /**
     * A bean rather than a bare new, so the same instance backs both the security chain and the tests
     * that assert how a claim becomes an authority.
     */
    @Bean
    Converter<Jwt, Mono<AbstractAuthenticationToken>> realmRoleConverter() {
        return new RealmRoleConverter();
    }

    @Bean
    SecurityWebFilterChain apiIngressChain(ServerHttpSecurity http) {
        return http
                // A gateway is a stateless API ingress: no cookie or session is issued and a browser never
                // presents ambient credentials, so there is no CSRF vector to protect against.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        // Preflights carry no credentials by specification — the browser sends
                        // Origin and Access-Control-Request-Method and nothing else — so there is
                        // no identity to verify and nothing to deny. Refusing them breaks every
                        // browser caller without adding security: the actual request that follows
                        // is still authenticated below. This rule is first because it must be.
                        .pathMatchers(HttpMethod.OPTIONS, "/**")
                        .permitAll()
                        .pathMatchers(HttpMethod.POST, "/register")
                        .permitAll()
                        .pathMatchers(OPERATIONAL_ENDPOINTS)
                        .permitAll()

                        // Order matters. The admin and support prefixes are also matched by /api/**, so
                        // they have to be decided first; a rule placed after the catch-all is dead
                        // configuration that still reads as if it were doing something.
                        //
                        // PLATFORM_ADMIN appears on every path. An administrator who cannot read the
                        // audit trail cannot support it, and a single explicit role list per path is
                        // cheaper to review than an implicit inheritance rule.
                        .pathMatchers(PLATFORM_ADMIN_PATH)
                        .hasRole(ROLE_PLATFORM_ADMIN)
                        .pathMatchers(SUPPORT_PATH)
                        .hasAnyRole(ROLE_SUPPORT_AGENT, ROLE_PLATFORM_ADMIN)
                        .pathMatchers(COMPLIANCE_PATH)
                        .hasAnyRole(ROLE_COMPLIANCE_OFFICER, ROLE_PLATFORM_ADMIN)
                        .pathMatchers(AUDIT_PATH)
                        .hasAnyRole(ROLE_AUDITOR, ROLE_COMPLIANCE_OFFICER, ROLE_PLATFORM_ADMIN)

                        // The fraud engine. Listed here even though fraud-service enforces the same rule,
                        // because the catch-all below admits CUSTOMER and SUPPORT_AGENT, and without this
                        // line the gateway would cheerfully forward a customer's token to a fraud endpoint
                        // and let the service be the only thing that noticed. Two layers is not
                        // redundancy for its own sake: the gateway is the layer whose job is to keep the
                        // staff surface off the customer one.
                        //
                        // SUPPORT_AGENT is deliberately absent, as it is from the service's actor set. An
                        // agent who can see that a payment was flagged learns the fraud model, which is
                        // the same reason an auditor cannot claim an alert.
                        .pathMatchers(FRAUD_PATH)
                        .hasAnyRole(ROLE_FRAUD_ANALYST, ROLE_COMPLIANCE_OFFICER, ROLE_AUDITOR, ROLE_PLATFORM_ADMIN)

                        // Settlement, split by method because a statement and the decision to close a
                        // period are not the same request. Reading a statement is how an auditor does
                        // their job; closing one is an act with financial consequences, and the reader
                        // roles are kept off it for the same reason they are kept off the fraud queue --
                        // an auditor who can close a period can stop being one, which is a conflict of
                        // interest rather than a permission.
                        //
                        // Two rules rather than a role hierarchy because the two sets are not nested:
                        // COMPLIANCE_OFFICER may read a statement without appearing anywhere near
                        // declaring an actual against it.
                        //
                        // The GET rule must precede the write rule. Both match the same path, and the
                        // first match wins, so a write rule placed above it would make every read 403.
                        .pathMatchers(HttpMethod.GET, SETTLEMENT_PATH)
                        .hasAnyRole(
                                ROLE_SETTLEMENT_OPERATOR, ROLE_COMPLIANCE_OFFICER, ROLE_AUDITOR, ROLE_PLATFORM_ADMIN)
                        .pathMatchers(SETTLEMENT_PATH)
                        .hasAnyRole(ROLE_SETTLEMENT_OPERATOR, ROLE_PLATFORM_ADMIN)

                        // The delivery log. One role set for reads and retries, because answering "was
                        // the customer told" and "tell them again" are the same support job: a role
                        // that may see a failed delivery but may not retry it is a queue that fills
                        // and nobody empties. No customer access, for the stronger reason stated on the
                        // route: this service cannot scope a notification to its caller, so a
                        // per-customer rule would be enforced by nothing.
                        .pathMatchers(NOTIFICATION_PATH)
                        .hasAnyRole(ROLE_SUPPORT_AGENT, ROLE_PLATFORM_ADMIN)

                        // The chargeback workflow. Listed even though the catch-all below admits the
                        // same three roles, because "admitted by the catch-all" is an accident of
                        // ordering rather than a decision: if the catch-all ever narrowed, disputes
                        // would silently close to customers with no line in this file saying they
                        // should be open. The ownership inside — opener vs staff — lives in the
                        // service, which is the only place that can see whose case an id names.
                        .pathMatchers(DISPUTE_PATH)
                        .hasAnyRole(ROLE_CUSTOMER, ROLE_SUPPORT_AGENT, ROLE_PLATFORM_ADMIN)

                        // The customer surface: callers who act on a customer's behalf. Audit and
                        // compliance are deliberately absent — they read the record, they do not operate
                        // the account. Listing them here is a one-word change that would let a read-only
                        // role initiate a transfer, and the matrix in GatewayAuthorisationTest is what
                        // makes that visible.
                        .pathMatchers(ANY_AUTHENTICATED_PATH)
                        .hasAnyRole(ROLE_CUSTOMER, ROLE_SUPPORT_AGENT, ROLE_PLATFORM_ADMIN)

                        // Anything not named above is refused, including paths no service implements
                        // yet. A new route therefore fails closed until someone writes down who may call it.
                        .anyExchange()
                        .denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(realmRoleConverter()))
                        .authenticationEntryPoint(entryPoint(UNAUTHORIZED_BODY, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler(deniedHandler(FORBIDDEN_BODY)))
                // Without an entry point, Spring Security answers 403, which claims the caller was
                // authenticated and simply not allowed. A missing token is a 401, and the distinction is
                // what tells a client to obtain a token instead of to give up.
                .exceptionHandling(handling -> handling.authenticationEntryPoint(
                                entryPoint(UNAUTHORIZED_BODY, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler(deniedHandler(FORBIDDEN_BODY)))
                .build();
    }

    private static ServerAuthenticationEntryPoint entryPoint(String body, HttpStatus status) {
        return (exchange, ex) -> writeJson(exchange.getResponse(), body, status);
    }

    private static ServerAccessDeniedHandler deniedHandler(String body) {
        return (exchange, ex) -> writeJson(exchange.getResponse(), body, HttpStatus.FORBIDDEN);
    }

    private static Mono<Void> writeJson(ServerHttpResponse response, String body, HttpStatus status) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }
}
