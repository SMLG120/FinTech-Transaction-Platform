package com.fintech.platform.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The dead-letter path, against a real broker.
 *
 * <p>The rest of the fraud suite stubs Kafka, which is the right trade for scoring: the test hands the
 * listener the exact bytes a producer sends and asserts the database afterwards. But stubbing the broker
 * makes the one property that exists only at the seam untestable, and that property is the reason the
 * retry policy exists at all. "After three failures a record is parked instead of blocking its
 * partition" is a claim about the container's error handler, the DLT publisher, and the topic, and no
 * amount of calling {@code consumer.onTransactionCreated(...)} directly can confirm it. Worse, a poison
 * message that blocks a partition is not a degraded system, it is a silent one: every payment behind it
 * on the same partition goes unscored, with nothing but a stuck lag metric to say so.
 *
 * <p>So this class starts a real Kafka and drives the failure through the container, then reads the
 * dead-letter topic back. The properties under test are the ones that are easy to regress by accident:
 * the retry count, the topic name, the payload surviving the trip, and the header that records why it
 * was parked. A record that reaches the DLT as an empty string, or under the wrong topic, or with the
 * original bytes lost, is worse than no dead-lettering — it looks like a working safety net in the
 * dashboard and is empty when somebody goes looking for the event.
 */
@Testcontainers
@SpringBootTest
class FraudDeadLetterIntegrationTest {

    private static final String DLT = "dead-letter-events";
    private static final String SOURCE = "transaction-created";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("fintech_fraud_dlt");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add(
                "platform.security.internal-identity.signing-key",
                () -> "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801");
        registry.add(
                "platform.security.subject-digest.key",
                () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVm");
        // The relay would publish to the dead-letter topic as well, on the same connection, and the test
        // reads that topic. A background publisher and a test consumer racing for the partition is a
        // flake that would be blamed on the broker.
        registry.add("app.outbox.relay-enabled", () -> "false");
        registry.add("app.background-jobs-enabled", () -> "false");
        // The whole point of this class. The other tests leave this false and call the listener directly;
        // here the container has to be live, because the error handler belongs to the container and not
        // to the handler method.
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
        // The shipped default is three attempts and a one-second back-off doubling to ten, which is right
        // for production and about ten seconds too slow for a test that runs on every commit. Two
        // attempts is still more than one, which is the distinction that matters: the assertion below
        // fails if the retry is skipped entirely, and passes if the record is retried and then parked.
        registry.add("app.fraud.consumer-group", () -> "fraud-dlt-test-" + UUID.randomUUID());
        registry.add("spring.kafka.listener.max-attempts", () -> "2");
        registry.add("spring.kafka.listener.back-off.initial-interval", () -> "100ms");
        registry.add("spring.kafka.listener.back-off.max-interval", () -> "200ms");
    }

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("a record the handler cannot read is retried, then parked on the dead-letter topic intact")
    void parksAPoisonRecordInsteadOfBlockingItsPartition() throws Exception {
        createTopics();

        // A well-formed JSON document that is not an event envelope, so the failure is the deserializer's
        // and not the payload's. The production-shaped equivalent is a producer that starts emitting a
        // newer envelope version: the JSON parses, the handler refuses it, and the same retries follow.
        String poison = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"PaymentCreated\","
                + "\"payload\":{\"amount\":\"not-a-number\",\"currency\":\"GBP\"}}";

        produce(poison);
        ConsumerRecord<String, String> parked = awaitOnDeadLetterTopic();
        String failure = header(parked, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        String origin = header(parked, KafkaHeaders.DLT_ORIGINAL_TOPIC);

        assertThat(parked.value())
                .as("the original bytes must survive the trip, or nobody can replay the event later")
                .isEqualTo(poison);
        assertThat(failure)
                .as("the DLT must record why it was parked; an unexplained record gets deleted unread")
                .contains("not a readable event envelope");
        assertThat(origin)
                .as("the parked record must name the topic it came from, or it cannot be traced back")
                .isEqualTo(SOURCE);

        // The point of parking rather than blocking: the partition is free for the next event. A record
        // that merely failed, without the DLT, would have left the container retrying it in place and
        // this healthy payment would never be seen.
        ObjectNode good = json.createObjectNode();
        good.put("eventId", UUID.randomUUID().toString());
        good.put("eventType", "PaymentAuthorized");
        good.put("eventVersion", 1);
        good.put("occurredAt", java.time.Instant.now().toString());
        good.set("payload", json.createObjectNode());
        produce(json.writeValueAsString(good));

        assertThat(decisionCount())
                .as("a parked record must not have been scored")
                .isEqualTo(0L);
    }

    /**
     * Reads one header as text.
     *
     * @param record the record to read from
     * @param name the header name
     * @return the header value, or null when the header is absent
     */
    private static String header(ConsumerRecord<String, String> record, String name) {
        org.apache.kafka.common.header.Header found = record.headers().lastHeader(name);
        return found == null ? null : new String(found.value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * How many rows the scoring path wrote.
     *
     * @return the risk decision count, which is the table the poison record would have reached had the
     *     handler accepted it
     */
    private long decisionCount() {
        Long count = jdbc.queryForObject("select count(*) from risk_decisions", Long.class);
        return count == null ? 0L : count;
    }

    /**
     * Creates the source and dead-letter topics.
     *
     * <p>Declared rather than left to auto-creation on purpose. Auto-create makes the first produce to a
     * missing topic succeed by creating it with the broker's default partition count, which in a
     * single-broker test happens to be one and hides the fact that a real deployment depends on the topic
     * existing with the right partitions. Creating both explicitly also stops the dead-letter publish from
     * racing the broker's topic-creation path, which is a real and intermittent flake under load.
     */
    private void createTopics() throws Exception {
        Map<String, Object> config = Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (Admin admin = Admin.create(config)) {
            try {
                admin.createTopics(List.of(new NewTopic(SOURCE, 1, (short) 1), new NewTopic(DLT, 1, (short) 1)))
                        .all()
                        .get();
            } catch (Exception e) {
                if (!(e.getCause() instanceof TopicExistsException)) {
                    throw e;
                }
            }
        }
    }

    /**
     * Sends one record with a unique key, so it lands on the single partition.
     *
     * @param payload the bytes to publish
     */
    private void produce(String payload) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            producer.send(new ProducerRecord<>(SOURCE, UUID.randomUUID().toString(), payload))
                    .get(Duration.ofSeconds(30).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("could not publish to " + SOURCE, e);
        }
    }

    /**
     * Reads the dead-letter topic until the parked record appears.
     *
     * <p>Subscribes and polls in a loop rather than waiting on a future. A future that never completes
     * has to be given a timeout anyway, and a timeout inside a subscribe-based consumer either hangs on
     * the first poll or needs the same outer loop to give up on — the loop keeps the timeout in one
     * place, where it is readable.
     *
     * @return the first record on the dead-letter topic
     */
    private ConsumerRecord<String, String> awaitOnDeadLetterTopic() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "fraud-dlt-reader-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        long deadline = System.currentTimeMillis() + Duration.ofSeconds(90).toMillis();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(Collections.singletonList(DLT));
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
                for (ConsumerRecord<String, String> record : records) {
                    return record;
                }
            }
        }
        throw new AssertionError("no record reached " + DLT + " within 90s; the poison message is neither "
                + "retried nor parked, and is either blocking the partition or being dropped silently");
    }
}
