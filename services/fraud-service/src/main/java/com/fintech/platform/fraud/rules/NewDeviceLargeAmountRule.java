package com.fintech.platform.fraud.rules;

import com.fintech.platform.fraud.config.FraudProperties;
import com.fintech.platform.fraud.domain.RiskFeature;
import com.fintech.platform.fraud.domain.RiskRule;
import com.fintech.platform.fraud.domain.RuleFinding;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * R003 — an unfamiliar device, spending real money.
 *
 * <p>The conjunction is the rule. A new device on its own is a customer who has switched phones, which
 * happens constantly; a large payment on its own is a customer buying a sofa. Together they are the shape
 * of account takeover, and requiring both halves is what keeps this from firing on every honest
 * device change.
 *
 * <p>"New" is scoped to the card, not the customer: a device that has never been seen with <em>this</em>
 * card is new here even if the same device was used with another card by the same customer yesterday. The
 * scope is deliberate — sharing a device between two of your own cards should not make each look new.
 *
 * <p>Does not fire at all without a device reference, and does not treat "device unknown" as "device new".
 */
@Component
public class NewDeviceLargeAmountRule implements RiskRule {

    private final FraudProperties.Rules thresholds;

    public NewDeviceLargeAmountRule(FraudProperties properties) {
        this.thresholds = properties.getRules();
    }

    @Override
    public String id() {
        return "R003";
    }

    @Override
    public String name() {
        return "NEW_DEVICE_LARGE_AMOUNT";
    }

    @Override
    public int points() {
        return 25;
    }

    @Override
    public Optional<RuleFinding> evaluate(RiskFeature feature) {
        if (!feature.facts().hasDevice()) {
            return Optional.empty();
        }
        if (!Boolean.FALSE.equals(feature.snapshot().deviceSeenOnCard())) {
            // null means the observation could not be read. Treating that as new would make an
            // unreadable row look exactly like the signal this rule exists to detect.
            return Optional.empty();
        }
        return AmountThresholds.compare(feature.amount(), thresholds.getNewDeviceAmount())
                .filter(AmountThresholds.Comparison::exceeded)
                .map(comparison -> {
                    Map<String, String> evidence = new LinkedHashMap<>(comparison.evidence("amount", "threshold"));
                    evidence.put("deviceScope", "card");
                    return new RuleFinding(
                            "Device is new to this card and the amount " + comparison.amount() + " is above the "
                                    + comparison.threshold() + " threshold.",
                            evidence);
                });
    }
}
