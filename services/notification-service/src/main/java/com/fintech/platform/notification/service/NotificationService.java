package com.fintech.platform.notification.service;

import com.fintech.platform.notification.domain.NotificationKind;
import com.fintech.platform.notification.domain.NotificationStatus;
import com.fintech.platform.notification.error.NotificationErrors;
import com.fintech.platform.notification.persistence.NotificationEntity;
import com.fintech.platform.notification.persistence.NotificationRepository;
import com.fintech.platform.notification.persistence.ProcessedEventRepository;
import com.fintech.platform.notification.service.NotificationTemplates.Rendered;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns consumed facts into messages, exactly once each, and retries the sends that fail.
 *
 * <p><b>Deduplication is claimed with an insert, not a lookup.</b> The claim is an
 * {@code INSERT ... ON CONFLICT DO NOTHING} in the same transaction that creates the notification,
 * so there is no window in which the event is marked processed and the message is lost, and no window
 * in which two consumers both send. A redelivery finds the claim taken and returns false, and the
 * customer is told once however many times the broker hands the event over.
 *
 * <p><b>A send failure does not fail the event.</b> The event was fine; the channel was not. So a
 * throw from the sender is caught, the notification stays {@code FAILED} with the attempt counted and
 * the next attempt scheduled, and the method still returns true — the event is consumed. Kafka
 * redelivery is for events that could not be <em>read</em>; the scheduler is for messages that could
 * not be <em>sent</em>. Confusing the two would park a well-formed payment event on the dead-letter
 * topic because an SMS gateway blipped.
 *
 * <p><b>Nothing here can fail a payment.</b> This service only ever reads facts about money that has
 * already moved. The worst a bug here can do is send a wrong message or none at all, which is why the
 * delivery log exists: every decision this service made is a row somebody can read.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;

    private final ProcessedEventRepository processed;

    private final NotificationSender sender;

    private final NotificationProperties properties;

    private final Clock clock;

    private final Counter created;

    private final Counter duplicates;

    private final Counter sendFailures;

    public NotificationService(
            NotificationRepository notifications,
            ProcessedEventRepository processed,
            NotificationSender sender,
            NotificationProperties properties,
            Clock clock,
            MeterRegistry meters) {
        this.notifications = notifications;
        this.processed = processed;
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
        this.created = Counter.builder("notification.messages.created")
                .description("Notifications created from consumed events, after deduplication")
                .register(meters);
        this.duplicates = Counter.builder("notification.events.duplicates")
                .description("Deliveries of an event whose id has already been claimed")
                .register(meters);
        this.sendFailures = Counter.builder("notification.deliveries.failed")
                .description("Delivery attempts that failed and were scheduled for retry")
                .register(meters);
    }

    /** A payment fact worth telling the customer about. */
    public record PaymentNotice(
            NotificationKind kind,
            String recipientDigest,
            UUID transactionId,
            String amountMinor,
            String currencyCode,
            String payeeName) {}

    /** A fraud outcome worth texting the customer about. Only REVIEW and DECLINE ever arrive here. */
    public record FraudNotice(
            NotificationKind kind,
            String recipientDigest,
            UUID transactionId,
            String amountMinor,
            String currencyCode,
            String payeeName) {}

    /** A settlement fact worth emailing an operator about. Detail is a prebuilt human fragment. */
    public record SettlementNotice(NotificationKind kind, String cycleReference, String detail) {}

    /**
     * Claims the event and, if it is new, records the payment notification and sends it.
     *
     * @return true when the event was applied, false when it is a redelivery
     */
    @Transactional
    public boolean notifyPayment(UUID eventId, String topic, PaymentNotice notice) {
        requirePaymentKind(notice.kind());
        Rendered rendered = NotificationTemplates.payment(
                notice.kind(), notice.amountMinor(), notice.currencyCode(), notice.payeeName());
        return claimAndSend(
                eventId,
                topic,
                notice.kind(),
                notice.recipientDigest(),
                notice.transactionId(),
                null,
                toMinor(notice.amountMinor(), notice.currencyCode()),
                notice.currencyCode(),
                notice.payeeName(),
                rendered);
    }

    /**
     * Claims the event and, if it is new, records the fraud notification and sends it.
     *
     * @return true when the event was applied, false when it is a redelivery
     */
    @Transactional
    public boolean notifyFraud(UUID eventId, String topic, FraudNotice notice) {
        if (notice.kind() != NotificationKind.FRAUD_REVIEW && notice.kind() != NotificationKind.FRAUD_DECLINED) {
            throw new IllegalArgumentException(notice.kind() + " is not a fraud kind");
        }
        Rendered rendered = NotificationTemplates.fraud(
                notice.kind(), notice.amountMinor(), notice.currencyCode(), notice.payeeName());
        return claimAndSend(
                eventId,
                topic,
                notice.kind(),
                notice.recipientDigest(),
                notice.transactionId(),
                null,
                toMinor(notice.amountMinor(), notice.currencyCode()),
                notice.currencyCode(),
                notice.payeeName(),
                rendered);
    }

    /**
     * Claims the event and, if it is new, records the settlement notification and sends it.
     *
     * @return true when the event was applied, false when it is a redelivery
     */
    @Transactional
    public boolean notifySettlement(UUID eventId, String topic, SettlementNotice notice) {
        if (notice.kind().isPaymentKind()) {
            throw new IllegalArgumentException(notice.kind() + " is not a settlement kind");
        }
        if (notice.cycleReference() == null || notice.cycleReference().isBlank()) {
            throw new IllegalArgumentException("a settlement notification needs a cycle reference");
        }
        Rendered rendered = NotificationTemplates.settlement(notice.kind(), notice.cycleReference(), notice.detail());
        return claimAndSend(
                eventId, topic, notice.kind(), null, null, notice.cycleReference(), null, null, null, rendered);
    }

    /**
     * Retries every failed notification whose next attempt is due.
     *
     * <p>Bounded to fifty rows per pass, so a provider outage cannot turn the scheduler into an
     * unbounded backlog that holds the thread past the next tick.
     *
     * @return how many due notifications were sent
     */
    @Transactional
    public int retryDue() {
        Instant now = clock.instant();
        List<NotificationEntity> due =
                notifications.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                        NotificationStatus.FAILED, now);
        int sent = 0;
        for (NotificationEntity notification : due) {
            if (attempt(notification, now)) {
                sent += 1;
            }
        }
        return sent;
    }

    /**
     * Retries one notification on demand, from the support API.
     *
     * <p>Refuses a notification that already went out: resending a SENT message is the duplicate the
     * event-id uniqueness was supposed to prevent, reached through the API instead of the consumer.
     */
    @Transactional
    public NotificationEntity retryOne(UUID id) {
        NotificationEntity notification = notifications
                .findById(id)
                .orElseThrow(() -> NotificationErrors.NOT_FOUND.exception("No notification with id " + id));
        if (notification.getStatus() == NotificationStatus.SENT) {
            throw NotificationErrors.ALREADY_SENT.exception(
                    "Notification " + id + " was already sent; a sent message is not retried");
        }
        attempt(notification, clock.instant());
        return notification;
    }

    private boolean claimAndSend(
            UUID eventId,
            String topic,
            NotificationKind kind,
            String recipientDigest,
            UUID transactionId,
            String cycleReference,
            Long amountMinor,
            String currencyCode,
            String payeeName,
            Rendered rendered) {
        Instant now = clock.instant();
        if (processed.claim(eventId, topic, now) == 0) {
            duplicates.increment();
            log.info("Ignoring redelivery of {} from {}; already notified", eventId, topic);
            return false;
        }
        // The managed copy, not the argument. The id is assigned in the constructor, so Spring
        // Data cannot tell this entity is new and save() merges rather than persists: it copies the
        // state into a managed instance and returns it, leaving the argument detached. Mutating the
        // argument afterwards — the markSent below — would run against a detached object and be
        // silently lost, committing the row as PENDING after the message demonstrably went out. The
        // live stack proved exactly that: every send logged, every row PENDING.
        NotificationEntity notification = notifications.save(NotificationEntity.pending(
                eventId,
                topic,
                kind,
                recipientDigest,
                transactionId,
                cycleReference,
                amountMinor,
                currencyCode,
                payeeName,
                rendered.subject(),
                rendered.body(),
                now));
        created.increment();
        attempt(notification, now);
        return true;
    }

    /**
     * One delivery attempt, recording the outcome on the row.
     *
     * @return true when the message went out
     */
    private boolean attempt(NotificationEntity notification, Instant now) {
        try {
            sender.send(notification);
        } catch (RuntimeException e) {
            sendFailures.increment();
            Instant next = nextAttempt(notification.getAttempts(), now);
            notification.markFailed(shortError(e), next, now);
            log.warn(
                    "Delivery of {} {} failed (attempt {}), next attempt at {}",
                    notification.getId(),
                    notification.getKind(),
                    notification.getAttempts(),
                    next);
            return false;
        }
        notification.markSent(now);
        return true;
    }

    private Instant nextAttempt(int attemptsSoFar, Instant now) {
        NotificationProperties.Delivery delivery = properties.getDelivery();
        // Exponential back-off on the failures so far, capped: attempt n waits initial * multiplier^n.
        double wait = delivery.getInitialBackoffMs() * Math.pow(delivery.getBackoffMultiplier(), attemptsSoFar);
        long capped = Math.min((long) wait, delivery.getMaxBackoffMs());
        return now.plus(Duration.ofMillis(capped));
    }

    private static String shortError(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private static void requirePaymentKind(NotificationKind kind) {
        if (!kind.isPaymentKind() || kind == NotificationKind.FRAUD_REVIEW || kind == NotificationKind.FRAUD_DECLINED) {
            throw new IllegalArgumentException(kind + " is not a payment kind");
        }
    }

    private static Long toMinor(String amountMinor, String currencyCode) {
        if (amountMinor == null || currencyCode == null) {
            throw new IllegalArgumentException("a payment notification needs an amount and a currency");
        }
        // The wire carries a decimal string ("12.00"); the row carries minor units. Converted here so
        // a malformed amount dead-letters before any row exists, rather than reaching the template
        // renderer mid-transaction.
        Currency currency;
        try {
            currency = Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + currencyCode + "' is not an ISO 4217 currency code", e);
        }
        return com.fintech.platform.notification.domain.Money.parse(amountMinor, currency)
                .minorUnits();
    }
}
