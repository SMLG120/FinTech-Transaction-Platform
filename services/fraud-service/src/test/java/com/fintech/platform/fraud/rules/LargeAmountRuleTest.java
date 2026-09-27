package com.fintech.platform.fraud.rules;

import static com.fintech.platform.fraud.FraudFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;

import com.fintech.platform.fraud.FraudFixtures;
import com.fintech.platform.fraud.config.FraudProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * R001 — a large amount.
 *
 * <p>The boundary is the whole test. "Above 5000" and "5000 or more" are different rules and only one of
 * them is what was asked for, so the tests sit exactly on the line and one minor unit either side of it.
 */
class LargeAmountRuleTest {

    private final FraudProperties properties = properties();

    private final LargeAmountRule rule = new LargeAmountRule(properties);

    @Test
    @DisplayName("is R001 LARGE_AMOUNT, worth 35")
    void identity() {
        assertThat(rule.id()).isEqualTo("R001");
        assertThat(rule.name()).isEqualTo("LARGE_AMOUNT");
        assertThat(rule.points()).isEqualTo(35);
    }

    @ParameterizedTest(name = "{0} GBP against a 5000.00 threshold")
    @CsvSource({
        // Exactly on the line does not fire: "above 5000" excludes 5000. This is the case most likely to
        // be wrong by accident, and a customer paying exactly their limit is the least interesting
        // payment on the platform.
        "5000.00, false",
        // One minor unit over does.
        "5000.01, true",
        "5001.00, true",
        "10000.00, true",
        // Comfortably under does not.
        "4999.99, false",
        "10.00, false",
        "0.00, false"
    })
    @DisplayName("fires strictly above the threshold, not on it")
    void boundaryIsExclusive(String amount, boolean expected) {
        assertThat(rule.evaluate(FraudFixtures.payment(amount).build()).isPresent())
                .isEqualTo(expected);
    }

    @Test
    @DisplayName(
            "puts the observed amount and the threshold in the evidence, because 'unusually large' is not a finding")
    void evidenceCarriesBothNumbers() {
        var finding = rule.evaluate(FraudFixtures.payment("6000.00").build()).orElseThrow();
        assertThat(finding.evidence()).containsEntry("amount", "6000.00 GBP").containsEntry("threshold", "5000.00 GBP");
    }

    @Test
    @DisplayName("uses the threshold for the payment's own currency, not the default one")
    void usesThePaymentsOwnCurrency() {
        // 5000 USD is above the USD threshold of 6000.00, and would be below the GBP one of 5000.00.
        // A rule that read the GBP threshold because it was configured first would get this backwards.
        assertThat(rule.evaluate(FraudFixtures.cleanPayment("5500.00", "USD").build()))
                .as("5500 USD is below the 6000 USD threshold")
                .isEmpty();
        assertThat(rule.evaluate(FraudFixtures.cleanPayment("6500.00", "USD").build()))
                .as("6500 USD is above the 6000 USD threshold")
                .isPresent();
    }

    @Test
    @DisplayName("skips a currency it has no threshold for rather than converting")
    void skipsAnUnconfiguredCurrency() {
        // JPY has no configured threshold. Guessing — treating 5000 minor units as 5000 pounds, or
        // converting at some rate nobody configured — would decide which payments look large in a
        // currency no one here has an opinion about. Not firing is the honest answer.
        assertThat(rule.evaluate(FraudFixtures.cleanPayment("9000.00", "JPY").build()))
                .as("no JPY threshold configured, so no finding rather than a wrong one")
                .isEmpty();
    }

    @Test
    @DisplayName("handles a zero-decimal currency through the same code path")
    void handlesZeroDecimalCurrency() {
        FraudProperties properties = properties();
        FraudProperties.AmountThresholds jpy = new FraudProperties.AmountThresholds();
        jpy.getByCurrency().put("JPY", "5000");
        jpy.getByCurrency().put("GBP", "5000.00");
        properties.getRules().setLargeAmount(jpy);
        FraudProperties.Rules rules = properties.getRules();
        // 5000 JPY is 5000 minor units, not 500000. A parser that assumed two decimal places would make
        // this threshold a hundred times too high and the rule would never fire.
        assertThat(new LargeAmountRule(properties)
                        .evaluate(FraudFixtures.cleanPayment("4999", "JPY").build()))
                .isEmpty();
        assertThat(new LargeAmountRule(properties)
                        .evaluate(FraudFixtures.cleanPayment("5001", "JPY").build()))
                .isPresent();
        assertThat(rules.getLargeAmount().getByCurrency()).containsKey("JPY");
    }

    @Test
    @DisplayName("is unaffected by a device, a card or a network, because the amount is the whole rule")
    void ignoresEverythingElse() {
        var withEverything = FraudFixtures.payment("6000.00")
                .device(FraudFixtures.DEVICE_DIGEST)
                .card(FraudFixtures.CARD_DIGEST)
                .network(FraudFixtures.NETWORK_DIGEST)
                .otherCustomersOnDevice(4)
                .velocity(99)
                .build();
        assertThat(rule.evaluate(withEverything)).isPresent();
    }
}
