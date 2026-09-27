package com.fintech.platform.fraud.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Everything a rule is allowed to know: what the payment said, and what the engine looked up about it.
 *
 * <p>A rule sees this and nothing else. Not the entity, not the repository, not the clock, not the other
 * rules. That is what makes a rule testable — {@code evaluate} is a pure function of a feature, so the
 * rule's tests construct a feature and assert a finding, with no Spring context, no database and no
 * waiting. It is also what stops a rule from being tempted to do its own lookups, because it has no way.
 *
 * <p>Named for the streaming-bundle convention this platform uses for facts assembled before
 * processing: the engine collects a feature from an event, then evaluates rules against it. The
 * difference from a Kafka Streams DSL is that this is a plain record passed to plain methods, so the same
 * feature can be assembled from a live event, from a replayed event, or from a test fixture.
 */
public record RiskFeature(PaymentFacts facts, FeatureSnapshot snapshot) {

    public RiskFeature {
        Objects.requireNonNull(facts, "facts must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
    }

    public static RiskFeature of(PaymentFacts facts, FeatureSnapshot snapshot) {
        return new RiskFeature(facts, snapshot);
    }

    public java.util.UUID transactionId() {
        return facts.transactionId();
    }

    public Money amount() {
        return facts.amount();
    }

    public Instant occurredAt() {
        return facts.occurredAt();
    }

    public VelocitySample velocity() {
        return snapshot.velocity();
    }
}
