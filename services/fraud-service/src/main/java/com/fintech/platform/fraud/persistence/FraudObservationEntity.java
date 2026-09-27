package com.fintech.platform.fraud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * One "I have seen this before", and when.
 *
 * <p>This table is the fraud engine's memory. Three rules depend on it — a device is new to a card, a
 * device is shared between customers, a merchant is new to a customer — and none of them can work without
 * a record of what has been seen before. There is no other source for that fact on this platform: nothing
 * else knows that this customer paid this merchant from this device.
 *
 * <p><b>Keyed by subject digest, never by subject.</b> A row here answers "has this customer done this
 * before", and the customer is identified by the digest transaction-service computed. The table therefore
 * cannot tell anyone who a customer is even if someone has the database, and a support agent exporting
 * it learns that a subject digest exists, not whose it is.
 *
 * <p><b>Scope is part of the key, and the scopes answer different questions.</b> A device seen with a
 * card answers "is this device new for this card"; a device seen by a customer answers "does this customer
 * know this device"; a merchant seen by a customer answers "is this merchant new to them". Encoding the
 * scope in the primary key rather than in a nullable column is what stops a card-scoped row from being
 * read as a customer-scoped one — a mistake that would make every device look new and every merchant
 * look new, which is a rule engine that alerts on all traffic and therefore none.
 *
 * <p><b>{@code first_seen_at} is never rewritten, {@code last_seen_at} always is.</b> The first is the
 * fact the recent-activation and rapid-network-change rules need, and a fact that moves every time it is
 * looked at is not a fact.
 */
@Entity
@Table(name = "fraud_observations")
@IdClass(FraudObservationId.class)
public class FraudObservationEntity {

    /** What a {@link #scope} value means. Kept as a column value, not an enum, to allow adding scopes. */
    public static final String SCOPE_CUSTOMER_DEVICE = "CUSTOMER_DEVICE";

    public static final String SCOPE_CARD_DEVICE = "CARD_DEVICE";

    public static final String SCOPE_CUSTOMER_MERCHANT = "CUSTOMER_MERCHANT";
    public static final String SCOPE_CUSTOMER_NETWORK = "CUSTOMER_NETWORK";
    public static final String SCOPE_CARD = "CARD";

    /** The five kinds, as they appear in {@link #scope}. */
    public static final String[] KINDS = {"DEVICE", "MERCHANT", "NETWORK", "CARD"};

    @Id
    @Column(name = "scope", nullable = false, length = 32)
    private String scope;

    @Id
    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    /**
     * The subject this observation is about, as a digest.
     *
     * <p>For a customer-scoped row this is the customer's own digest. For {@link #SCOPE_CARD_DEVICE} it is
     * the card reference, and for {@link #SCOPE_CARD} it is the card reference as well. It is a digest in
     * every case, which is the property that lets one column hold all five scopes without becoming a
     * place raw identifiers end up.
     */
    @Id
    @Column(name = "subject_digest", nullable = false, length = 64)
    private String subjectDigest;

    /**
     * The thing observed, as a digest or as merchant identity text.
     *
     * <p>A digest for a device or a network, and merchant-supplied text for a merchant. The column is
     * documented as mixed because the alternative is two tables with identical logic, and the difference
     * is only in whether the value is reversible.
     */
    @Id
    @Column(name = "observation_key", nullable = false, length = 256)
    private String observationKey;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "hit_count", nullable = false)
    private int hitCount;

    protected FraudObservationEntity() {}

    private FraudObservationEntity(
            String scope, String kind, String subjectDigest, String observationKey, Instant now) {
        this.scope = Objects.requireNonNull(scope, "scope must not be null");
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.subjectDigest = Objects.requireNonNull(subjectDigest, "subjectDigest must not be null");
        this.observationKey = Objects.requireNonNull(observationKey, "observationKey must not be null");
        this.firstSeenAt = Objects.requireNonNull(now, "now must not be null");
        this.lastSeenAt = now;
        this.hitCount = 1;
    }

    public static FraudObservationEntity firstSeen(
            String scope, String kind, String subjectDigest, String observationKey, Instant now) {
        return new FraudObservationEntity(scope, kind, subjectDigest, observationKey, now);
    }

    /**
     * Records another sighting.
     *
     * <p>{@code firstSeenAt} is only supplied when the row does not exist yet, which is why the caller
     * uses an insert-on-conflict rather than a read-modify-write: two consumers scoring two payments from
     * the same device at the same instant must not both insert, and must not both believe they created
     * the row. See {@code ObservationRecorder}.
     */
    public void recordHit(Instant now) {
        this.lastSeenAt = now;
        this.hitCount = this.hitCount + 1;
    }

    public String scope() {
        return scope;
    }

    public String kind() {
        return kind;
    }

    public String subjectDigest() {
        return subjectDigest;
    }

    public String observationKey() {
        return observationKey;
    }

    public Instant firstSeenAt() {
        return firstSeenAt;
    }

    public Instant lastSeenAt() {
        return lastSeenAt;
    }

    public int hitCount() {
        return hitCount;
    }

    /** Whether this sighting falls outside the configured retention, and the row may be deleted. */
    public boolean isOlderThan(Duration retention, Instant now) {
        return lastSeenAt().isBefore(now.minus(retention));
    }
}
