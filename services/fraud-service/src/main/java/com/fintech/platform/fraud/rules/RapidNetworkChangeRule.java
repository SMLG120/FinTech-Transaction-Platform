package com.fintech.platform.fraud.rules;

import com.fintech.platform.fraud.config.FraudProperties;
import com.fintech.platform.fraud.domain.RiskFeature;
import com.fintech.platform.fraud.domain.RiskRule;
import com.fintech.platform.fraud.domain.RuleFinding;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * R004 — the customer moved between networks moments ago.
 *
 * <p>This is the weakest signal in the engine and is worth 30 points rather than a decision on its own,
 * for a reason worth stating plainly: <b>the network reference is only as trustworthy as the header that
 * produced it.</b> {@code SourceNetwork} in transaction-service masks the address to a /24 and hashes it,
 * which removes the privacy problem, but which address it sees depends on what the caller put in
 * {@code X-Forwarded-For}. Behind the local gateway that header is the client's own and can be forged, so
 * an attacker can make a fixed device look like it is hopping networks.
 *
 * <p>What it is still good for: a real customer who travels, or a real thief using someone else's
 * connection, does produce genuine changes, and a customer moving twice in ten minutes is unusual enough to
 * be worth a look. What it must not become is a primary control, and nothing in the decision treats it as
 * one.
 *
 * <p>The right fix is not a bigger score. It is a network reference the caller cannot choose — a
 * connection-level identity asserted by something in front of the application — and ADR-0008 records that
 * as the prerequisite for treating this signal as anything more than advisory.
 */
@Component
public class RapidNetworkChangeRule implements RiskRule {

    private final FraudProperties.Rules thresholds;

    public RapidNetworkChangeRule(FraudProperties properties) {
        this.thresholds = properties.getRules();
    }

    @Override
    public String id() {
        return "R004";
    }

    @Override
    public String name() {
        return "RAPID_NETWORK_CHANGE";
    }

    @Override
    public int points() {
        return 30;
    }

    @Override
    public Optional<RuleFinding> evaluate(RiskFeature feature) {
        if (!feature.facts().hasNetwork()) {
            return Optional.empty();
        }
        Duration window = Duration.ofSeconds(thresholds.getNetworkChangeWindowSeconds());
        if (!feature.snapshot().hasRecentNetworkChange(window)) {
            return Optional.empty();
        }
        return Optional.of(new RuleFinding(
                "Network changed within the last " + thresholds.getNetworkChangeWindowSeconds()
                        + "s. The network reference is masked and digest-only, and in this deployment it "
                        + "derives from a caller-influenceable header, so treat it as a weak signal.",
                Map.of("window", thresholds.getNetworkChangeWindowSeconds() + "s", "signalStrength", "weak")));
    }
}
