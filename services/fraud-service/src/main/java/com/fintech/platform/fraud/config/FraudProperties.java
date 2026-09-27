package com.fintech.platform.fraud.config;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every tunable in the fraud engine, bound from {@code app.fraud.*}.
 *
 * <p>Thresholds are configuration and points are not. The thresholds — "is 5000 large", "is 5 payments a
 * minute too many" — legitimately differ between an environment with synthetic traffic and a real one,
 * and changing one in a deployment must not require a release. The points, and the band boundaries they
 * sum into, are a published meaning for a number that appears in an API response and on a screen next to
 * the word "decline"; a tunable that lets one deployment weight a rule differently makes that number mean
 * something else there, invisibly. So points live in each rule as code, and only thresholds come from
 * here.
 *
 * <p><b>Amount thresholds are keyed by currency.</b> {@code 5000.00} is not a quantity; 5000 minor units
 * is 50 GBP, 5000 JPY and, for a currency with no minor unit, nothing at all. A single number compared
 * against a count of minor units is either wrong for most currencies or wrong for one, so each amount
 * rule carries a map and the rule simply does not fire on a currency that is absent from it. That is
 * deliberate: skipping is honest, and the alternative — guessing a rate — would silently decide which
 * payments look large.
 *
 * <p>Implements {@link InitializingBean} so that an unusable configuration fails at startup rather than
 * on the first payment that happens to hit the bad value. A fraud threshold that is unparseable is a
 * service that would answer every transaction with a 500, and a service that is up is easier to notice
 * than one that started and quietly stopped scoring.
 */
@ConfigurationProperties(prefix = "app.fraud")
public class FraudProperties implements InitializingBean {

    /**
     * The currency a single-scalar threshold is expressed in, and the one used to report it in logs.
     *
     * <p>Only used where a threshold is genuinely currency-independent (a count, a duration) and for
     * reporting. Amounts always carry a currency.
     */
    private String defaultCurrency = "GBP";

    @Override
    public void afterPropertiesSet() {
        if (defaultCurrency == null || !defaultCurrency.matches("[A-Z]{3}")) {
            throw new IllegalStateException(
                    "app.fraud.default-currency must be a 3-letter code, got " + defaultCurrency);
        }
        decisions.validate();
        rules.validate(defaultCurrency);
        velocity.validate();
        observations.validate();
        alerts.validate();
        deadLetter.validate();
        validateCapAndThresholds();
    }

    /**
     * Checks the manual-adjustment cap against the decision thresholds.
     *
     * <p>This is the one validation here that is about privilege rather than about a number being
     * impossible. {@code max-manual-score} above {@code decline-threshold} would mean an analyst with one
     * role can write {@code DECLINE} onto a payment by hand — a step-down the rules refused to make,
     * reached through the API instead of through the scorer. Nothing at runtime should need to refuse it,
     * because by then the cap has already been configured that way; this makes it a startup failure
     * instead. The default pairing (75 against 76) is deliberately one point short of the line, so the
     * refusal is the default behaviour rather than a special case.
     */
    private void validateCapAndThresholds() {
        if (alerts.getMaxManualScore() >= decisions.getDeclineThreshold()) {
            throw new IllegalStateException("app.fraud.alerts.max-manual-score ("
                    + alerts.getMaxManualScore() + ") must be below app.fraud.decisions.decline-threshold ("
                    + decisions.getDeclineThreshold()
                    + "): a manual adjustment at or above it could write a DECLINE the rules did not");
        }
    }

    /** Where the score stops being a number and becomes a decision. */
    public static class Decisions {

        /** At or above this score, the decision is {@code REVIEW} and an alert is raised. */
        private int reviewThreshold = 51;

        /** At or above this score, the decision is {@code DECLINE}. */
        private int declineThreshold = 76;

        /**
         * At or above this score an alert exists, whatever the decision.
         *
         * <p>Separate from the decision thresholds so that "tell a human" and "refuse the payment" can be
         * moved independently. They happen to share a value by default, which is a choice and not a
         * coupling.
         */
        private int alertThreshold = 51;

