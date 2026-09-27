package com.fintech.platform.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The double-entry invariant, and the postings that have to satisfy it.
 *
 * <p>These tests are the reason the ledger is trustworthy, so they assert the property rather than the
 * implementation: every posting balances, and conservation holds across the four accounts. A test that
 * checked "hold credits available and debits reserved" would pass on a ledger that had invented a
 * fifth account nobody looked at.
 */
class LedgerPostingsTest {

    private static final Currency GBP = Currency.getInstance("GBP");

    private static Money gbp(String decimal) {
        return Money.parse(decimal, GBP);
    }

    @Nested
    @DisplayName("every posting balances")
    class Balancing {

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = JournalEntryKind.class,
                names = {"FUNDING", "HOLD", "CAPTURE", "RELEASE"})
        @DisplayName("has equal debits and credits")
        void everyPostingBalances(JournalEntryKind kind) {
            Posting posting = postingFor(kind);

            long debits = posting.legs().stream()
                    .filter(leg -> leg.direction() == PostingDirection.DEBIT)
                    .mapToLong(leg -> leg.amount().minorUnits())
                    .sum();
            long credits = posting.legs().stream()
                    .filter(leg -> leg.direction() == PostingDirection.CREDIT)
                    .mapToLong(leg -> leg.amount().minorUnits())
                    .sum();

            assertThat(debits)
                    .as("%s must balance, or money is created or destroyed by this kind", kind)
                    .isEqualTo(credits);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = JournalEntryKind.class,
                names = {"FUNDING", "HOLD", "CAPTURE", "RELEASE"})
        @DisplayName("names exactly two accounts")
        void everyPostingIsAPair(JournalEntryKind kind) {
            assertThat(postingFor(kind).legs()).hasSize(2);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = JournalEntryKind.class,
                names = {"FUNDING", "HOLD", "CAPTURE", "RELEASE"})
        @DisplayName("involves at least one customer account")
        void everyPostingHasACustomerSide(JournalEntryKind kind) {
            long customerLegs = postingFor(kind).legs().stream()
                    .filter(leg -> leg.accountType().isCustomerAccount())
                    .count();

            // At least one, not exactly one: FUNDING and CAPTURE have one customer leg each (a platform
            // account on the other side), while HOLD and RELEASE have two (money moving between the
            // customer's own available and reserved accounts). A posting with no customer leg at all
            // would be a movement of value that no customer is party to, which on this platform means
            // value appearing inside the ledger with no explanation of where it came from.
            assertThat(customerLegs)
                    .as("%s must involve a customer account", kind)
                    .isPositive();
        }

