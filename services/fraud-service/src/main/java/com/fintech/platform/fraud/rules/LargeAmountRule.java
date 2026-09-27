package com.fintech.platform.fraud.rules;

import com.fintech.platform.fraud.config.FraudProperties;
import com.fintech.platform.fraud.domain.RiskFeature;
import com.fintech.platform.fraud.domain.RiskRule;
import com.fintech.platform.fraud.domain.RuleFinding;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * R001 — the payment is larger than this deployment considers large.
 *
 * <p>The rule the roadmap lists first, and the one with the most false positives in a real deployment: a
 * genuine large purchase looks exactly like a fraudulent one on this evidence alone. It is worth 35 points
 * rather than a decline of its own for that reason, and the {@code high-signal-points} floor in
 * {@link com.fintech.platform.fraud.scoring.DecisionPolicy} means it can never be the only reason a payment
 * is escalated to a human.
 *
 * <p>Skipped entirely on a currency it has no threshold for, rather than converted.
 */
@Component
public class LargeAmountRule implements RiskRule {

    private final FraudProperties.Rules thresholds;

    public LargeAmountRule(FraudProperties properties) {
        this.thresholds = properties.getRules();
    }

    @Override
    public String id() {
        return "R001";
    }

    @Override
    public String name() {
        return "LARGE_AMOUNT";
    }

    @Override
    public int points() {
        return 35;
    }

    @Override
    public Optional<RuleFinding> evaluate(RiskFeature feature) {
        return AmountThresholds.compare(feature.amount(), thresholds.getLargeAmount())
                .filter(AmountThresholds.Comparison::exceeded)
                .map(comparison -> new RuleFinding(
                        "Amount " + comparison.amount() + " is above the large-amount threshold of "
                                + comparison.threshold() + " for "
                                + comparison.amount().currency().getCurrencyCode() + ".",
                        comparison.evidence("amount", "threshold")));
    }
}
