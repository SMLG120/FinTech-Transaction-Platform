package com.fintech.platform.fraud.rules;

import static com.fintech.platform.fraud.FraudFixtures.NOW;
import static com.fintech.platform.fraud.FraudFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;

import com.fintech.platform.fraud.FraudFixtures;
import com.fintech.platform.fraud.config.FraudProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * R002 and R004 — the two rules that read a clock, plus the one that reads a remote counter.
 *
 * <p>Both share the same hazard and it is the reason they are tested together. Each compares an elapsed
 * duration against a window, and each has a way to be told something it should not believe: R002 can be
 * handed a count that is a real number but was never obtained, and R004 can be handed a change time in
 * the future. In both cases the tempting implementation treats the number as a fact, and in both cases
 * the result is an alert raised by a system that is broken rather than by a customer who is.
 */
class WindowRulesTest {

    private final FraudProperties properties = properties();

    // ------------------------------------------------------------------------------- R002 velocity

    @Nested
    @DisplayName("R002 TRANSACTION_VELOCITY")
    class Velocity {

        private final TransactionVelocityRule rule = new TransactionVelocityRule(properties);

        @Test
        @DisplayName("is R002 TRANSACTION_VELOCITY, worth 35")
        void identity() {
            assertThat(rule.id()).isEqualTo("R002");
            assertThat(rule.name()).isEqualTo("TRANSACTION_VELOCITY");
            assertThat(rule.points()).isEqualTo(35);
        }

        @ParameterizedTest(name = "{0} payments in the window against a limit of 5")
        @CsvSource({
            // The limit is "more than 5", so 5 is fine and 6 is not. A customer making five payments a
            // minute is busy, not fraudulent, and a rule that fires at five would be off by one on the
            // most common everyday case there is.
            "5, false",
            "6, true",
            "7, true",
            "50, true",
            "1, false",
            "0, false"
        })
        @DisplayName("fires strictly above the configured limit")
        void boundaryIsExclusive(int count, boolean expected) {
            assertThat(rule.evaluate(FraudFixtures.payment("10.00")
                                    .velocity(count)
                                    .build())
                            .isPresent())
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("does not fire when the counter could not be reached")
        void unavailableCounterDoesNotFire() {
            // The most important test in this file. Redis is a remote host and an attacker who can make
            // it slow, or an operator who restarts it, would otherwise cause every in-flight payment to
            // look like it was the first of a burst — or worse, look like count 0 and be waved through.
            // The rule declines, and the decision's facts say velocityAvailable=false so a later reader
            // can see the payment was not checked on this dimension.
            assertThat(rule.evaluate(
                            FraudFixtures.payment("10.00").velocity(0, false).build()))
                    .as("an unreachable counter must not be read as a count of zero")
                    .isEmpty();
        }

        @Test
        @DisplayName("an unavailable counter cannot fire even with a high count recorded alongside it")
        void unavailableWinsOverTheCount() {
            // Defensive: if a future change let a caller pass a high count with available=false, the
            // availability flag has to dominate. The flag exists precisely because the number is not to
            // be trusted.
            assertThat(rule.evaluate(
                            FraudFixtures.payment("10.00").velocity(99, false).build()))
                    .isEmpty();
        }

        @Test
        @DisplayName("counts the current payment, and says so in the evidence")
        void evidenceNamesWhatWasCounted() {
            // The Redis counter is incremented by the current payment before the rule reads it, so the
            // number includes it. An analyst reading "5 payments" against a limit of 5 needs to know
            // whether that 5 includes the one in front of them, and the evidence key is what settles it.
            var finding = rule.evaluate(
                            FraudFixtures.payment("10.00").velocity(6).build())
                    .orElseThrow();
            assertThat(finding.evidence())
                    .containsEntry("countIncludingThis", "6")
                    .containsEntry("threshold", "5")
                    .containsEntry("window", "60s");
        }

        @Test
        @DisplayName("is unaffected by the amount, because a burst is a burst at any size")
        void amountIrrelevant() {
            assertThat(rule.evaluate(FraudFixtures.payment("0.01").velocity(10).build()))
                    .isPresent();
        }

        @Test
        @DisplayName("honours a configured limit, which is a threshold and therefore configuration")
        void thresholdIsConfiguration() {
            FraudProperties properties = properties();
            properties.getRules().setVelocityMaxPayments(1);
            TransactionVelocityRule strict = new TransactionVelocityRule(properties);
            assertThat(strict.evaluate(
                            FraudFixtures.payment("10.00").velocity(2).build()))
                    .isPresent();
            assertThat(strict.evaluate(
                            FraudFixtures.payment("10.00").velocity(1).build()))
                    .isEmpty();
        }

        @Test
        @DisplayName("reports the window the sample was measured over, not the rule's configured one")
        void usesTheSamplesWindow() {
            // The counter and the rule must agree on what "the last 60 seconds" means, so the rule reads
            // the sample's window. A mismatch between the two is a silent change in what the rule means,
            // and using the sample's value makes the number on the reason line the number that was
            // actually compared.
            var finding = rule.evaluate(
                            FraudFixtures.payment("10.00").velocity(6).build())
                    .orElseThrow();
            assertThat(finding.evidence().get("window")).isEqualTo("60s");
        }
    }

