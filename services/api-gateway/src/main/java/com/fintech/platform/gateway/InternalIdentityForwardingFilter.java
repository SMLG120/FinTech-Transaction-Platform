package com.fintech.platform.gateway;

import com.fintech.platform.common.correlation.CorrelationId;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.common.identity.InternalIdentityCodec;
import com.fintech.platform.common.identity.InternalIdentityProperties;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Turns a verified token into the signed identity headers a downstream service trusts, and removes any
 * copy the client tried to supply.
 *
 * <p>The strip is not a defensive extra. Verifying the signature on the service side only means
 * something if the gateway is the only writer: if a client's {@code X-Internal-Identity-Roles} survived
 * and the gateway added its own, the service would receive two values for one header and which one wins
 * would depend on how the parsing library joined them. Some concatenate. So the client's copy is
 * removed unconditionally and the gateway's own is written afterwards, in the same header map.
 *
 * <p>Both happen inside one {@link org.springframework.web.server.ServerHttpRequestDecorator} because a
 * {@code ServerHttpRequest}'s own header map is read-only, and because doing strip-then-sign as two
 * passes would leave a window in which a mutation could be observed by another filter.
 */
@Component
class InternalIdentityForwardingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(InternalIdentityForwardingFilter.class);

    private static final String IDENTITY_HEADER_PREFIX = "x-internal-identity-";
    private static final String ROLE_PREFIX = "ROLE_";

    private final InternalIdentityCodec codec;
    private final Clock clock;

    InternalIdentityForwardingFilter(InternalIdentityProperties properties, Clock clock) {
        this.codec = properties.toCodec(clock);
        this.clock = clock;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Read the authentication before the request proceeds, and mutate before it is dispatched. Doing
        // the signing afterwards would decorate a request that has already been sent.
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated)
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(authentication -> sign(exchange, authentication))
                .defaultIfEmpty(strip(exchange))
                .flatMap(chain::filter);
    }

    private ServerWebExchange sign(ServerWebExchange exchange, JwtAuthenticationToken authentication) {
        Jwt jwt = authentication.getToken();
        Map<String, String> signed;
        try {
            signed = codec.headersFor(new InternalIdentity(
                    jwt.getSubject(),
                    principalName(jwt),
                    roles(authentication),
                    correlationId(exchange),
                    clock.instant()));
        } catch (RuntimeException e) {
            // A username or role that cannot be represented is never forwarded as a partial header set.
            // A service would reject it anyway, and a half-signed identity in a packet capture is more
            // misleading than an absent one.
            log.error("could not sign internal identity for subject {}: {}", jwt.getSubject(), e.getMessage());
            return strip(exchange);
        }
        // Strip first, then sign, in one pass over the same header map. Signing alone would overwrite
        // the six headers this codec happens to use and let everything else the client sent through, so
        // a header added to the codec later would be unverified on its first day in production.
        return rebuild(exchange, headers -> {
            removeIdentityHeaders(headers);
            signed.forEach((name, value) -> headers.set(name, value));
        });
    }

    /** Removes any client-supplied identity header. Correct behaviour for unauthenticated traffic too. */
    private static ServerWebExchange strip(ServerWebExchange exchange) {
        return rebuild(exchange, headers -> removeIdentityHeaders(headers));
    }

    private static void removeIdentityHeaders(HttpHeaders headers) {
        headers.keySet().removeIf(name -> name.toLowerCase(Locale.ROOT).startsWith(IDENTITY_HEADER_PREFIX));
    }

    private static ServerWebExchange rebuild(
            ServerWebExchange exchange, java.util.function.Consumer<HttpHeaders> mutate) {
        return exchange.mutate()
                .request(new SignedIdentityRequestDecorator(exchange.getRequest(), mutate))
                .build();
    }

    private List<String> roles(JwtAuthenticationToken authentication) {
        return authentication.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .filter(role -> role.matches("[A-Z][A-Z0-9_]*"))
                .toList();
    }

    private static String principalName(Jwt jwt) {
        String preferred = jwt.getClaimAsString("preferred_username");
        return preferred != null && !preferred.isBlank() ? preferred : jwt.getSubject();
    }

    private static String correlationId(ServerWebExchange exchange) {
        Object attribute = exchange.getAttributes().get(CorrelationId.REQUEST_ATTRIBUTE);
        if (attribute instanceof String id && CorrelationId.isAcceptable(id)) {
            return id;
        }
        String header = exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER);
        return header != null && CorrelationId.isAcceptable(header) ? header : CorrelationId.generate();
    }

    /**
     * Applies the header mutation once, lazily, and caches the result.
     *
     * <p>The mutation is not idempotent in general: applying {@code putAll} twice is harmless, but a
     * future caller who appends rather than replaces would corrupt the signature. Building the map a
     * single time makes "exactly once" a property of this class instead of a property of how many times
     * the container felt like asking.
     */
    private static final class SignedIdentityRequestDecorator
            extends org.springframework.http.server.reactive.ServerHttpRequestDecorator {

        private final java.util.function.Consumer<HttpHeaders> mutate;
        private volatile HttpHeaders headers;

        private SignedIdentityRequestDecorator(
                org.springframework.http.server.reactive.ServerHttpRequest delegate,
                java.util.function.Consumer<HttpHeaders> mutate) {
            super(delegate);
            this.mutate = mutate;
        }

        @Override
        public HttpHeaders getHeaders() {
            HttpHeaders result = headers;
            if (result == null) {
                synchronized (this) {
                    result = headers;
                    if (result == null) {
                        // A copy, mutated, then re-wrapped. Mutating the read-only view the caller holds
                        // would throw, and mutating the delegate's own map would edit the container's
                        // state, which is how a filter ends up corrupting an unrelated request.
                        HttpHeaders copy = new HttpHeaders();
                        copy.putAll(getDelegate().getHeaders());
                        mutate.accept(copy);
                        result = HttpHeaders.readOnlyHttpHeaders(copy);
                        headers = result;
                    }
                }
            }
            return result;
        }
    }

    /** After the security filters that establish authentication, before routing dispatches downstream. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 100;
    }
}
