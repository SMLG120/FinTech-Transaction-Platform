package com.fintech.platform.fraud.rules;

import static com.fintech.platform.fraud.FraudFixtures.History.NOT_SEEN;
import static com.fintech.platform.fraud.FraudFixtures.History.SEEN;
import static com.fintech.platform.fraud.FraudFixtures.History.UNKNOWN;
import static com.fintech.platform.fraud.FraudFixtures.NOW;
import static com.fintech.platform.fraud.FraudFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;

import com.fintech.platform.fraud.FraudFixtures;
import com.fintech.platform.fraud.config.FraudProperties;
import com.fintech.platform.fraud.domain.RiskRule;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * R003, R005, R006 and R007 — the four rules that read history.
 *
 * <p>They are tested together because the property that matters for all of them is the same one, and
 * splitting them into four files would repeat that property four times instead of stating it once:
 * <b>an unknown fact is not a negative fact.</b> Each rule has a box where "the lookup failed" is
 * distinct from "the lookup succeeded and found nothing", and a rule that collapses the two turns a
 * database hiccup into a fraud signal. That is the failure mode with the worst possible direction — it
 * manufactures alerts precisely when the system is already unhealthy — and it is invisible in a test
 * that only ever passes either true or false.
 *
 * <p>So every rule here is tested three ways for the same fact: known-new (fires), known-old (does not),
 * and unknown (does not fire, and says so).
 */
class HistoryRulesTest {

    private final FraudProperties properties = properties();

    // ------------------------------------------------------------------------------- R003 new device

    @Nested
    @DisplayName("R003 NEW_DEVICE_LARGE_AMOUNT")
    class NewDeviceLargeAmount {

        private final NewDeviceLargeAmountRule rule = new NewDeviceLargeAmountRule(properties);

        @Test
        @DisplayName("is worth 25")
        void identity() {
            assertThat(rule.id()).isEqualTo("R003");
            assertThat(rule.points()).isEqualTo(25);
        }

        @Test
        @DisplayName("needs both halves: a device new to this card, and a large amount")
        void requiresBothHalves() {
            assertThat(rule.evaluate(FraudFixtures.payment("1500.00")
                            .deviceSeenOnCard(NOT_SEEN.boxed())
                            .build()))
                    .as("new device and large amount")
                    .isPresent();
            assertThat(rule.evaluate(FraudFixtures.payment("10.00")
                            .deviceSeenOnCard(NOT_SEEN.boxed())
                            .build()))
                    .as("new device, but a small payment — a customer who switched phones")
                    .isEmpty();
            assertThat(rule.evaluate(FraudFixtures.payment("1500.00")
                            .deviceSeenOnCard(SEEN.boxed())
                            .build()))
                    .as("large amount, but a device this card has used before")
                    .isEmpty();
        }

        @Test
        @DisplayName("does not fire when the device lookup failed, because unread is not new")
        void unknownIsNotNew() {
            // This is the test the rule exists to pass. An observation row that could not be read is a
            // degraded read, and treating it as "no one has ever seen this device" would raise an alert
            // on every payment made while the database was unhappy.
            assertThat(rule.evaluate(FraudFixtures.payment("1500.00")
                            .deviceSeenOnCard(UNKNOWN.boxed())
                            .build()))
                    .as("device-on-card lookup failed; the rule must not infer 'new' from that")
                    .isEmpty();
        }

        @Test
        @DisplayName("does not fire without a device at all, and does not call that new")
        void absentDeviceIsNotNew() {
            assertThat(rule.evaluate(FraudFixtures.payment("1500.00").noClient().build()))
                    .as("a merchant-server payment has no device; that is not a new device")
                    .isEmpty();
        }

        @Test
        @DisplayName("scopes newness to the card, and records that scope in the evidence")
        void scopedToTheCard() {
            // The same customer, the same device, a different card. "New to this card" is what the rule
            // means; if a customer's own second card made every payment look like a new device, the rule
            // would be unusable within a week of launch.
            var finding = rule.evaluate(FraudFixtures.payment("1500.00")
                            .deviceSeenOnCard(NOT_SEEN.boxed())
                            .deviceSeenByCustomer(SEEN.boxed())
                            .build())
                    .orElseThrow();
            assertThat(finding.evidence()).containsEntry("deviceScope", "card");
        }

        @ParameterizedTest
        @CsvSource({"1000.00, false", "1000.01, true", "999.99, false"})
        @DisplayName("applies the new-device amount threshold on its own")
        void ownThreshold(String amount, boolean expected) {
            assertThat(rule.evaluate(FraudFixtures.payment(amount)
                                    .deviceSeenOnCard(NOT_SEEN.boxed())
                                    .build())
                            .isPresent())
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("skips a currency with no configured threshold")
        void skipsUnconfiguredCurrency() {
            assertThat(rule.evaluate(FraudFixtures.cleanPayment("9000.00", "JPY")
                            .deviceSeenOnCard(NOT_SEEN.boxed())
                            .build()))
                    .isEmpty();
        }
    }

    // ------------------------------------------------------------------------------- R005 recent activation

