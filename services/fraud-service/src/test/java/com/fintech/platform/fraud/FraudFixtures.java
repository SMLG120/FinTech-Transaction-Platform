package com.fintech.platform.fraud;

import com.fintech.platform.fraud.config.FraudProperties;
import com.fintech.platform.fraud.domain.FeatureSnapshot;
import com.fintech.platform.fraud.domain.Money;
import com.fintech.platform.fraud.domain.PaymentFacts;
import com.fintech.platform.fraud.domain.RiskFeature;
import com.fintech.platform.fraud.domain.VelocitySample;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/**
 * Builds features and configuration for the rule tests, without a Spring context.
 *
 * <p>Every test in this package constructs a {@link RiskFeature} and asserts on a finding, which is the
 * property that makes a rule testable at all: {@code evaluate} is a pure function of a feature, so
 * nothing here needs a database, a Redis, a clock, or a container that might be slow for reasons that
 * have nothing to do with the rule.
 *
 * <p>The builder is deliberately shaped around <em>the fact a rule is about</em>. {@code withDevice}
 * present or absent is the difference between R003 firing and not, so it is a first-class method rather
 * than a field to remember to set; a fixture where "no device" is expressed as a null string literal in
 * the body of each test is a fixture where one test quietly passes because the null was forgotten.
 *
 * <p>Timestamps are absolute and fixed. A test that read {@code Instant.now()} would pass in February and
 * fail in the month the card-activation window is measured in, which is the kind of test that is only
 * trusted until the first quarterly release.
 */
public final class FraudFixtures {

    /**
     * A fixed instant, well inside any plausible test run.
     *
     * <p>2024-06-15T12:00:00Z. Chosen as a round hour in UTC so that a failure reads as a clock question
     * and not as a timezone one.
     */
    public static final Instant NOW = Instant.parse("2024-06-15T12:00:00Z");

    private static final String GBP = "GBP";

    /**
     * 64 hex characters, because {@code PaymentFacts} is fed values a producing service has already
     * digested and a test that used a short readable string would not be testing the same shape.
     */
    public static final String CARD_DIGEST = "a".repeat(64);

    public static final String DEVICE_DIGEST = "b".repeat(64);
    public static final String NETWORK_DIGEST = "c".repeat(64);
    public static final String OWNER_DIGEST = "d".repeat(64);

    private FraudFixtures() {}

    // ---------------------------------------------------------------------------------- configuration

    /**
     * Configuration with the documented defaults, plus GBP/USD/EUR amounts.
     *
     * <p>Validated on the way out, so a test cannot pass against a configuration this service would
     * refuse to start with — which would mean the rule under test was never reachable in production.
     */
    public static FraudProperties properties() {
        FraudProperties properties = new FraudProperties();
        properties.setDefaultCurrency(GBP);
        properties.getRules().setLargeAmount(thresholds("GBP", "5000.00", "USD", "6000.00", "EUR", "5500.00"));
        properties.getRules().setNewDeviceAmount(thresholds("GBP", "1000.00", "USD", "1200.00", "EUR", "1100.00"));
        properties.getRules().setNewMerchantAmount(thresholds("GBP", "500.00", "USD", "600.00", "EUR", "550.00"));
        properties.getVelocity().setWindowSeconds(60);
        try {
            properties.afterPropertiesSet();
        } catch (RuntimeException e) {
            throw new IllegalStateException("the test fixture built a configuration the service would refuse", e);
        }
        return properties;
    }

    private static FraudProperties.AmountThresholds thresholds(String... currencyThenAmount) {
        FraudProperties.AmountThresholds configured = new FraudProperties.AmountThresholds();
        for (int i = 0; i < currencyThenAmount.length; i += 2) {
            configured.getByCurrency().put(currencyThenAmount[i], currencyThenAmount[i + 1]);
        }
        return configured;
    }

    // ---------------------------------------------------------------------------------- features

    /**
     * A payment with no device, no card and no network, and every history lookup answering "no".
     *
     * <p>The baseline for "a payment that looks entirely ordinary": no rule fires, and a test that starts
     * here and adds one fact at a time can attribute a firing to the fact it added.
     */
    public static FeatureBuilder cleanPayment(String amount, String currency) {
        return new FeatureBuilder().amount(amount, currency).history(History.NOT_SEEN, History.NOT_SEEN, 0);
    }

    public static FeatureBuilder payment(String amount) {
        return cleanPayment(amount, GBP);
    }

    /** A fluent feature builder. Every method names a fact a rule reads. */
    public static final class FeatureBuilder {

        private String amount = "10.00";
        private Currency currency = Currency.getInstance(GBP);
        private String payeeName = "Coffee Bar";
        private String merchantReference = "merchant-1";
        private String channel = "WEB";
        private String cardReference = CARD_DIGEST;
        private String deviceReference = DEVICE_DIGEST;
        private String networkReference = NETWORK_DIGEST;
        private VelocitySample velocity = VelocitySample.of(1, 60);
        private Boolean deviceSeenOnCard = Boolean.FALSE;
        private Boolean deviceSeenByCustomer = Boolean.FALSE;
        private Boolean merchantSeen = Boolean.FALSE;
        private int otherCustomersOnDevice;
        private Instant cardFirstSeenAt;
        private Instant lastSeenAt;
        private Instant lastNetworkChangedAt;
        private Instant evaluatedAt = NOW;