        @Test
        @DisplayName("the four postings cover every account type, and none is orphaned")
        void everyAccountTypeIsUsed() {
            // If a new account type were added and never posted to, it would sit in the enum looking
            // supported while never holding money. Summing the types used across all four postings
            // catches that here rather than in a reconciliation.
            java.util.Set<LedgerAccountType> used = new java.util.HashSet<>();
            for (JournalEntryKind kind : new JournalEntryKind[] {
                JournalEntryKind.FUNDING, JournalEntryKind.HOLD, JournalEntryKind.CAPTURE, JournalEntryKind.RELEASE
            }) {
                used.addAll(postingFor(kind).accountTypes());
            }
            assertThat(used).containsExactlyInAnyOrder(LedgerAccountType.values());
        }
    }

    @Nested
    @DisplayName("rejects an unbalanced posting")
    class Rejection {

        @Test
        @DisplayName("debits and credits that differ")
        void rejectsUnbalanced() {
            assertThatThrownBy(() -> Posting.balanced(
                            JournalEntryKind.HOLD,
                            List.of(
                                    new Posting.Leg(
                                            LedgerAccountType.CUSTOMER_AVAILABLE,
                                            PostingDirection.CREDIT,
                                            gbp("10.00")),
                                    new Posting.Leg(
                                            LedgerAccountType.CUSTOMER_RESERVED, PostingDirection.DEBIT, gbp("9.99")))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must balance");
        }

        @Test
        @DisplayName("a single leg, which is money from nowhere")
        void rejectsSingleLeg() {
            assertThatThrownBy(() -> Posting.balanced(
                            JournalEntryKind.HOLD,
                            List.of(new Posting.Leg(
                                    LedgerAccountType.CUSTOMER_AVAILABLE, PostingDirection.CREDIT, gbp("10.00")))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least two legs");
        }

        @Test
        @DisplayName("legs in two currencies, which would need a rate that does not exist here")
        void rejectsMixedCurrency() {
            assertThatThrownBy(() -> Posting.balanced(
                            JournalEntryKind.HOLD,
                            List.of(
                                    new Posting.Leg(
                                            LedgerAccountType.CUSTOMER_AVAILABLE,
                                            PostingDirection.CREDIT,
                                            gbp("10.00")),
                                    new Posting.Leg(
                                            LedgerAccountType.CUSTOMER_RESERVED,
                                            PostingDirection.DEBIT,
                                            Money.parse("10.00", Currency.getInstance("USD"))))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("one currency");
        }

        @Test
        @DisplayName("a zero leg, which balances arithmetically and means nothing")
        void rejectsZeroLeg() {
            assertThatThrownBy(() -> Posting.balanced(
                            JournalEntryKind.HOLD,
                            List.of(
                                    new Posting.Leg(
                                            LedgerAccountType.CUSTOMER_AVAILABLE,
                                            PostingDirection.CREDIT,
                                            Money.zero(GBP)),
                                    new Posting.Leg(
                                            LedgerAccountType.CUSTOMER_RESERVED,
                                            PostingDirection.DEBIT,
                                            Money.zero(GBP)))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be positive");
        }
    }

    @Nested
    @DisplayName("conservation across a whole payment")
    class Conservation {

        @Test
        @DisplayName("fund then hold then capture leaves the total across all accounts at zero")
        void fullLifecycleConservesValue() {
            // The property that matters most, and the one that would be broken by an off-by-sign in
            // any of the four postings. Funded 100, paid 40, the platform is holding 60 and the
            // customer's 60 is reserved; summing all four accounts must be exactly zero, because every
            // posting moved value between two of them and none of them created any.
            LedgerAccount available = account(LedgerAccountType.CUSTOMER_AVAILABLE);
            LedgerAccount reserved = account(LedgerAccountType.CUSTOMER_RESERVED);
            LedgerAccount funding = account(LedgerAccountType.PLATFORM_FUNDING);
            LedgerAccount clearing = account(LedgerAccountType.PLATFORM_CLEARING);

            apply(available, reserved, funding, LedgerPostings.funding(gbp("100.00")));
            assertThat(available.balance()).isEqualTo(gbp("100.00"));

            apply(available, reserved, funding, LedgerPostings.hold(gbp("40.00")));
            assertThat(available.balance()).isEqualTo(gbp("60.00"));
            assertThat(reserved.balance()).isEqualTo(gbp("40.00"));

            apply(available, reserved, clearing, LedgerPostings.capture(gbp("40.00")));
            assertThat(reserved.balance()).isEqualTo(Money.zero(GBP));
            assertThat(clearing.balance()).isEqualTo(gbp("40.00"));

            long total = available.balanceMinor()
                    + reserved.balanceMinor()
                    + funding.balanceMinor()
                    + clearing.balanceMinor();
            assertThat(total)
                    .as("sum of every account in the ledger must be zero, or value was created")
                    .isZero();
        }

        @Test
        @DisplayName("a released hold returns the customer to exactly the starting balance")
        void releaseRestoresTheStartingBalance() {
            LedgerAccount available = account(LedgerAccountType.CUSTOMER_AVAILABLE);
            LedgerAccount reserved = account(LedgerAccountType.CUSTOMER_RESERVED);
            LedgerAccount funding = account(LedgerAccountType.PLATFORM_FUNDING);

            apply(available, reserved, funding, LedgerPostings.funding(gbp("100.00")));
            long afterFunding = available.balanceMinor();

            apply(available, reserved, funding, LedgerPostings.hold(gbp("40.00")));
            apply(available, reserved, funding, LedgerPostings.release(gbp("40.00")));

            assertThat(available.balanceMinor()).isEqualTo(afterFunding);
            assertThat(reserved.balanceMinor()).isZero();
        }

        @Test
        @DisplayName("a reversal of a capture undoes it exactly")
        void reversalUndoesACapture() {
            LedgerAccount available = account(LedgerAccountType.CUSTOMER_AVAILABLE);
            LedgerAccount reserved = account(LedgerAccountType.CUSTOMER_RESERVED);
            LedgerAccount funding = account(LedgerAccountType.PLATFORM_FUNDING);
            LedgerAccount clearing = account(LedgerAccountType.PLATFORM_CLEARING);

            apply(available, reserved, funding, LedgerPostings.funding(gbp("100.00")));
            apply(available, reserved, funding, LedgerPostings.hold(gbp("40.00")));
            apply(available, reserved, clearing, LedgerPostings.capture(gbp("40.00")));

            long totalBefore = sumAll(available, reserved, funding, clearing);

            Posting capture = LedgerPostings.capture(gbp("40.00"));
            apply(available, reserved, clearing, capture.asReversalOf(capture));

            assertThat(clearing.balanceMinor()).isZero();
            assertThat(reserved.balanceMinor()).isEqualTo(gbp("40.00").minorUnits());
            assertThat(sumAll(available, reserved, funding, clearing))
                    .as("a reversal must restore the sum, not merely move it elsewhere")
                    .isEqualTo(totalBefore);
        }
    }

    @Test
    @DisplayName("a reversal flips every direction and changes nothing else")
    void reversalIsTheMirrorImage() {
        Posting hold = LedgerPostings.hold(gbp("40.00"));
        Posting reversal = hold.asReversalOf(hold);

        assertThat(reversal.kind()).isEqualTo(JournalEntryKind.REVERSAL);
        assertThat(reversal.amount()).isEqualTo(hold.amount());
        for (int i = 0; i < hold.legs().size(); i++) {
            assertThat(reversal.legs().get(i).accountType())
                    .isEqualTo(hold.legs().get(i).accountType());
            assertThat(reversal.legs().get(i).amount())
                    .isEqualTo(hold.legs().get(i).amount());
            assertThat(reversal.legs().get(i).direction())
                    .isEqualTo(hold.legs().get(i).direction().opposite());
        }
    }

    // ---------------------------------------------------------------------------- helpers

    private static Posting postingFor(JournalEntryKind kind) {
        Money amount = gbp("40.00");
        return switch (kind) {
            case FUNDING -> LedgerPostings.funding(amount);
            case HOLD -> LedgerPostings.hold(amount);
            case CAPTURE -> LedgerPostings.capture(amount);
            case RELEASE -> LedgerPostings.release(amount);
            case REVERSAL ->
                throw new IllegalArgumentException("REVERSAL is produced by asReversalOf, not built directly");
        };
    }

    private static LedgerAccount account(LedgerAccountType type) {
        return type.isCustomerAccount()
                ? LedgerAccount.customer(java.util.UUID.randomUUID(), "digest", type, GBP)
                : LedgerAccount.platform(java.util.UUID.randomUUID(), type, GBP);
    }

    /** Applies a posting's legs to the matching accounts, as the service would. */
    private static void apply(
            LedgerAccount available, LedgerAccount reserved, LedgerAccount fundingOrClearing, Posting posting) {
        for (Posting.Leg leg : posting.legs()) {
            LedgerAccount target = switch (leg.accountType()) {
                case CUSTOMER_AVAILABLE -> available;
                case CUSTOMER_RESERVED -> reserved;
                case PLATFORM_FUNDING, PLATFORM_CLEARING -> fundingOrClearing;
            };
            target.apply(leg.direction(), leg.amount());
        }
    }

    private static long sumAll(LedgerAccount... accounts) {
        long total = 0;
        for (LedgerAccount account : accounts) {
            total += account.balanceMinor();
        }
        return total;
    }
}