    @Nested
    @DisplayName("R005 RECENT_CARD_ACTIVATION")
    class RecentCardActivation {

        private final RecentCardActivationRule rule = new RecentCardActivationRule(properties);

        @Test
        @DisplayName("is worth 20")
        void identity() {
            assertThat(rule.id()).isEqualTo("R005");
            assertThat(rule.points()).isEqualTo(20);
        }

        @Test
        @DisplayName("fires for a card first observed minutes ago")
        void firesForNewCard() {
            assertThat(rule.evaluate(FraudFixtures.payment("50.00")
                            .cardFirstSeenMinutesAgo(30)
                            .build()))
                    .isPresent();
        }

        @Test
        @DisplayName("fires exactly on the window boundary, and not a second past it")
        void boundaryIsInclusive() {
            // 24 hours is the configured window. "Within 24 hours" includes exactly 24 hours; the
            // comparison is > window for the rejection, so the boundary itself fires.
            assertThat(rule.evaluate(FraudFixtures.payment("50.00")
                            .cardFirstSeenAt(NOW.minusSeconds(86_400))
                            .build()))
                    .as("exactly 24h ago is inside a 24h window")
                    .isPresent();
            assertThat(rule.evaluate(FraudFixtures.payment("50.00")
                            .cardFirstSeenAt(NOW.minusSeconds(86_401))
                            .build()))
                    .as("one second past 24h is outside it")
                    .isEmpty();
        }

        @Test
        @DisplayName("does not fire for a card seen months ago")
        void quietForAnOldCard() {
            assertThat(rule.evaluate(FraudFixtures.payment("50.00")
                            .cardFirstSeenAt(NOW.minusSeconds(86_400L * 90))
                            .build()))
                    .isEmpty();
        }

        @Test
        @DisplayName("does not fire when the first-seen time is in the future, which is a clock problem not a signal")
        void ignoresAFutureFirstSeen() {
            // A negative duration would otherwise satisfy "recent". This is what a skewed replica or a
            // replayed event from a node with a fast clock looks like, and it must not be evidence of
            // anything.
            assertThat(rule.evaluate(FraudFixtures.payment("50.00")
                            .cardFirstSeenAt(NOW.plusSeconds(3600))
                            .build()))
                    .isEmpty();
        }

        @Test
        @DisplayName("does not fire without a card, or without a first-seen time")
        void needsBothCardAndTime() {
            assertThat(rule.evaluate(FraudFixtures.payment("50.00").card(null).build()))
                    .as("no card reference at all")
                    .isEmpty();
            assertThat(rule.evaluate(FraudFixtures.payment("50.00").build()))
                    .as("a card is present but has never been seen before, so there is no interval")
                    .isEmpty();
        }

        @Test
        @DisplayName("labels itself a proxy, because fraud-service has no card lifecycle events")
        void evidenceSaysItIsAProxy() {
            // An analyst shown "recent card activation" would reasonably ask which system reported the
            // activation. Nobody did: the first thing this service saw is not an activation, and saying
            // so in the evidence is what stops the finding being read as a fact about card processing.
            var finding = rule.evaluate(FraudFixtures.payment("50.00")
                            .cardFirstSeenMinutesAgo(5)
                            .build())
                    .orElseThrow();
            assertThat(finding.evidence()).containsEntry("proxy", "first-observed-by-fraud-service");
            assertThat(finding.explanation()).contains("stands in for");
        }
    }

    // ------------------------------------------------------------------------------- R006 shared device

    @Nested
    @DisplayName("R006 SHARED_DEVICE")
    class SharedDevice {

        private final SharedDeviceRule rule = new SharedDeviceRule(properties);

        @Test
        @DisplayName("is worth 25")
        void identity() {
            assertThat(rule.id()).isEqualTo("R006");
            assertThat(rule.points()).isEqualTo(25);
        }

        @Test
        @DisplayName("fires when another customer has used this device")
        void firesOnASharedDevice() {
            assertThat(rule.evaluate(FraudFixtures.payment("10.00")
                            .otherCustomersOnDevice(1)
                            .build()))
                    .isPresent();
            assertThat(rule.evaluate(FraudFixtures.payment("10.00")
                            .otherCustomersOnDevice(4)
                            .build()))
                    .isPresent();
        }

        @Test
        @DisplayName("does not fire for a device used only by this customer")
        void quietForASoloDevice() {
            assertThat(rule.evaluate(FraudFixtures.payment("10.00")
                            .otherCustomersOnDevice(0)
                            .build()))
                    .as("zero other customers is a count, not an unknown, and it is a negative")
                    .isEmpty();
        }

        @Test
        @DisplayName("needs a device; the count is meaningless without one")
        void needsADevice() {
            assertThat(rule.evaluate(FraudFixtures.payment("10.00")
                            .noClient()
                            .otherCustomersOnDevice(9)
                            .build()))
                    .isEmpty();
        }

        @Test
        @DisplayName("is unaffected by the amount, because the amount is not part of this rule")
        void amountIrrelevant() {
            assertThat(rule.evaluate(FraudFixtures.payment("99999.00")
                            .otherCustomersOnDevice(2)
                            .build()))
                    .isPresent();
        }

