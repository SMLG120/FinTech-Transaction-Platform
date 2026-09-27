package com.fintech.platform.common.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

class CorrelationIdTest {

    @ParameterizedTest(name = "accepts a well-formed id: {0}")
    @ValueSource(strings = {"0f3c1f0e-6f0a-4f2f-9a1e-1b2c3d4e5f60", "trace.123_ABC", "abc123"})
    @DisplayName("accepts sane client-supplied ids")
    void acceptsSaneIds(String candidate) {
        assertThat(CorrelationId.isAcceptable(candidate)).isTrue();
    }

    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(
            strings = {
                "has space",
                "has\nnewline",
                "has\rcarriage-return",
                "quote\"inject",
                "semi;colon",
                "tab\there",
                "01234567890123456789012345678901234567890123456789012345678901234"
            })
    @DisplayName("rejects ids that could forge log entries or bloat every log line")
    void rejectsUnsafeIds(String candidate) {
        assertThat(CorrelationId.isAcceptable(candidate)).isFalse();
    }

    @Test
    @DisplayName("the 64 character boundary is inclusive")
    void boundaryLength() {
        assertThat(CorrelationId.isAcceptable("a".repeat(64))).isTrue();
        assertThat(CorrelationId.isAcceptable("a".repeat(65))).isFalse();
    }

    @Test
    void rejectsNullAndEmpty() {
        assertThat(CorrelationId.isAcceptable(null)).isFalse();
        assertThat(CorrelationId.isAcceptable("")).isFalse();
    }

    @Test
    @DisplayName("generate produces unique RFC 4122 version 4 identifiers")
    void generateProducesUuidV4() {
        String first = CorrelationId.generate();
        String second = CorrelationId.generate();

        assertThat(first).isNotEqualTo(second);
        assertThat(first).matches("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    }

    @Test
    @DisplayName("current reads the value the correlation filter published to the MDC")
    void currentReadsMdc() {
        assertThat(CorrelationId.current()).isNull();

        MDC.put(CorrelationId.MDC_KEY, "abc-123");
        try {
            assertThat(CorrelationId.current()).isEqualTo("abc-123");
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    @Test
    @DisplayName("MDC is the only carrier, so it cannot leak between pooled request threads")
    void mdcKeyIsStable() {
        assertThat(CorrelationId.MDC_KEY).isEqualTo("correlationId");
        assertThat(CorrelationId.HEADER).isEqualToIgnoringCase("X-Correlation-Id");
    }
}