        private FeatureBuilder() {}

        public FeatureBuilder amount(String decimal, String currencyCode) {
            this.amount = decimal;
            this.currency = Currency.getInstance(currencyCode);
            return this;
        }

        public FeatureBuilder payee(String name, String reference) {
            this.payeeName = name;
            this.merchantReference = reference;
            return this;
        }

        public FeatureBuilder channel(String channel) {
            this.channel = channel;
            return this;
        }

        public FeatureBuilder card(String cardReference) {
            this.cardReference = cardReference;
            return this;
        }

        public FeatureBuilder device(String deviceReference) {
            this.deviceReference = deviceReference;
            return this;
        }

        public FeatureBuilder network(String networkReference) {
            this.networkReference = networkReference;
            return this;
        }

        /** The state a merchant-server payment has: no device, no network, but a card. */
        public FeatureBuilder noClient() {
            this.deviceReference = null;
            this.networkReference = null;
            this.channel = "MERCHANT_API";
            return this;
        }

        public FeatureBuilder velocity(int count, boolean available) {
            this.velocity = available ? VelocitySample.of(count, 60) : VelocitySample.unavailable(60);
            return this;
        }

        public FeatureBuilder velocity(int count) {
            return velocity(count, true);
        }

        public FeatureBuilder deviceSeenOnCard(Boolean seen) {
            this.deviceSeenOnCard = seen;
            return this;
        }

        public FeatureBuilder deviceSeenByCustomer(Boolean seen) {
            this.deviceSeenByCustomer = seen;
            return this;
        }

        public FeatureBuilder merchantSeen(Boolean seen) {
            this.merchantSeen = seen;
            return this;
        }

        public FeatureBuilder otherCustomersOnDevice(int count) {
            this.otherCustomersOnDevice = count;
            return this;
        }

        public FeatureBuilder cardFirstSeenAt(Instant instant) {
            this.cardFirstSeenAt = instant;
            return this;
        }

        /** A card first observed this many minutes before {@link #NOW}. */
        public FeatureBuilder cardFirstSeenMinutesAgo(long minutes) {
            return cardFirstSeenAt(NOW.minusSeconds(minutes * 60));
        }

        public FeatureBuilder lastSeenAt(Instant instant) {
            this.lastSeenAt = instant;
            return this;
        }

        public FeatureBuilder networkChangedAt(Instant instant) {
            this.lastNetworkChangedAt = instant;
            return this;
        }

        public FeatureBuilder networkChangedSecondsAgo(long seconds) {
            return networkChangedAt(NOW.minusSeconds(seconds));
        }

        public FeatureBuilder evaluatedAt(Instant instant) {
            this.evaluatedAt = instant;
            return this;
        }

        /** Shortcut for the state in which every optional history lookup failed to read. */
        public FeatureBuilder history(History deviceOnCard, History merchant, int othersOnDevice) {
            this.deviceSeenOnCard = deviceOnCard.boxed();
            this.deviceSeenByCustomer = deviceOnCard.boxed();
            this.merchantSeen = merchant.boxed();
            this.otherCustomersOnDevice = othersOnDevice;
            return this;
        }

        public RiskFeature build() {
            PaymentFacts facts = new PaymentFacts(
                    UUID.randomUUID(),
                    OWNER_DIGEST,
                    Money.parse(amount, currency),
                    payeeName,
                    merchantReference,
                    channel,
                    cardReference,
                    deviceReference,
                    networkReference,
                    NOW);
            FeatureSnapshot snapshot = new FeatureSnapshot(
                    velocity,
                    deviceSeenOnCard,
                    deviceSeenByCustomer,
                    merchantSeen,
                    otherCustomersOnDevice,
                    cardFirstSeenAt,
                    lastSeenAt,
                    lastNetworkChangedAt,
                    evaluatedAt);
            return RiskFeature.of(facts, snapshot);
        }
    }

    /**
     * The three states an optional history lookup can be in.
     *
     * <p>Modelled as an enum because "could not read it" is the state most likely to be lost in a test:
     * written as a {@code null} it is indistinguishable at a glance from a field somebody forgot to set,
     * and a rule that treats unknown as false then passes its test while being wrong.
     */
    public enum History {
        /** The lookup succeeded and found nothing: genuinely new. */
        NOT_SEEN(false),
        /** The lookup succeeded and found it: genuinely known. */
        SEEN(true),
        /** The lookup could not be completed. Must not be treated as {@link #NOT_SEEN}. */
        UNKNOWN(null);

        private final Boolean boxed;

        History(Boolean boxed) {
            this.boxed = boxed;
        }

        public Boolean boxed() {
            return boxed;
        }
    }
}