        @Test
        @DisplayName("honours a configured threshold above one")
        void thresholdIsConfiguration() {
            FraudProperties properties = properties();
            properties.getRules().setSharedDeviceMinOtherCustomers(3);
            SharedDeviceRule strict = new SharedDeviceRule(properties);
            assertThat(strict.evaluate(FraudFixtures.payment("10.00")
                            .otherCustomersOnDevice(2)
                            .build()))
                    .as("two others is below a threshold of three")
                    .isEmpty();
            assertThat(strict.evaluate(FraudFixtures.payment("10.00")
                            .otherCustomersOnDevice(3)
                            .build()))
                    .isPresent();
        }
    }

    // ------------------------------------------------------------------------------- R007 new merchant

    @Nested
    @DisplayName("R007 NEW_MERCHANT_LARGE_AMOUNT")
    class NewMerchantLargeAmount {

        private final NewMerchantLargeAmountRule rule = new NewMerchantLargeAmountRule(properties);

        @Test
        @DisplayName("is worth 15")
        void identity() {
            assertThat(rule.id()).isEqualTo("R007");
            assertThat(rule.points()).isEqualTo(15);
        }

        @Test
        @DisplayName("needs both halves: a merchant this customer has not paid, and an amount above its threshold")
        void requiresBothHalves() {
            assertThat(rule.evaluate(FraudFixtures.payment("600.00")
                            .merchantSeen(NOT_SEEN.boxed())
                            .build()))
                    .isPresent();
            assertThat(rule.evaluate(FraudFixtures.payment("10.00")
                            .merchantSeen(NOT_SEEN.boxed())
                            .build()))
                    .as("first payment to a coffee shop for a coffee")
                    .isEmpty();
            assertThat(rule.evaluate(FraudFixtures.payment("600.00")
                            .merchantSeen(SEEN.boxed())
                            .build()))
                    .as("a known merchant, large amount")
                    .isEmpty();
        }

        @Test
        @DisplayName("does not fire when the merchant lookup failed")
        void unknownIsNotNew() {
            assertThat(rule.evaluate(FraudFixtures.payment("600.00")
                            .merchantSeen(UNKNOWN.boxed())
                            .build()))
                    .as("merchant history unread; every merchant on the platform is not suddenly new")
                    .isEmpty();
        }

        @Test
        @DisplayName("does not fire when the payment carries no merchant identity at all")
        void needsAnIdentity() {
            assertThat(rule.evaluate(FraudFixtures.payment("600.00")
                            .payee(null, null)
                            .merchantSeen(NOT_SEEN.boxed())
                            .build()))
                    .isEmpty();
        }

        @Test
        @DisplayName("records which of the two merchant identifiers it keyed on, and scopes to the customer")
        void evidenceNamesTheIdentitySource() {
            // A payeeReference is assigned by a merchant and cannot be typed freely; a payeeName is text
            // anyone can supply. The rule fires on both, but an analyst triaging a finding needs to know
            // which one produced it — a name-based finding is weaker, and the evidence says so.
            var byReference = rule.evaluate(FraudFixtures.payment("600.00")
                            .payee("Any Name", "merchant-99")
                            .merchantSeen(NOT_SEEN.boxed())
                            .build())
                    .orElseThrow();
            assertThat(byReference.evidence())
                    .containsEntry("identitySource", "payeeReference")
                    .containsEntry("merchantScope", "customer");

            var byName = rule.evaluate(FraudFixtures.payment("600.00")
                            .payee("Corner Shop", null)
                            .merchantSeen(NOT_SEEN.boxed())
                            .build())
                    .orElseThrow();
            assertThat(byName.evidence()).containsEntry("identitySource", "payeeName");
        }

        @ParameterizedTest
        @CsvSource({"500.00, false", "500.01, true", "499.99, false"})
        @DisplayName("applies the new-merchant amount threshold on its own")
        void ownThreshold(String amount, boolean expected) {
            assertThat(rule.evaluate(FraudFixtures.payment(amount)
                                    .merchantSeen(NOT_SEEN.boxed())
                                    .build())
                            .isPresent())
                    .isEqualTo(expected);
        }
    }

    // ------------------------------------------------------------------------------- shared contract

    @Test
    @DisplayName("no history rule fires on a payment with no history to read")
    void nothingFiresOnAnUnknowablePayment() {
        // A payment where every lookup failed and no client was observed. Every rule must decline: this
        // is the payment the engine knows least about, and it is precisely the one where inventing a
        // signal would be most damaging.
        var blind = FraudFixtures.payment("9000.00")
                .noClient()
                .history(UNKNOWN, UNKNOWN, 0)
                .velocity(0, false)
                .build();
        List<RiskRule> rules = List.of(
                new NewDeviceLargeAmountRule(properties),
                new RecentCardActivationRule(properties),
                new SharedDeviceRule(properties),
                new NewMerchantLargeAmountRule(properties));
        for (RiskRule rule : rules) {
            assertThat(rule.evaluate(blind))
                    .as("%s on a payment with no readable history", rule.id())
                    .isEmpty();
        }
    }
}
