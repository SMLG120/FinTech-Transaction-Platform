package com.fintech.platform.fraud.rules;

import com.fintech.platform.fraud.config.FraudProperties;
import com.fintech.platform.fraud.domain.RiskFeature;
import com.fintech.platform.fraud.domain.RiskRule;
import com.fintech.platform.fraud.domain.RuleFinding;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * R006 — this device has been used by other customers.
 *
 * <p>The clearest signal in the engine, because a device genuinely used by unrelated accounts is either a
 * stolen device, a fraud farm, or a very unusual family arrangement, and all three deserve a look. It is
 * also the rule most exposed to false positives from the way identity is established upstream: if two
 * customers' subject digests were ever computed from the same subject, every device would look shared.
 * The count is of <em>distinct subject digests other than this payment's</em>, which is why the evidence
 * reports the number of other customers rather than the number of other payments.
 *
 * <p>No points for a shared device seen once, and no effect on the decision below
 * {@code high-signal-points}. It escalates in combination, which is how a real account takeover tends to
 * look: a shared device, a new device for the card, and a burst of payments.
 */
@Component
public class SharedDeviceRule implements RiskRule {

    private final FraudProperties.Rules thresholds;

    public SharedDeviceRule(FraudProperties properties) {
        this.thresholds = properties.getRules();
    }

    @Override
    public String id() {
        return "R006";
    }

    @Override
    public String name() {
        return "SHARED_DEVICE";
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
        int others = feature.snapshot().otherCustomersOnDevice();
        if (others < thresholds.getSharedDeviceMinOtherCustomers()) {
            return Optional.empty();
        }
        return Optional.of(new RuleFinding(
                "Device has been used by " + others + " other customer" + (others == 1 ? "" : "s")
                        + " on this platform.",
                Map.of(
                        "otherCustomers", Integer.toString(others),
                        "threshold", Integer.toString(thresholds.getSharedDeviceMinOtherCustomers()))));
    }
}
