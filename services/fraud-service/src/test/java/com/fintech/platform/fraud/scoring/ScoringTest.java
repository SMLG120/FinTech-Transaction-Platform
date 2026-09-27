package com.fintech.platform.fraud.scoring;

import static com.fintech.platform.fraud.FraudFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.fraud.FraudFixtures;
import com.fintech.platform.fraud.config.FraudProperties;
import com.fintech.platform.fraud.domain.FraudDecision;
import com.fintech.platform.fraud.domain.RiskBand;
import com.fintech.platform.fraud.domain.RiskFeature;
import com.fintech.platform.fraud.domain.RiskRule;
import com.fintech.platform.fraud.domain.RiskScore;
import com.fintech.platform.fraud.domain.RuleFinding;
import com.fintech.platform.fraud.domain.RuleOutcome;
import com.fintech.platform.fraud.rules.LargeAmountRule;
import com.fintech.platform.fraud.rules.NewDeviceLargeAmountRule;
import com.fintech.platform.fraud.rules.NewMerchantLargeAmountRule;
import com.fintech.platform.fraud.rules.RapidNetworkChangeRule;
import com.fintech.platform.fraud.rules.RecentCardActivationRule;
import com.fintech.platform.fraud.rules.SharedDeviceRule;
import com.fintech.platform.fraud.rules.TransactionVelocityRule;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The score, the bands, the thresholds, and the arithmetic that turns seven booleans into a decision.
 *
 * <p>Two things are being pinned here that are easy to get subtly wrong and hard to notice afterwards.
 *
 * <p><b>Where the boundaries are.</b> A score of 25 and a score of 26 are in different bands, 50 and 51
 * are different decisions, and 75 and 76 are the difference between "an analyst should look" and
 * "this payment is refused". Every one of those is a published number, and every one of them is tested
 * on both sides.
 *
 * <p><b>That the cap does not lose the reasons.</b> Seven rules can total far more than 100. The score
 * clamps, and the reasons list does not — an analyst looking at a CRITICAL must still be able to see
 * that four rules fired and not just that "100" did.
 */
class ScoringTest {

    private final FraudProperties properties = properties();

    private final DecisionPolicy policy = new DecisionPolicy(properties);

    // ------------------------------------------------------------------------------- the score itself

    @Nested
    @DisplayName("RiskScore")
    class Score {

        @ParameterizedTest(name = "{0} points is {1}")
        @CsvSource({
            "0, LOW",
            "1, LOW",
            "25, LOW",
            // The band boundary. 25 is the top of LOW and 26 the bottom of MEDIUM; there is no value
            // between them and no rounding decision to make.
            "26, MEDIUM",
            "50, MEDIUM",
            "51, HIGH",
            "75, HIGH",
            "76, CRITICAL",
            "100, CRITICAL"
        })
        @DisplayName("places every published boundary in the documented band")
        void bandBoundaries(int points, RiskBand expected) {
            assertThat(RiskScore.of(points).band()).isEqualTo(expected);
        }

        @Test
        @DisplayName("clamps a total above 100 rather than refusing it")
        void clampsAtTheTop() {
            // Seven rules at their published points total 190. A score of 190 is not a more severe
            // finding, it is a number no band holds and no threshold table can be written against, so the
            // clamp happens at construction and every consumer downstream can assume 0..100.
            assertThat(RiskScore.of(190).value()).isEqualTo(100);
            assertThat(RiskScore.of(190).band()).isEqualTo(RiskBand.CRITICAL);
        }

        @Test
        @DisplayName("clamps below zero, though nothing should produce it")
        void clampsAtTheBottom() {
            assertThat(RiskScore.of(-5).value()).isEqualTo(0);
        }

