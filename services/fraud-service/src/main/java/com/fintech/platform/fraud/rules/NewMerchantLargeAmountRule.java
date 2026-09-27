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
 * R007 — a merchant this customer has never paid before, for a noticeable amount.
 *
 * <p>The smallest contribution in the engine at 15 points, and the rule that most often turns out to be
 * innocent. A customer who has only ever paid one supermarket pays a new one, and the finding is true and
 * useless. It earns its place by accumulating: a first payment to a new merchant on a new device, on a
 * network that just changed, is a different row from a first payment to a new merchant.
 *
 * <p>Two kinds of "merchant" reach this rule and they are not the same, which is why the evidence names
 * the one used. A server-to-server payment carries the payee's own reference — stable, trustworthy, the
 * thing a fraudster cannot mint for free. A consumer card payment usually has none, and the fallback is
 * the payee's name, which is attacker-chosen text. The fallback is what makes this rule work at all in
 * the consumer path, and it is also why a renamed payee makes it fire. Both are recorded rather than
 * smoothed over.
 *
 * <p>Merchant history is scoped to the customer, not to the merchant: being new to <em>you</em> is the
 * signal. A merchant ten thousand customers have paid is not a suspicious merchant.
 */
@Component
public class NewMerchantLargeAmountRule implements RiskRule {

    private final FraudProperties.Rules thresholds;

    public NewMerchantLargeAmountRule(FraudProperties properties) {
        this.thresholds = properties.getRules();
    }

    @Override
    public String id() {
        return "R007";
    }

    @Override
    public String name() {
        return "NEW_MERCHANT_LARGE_AMOUNT";
    }

    @Override
    public int points() {
        return 15;
    }

    @Override
    public Optional<RuleFinding> evaluate(RiskFeature feature) {
        if (!feature.facts().hasMerchantIdentity()
                || !Boolean.FALSE.equals(feature.snapshot().merchantSeen())) {
            return Optional.empty();
        }
        return AmountThresholds.compare(feature.amount(), thresholds.getNewMerchantAmount())
                .filter(AmountThresholds.Comparison::exceeded)
                .map(comparison -> {
                    Map<String, String> evidence = new LinkedHashMap<>(comparison.evidence("amount", "threshold"));
                    evidence.put("merchantScope", "customer");
                    evidence.put("identitySource", feature.facts().merchantIdentitySource());
                    return new RuleFinding(
                            "First payment to this merchant for an amount of " + comparison.amount() + ", above the "
                                    + comparison.threshold() + " threshold.",
                            evidence);
                });
    }
}
