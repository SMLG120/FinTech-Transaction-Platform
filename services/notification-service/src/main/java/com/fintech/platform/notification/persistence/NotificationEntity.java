package com.fintech.platform.notification.persistence;

import com.fintech.platform.notification.domain.NotificationChannel;
import com.fintech.platform.notification.domain.NotificationKind;
import com.fintech.platform.notification.domain.NotificationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One message the platform decided to send, and whether it went out.
 *
 * <p>The row is created {@code PENDING} and leaves that state exactly once per delivery attempt: to
 * {@code SENT} with a timestamp, or back to {@code FAILED} with the attempt counted and the next attempt
 * scheduled. There is no update path that rewrites the subject or body after creation — a message that
 * changes between attempts is two messages, and only one of them was ever sent.
 */
@Entity
@Table(name = "notifications")
public class NotificationEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "topic", nullable = false, length = 128)
    private String topic;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private NotificationKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 8)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 8)
    private NotificationStatus status;

    @Column(name = "recipient_digest", length = 64)
    private String recipientDigest;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "cycle_reference", length = 64)
    private String cycleReference;

    @Column(name = "amount_minor")
    private Long amountMinor;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Column(name = "payee_name", length = 256)
    private String payeeName;

    @Column(name = "subject", nullable = false, length = 256)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationEntity() {}

    private NotificationEntity(
            UUID id,
            UUID eventId,
            String topic,
            NotificationKind kind,
            String recipientDigest,
            UUID transactionId,
            String cycleReference,
            Long amountMinor,
            String currencyCode,
            String payeeName,
            String subject,
            String body,
            Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.topic = Objects.requireNonNull(topic, "topic must not be null");
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.channel = kind.channel();
        this.status = NotificationStatus.PENDING;
        this.recipientDigest = recipientDigest;
        this.transactionId = transactionId;
        this.cycleReference = cycleReference;
        this.amountMinor = amountMinor;
        this.currencyCode = currencyCode;
        this.payeeName = payeeName;
        this.subject = Objects.requireNonNull(subject, "subject must not be null");
        this.body = Objects.requireNonNull(body, "body must not be null");
        this.attempts = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static NotificationEntity pending(
            UUID eventId,
            String topic,
            NotificationKind kind,
            String recipientDigest,
            UUID transactionId,
            String cycleReference,
            Long amountMinor,
            String currencyCode,
            String payeeName,
            String subject,
            String body,
            Instant now) {
        return new NotificationEntity(
                UUID.randomUUID(),
                eventId,
                topic,
                kind,
                recipientDigest,
                transactionId,
                cycleReference,
                amountMinor,
                currencyCode,
                payeeName,
                subject,
                body,
                now);
    }

    /**
     * Records a successful delivery.
     *
     * <p>Refuses a notification that already went out, because sending it again would be the second
     * delivery the event-id uniqueness was supposed to prevent — reached through the retry path
     * instead of through the consumer.
     */
    public void markSent(Instant now) {
        if (status == NotificationStatus.SENT) {
            throw new IllegalStateException("notification " + id + " was already sent");
        }
        status = NotificationStatus.SENT;
        sentAt = now;
        nextAttemptAt = null;
        lastError = null;
        updatedAt = now;
    }

    /** Records a failed attempt and when it becomes due again. */
    public void markFailed(String error, Instant nextAttempt, Instant now) {
        attempts += 1;
        status = NotificationStatus.FAILED;
        lastError = error;
        nextAttemptAt = nextAttempt;
        updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getTopic() {
        return topic;
    }

    public NotificationKind getKind() {
        return kind;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public String getRecipientDigest() {
        return recipientDigest;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public String getCycleReference() {
        return cycleReference;
    }

    public Long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public String getPayeeName() {
        return payeeName;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
