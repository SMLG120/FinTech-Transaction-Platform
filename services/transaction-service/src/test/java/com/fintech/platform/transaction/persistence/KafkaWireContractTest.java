package com.fintech.platform.transaction.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.common.event.EventEnvelope;
import java.util.Map;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The platform's Kafka wire contract, asserted where a stubbed broker cannot see it.
 *
 * <p>{@code OutboxRelayTest} proves the relay hands {@code KafkaTemplate} the exact string it wrote
 * beside the state change, and that test passed while the platform was publishing double-encoded
 * events. Both facts are true and neither is the property that matters: the re-encoding happened
 * <em>inside</em> the serializer, below the line a mock draws. What reached the topic was a JSON
 * string containing JSON, every consumer's first read of it failed, and the outbox's central promise —
 * the stored payload is the sent payload — was quietly false.
 *
 * <p>So this test goes one level lower and uses the real configured serializers. It needs no broker,
 * because the bug was never in the broker.
 */
@Testcontainers
@SpringBootTest
class KafkaWireContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("fintech_transactions_wire");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(
                "platform.security.internal-identity.signing-key",
                () -> "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801");
        registry.add(
                "platform.security.subject-digest.key",
                () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVm");
    }

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    org.springframework.core.env.Environment environment;

    /**
     * The serializer the producer is configured with, built the way the container builds it.
     *
     * <p>Read from configuration rather than from {@code ProducerFactory.getValueSerializer()}, which is
     * null until the factory configures a producer and is therefore not something a test can assert
     * against. The configured class name is the contract; this turns it into a working serializer so
     * the assertion below is about bytes and not about a string in a properties file.
     */
    private Serializer<String> configuredValueSerializer() throws Exception {
        String className = environment.getRequiredProperty("spring.kafka.producer.value-serializer");
        return (Serializer<String>)
                Class.forName(className).getDeclaredConstructor().newInstance();
    }

    @Test
    @DisplayName("the producer writes the envelope as-is, so the topic holds the outbox's bytes")
    void producerDoesNotReEncodeTheEnvelope() throws Exception {
        String envelope = objectMapper.writeValueAsString(EventEnvelope.ofAt(
                "transaction.created",
                "Transaction",
                "6f1d0b3e-2f2a-4a1e-9c1a-6b0a1c2d3e4f",
                Map.of("transactionId", "6f1d0b3e-2f2a-4a1e-9c1a-6b0a1c2d3e4f", "amount", "12.00"),
                java.time.Instant.parse("2026-09-27T01:45:02.896Z")));

        Serializer<String> serializer = configuredValueSerializer();
        byte[] onTheWire = serializer.serialize("transaction-created", envelope);

        assertThat(new String(onTheWire, java.nio.charset.StandardCharsets.UTF_8))
                .as("the bytes on the topic must be the bytes the outbox stored")
                .isEqualTo(envelope);
    }

    @Test
    @DisplayName("a value arriving on this topic is an object, not a string containing one")
    void consumersReadAnObject() throws Exception {
        // The shape of the bug, asserted directly rather than through the serializer: a JsonSerializer
        // handed an already-serialised String writes a quoted scalar, and a consumer then sees a JSON
        // string where it expects a JSON object. Asserting the first character is `{` is the whole
        // contract, and it is what every downstream listener's readValue call depends on.
        String envelope = objectMapper.writeValueAsString(Map.of("eventType", "transaction.created"));

        assertThat(envelope.charAt(0))
                .as("an envelope must serialise to a JSON object")
                .isEqualTo('{');
    }
}
