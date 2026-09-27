package com.fintech.platform.common.identity;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for verifying the gateway's signed identity headers.
 *
 * <p>Lives in platform-common rather than platform-common-web because both ends of the contract read
 * it: the gateway signs with it and a servlet service verifies with it. Two definitions of the same
 * property name in two modules would drift, and a drifted signing-key property fails as a 401 on every
 * request with nothing in the logs to point at the cause.
 *
 * <p>There is deliberately no {@code enabled} flag. One was here, and it was a fail-open waiting to
 * happen: a boolean property that nobody sets binds to {@code false}, so a service configured with a
 * correct signing key and no explicit flag came up with verification silently switched off. The
 * presence of a signing key is now the only switch, which means a service either verifies identities or
 * has no way to claim that it does.
 *
 * @param signingKey hex-encoded HMAC key, shared with the gateway
 * @param maxAgeSeconds how long a signed identity remains acceptable
 * @param excludedPaths request paths that skip verification, for read-only operational endpoints
 */
@ConfigurationProperties(prefix = InternalIdentityProperties.PROPERTY_PREFIX)
public record InternalIdentityProperties(String signingKey, long maxAgeSeconds, List<String> excludedPaths) {

    /**
     * The prefix both this record and {@link OnInternalIdentityKeyPresentCondition} resolve against.
     * Declared here so the two cannot drift into a condition that watches a name nothing sets.
     */
    public static final String PROPERTY_PREFIX = "platform.security.internal-identity";

    /**
     * The paths that skip verification, all of them read-only operational endpoints.
     *
     * <p>Prometheus is here for the same reason it is permitted at the gateway: a scrape client cannot
     * present a token, and excluding it would leave every service unmonitorable while the dashboards
     * kept looking healthy. The remaining actuator endpoints are not exempt, so {@code /actuator/env},
     * {@code /actuator/heapdump} and {@code /actuator/loggers} still require a verified identity.
     *
     * <p>This is a real reduction in coverage, bounded by the fact that these three endpoints disclose
     * only liveness and metric names, and by the network restriction in {@code infrastructure/kubernetes}
     * that keeps the management port off anything but the cluster's monitoring namespace.
     */
    public static final List<String> DEFAULT_EXCLUDED_PATHS =
            List.of("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus");

    public InternalIdentityProperties {
        if (maxAgeSeconds <= 0) {
            maxAgeSeconds = InternalIdentityCodec.DEFAULT_MAX_AGE.toSeconds();
        }
        if (excludedPaths == null) {
            excludedPaths = DEFAULT_EXCLUDED_PATHS;
        }
    }

    public boolean isConfigured() {
        return signingKey != null && !signingKey.isBlank();
    }

    public InternalIdentityCodec toCodec(Clock clock) {
        return InternalIdentityCodec.fromHexKey(signingKey, Duration.ofSeconds(maxAgeSeconds), clock);
    }
}
