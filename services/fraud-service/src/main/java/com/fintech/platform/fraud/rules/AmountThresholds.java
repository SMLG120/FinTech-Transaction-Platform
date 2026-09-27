package com.fintech.platform.fraud.rules;

import com.fintech.platform.fraud.config.FraudProperties;
import com.fintech.platform.fraud.domain.Money;
import java.util.Currency;
import java.util.Map;
import java.util.Optional;

/**
 * The one place an amount is compared against a configured threshold.
 *
 * <p>All three amount rules — large amount, new device with a large amount, new merchant with a large
 * amount — need the same two decisions: does this rule have a threshold for the payment's currency, and is
 * the amount above it. Doing that three times in three rules is how one of them ends up comparing GBP
 * minor units against a USD amount.
 *
 * <p><b>An unconfigured currency is a skip, not a zero.</b> If a rule has no threshold for EUR, the answer
 * is {@link Optional#empty()} and the rule does not fire. Falling back to a default currency would mean
 * treating "5000 cents" as "5000 pounds" and firing on payments two thousand times too small, so the
 * absence is surfaced instead of papered over.
 */
final class AmountThresholds {

    private AmountThresholds() {}

    /**
     * Compares an amount against the threshold configured for its own currency.
     *
     * @return the comparison, or empty when the rule has no threshold in that currency
     */
    static Optional<Comparison> compare(Money amount, FraudProperties.AmountThresholds configured) {
        Currency currency = amount.currency();
        String decimal = configured.getByCurrency().get(currency.getCurrencyCode());
        if (decimal == null) {
            return Optional.empty();
        }
        // Parsed in the payment's currency, so JPY's zero decimal places are handled by the same code
        // path as GBP's two.
        Money threshold = Money.parse(decimal, currency);
        return Optional.of(new Comparison(amount, threshold, amount.minorUnits() > threshold.minorUnits()));
    }

    /**
     * One comparison, with its inputs already rendered for the evidence map.
     *
     * @param amount the payment's amount
     * @param threshold the rule's threshold in the same currency
     * @param exceeded strictly greater than, which is what "more than 5000" means
     */
    record Comparison(Money amount, Money threshold, boolean exceeded) {

        /** The evidence a reason line carries: the observed value and the line it crossed. */
        Map<String, String> evidence(String observedLabel, String thresholdLabel) {
            return Map.of(
                    observedLabel, amount.toString(),
                    thresholdLabel, threshold.toString());
        }
    }
}
