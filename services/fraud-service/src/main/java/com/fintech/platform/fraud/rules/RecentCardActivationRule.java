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
 * R005 — the card is new to this platform, and the payment came quickly.
 *
 * <p>The roadmap's rule is "recently unlocked card", which is a property of a device, not of a card, and
 * this platform has no event that says a card was just unlocked. What the fraud service <em>can</em>
 * observe honestly is the first time it has seen this card reference at all, so that is what this fires
 * on, and the rule's own evidence says so.
 *
 * <p>The substitution is not equivalent and the difference is worth being explicit about: a card that was
 * genuinely stolen and enrolled years ago looks old here, and a card enrolled this morning by a customer
 * who has been waiting weeks for it looks new. It is a proxy for onboarding recency, standing in for
 * activation recency until card-service publishes card lifecycle events, which ADR-0008 lists as the
 * change that would let this rule be its real self.
 *
 * <p>A card reference the fraud service has never seen is {@code null} here rather than "not recent",
 * because a missing observation is a gap in the data, not a fact about the card. This rule therefore
 * mostly fires for cards enrolled after the fraud service started observing, which is the correct
 * behaviour for a proxy and is documented as such.
 */
@Component
public class RecentCardActivationRule implements RiskRule {

    private final FraudProperties.Rules thresholds;

    public RecentCardActivationRule(FraudProperties properties) {
        this.thresholds = properties.getRules();
    }

    @Override
    public String id() {
        return "R005";
    }

    @Override
    public String name() {
        return "RECENT_CARD_ACTIVATION";
    }

    @Override
    public int points() {
        return 20;
    }

    @Override
    public Optional<RuleFinding> evaluate(RiskFeature feature) {
        if (!feature.facts().hasCard() || feature.snapshot().cardFirstSeenAt() == null) {
            return Optional.empty();
        }
        Duration window = Duration.ofSeconds(thresholds.getCardActivationWindowSeconds());
        Duration sinceFirstSeen = Duration.between(feature.snapshot().cardFirstSeenAt(), feature.occurredAt());
        if (sinceFirstSeen.isNegative() || sinceFirstSeen.compareTo(window) > 0) {
            return Optional.empty();
        }
        return Optional.of(new RuleFinding(
                "Card was first observed " + sinceFirstSeen.toHours() + "h ago, inside the "
                        + thresholds.getCardActivationWindowSeconds()
                        + "s window. This stands in for recent activation until card lifecycle events exist.",
                Map.of(
                        "firstSeenAge", sinceFirstSeen.toHours() + "h",
                        "window", thresholds.getCardActivationWindowSeconds() + "s",
                        "proxy", "first-observed-by-fraud-service")));
    }
}