        /**
         * A rule that contributes at least this many points, on its own, forces the decision to at least
         * {@code REVIEW} and opens an alert regardless of the total.
         *
         * <p>The reason this exists: the score is a sum, and a sum can be diluted. Four small findings —
         * a new device, a new merchant, a recent activation, a network change — total 90 and are already
         * a decline, but a single finding worth 30 can also be pushed under 51 by nothing at all, which
         * looks like a design oversight to anyone who reads the reasons list. Some single signals are bad
         * enough to require a human regardless of what else was or was not found.
         */
        private int highSignalPoints = 30;

        void validate() {
            requireRange("app.fraud.decisions.review-threshold", reviewThreshold);
            requireRange("app.fraud.decisions.decline-threshold", declineThreshold);
            requireRange("app.fraud.decisions.alert-threshold", alertThreshold);
            if (declineThreshold < reviewThreshold) {
                throw new IllegalStateException(
                        "app.fraud.decisions.decline-threshold must be at or above review-threshold; otherwise a band of scores can never be declined");
            }
            if (highSignalPoints < 0) {
                throw new IllegalStateException("app.fraud.decisions.high-signal-points must not be negative");
            }
        }

        public int getReviewThreshold() {
            return reviewThreshold;
        }

        public void setReviewThreshold(int reviewThreshold) {
            this.reviewThreshold = reviewThreshold;
        }

        public int getDeclineThreshold() {
            return declineThreshold;
        }

        public void setDeclineThreshold(int declineThreshold) {
            this.declineThreshold = declineThreshold;
        }

        public int getAlertThreshold() {
            return alertThreshold;
        }

        public void setAlertThreshold(int alertThreshold) {
            this.alertThreshold = alertThreshold;
        }

        public int getHighSignalPoints() {
            return highSignalPoints;
        }

        public void setHighSignalPoints(int highSignalPoints) {
            this.highSignalPoints = highSignalPoints;
        }
    }

    private Decisions decisions = new Decisions();

    /** One entry per rule. Only the comparison values; the points are in the rule. */
    public static class Rules {

        private AmountThresholds largeAmount = AmountThresholds.of("5000.00");

        private AmountThresholds newDeviceAmount = AmountThresholds.of("1000.00");

        private AmountThresholds newMerchantAmount = AmountThresholds.of("500.00");

        /** Payments per customer inside the window before velocity fires. */
        private int velocityMaxPayments = 5;

        private int velocityWindowSeconds = 60;

        /** How recently the customer's network must have changed for the change to count as rapid. */
        private int networkChangeWindowSeconds = 600;

        /** How recently the card must have been first seen for the activation rule to fire. */
        private int cardActivationWindowSeconds = 86_400;

        /** Other customers on the same device required before a device is considered shared. */
        private int sharedDeviceMinOtherCustomers = 1;

        void validate(String defaultCurrency) {
            largeAmount.validate("app.fraud.rules.large-amount", defaultCurrency);
            newDeviceAmount.validate("app.fraud.rules.new-device-amount", defaultCurrency);
            newMerchantAmount.validate("app.fraud.rules.new-merchant-amount", defaultCurrency);
            if (velocityMaxPayments < 0) {
                throw new IllegalStateException("app.fraud.rules.velocity-max-payments must not be negative");
            }
            requirePositiveSeconds("app.fraud.rules.velocity-window-seconds", velocityWindowSeconds);
            requirePositiveSeconds("app.fraud.rules.network-change-window-seconds", networkChangeWindowSeconds);
            requirePositiveSeconds("app.fraud.rules.card-activation-window-seconds", cardActivationWindowSeconds);
            if (sharedDeviceMinOtherCustomers < 0) {
                throw new IllegalStateException(
                        "app.fraud.rules.shared-device-min-other-customers must not be negative");
            }
        }

        public AmountThresholds getLargeAmount() {
            return largeAmount;
        }

