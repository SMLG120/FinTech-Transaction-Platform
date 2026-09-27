package com.fintech.platform.customer.pii;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Proves the key reaches the cipher the way an operator would supply it, and that a deployment which
 * forgets to supply it does not come up quietly.
 *
 * <p>The second half matters more than the first. A service that starts and then writes unencrypted
 * PII is a slow, invisible failure; a service that refuses to start is a five-minute outage an
 * operator cannot miss.
 */
class PiiConfigurationTest {

    private static final String SYNTHETIC_KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(UnderTest.class);

    @Test
    @DisplayName("binds a base64 32-byte key from the property the platform documents")
    void bindsSuppliedKey() {
        runner.withPropertyValues(
                        "platform.customer.pii.master-key=" + SYNTHETIC_KEY, "platform.customer.pii.key-version=3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PiiProperties.class).masterKey()).isEqualTo(SYNTHETIC_KEY);
                    assertThat(context.getBean(PiiProperties.class).resolvedKeyVersion())
                            .isEqualTo(3);
                });
    }

    @Test
    @DisplayName("builds a working cipher bean from the supplied key")
    void cipherIsWired() {
        runner.withPropertyValues("platform.customer.pii.master-key=" + SYNTHETIC_KEY)
                .run(context -> {
                    PiiCipher cipher = context.getBean(PiiCipher.class);
                    assertThat(cipher.encrypt("subject-1", "Alice Chen")).isNotBlank();
                });
    }

    @Test
    @DisplayName("refuses to start with no key, naming the property and the variable that sets it")
    void refusesToStartWithoutKey() {
        runner.run(context -> {
            assertRefusedToStart(context, "platform.customer.pii.master-key is required");
            assertRefusedToStart(context, "CUSTOMER_PII_MASTER_KEY");
        });
    }

    @Test
    @DisplayName("refuses to start when the key is present but not base64")
    void refusesToStartWithMalformedKey() {
        runner.withPropertyValues("platform.customer.pii.master-key=not-valid-base64!!")
                .run(context -> {
                    assertRefusedToStart(context, "must be valid base64");
                });
    }

    @Test
    @DisplayName("refuses to start when the key is the wrong length for AES-256")
    void refusesToStartWithShortKey() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);
        runner.withPropertyValues("platform.customer.pii.master-key=" + tooShort)
                .run(context -> {
                    assertRefusedToStart(context, "32 bytes");
                });
    }

    @Test
    @DisplayName("refuses a key version below 1, since a ciphertext must declare a generation")
    void refusesImpossibleKeyVersion() {
        runner.withPropertyValues(
                        "platform.customer.pii.master-key=" + SYNTHETIC_KEY, "platform.customer.pii.key-version=0")
                .run(context -> {
                    assertRefusedToStart(context, "key-version must be >= 1");
                });
    }

    /**
     * Asserts startup failed with a message this codebase chose, not merely that something threw.
     *
     * <p>Walks the cause chain for the {@link IllegalStateException} rather than asserting on the
     * context's own wrapper type, and rather than using AssertJ's {@code rootCause()}: the malformed-key
     * case deliberately keeps the decoder's {@link IllegalArgumentException} as the cause, so the
     * deepest throwable is the JDK's and says nothing about which property was wrong.
     */
    private static void assertRefusedToStart(
            org.springframework.boot.test.context.assertj.AssertableApplicationContext context,
            String expectedMessage) {
        assertThat(context).hasFailed();
        Throwable failure = context.getStartupFailure();
        Throwable found = null;
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof IllegalStateException) {
                found = t;
                break;
            }
        }
        assertThat(found).as("startup failure carrying the PII key diagnostic").isNotNull();
        assertThat(found.getMessage()).contains(expectedMessage);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PiiProperties.class)
    @Import(PiiCipher.class)
    static class UnderTest {}
}