        @Test
        @DisplayName("refuses to be constructed out of range, so a bypassed clamp is a crash and not a silent CRITICAL")
        void refusesAnOutOfRangeValue() {
            // RiskScore.of clamps; the constructor does not. That asymmetry is the point: the factory is
            // the only sanctioned way in from a sum, and anything that skips it is a bug that should be
            // visible rather than a decision that quietly reports 100.
            assertThatThrownBy(() -> new RiskScore(101, RiskBand.CRITICAL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot exceed");
            assertThatThrownBy(() -> new RiskScore(-1, RiskBand.LOW)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("every integer from 0 to 100 lands in exactly one band")
        void bandsPartitionTheRange() {
            for (int value = RiskScore.MIN; value <= RiskScore.MAX; value++) {
                RiskBand band = RiskBand.of(value);
                assertThat(value)
                        .as("%d is inside %s", value, band)
                        .isBetween(band.lowestInclusive(), band.highestInclusive());
            }
        }
    }

    // ------------------------------------------------------------------------------- the policy

    @Nested
    @DisplayName("DecisionPolicy")
    class Policy {

        @Test
        @DisplayName("approves below the review threshold, reviews at it, and declines at the decline threshold")
        void thresholdsAreInclusiveAtTheBottom() {
            assertThat(decisionAt(50)).isEqualTo(FraudDecision.APPROVE);
            assertThat(decisionAt(51)).as("51 is the review threshold").isEqualTo(FraudDecision.REVIEW);
            assertThat(decisionAt(75)).isEqualTo(FraudDecision.REVIEW);
            assertThat(decisionAt(76)).as("76 is the decline threshold").isEqualTo(FraudDecision.DECLINE);
            assertThat(decisionAt(100)).isEqualTo(FraudDecision.DECLINE);
        }

        @Test
        @DisplayName("escalates on a single strong signal, even when the total is below the review threshold")
        void singleStrongSignalEscalates() {
            // The motivating case: six payments in a minute (R002, 35) on 3.00 each. Without the floor,
            // 35 is an APPROVE and a burst that looks exactly like card testing gets waved through on the
            // grounds that no single individual payment was large.
            DecisionPolicy.Verdict verdict = policy.verdict(RiskScore.of(35), List.of(outcome("R002", 35)));
            assertThat(verdict.decision()).isEqualTo(FraudDecision.REVIEW);
            assertThat(verdict.escalatedBySignal()).isTrue();
            assertThat(verdict.alertRequired()).isTrue();
        }

        @Test
        @DisplayName("does not escalate on R006 alone, because a shared household device is not a signal on its own")
        void sharedDeviceAloneDoesNotEscalate() {
            // R006 is worth 25, below the floor of 30. A device legitimately shared between members of
            // one household would otherwise open an alert on every payment any of them made, and the
            // fastest way to have the alert queue ignored is to fill it with that.
            DecisionPolicy.Verdict verdict = policy.verdict(RiskScore.of(25), List.of(outcome("R006", 25)));
            assertThat(verdict.decision()).isEqualTo(FraudDecision.APPROVE);
            assertThat(verdict.escalatedBySignal()).isFalse();
        }

        @Test
        @DisplayName("does not escalate on a single weak signal")
        void singleWeakSignalDoesNotEscalate() {
            // R007 is worth 15, below the 30-point floor. A new merchant on a small first payment is a
            // new merchant on a small first payment, and escalating every one of them would be the fastest
            // way to have the alert queue ignored.
            var verdict = policy.verdict(RiskScore.of(15), List.of(outcome("R007", 15)));
            assertThat(verdict.decision()).isEqualTo(FraudDecision.APPROVE);
            assertThat(verdict.alertRequired()).isFalse();
        }

        @Test
        @DisplayName("escalates on a signal exactly at the high-signal floor")
        void highSignalFloorIsInclusive() {
            // 30 points is "at least 30", so R004 on its own escalates. An off-by-one here would silently
            // demote the one rule whose finding is most often correct.
            var verdict = policy.verdict(RiskScore.of(30), List.of(outcome("R004", 30)));
            assertThat(verdict.decision()).isEqualTo(FraudDecision.REVIEW);
            assertThat(verdict.escalatedBySignal()).isTrue();
            assertThat(verdict.alertRequired()).isTrue();
        }

        @Test
        @DisplayName("two weak signals summing to 30 are a total, not a signal, and 30 is not a review")
        void twoWeakSignalsAreNotAnEscalation() {
            // 15 + 15 = 30. The high-signal floor is about one rule on its own, so this stays an APPROVE:
            // two new merchants on small payments is not account takeover, and escalating it would be the
            // fastest way to have the queue ignored.
            DecisionPolicy.Verdict verdict =
                    policy.verdict(RiskScore.of(30), List.of(outcome("R007", 15), outcome("R007", 15)));
            assertThat(verdict.decision()).isEqualTo(FraudDecision.APPROVE);
            assertThat(verdict.escalatedBySignal()).isFalse();
        }

        @Test
        @DisplayName("a strong signal cannot push a payment above DECLINE, however high the total")
        void strongSignalNeverDeclines() {
            // R004 is worth 30 and escalates on its own, but 30 is not a decline. A single signal is
            // grounds for a human, never for refusing a payment — which is a separate decision made by
            // the transaction service once it reads the completed analysis.
            var verdict = policy.verdict(RiskScore.of(30), List.of(outcome("R004", 30)));
            assertThat(verdict.decision()).isEqualTo(FraudDecision.REVIEW);
            assertThat(verdict.decision().isAdverse())
                    .as("isAdverse means refused, and a REVIEW is a payment that was not refused")
                    .isFalse();
            assertThat(FraudDecision.DECLINE.isAdverse())
                    .as("only DECLINE is adverse")
                    .isTrue();
        }

        @Test
        @DisplayName("a configured threshold moves the decision without moving the band")
        void thresholdsAreConfigurationAndBandsAreNot() {
            // A deployment that declines at 85 must still report 80 as HIGH, because HIGH is a published
            // band name. Coupling the two would let a configuration change silently redefine what a band
            // means on a screen that says "HIGH".
            FraudProperties stricter = properties();
            stricter.getDecisions().setDeclineThreshold(90);
            stricter.getDecisions().setReviewThreshold(80);
            var verdict = new DecisionPolicy(stricter).verdict(RiskScore.of(80), List.of());
            assertThat(verdict.decision()).isEqualTo(FraudDecision.REVIEW);
            assertThat(RiskBand.of(80)).isEqualTo(RiskBand.CRITICAL);
        }

        private FraudDecision decisionAt(int score) {
            return policy.verdict(RiskScore.of(score), List.of()).decision();
        }
    }

    // ------------------------------------------------------------------------------- the engine

    @Nested
    @DisplayName("RiskEngine")
    class Engine {

        private final RiskEngine engine = new RiskEngine(allRules(), new DecisionPolicy(properties));

        @Test
        @DisplayName("scores a clean payment at zero and approves it")
        void cleanPayment() {
            var assessment = engine.evaluate(FraudFixtures.cleanPayment("10.00", "GBP")
                    .deviceSeenOnCard(FraudFixtures.History.SEEN.boxed())
                    .deviceSeenByCustomer(FraudFixtures.History.SEEN.boxed())
                    .merchantSeen(FraudFixtures.History.SEEN.boxed())
                    .otherCustomersOnDevice(0)
                    .velocity(1)
                    .build());
            assertThat(assessment.scoreValue()).isZero();
            assertThat(assessment.band()).isEqualTo(RiskBand.LOW);
            assertThat(assessment.decision()).isEqualTo(FraudDecision.APPROVE);
            assertThat(assessment.reasons()).isEmpty();
            assertThat(assessment.warrantsAlert()).isFalse();
        }

        @Test
        @DisplayName("records the rules that did not fire, because 'nothing fired' is not 'nothing else was wrong'")
        void recordsWhatDidNotFire() {
            // A decision that lists only what fired reads as "nothing else was wrong", which is not what
            // was found. The rules that declined travel in the facts so an analyst comparing a MEDIUM with
            // an expected HIGH can see that velocity had nothing to say because its counter was
            // unavailable, rather than guessing.
            var assessment = engine.evaluate(FraudFixtures.payment("10.00")
                    .deviceSeenOnCard(FraudFixtures.History.SEEN.boxed())
                    .deviceSeenByCustomer(FraudFixtures.History.SEEN.boxed())
                    .merchantSeen(FraudFixtures.History.SEEN.boxed())
                    .build());
            assertThat(assessment.facts()).containsKey("rulesNotFired");
            assertThat(assessment.facts().get("rulesEvaluated")).isEqualTo("7");
            assertThat(assessment.facts().get("rulesFired")).isEqualTo("0");
        }

        @Test
        @DisplayName("labels an unreadable fact 'unknown' rather than 'false'")
        void unknownIsLabelledUnknown() {
            var assessment = engine.evaluate(FraudFixtures.payment("10.00")
                    .history(FraudFixtures.History.UNKNOWN, FraudFixtures.History.UNKNOWN, 0)
                    .build());
            assertThat(assessment.facts())
                    .containsEntry("deviceSeenOnCard", "unknown")
                    .containsEntry("merchantSeen", "unknown")
                    .containsEntry("velocityAvailable", "true");
        }

        @Test
        @DisplayName("caps the score but keeps every reason")
        void capDoesNotLoseReasons() {
            // Everything fires.
            var assessment = engine.evaluate(FraudFixtures.payment("9000.00")
                    .history(FraudFixtures.History.NOT_SEEN, FraudFixtures.History.NOT_SEEN, 5)
                    .velocity(20)
                    .networkChangedSecondsAgo(10)
                    .cardFirstSeenMinutesAgo(5)
                    .build());
            assertThat(assessment.scoreValue()).isEqualTo(100);
            assertThat(assessment.band()).isEqualTo(RiskBand.CRITICAL);
            assertThat(assessment.decision()).isEqualTo(FraudDecision.DECLINE);
            assertThat(assessment.reasons()).hasSizeGreaterThanOrEqualTo(6);
            // 35+35+25+30+20+25+15 = 185, clamped to 100. The reasons list is what an analyst works
            // from, so the clamp must not touch it; the raw total stays visible in the facts so a reader
            // can see how far past the ceiling the payment went.
            assertThat(assessment.facts().get("rawPoints")).isEqualTo("185");
            assertThat(assessment.facts().get("score")).isEqualTo("100");
        }

        @Test
        @DisplayName("keeps going when one rule throws, and records that it failed")
        void oneFailingRuleDoesNotLoseThePayment() {
            // A rule that fails is a bug or a broken dependency, and the honest response to a payment that
            // looks like account takeover is not to record nothing. The payment still gets a score, and
            // the facts say the score is incomplete — which is a very different thing from a score that
            // silently means six-sevenths of what it should.
            RiskRule exploding = new RiskRule() {
                @Override
                public String id() {
                    return "R999";
                }

                @Override
                public String name() {
                    return "EXPLODES";
                }

                @Override
                public int points() {
                    return 99;
                }

                @Override
                public Optional<RuleFinding> evaluate(RiskFeature feature) {
                    throw new IllegalStateException("the observation table is unreachable");
                }
            };
            var engineWithFailure = new RiskEngine(
                    java.util.stream.Stream.concat(allRules().stream(), java.util.stream.Stream.of(exploding))
                            .toList(),
                    new DecisionPolicy(properties));
            var assessment = engineWithFailure.evaluate(
                    FraudFixtures.payment("6000.00").velocity(20).build());

            assertThat(assessment.facts()).containsEntry("rulesFailed", "R999");
            assertThat(assessment.reasons())
                    .as("R001 and R002 still fired, so the payment is not left unscored by an unrelated bug")
                    .isNotEmpty();
            assertThat(assessment.decision())
                    .as("a broken rule must not rescue a payment that the working rules decline")
                    .isEqualTo(FraudDecision.DECLINE);
        }

        @Test
        @DisplayName("sorts rules by id, so a decision's reasons do not shuffle between restarts")
        void reasonsAreStablyOrdered() {
            var ids = engine.rules().stream().map(RiskRule::id).toList();
            assertThat(ids).containsExactly("R001", "R002", "R003", "R004", "R005", "R006", "R007");
        }

        @Test
        @DisplayName("carries the whole published rule set")
        void ruleSetIsComplete() {
            // The roadmap's seven rules, by id and by points. Points are code rather than configuration
            // because the bands are a published promise about what a number means, so this test is what
            // stops a well-meaning "let me tune the weights" from silently redefining HIGH.
            assertThat(engine.rules())
                    .extracting(RiskRule::id, RiskRule::points)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("R001", 35),
                            org.assertj.core.groups.Tuple.tuple("R002", 35),
                            org.assertj.core.groups.Tuple.tuple("R003", 25),
                            org.assertj.core.groups.Tuple.tuple("R004", 30),
                            org.assertj.core.groups.Tuple.tuple("R005", 20),
                            org.assertj.core.groups.Tuple.tuple("R006", 25),
                            org.assertj.core.groups.Tuple.tuple("R007", 15));
        }
    }

    // ------------------------------------------------------------------------------- helpers

    private static RuleOutcome outcome(String ruleId, int points) {
        return new RuleOutcome(ruleId, ruleId, points, "a finding", java.util.Map.of());
    }

    private static List<RiskRule> allRules() {
        FraudProperties properties = properties();
        return List.of(
                new LargeAmountRule(properties),
                new TransactionVelocityRule(properties),
                new NewDeviceLargeAmountRule(properties),
                new RapidNetworkChangeRule(properties),
                new RecentCardActivationRule(properties),
                new SharedDeviceRule(properties),
                new NewMerchantLargeAmountRule(properties));
    }
}