        public void setLargeAmount(AmountThresholds largeAmount) {
            this.largeAmount = largeAmount;
        }

        public AmountThresholds getNewDeviceAmount() {
            return newDeviceAmount;
        }

        public void setNewDeviceAmount(AmountThresholds newDeviceAmount) {
            this.newDeviceAmount = newDeviceAmount;
        }

        public AmountThresholds getNewMerchantAmount() {
            return newMerchantAmount;
        }

        public void setNewMerchantAmount(AmountThresholds newMerchantAmount) {
            this.newMerchantAmount = newMerchantAmount;
        }

        public int getVelocityMaxPayments() {
            return velocityMaxPayments;
        }

        public void setVelocityMaxPayments(int velocityMaxPayments) {
            this.velocityMaxPayments = velocityMaxPayments;
        }

        public int getVelocityWindowSeconds() {
            return velocityWindowSeconds;
        }

        public void setVelocityWindowSeconds(int velocityWindowSeconds) {
            this.velocityWindowSeconds = velocityWindowSeconds;
        }

        public int getNetworkChangeWindowSeconds() {
            return networkChangeWindowSeconds;
        }

        public void setNetworkChangeWindowSeconds(int networkChangeWindowSeconds) {
            this.networkChangeWindowSeconds = networkChangeWindowSeconds;
        }

        public int getCardActivationWindowSeconds() {
            return cardActivationWindowSeconds;
        }

        public void setCardActivationWindowSeconds(int cardActivationWindowSeconds) {
            this.cardActivationWindowSeconds = cardActivationWindowSeconds;
        }

        public int getSharedDeviceMinOtherCustomers() {
            return sharedDeviceMinOtherCustomers;
        }

        public void setSharedDeviceMinOtherCustomers(int sharedDeviceMinOtherCustomers) {
            this.sharedDeviceMinOtherCustomers = sharedDeviceMinOtherCustomers;
        }
    }

    private Rules rules = new Rules();

    /** The Redis counter behind the velocity rule. */
    public static class Velocity {

        /**
         * How long a payment stays in the window.
         *
         * <p>Deliberately the same number as the rule's window so that the counter's contents and the
         * rule's comparison cannot drift apart, which is why the rule reads the sample's window rather
         * than its own configured one.
         */
        private int windowSeconds = 60;

        /**
         * The score member for a payment, in milliseconds since the epoch.
         *
         * <p>Two payments in the same millisecond would collide and under-count, which fails in the
         * direction of not raising an alert. Acceptable; a counter that over-counts would be worse.
         */
        private String scoreFormat = "java:Instant#toEpochMilli";

        void validate() {
            requirePositiveSeconds("app.fraud.velocity.window-seconds", windowSeconds);
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public String getScoreFormat() {
            return scoreFormat;
        }

        public void setScoreFormat(String scoreFormat) {
            this.scoreFormat = scoreFormat;
        }
    }

    private Velocity velocity = new Velocity();

    /**
     * Whether observations are written for a customer, and how long they are kept.
     *
     * <p>These rows are the engine's memory of "I have seen this device, network and merchant before".
     * They are keyed by subject digest, never by name, and a retention period exists because a fact that
     * is never forgotten is a fact that can never be corrected.
     */
    public static class Observations {

        /** Set false in an environment that must not accumulate customer history. */
        private boolean enabled = true;

        private int deviceRetentionDays = 180;

        private int networkRetentionDays = 30;

        private int merchantRetentionDays = 365;

        private int cardRetentionDays = 1095;

