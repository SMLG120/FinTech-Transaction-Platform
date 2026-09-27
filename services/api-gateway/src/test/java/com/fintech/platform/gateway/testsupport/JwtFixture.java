package com.fintech.platform.gateway.testsupport;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mints genuinely signed RS256 tokens for the gateway tests.
 *
 * <p>The tokens are real. That is the point: the security chain under test runs the same issuer,
 * audience and lifetime validators it runs in production, and it is the only way an assertion about
 * them means anything. A stubbed decoder would let a chain that accepts every token pass its own tests.
 */
public final class JwtFixture {

    /** Matches platform.keycloak.issuer in the test context. */
    public static final String ISSUER = "http://localhost:8180/realms/fintech";

    private static final long LIFETIME_SECONDS = 300;

    private final KeyPair keyPair;

    private JwtFixture(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public static JwtFixture create() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            // 2048 matches what Keycloak publishes. A shorter key would make the test fast and would
            // also stop exercising the signature path that costs real deployments their latency budget.
            generator.initialize(2048);
            return new JwtFixture(generator.generateKeyPair());
        } catch (Exception e) {
            throw new IllegalStateException("could not generate a test RSA key pair", e);
        }
    }

    public RSAPublicKey publicKey() {
        return (RSAPublicKey) keyPair.getPublic();
    }

    /** A well-formed token for the configured realm: correct issuer, audience, and a live lifetime. */
    public String tokenFor(String role, String audience) {
        return token(builder -> {
            builder.audience(audience).issuer(ISSUER).issueTime(new Date()).expirationTime(future());
            if (role != null) {
                builder.claim("realm_access", Map.of("roles", List.of(role)));
            }
        });
    }

    public String tokenWith(String role, String audience, String issuer, Date issuedAt, Date expiresAt) {
        return token(builder -> {
            builder.issuer(issuer).issueTime(issuedAt).expirationTime(expiresAt);
            if (role != null) {
                builder.claim("realm_access", Map.of("roles", List.of(role)));
            }
        });
    }

    /** Correctly signed and correctly addressed, but issued an hour ago. */
    public String tokenExpired(String role, String audience) {
        return token(builder -> {
            builder.audience(audience)
                    .issuer(ISSUER)
                    .issueTime(new Date(System.currentTimeMillis() - 3_600_000))
                    .expirationTime(new Date(System.currentTimeMillis() - 1_800_000));
            if (role != null) {
                builder.claim("realm_access", Map.of("roles", List.of(role)));
            }
        });
    }

    private String token(java.util.function.Consumer<JWTClaimsSet.Builder> customiser) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject("8f14e45f-ea0c-4f2b-9a1d-1234567890ab")
                .claim("preferred_username", "test@fintech.test");
        customiser.accept(claims);
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
            jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("could not sign a test token", e);
        }
    }

    private static Date future() {
        return new Date(System.currentTimeMillis() + LIFETIME_SECONDS * 1000L);
    }

    /** A header map shaped like a spoofed identity, for the forwarding tests. */
    public static Map<String, String> spoofedIdentityHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Internal-Identity-Subject", "attacker-chosen-subject");
        headers.put("X-Internal-Identity-Username", "admin@fintech.test");
        headers.put("X-Internal-Identity-Roles", "PLATFORM_ADMIN,CUSTOMER");
        headers.put("X-Internal-Identity-Correlation-Id", "attacker-correlation-id");
        headers.put("X-Internal-Identity-Issued-At", String.valueOf(System.currentTimeMillis() / 1000));
        headers.put("X-Internal-Identity-Signature", "Zm9yZ2VkLXNpZ25hdHVyZQ");
        return headers;
    }
}
