package com.fintech.platform.fraud.rules;

import com.fintech.platform.fraud.config.FraudProperties;
import com.fintech.platform.fraud.domain.RiskFeature;
import com.fintech.platform.fraud.domain.RiskRule;
import com.fintech.platform.fraud.domain.RuleFinding;
import com.fintech.platform.fraud.domain.VelocitySample;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * R002 — this customer has made more payments in the last minute than usual.
 *
 * <p><b>Counts the payment being scored.</b> The sixth payment in a minute is the one that trips the
 * threshold, so a counter that excluded the current payment would fire on the seventh and the number in
 * the reason would be off by one from the number the analyst is looking at. The collector adds the
 * payment before scoring and the sample therefore includes it; the evidence says {@code countIncludingThis}
 * for the same reason.
 *
 * <p><b>Fails open when the counter is unavailable.</b> {@link VelocitySample#exceeds(int)} returns false
 * for an unavailable sample, so a Redis outage does not turn every payment on the platform into a
 * high-velocity one. The outage is not invisible: the decision's facts carry
 * {@code velocityAvailable=false}, the score is lower than it would have been, and a metric counts the
 * skips. See ADR-0008 for why a degraded fraud path is allowed to under-score rather than fail the
 * payment.
 */
@Component
public class TransactionVelocityRule implements RiskRule {

    private final FraudProperties.Rules thresholds;

    public TransactionVelocityRule(FraudProperties properties) {
        this.thresholds = properties.getRules();
    }

    @Override
    public String id() {
        return "R002";
    }

    @Override
    public String name() {
        return "TRANSACTION_VELOCITY";
    }

    @Override
    public int points() {
        return 35;
    }

    @Override
    public Optional<RuleFinding> evaluate(RiskFeature feature) {
        VelocitySample sample = feature.velocity();
        if (!sample.exceeds(thresholds.getVelocityMaxPayments())) {
            return Optional.empty();
        }
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("countIncludingThis", Integer.toString(sample.count()));
        evidence.put("threshold", Integer.toString(thresholds.getVelocityMaxPayments()));
        evidence.put("window", sample.windowSeconds() + "s");
        return Optional.of(new RuleFinding(
                sample.count() + " payments in " + sample.windowSeconds() + "s, above the limit of "
                        + thresholds.getVelocityMaxPayments() + ".",
                evidence));
    }
}