        void validate() {
            requirePositive("app.fraud.observations.device-retention-days", deviceRetentionDays);
            requirePositive("app.fraud.observations.network-retention-days", networkRetentionDays);
            requirePositive("app.fraud.observations.merchant-retention-days", merchantRetentionDays);
            requirePositive("app.fraud.observations.card-retention-days", cardRetentionDays);
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getDeviceRetentionDays() {
            return deviceRetentionDays;
        }

        public void setDeviceRetentionDays(int deviceRetentionDays) {
            this.deviceRetentionDays = deviceRetentionDays;
        }

        public int getNetworkRetentionDays() {
            return networkRetentionDays;
        }

        public void setNetworkRetentionDays(int networkRetentionDays) {
            this.networkRetentionDays = networkRetentionDays;
        }

        public int getMerchantRetentionDays() {
            return merchantRetentionDays;
        }

        public void setMerchantRetentionDays(int merchantRetentionDays) {
            this.merchantRetentionDays = merchantRetentionDays;
        }

        public int getCardRetentionDays() {
            return cardRetentionDays;
        }

        public void setCardRetentionDays(int cardRetentionDays) {
            this.cardRetentionDays = cardRetentionDays;
        }
    }

    private Observations observations = new Observations();

    /** Alert lifecycle defaults. */
    public static class Alerts {

        /** Hours an alert may sit unclaimed before the dashboard counts it as breaching its SLA. */
        private int breachHours = 4;

        /** Highest an analyst may set a decision by hand. Beyond this, a second pair of eyes. */
        private int maxManualScore = 75;

        void validate() {
            requirePositive("app.fraud.alerts.breach-hours", breachHours);
            if (maxManualScore < 0 || maxManualScore > 100) {
                throw new IllegalStateException("app.fraud.alerts.max-manual-score must be between 0 and 100");
            }
        }

        public int getBreachHours() {
            return breachHours;
        }

        public void setBreachHours(int breachHours) {
            this.breachHours = breachHours;
        }

        public int getMaxManualScore() {
            return maxManualScore;
        }

        public void setMaxManualScore(int maxManualScore) {
            this.maxManualScore = maxManualScore;
        }
    }

    private Alerts alerts = new Alerts();

    private DeadLetter deadLetter = new DeadLetter();

    /**
     * What happens to an event this consumer cannot process.
     *
     * <p>Bound here, under {@code app.fraud}, rather than under {@code spring.kafka.listener} where it
     * used to live. Those three keys did not bind to anything: Spring Boot's
     * {@code KafkaProperties.Listener} has no {@code max-attempts}, {@code back-off} or
     * {@code dead-letter} property, and unknown keys on a {@code @ConfigurationProperties} class are
     * ignored rather than rejected. The result was a configuration file that read like a dead-letter
     * policy, a topic catalogue that provisioned {@code dead-letter-events}, and a consumer that on a
     * poison event retried on the container's built-in schedule, logged, and moved on — committing the
     * offset and dropping the event. Nothing was ever published to the dead-letter topic, so the safety
     * net existed in the YAML, the docs and the topic list, and nowhere else.
     */
    public static class DeadLetter {

        private String topic = "dead-letter-events";

        private int maxAttempts = 3;

        private long initialIntervalMs = 1000;

        private double multiplier = 2.0;

        private long maxIntervalMs = 10_000;

        /**
         * Fails at startup rather than leaving a consumer that quietly drops what it cannot read.
         *
         * @throws IllegalStateException if a value is out of range
         */
        public void validate() {
            if (topic == null || topic.isBlank()) {
                throw new IllegalStateException("app.fraud.dead-letter.topic must be a topic name");
            }
            if (maxAttempts < 2) {
                throw new IllegalStateException("app.fraud.dead-letter.max-attempts must be at least 2, got "
                        + maxAttempts + "; a value of 1 would park a record without ever retrying it, which "
                        + "turns a transient failure into a dead event");
            }
            if (initialIntervalMs <= 0 || multiplier < 1.0 || maxIntervalMs < initialIntervalMs) {
                throw new IllegalStateException("app.fraud.dead-letter back-off is unusable: initial="
                        + initialIntervalMs + "ms multiplier=" + multiplier + " max=" + maxIntervalMs
                        + "ms; expected a positive initial interval, a multiplier of at least 1, and a max "
                        + "at least as large as the initial interval");
            }
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getInitialIntervalMs() {
            return initialIntervalMs;
        }

        public void setInitialIntervalMs(long initialIntervalMs) {
            this.initialIntervalMs = initialIntervalMs;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public long getMaxIntervalMs() {
            return maxIntervalMs;
        }

        public void setMaxIntervalMs(long maxIntervalMs) {
            this.maxIntervalMs = maxIntervalMs;
        }
    }

    public DeadLetter getDeadLetter() {
        return deadLetter;
    }

    public void setDeadLetter(DeadLetter deadLetter) {
        this.deadLetter = deadLetter;
    }

    private static void requireRange(String name, int value) {
        if (value < 0 || value > 100) {
            throw new IllegalStateException(name + " must be between 0 and 100, got " + value);
        }
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalStateException(name + " must be positive, got " + value);
        }
    }

