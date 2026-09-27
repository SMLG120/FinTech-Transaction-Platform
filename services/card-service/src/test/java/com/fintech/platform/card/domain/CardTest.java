package com.fintech.platform.card.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.card.error.CardErrorCodes;
import com.fintech.platform.common.error.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The card aggregate, with no database and no Spring.
 *
 * <p>Everything asserted here is a rule the database cannot enforce, which is the reason this file
 * exists separately from {@code CardPersistenceTest}. A CHECK constraint can confirm a row is
 * self-consistent; it cannot tell you that a card which was frozen and then cancelled ought to have
 * kept its {@code frozenAt}, that a validity of zero months is meaningless, or that a caller who
 * tries to unfreeze a lost card deserves a specific error rather than a generic conflict.
 */
class CardTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    private static final String TOKEN = "a".repeat(64);
    private static final String OWNER_DIGEST = "b".repeat(64);

    private static Card issue(Period validity) {
        return issue(validity, NOW);
    }

    private static Card issue(Period validity, Instant at) {
        return Card.issue(
                UUID.randomUUID(), TOKEN, OWNER_DIGEST, UUID.randomUUID(), Pan.mint(), CardBrand.DEBIT, validity, at);
    }

    @Nested
    @DisplayName("issuing")
    class Issuing {

        @Test
        @DisplayName("keeps only the last four digits and the token, never the number")
        void keepsNoCardNumber() {
            Pan pan = Pan.mint();
            Card card = Card.issue(
                    UUID.randomUUID(),
                    TOKEN,
                    OWNER_DIGEST,
                    UUID.randomUUID(),
                    pan,
                    CardBrand.DEBIT,
                    Period.ofMonths(36),
                    NOW);

            assertThat(card.getLast4()).isEqualTo(pan.last4());
            // The aggregate exposes no accessor for a PAN because there is no field to expose. Asserted
            // through the rendered form too, since a field could be added later and only the rendering
            // would give it away.
            assertThat(card.toString()).doesNotContain(pan.digits());
            assertThat(card.toString()).contains(card.getLast4());
        }

        @Test
        @DisplayName("never renders the token or the owner digest")
        void rendersNoSecrets() {
            // A token is a stable handle on a card number, so leaking one into a log line is a partial
            // leak of the number it stands for. The owner digest is a correlation handle across
            // cardholder identities, and has the same problem. Neither belongs in a toString.
            assertThat(issue(Period.ofMonths(36)).toString()).doesNotContain(TOKEN, OWNER_DIGEST);
        }

        @Test
        @DisplayName("starts active, un-timestamped, and valid from today")
        void startsActive() {
            Card card = issue(Period.ofMonths(36));

            assertThat(card.getStatus()).isEqualTo(CardStatus.ACTIVE);
            assertThat(card.getFrozenAt()).isNull();
            assertThat(card.getLostAt()).isNull();
            assertThat(card.getCancelledAt()).isNull();
            assertThat(card.getCreatedAt()).isEqualTo(NOW);
            assertThat(card.getUpdatedAt()).isEqualTo(NOW);
            assertThat(card.isUsable(TODAY)).isTrue();
            assertThat(card.hasLapsed(TODAY)).isFalse();
        }

        @Test
        @DisplayName("expires on the last day of the month, not the same day of the month")
        void expiresAtEndOfMonth() {
            // Every scheme's "valid through" date is the end of the month. Expiring on the anniversary
            // day would silently shorten a card issued on the 30th or 31st, which is a customer-visible
            // loss of time the customer paid for.
            Card card = issue(Period.ofMonths(12), Instant.parse("2026-01-31T09:00:00Z"));

            assertThat(card.getExpiresOn()).isEqualTo(LocalDate.of(2027, 1, 31));
        }

        @Test
        @DisplayName("treats the expiry date itself as still valid")
        void validThroughTheExpiryDate() {
            Card card = issue(Period.ofMonths(1));

            // A card that stops working at midnight starting on its expiry date loses its last day. The
            // comparison is hasLapsed that flips, so the boundary is asserted directly on it.
            assertThat(card.hasLapsed(card.getExpiresOn())).isFalse();
            assertThat(card.hasLapsed(card.getExpiresOn().plusDays(1))).isTrue();
        }

        @Test
        @DisplayName("refuses a validity of zero or negative months")
        void refusesNonPositiveValidity() {
            // `Period.ofDays(-1)` is the shape of mistake worth catching: a caller computing an expiry
            // from a deadline that has already passed would produce one, and a card issued with it would
            // be born expired and look like a data problem forever after.
            assertThatThrownBy(() -> issue(Period.ofDays(-1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("validityPeriod must be between 1");
            assertThatThrownBy(() -> issue(Period.ZERO)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("refuses a validity beyond the maximum lifetime")
        void refusesAbsurdValidity() {
            assertThatThrownBy(() -> issue(Period.ofMonths((int) Card.MAX_VALIDITY_MONTHS + 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("validityPeriod must be between 1");
        }
    }

    @Nested
    @DisplayName("freezing")
    class Freezing {

        @Test
        @DisplayName("records when the card was frozen")
        void recordsTheMoment() {
            Card card = issue(Period.ofMonths(36));
            Instant frozenAt = NOW.plusSeconds(3600);

            card.freeze(frozenAt);

            assertThat(card.getStatus()).isEqualTo(CardStatus.FROZEN);
            assertThat(card.getFrozenAt()).isEqualTo(frozenAt);
            assertThat(card.getUpdatedAt()).isEqualTo(frozenAt);
            assertThat(card.isUsable(TODAY)).isFalse();
        }

        @Test
        @DisplayName("clears the freeze on unfreeze")
        void clearsTheMoment() {
            Card card = issue(Period.ofMonths(36));
            card.freeze(NOW);

            card.unfreeze(NOW.plusSeconds(600));

            assertThat(card.getStatus()).isEqualTo(CardStatus.ACTIVE);
            // Nulled rather than kept, because a second freeze must overwrite it and a retained value
            // would make "frozen since" ambiguous.
            assertThat(card.getFrozenAt()).isNull();
            assertThat(card.getLostAt()).isNull();
            assertThat(card.isUsable(TODAY)).isTrue();
        }

        @Test
        @DisplayName("refuses to unfreeze a card that was never frozen")
        void refusesUnfreezingAnActiveCard() {
            Card card = issue(Period.ofMonths(36));

            assertThatThrownBy(() -> card.unfreeze(NOW))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode().code())
                    .isEqualTo(CardErrorCodes.CARD_INVALID_TRANSITION.code());
        }

        @Test
        @DisplayName("refuses to unfreeze a card reported lost, with a specific answer")
        void refusesUnfreezingALostCard() {
            // The transition a cardholder is most likely to attempt by mistake, and the one that needs to
            // say why. A generic "invalid transition" would leave them thinking the card was merely
            // frozen and worth retrying.
            Card card = issue(Period.ofMonths(36));
            card.reportLost(NOW.plusSeconds(60));

            assertThatThrownBy(() -> card.unfreeze(NOW.plusSeconds(120)))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode().code())
                    .isEqualTo(CardErrorCodes.CARD_REPORTED_LOST.code());
        }
    }

    @Nested
    @DisplayName("losing and cancelling")
    class LosingAndCancelling {

        @Test
        @DisplayName("keeps frozenAt when a frozen card is reported lost")
        void keepsTheEarlierFreeze() {
            // The aggregate is not obliged to clear frozenAt, and the database's one-way check depends on
            // it not being cleared: frozen_at set with a status of LOST is valid, whereas clearing it
            // would discard the only record that the card was ever suspended.
            Card card = issue(Period.ofMonths(36));
            Instant frozenAt = NOW.plusSeconds(60);
            card.freeze(frozenAt);
            Instant lostAt = NOW.plusSeconds(120);

            card.reportLost(lostAt);

            assertThat(card.getStatus()).isEqualTo(CardStatus.LOST);
            assertThat(card.getLostAt()).isEqualTo(lostAt);
            assertThat(card.getFrozenAt()).isEqualTo(frozenAt);
        }

        @Test
        @DisplayName("keeps every timestamp when a frozen card is cancelled")
        void keepsHistoryOnCancellation() {
            // "When was this card closed" is a question a dispute or a regulator may ask long after the
            // fact, and it is the answer that distinguishes a closure from a card that vanished.
            Card card = issue(Period.ofMonths(36));
            card.freeze(NOW.plusSeconds(60));
            Instant cancelledAt = NOW.plusSeconds(120);

            card.cancel(cancelledAt);

            assertThat(card.getStatus()).isEqualTo(CardStatus.CANCELLED);
            assertThat(card.getCancelledAt()).isEqualTo(cancelledAt);
            assertThat(card.getFrozenAt()).isEqualTo(NOW.plusSeconds(60));
        }

        @Test
        @DisplayName("treats cancelled and expired as final, refusing every further transition")
        void refusesTransitionsAfterTerminal() {
            Card cancelled = issue(Period.ofMonths(36));
            cancelled.cancel(NOW);
            Card expired = issue(Period.ofMonths(36));
            expired.expire(NOW);

            for (Card card : new Card[] {cancelled, expired}) {
                assertThat(card.isOperable()).isFalse();
                for (var transition : java.util.List.<Runnable>of(
                        () -> card.freeze(NOW),
                        () -> card.unfreeze(NOW),
                        () -> card.reportLost(NOW),
                        () -> card.cancel(NOW),
                        () -> card.expire(NOW))) {
                    assertThatThrownBy(transition::run)
                            .isInstanceOf(ApiException.class)
                            .extracting(e -> ((ApiException) e).getErrorCode().code())
                            .isEqualTo(CardErrorCodes.CARD_INVALID_TRANSITION.code());
                }
            }
        }

        @Test
        @DisplayName("still allows a card reported lost to be cancelled")
        void lostIsStillOperable() {
            // LOST blocks reactivation but not closure, and that asymmetry is the point. A cardholder
            // who reports a card stolen still has to be able to close it, and freezing a lost card is
            // likewise permitted because a support agent may legitimately stop a card before marking it
            // lost. Treating LOST as terminal would block the one action a cardholder most needs.
            Card lost = issue(Period.ofMonths(36));
            lost.reportLost(NOW.plusSeconds(60));

            assertThat(lost.isOperable()).isTrue();

            lost.cancel(NOW.plusSeconds(120));
            assertThat(lost.getStatus()).isEqualTo(CardStatus.CANCELLED);
            assertThat(lost.getLostAt()).isEqualTo(NOW.plusSeconds(60));
            assertThat(lost.getCancelledAt()).isEqualTo(NOW.plusSeconds(120));
        }
    }

    @Nested
    @DisplayName("lapsing")
    class Lapsing {

        @Test
        @DisplayName("reports a card past its date as lapsed, even while frozen")
        void frozenAndLapsed() {
            // Nothing may unfreeze it, and the read path would only correct it if somebody happened to
            // look at that card, so the sweep has to be able to see it.
            Card card = issue(Period.ofMonths(1));
            card.freeze(NOW);

            assertThat(card.hasLapsed(LocalDate.of(2026, 8, 1))).isTrue();
        }

        @Test
        @DisplayName("does not report a cancelled card as merely lapsed")
        void cancelledIsNotLapsed() {
            // A cancelled card can be years past its date, and the sweep must not keep picking it up.
            Card card = issue(Period.ofMonths(1));
            card.cancel(NOW.plusSeconds(60));

            assertThat(card.hasLapsed(LocalDate.of(2030, 1, 1))).isFalse();
        }

        @Test
        @DisplayName("does not report a card still within its validity as lapsed")
        void currentIsNotLapsed() {
            assertThat(issue(Period.ofMonths(36)).hasLapsed(TODAY)).isFalse();
        }
    }
}