    // ------------------------------------------------------------------------------- R004 rapid network change

    @Nested
    @DisplayName("R004 RAPID_NETWORK_CHANGE")
    class RapidNetworkChange {

        private final RapidNetworkChangeRule rule = new RapidNetworkChangeRule(properties);

        @Test
        @DisplayName("is R004 RAPID_NETWORK_CHANGE, worth 30")
        void identity() {
            assertThat(rule.id()).isEqualTo("R004");
            assertThat(rule.points()).isEqualTo(30);
        }

        @Test
        @DisplayName("fires when the network changed inside the window")
        void firesOnARecentChange() {
            assertThat(rule.evaluate(FraudFixtures.payment("10.00")
                            .networkChangedSecondsAgo(60)
                            .build()))
                    .isPresent();
        }

        @Test
        @DisplayName("applies the window inclusively at its edge")
        void windowEdge() {
            // 600s is the configured window. A change exactly 600 seconds old is inside "the last ten
            // minutes", so it fires; 601 is not.
            assertThat(rule.evaluate(FraudFixtures.payment("10.00")
                            .networkChangedAt(NOW.minusSeconds(600))
                            .build()))
                    .isPresent();
            assertThat(rule.evaluate(FraudFixtures.payment("10.00")
                            .networkChangedAt(NOW.minusSeconds(601))
                            .build()))
                    .isEmpty();
        }

        @Test
        @DisplayName("does not fire when the customer's network has never changed")
        void quietForAStableNetwork() {
            // Every payment ever made by a steady customer has no "recent change", and this is the case
            // that has to stay silent: a rule that fired on the absence of a change would fire on the
            // first payment from every new customer.
            assertThat(rule.evaluate(FraudFixtures.payment("10.00").build()))
                    .as("no recorded network change is not a recent network change")
                    .isEmpty();
        }

        @Test
        @DisplayName("does not fire when the change time is in the future")
        void ignoresAFutureChange() {
            assertThat(rule.evaluate(FraudFixtures.payment("10.00")
                            .networkChangedAt(NOW.plusSeconds(120))
                            .build()))
                    .as("a change from the future is a clock or replay problem, not a movement")
                    .isEmpty();
        }

        @Test
        @DisplayName("does not fire without a network reference at all")
        void needsANetwork() {
            assertThat(rule.evaluate(FraudFixtures.payment("10.00")
                            .noClient()
                            .networkChangedSecondsAgo(10)
                            .build()))
                    .isEmpty();
        }

        @Test
        @DisplayName("marks itself a weak signal, because the reference derives from a caller-settable header")
        void saysItIsWeak() {
            // This is the finding an analyst is most likely to over-trust, and it is the one least
            // entitled to it. In this deployment the network reference masks X-Forwarded-For, which any
            // caller can set, so an attacker can manufacture a network change at will. The rule still
            // fires — the pattern is worth a look — but the evidence and the sentence both say why it
            // should not be believed on its own.
            var finding = rule.evaluate(FraudFixtures.payment("10.00")
                            .networkChangedSecondsAgo(10)
                            .build())
                    .orElseThrow();
            assertThat(finding.evidence())
                    .containsEntry("signalStrength", "weak")
                    .containsEntry("window", "600s");
            assertThat(finding.explanation()).contains("weak signal").contains("caller-influenceable");
        }
    }
}