    private static void requirePositiveSeconds(String name, int value) {
        if (value <= 0) {
            throw new IllegalStateException(name + " must be a positive number of seconds, got " + value);
        }
    }

    public String getDefaultCurrency() {
        return defaultCurrency;
    }

    public void setDefaultCurrency(String defaultCurrency) {
        this.defaultCurrency = defaultCurrency;
    }

    public Decisions getDecisions() {
        return decisions;
    }

    public void setDecisions(Decisions decisions) {
        this.decisions = decisions;
    }

    public Rules getRules() {
        return rules;
    }

    public void setRules(Rules rules) {
        this.rules = rules;
    }

    public Velocity getVelocity() {
        return velocity;
    }

    public void setVelocity(Velocity velocity) {
        this.velocity = velocity;
    }

    public Observations getObservations() {
        return observations;
    }

    public void setObservations(Observations observations) {
        this.observations = observations;
    }

    public Alerts getAlerts() {
        return alerts;
    }

    public void setAlerts(Alerts alerts) {
        this.alerts = alerts;
    }

    /**
     * A set of amount thresholds, one per currency.
     *
     * <p>Bound as {@code {GBP: 5000.00, USD: 6000.00}} from YAML. A currency that is absent produces
     * {@link java.util.Optional#empty()} and the rule does not fire, which the decision records.
     */
    public static class AmountThresholds {

        private Map<String, String> byCurrency = new LinkedHashMap<>();

        public AmountThresholds() {}

        public static AmountThresholds of(String decimalInDefaultCurrency) {
            AmountThresholds thresholds = new AmountThresholds();
            thresholds.byCurrency.put("GBP", decimalInDefaultCurrency);
            return thresholds;
        }

        public Map<String, String> getByCurrency() {
            return byCurrency;
        }

        public void setByCurrency(Map<String, String> byCurrency) {
            this.byCurrency = byCurrency == null ? new LinkedHashMap<>() : new LinkedHashMap<>(byCurrency);
        }

        void validate(String propertyName, String defaultCurrency) {
            if (byCurrency.isEmpty()) {
                throw new IllegalStateException(propertyName + " must configure at least one currency");
            }
            byCurrency.forEach((code, decimal) -> {
                if (code == null || !code.matches("[A-Z]{3}")) {
                    throw new IllegalStateException(
                            propertyName + ".by-currency keys must be 3-letter currency codes, got " + code);
                }
                if (decimal == null || !decimal.matches("^\\d+(\\.\\d+)?$")) {
                    throw new IllegalStateException(propertyName + ".by-currency[" + code
                            + "] must be a plain positive decimal, got " + decimal);
                }
                try {
                    new BigDecimal(decimal);
                } catch (NumberFormatException e) {
                    throw new IllegalStateException(propertyName + ".by-currency[" + code + "] is not a number", e);
                }
            });
            if (!byCurrency.containsKey(defaultCurrency)) {
                throw new IllegalStateException(
                        propertyName + " has no threshold for the default currency " + defaultCurrency);
            }
        }

        /** The currencies this rule is configured for, in declaration order. */
        public List<String> currencies() {
            return List.copyOf(byCurrency.keySet());
        }
    }
}
