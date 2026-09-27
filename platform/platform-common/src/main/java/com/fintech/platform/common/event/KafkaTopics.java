package com.fintech.platform.common.event;

import java.util.List;

/**
 * The platform's Kafka topic catalogue.
 *
 * <p>Naming rules, applied without exception:
 *
 * <ul>
 *   <li><b>Past tense</b> — a topic carries an immutable fact, never a command. {@code
 *       transaction-created} means it exists; it does not ask anyone to create it.
 *   <li><b>One aggregate per topic</b> — this is what makes per-aggregate ordering free. Partition
 *       keying on {@code aggregateId} gives all events for one transaction to one partition, so
 *       they are consumed in publish order with no distributed coordination.
 *   <li><b>Hyphenated lowercase</b> — matches Spring's relaxed binding to {@code
 *       spring.kafka.consumer.topic} properties and any {@code KAFKA_TOPIC_*} environment
 *       variable without quoting gymnastics.
 * </ul>
 */
public final class KafkaTopics {

    private KafkaTopics() {}

    // ---------------------------------------------------------------- transaction lifecycle
    public static final String TRANSACTION_CREATED = "transaction-created";
    public static final String TRANSACTION_AUTHORIZED = "transaction-authorized";
    public static final String TRANSACTION_DECLINED = "transaction-declined";
    public static final String TRANSACTION_SETTLED = "transaction-settled";
    public static final String TRANSACTION_REVERSED = "transaction-reversed";

    // ---------------------------------------------------------------- fraud / risk
    public static final String FRAUD_ANALYSIS_REQUESTED = "fraud-analysis-requested";
    public static final String FRAUD_ANALYSIS_COMPLETED = "fraud-analysis-completed";

    // ---------------------------------------------------------------- side effects
    public static final String NOTIFICATION_EVENTS = "notification-events";
    public static final String AUDIT_EVENTS = "audit-events";

    // ---------------------------------------------------------------- disputes
    public static final String DISPUTE_CREATED = "dispute-created";
    public static final String DISPUTE_STATUS_CHANGED = "dispute-status-changed";

    /**
     * Terminal parking lot for events that could not be processed.
     *
     * <p>A consumer that keeps failing on a poison message must not block its partition forever, and a
     * bad message must not be silently dropped either. After the configured number of attempts the
     * record is republished here with the failure reason attached, so the event is preserved for
     * forensics and the partition resumes.
     */
    public static final String DEAD_LETTER_EVENTS = "dead-letter-events";

    /** Every topic the platform owns. Used to provision topics in local dev and in Kubernetes. */
    public static final List<String> ALL = List.of(
            TRANSACTION_CREATED,
            TRANSACTION_AUTHORIZED,
            TRANSACTION_DECLINED,
            TRANSACTION_SETTLED,
            TRANSACTION_REVERSED,
            FRAUD_ANALYSIS_REQUESTED,
            FRAUD_ANALYSIS_COMPLETED,
            NOTIFICATION_EVENTS,
            AUDIT_EVENTS,
            DISPUTE_CREATED,
            DISPUTE_STATUS_CHANGED,
            DEAD_LETTER_EVENTS);

    /**
     * Partition count per topic.
     *
     * <p>Chosen as a fixed number rather than left to broker defaults so that partition counts — and
     * therefore the maximum consumer parallelism and therefore the ordering guarantee — are a
     * deliberate, reviewable decision instead of an accident of the broker config. Raise a value here
     * only with a note about key distribution, because changing it changes the ordering domain.
     */
    public static final int DEFAULT_PARTITIONS = 6;

    /** Replication factor. Single-broker local dev; production uses 3 across availability zones. */
    public static final short DEFAULT_REPLICATION_FACTOR = 1;
}
