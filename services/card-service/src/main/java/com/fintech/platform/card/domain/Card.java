package com.fintech.platform.card.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * A card, the root of this service's aggregate.
 *
 * <p>Note what is not a field. There is no PAN, no CVV, no track data, no PIN and no cardholder name.
 * The identifying value is a {@link CardToken}, and the only human-visible fragment is
 * {@link #last4}, which is not sensitive authentication data and is what appears on every receipt a
 * cardholder has ever seen. A card number has no path into this class: it is minted, passed to
 * {@link com.fintech.platform.card.tokenisation.CardTokenizer}, and the local variable that held it
 * goes out of scope. That is a stronger guarantee than a policy, because there is no column a future
 * migration could accidentally populate.
 *
 * <p>The CVV is absent by a different route, and the distinction is worth being explicit about. PCI DSS
 * permits storing a PAN encrypted and forbids storing a CVV <em>after</em> authorisation, always. This
 * platform chooses the stronger of the two options available to it: it stores neither, so there is no
 * window in which a card that has been used, and whose CVV is therefore known to have existed, leaves a
 * CVV behind. Since this platform never authorises, the simpler rule holds everywhere.
 *
 * <p>Ownership is held as {@link #ownerSubjectDigest}, a keyed one-way handle. See
 * {@link com.fintech.platform.card.tokenisation.CardTokenizer#ownerDigest} for why that is not the raw
 * subject, and ADR-0006 for the whole arrangement.
 *
 * <p>Not thread-safe and not meant to be: loaded, mutated and saved within one transaction, with
 * {@link #version} turning a lost update into a conflict rather than a silent overwrite.
 */
@Entity
@Table(name = "cards")
public class Card {

    /**
     * The longest lifetime a card may be issued with: ten years.
     *
     * <p>A bound rather than a convention, because {@code Period} accepts arbitrarily large values and
     * a negative or absurd one would otherwise be caught only by a date arithmetic overflow. Ten years
     * exceeds the longest real validity period by a wide margin.
     */
    public static final long MAX_VALIDITY_MONTHS = 120;

    @Id
    private UUID id;
    /**
     * The tokenised card number.
     *
     * <p>Unique, so two cards cannot end up sharing a token. Deterministic tokenisation means the same
     * number would produce the same token, so this constraint is what stops a second issue of an
     * already-issued number rather than being a formality.
     */
    @Column(name = "token", nullable = false, unique = true, length = 64)
    private String token;

    /** Keyed digest of the cardholder's subject. Indexed; the only way a card is found by its owner. */
    @Column(name = "owner_subject_digest", nullable = false, length = 64)
    private String ownerSubjectDigest;

    /**
     * The customer profile this card belongs to.
     *
     * <p>A reference, not a foreign key, because ADR-0002 gives each service its own database and no
     * cross-database constraints are possible. It is also the value the platform checks for identity
     * approval at issue time, and a card therefore always names a customer that existed and was
     * approved at some point, even if that service is unavailable later.
     */
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    /** The last four digits. Display only; the only part of the number the platform keeps. */
    @Column(name = "last4", nullable = false, length = 4)
    private String last4;

    @Enumerated(EnumType.STRING)
    @Column(name = "brand", nullable = false, length = 16)
    private CardBrand brand;

    /**
     * The last day the card is valid.
     *
     * <p>A date rather than a month and year, because "valid through the end of the month" is the rule
     * every scheme uses and expressing it as the month alone would push that comparison into every
     * caller. Storing the last valid day moves the rule to the one place that owns the card.
     */
    @Column(name = "expires_on", nullable = false)
    private LocalDate expiresOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CardStatus status;

    @Column(name = "frozen_at")
    private Instant frozenAt;

    @Column(name = "lost_at")
    private Instant lostAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** JPA only. */
    protected Card() {}

    /**
     * Issues a card from a freshly minted number.
     *
     * <p>The {@link Pan} parameter is taken, reduced to its last four digits and dropped. It is not
     * stored, not copied to a field, and not returned; the caller keeps the only other copy and shows
     * it to the cardholder once. Keeping the parameter typed as {@link Pan} rather than pre-digested
     * makes the "reduce to last4 immediately" step impossible to forget at a call site.
     *
     * @param validityPeriod how long the card is good for, measured from issue
     * @throws IllegalArgumentException if the period is not positive
     */
    public static Card issue(
            UUID id,
            String token,
            String ownerSubjectDigest,
            UUID customerId,
            Pan pan,
            CardBrand brand,
            Period validityPeriod,
            Instant now) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(token, "token must not be null");
        Objects.requireNonNull(ownerSubjectDigest, "ownerSubjectDigest must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(pan, "pan must not be null");
        Objects.requireNonNull(brand, "brand must not be null");
        Objects.requireNonNull(validityPeriod, "validityPeriod must not be null");
        Objects.requireNonNull(now, "now must not be null");

        // Whole months, not years and not days. Years would let a card issued on 29 February reach a
        // 29 February in a year that has no 29 February, and days would express a lifetime quoted in
        // years as a number that varies with the length of each month. Plus-then-truncate to the end
        // of the month is the rule schemes actually use, and it is a pure function of `now` so a test
        // can pin an expiry without touching the wall clock.
        long months = validityPeriod.toTotalMonths();
        if (months <= 0 || months > MAX_VALIDITY_MONTHS) {
            throw new IllegalArgumentException(
                    "validityPeriod must be between 1 and " + MAX_VALIDITY_MONTHS + " months");
        }
        LocalDate issuedOn = LocalDate.ofInstant(now, ZoneOffset.UTC);
        LocalDate expiresOn = lastDayOfMonth(issuedOn.plusMonths(months));

        Card card = new Card();
        card.id = id;
        card.token = token;
        card.ownerSubjectDigest = ownerSubjectDigest;
        card.customerId = customerId;
        card.last4 = pan.last4();
        card.brand = brand;
        card.expiresOn = lastDayOfMonth(expiresOn);
        card.status = CardStatus.ACTIVE;
        card.createdAt = now;
        card.updatedAt = now;
        return card;
    }

    /** Suspends the card. Reversible by {@link #unfreeze}. */
    public void freeze(Instant now) {
        requireMutably();
        this.status = CardStateMachine.require(this.status, CardStatus.FROZEN);
        this.frozenAt = Objects.requireNonNull(now, "now must not be null");
        this.updatedAt = now;
    }

    /**
     * Returns a frozen card to service.
     *
     * @throws com.fintech.platform.common.error.ApiException {@link CardErrorCodes#CARD_REPORTED_LOST}
     *     if the card was reported lost, which is the transition a cardholder is most likely to attempt
     *     by mistake and the one that needs a specific answer rather than a generic conflict
     */
    public void unfreeze(Instant now) {
        requireMutably();
        if (this.status == CardStatus.LOST) {
            throw com.fintech.platform.card.error.CardErrorCodes.CARD_REPORTED_LOST.exception(
                    "card was reported lost on " + this.lostAt + " and cannot be reactivated");
        }
        this.status = CardStateMachine.require(this.status, CardStatus.ACTIVE);
        this.frozenAt = null;
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    /** Reports the card lost or stolen. Terminal for this card. */
    public void reportLost(Instant now) {
        requireMutably();
        this.status = CardStateMachine.require(this.status, CardStatus.LOST);
        this.lostAt = Objects.requireNonNull(now, "now must not be null");
        this.updatedAt = now;
    }

    /** Closes the card at the holder's request. Terminal. */
    public void cancel(Instant now) {
        requireMutably();
        this.status = CardStateMachine.require(this.status, CardStatus.CANCELLED);
        this.cancelledAt = Objects.requireNonNull(now, "now must not be null");
        this.updatedAt = now;
    }

    /**
     * Retires the card on expiry.
     *
     * <p>Reached by the service, never by a caller, exactly as a lapsed identity check is. A cardholder
     * asking to expire their own card is asking for the one transition that belongs to the calendar.
     */
    public void expire(Instant now) {
        requireMutably();
        this.status = CardStateMachine.require(this.status, CardStatus.EXPIRED);
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    /**
     * Whether the calendar has passed this card's expiry while it is still nominally in use.
     *
     * <p>Checked against the status as well as the date, so an already-cancelled card is never reported
     * as merely lapsed. A card can be past its date and still {@code FROZEN}: nobody has noticed yet,
     * and the correct next step is expiry, not an unfreeze that would be immediately undone.
     *
     * <p>Strictly after, not on or after. {@link #expiresOn} is the last day the card works, so a card
     * expiring 31 July is good for the whole of 31 July and lapses on 1 August. The inclusive form
     * silently costs every cardholder the final day of its validity, and because the sweep uses the
     * same comparison the error would be invisible: the card would be retired a day early and nothing
     * downstream would ever see the difference.
     */
    public boolean hasLapsed(LocalDate today) {
        return !CardStateMachine.isTerminal(status) && today.isAfter(expiresOn);
    }

    /** Whether the card can be presented for a payment on this date. The mirror of {@link #hasLapsed}. */
    public boolean isUsable(LocalDate today) {
        return status == CardStatus.ACTIVE && !today.isAfter(expiresOn);
    }

    /** Whether the holder may still operate on the card, as opposed to merely hold its record. */
    public boolean isOperable() {
        return !CardStateMachine.isTerminal(status);
    }

    private void requireMutably() {
        if (CardStateMachine.isTerminal(status)) {
            throw com.fintech.platform.card.error.CardErrorCodes.CARD_INVALID_TRANSITION.exception(
                    "card is " + status + " and no further operations are possible",
                    java.util.Map.of("status", status.name()));
        }
    }

    private static LocalDate lastDayOfMonth(LocalDate anyDayInMonth) {
        return anyDayInMonth.withDayOfMonth(anyDayInMonth.lengthOfMonth());
    }

    public UUID getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public String getOwnerSubjectDigest() {
        return ownerSubjectDigest;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getLast4() {
        return last4;
    }

    public CardBrand getBrand() {
        return brand;
    }

    public LocalDate getExpiresOn() {
        return expiresOn;
    }

    public CardStatus getStatus() {
        return status;
    }

    public Instant getFrozenAt() {
        return frozenAt;
    }

    public Instant getLostAt() {
        return lostAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    /** Never renders the token or the owner digest; see {@link #token}. */
    @Override
    public String toString() {
        return "Card[id=" + id + ", last4=" + last4 + ", brand=" + brand + ", status=" + status + ", expiresOn="
                + expiresOn + "]";
    }
}
