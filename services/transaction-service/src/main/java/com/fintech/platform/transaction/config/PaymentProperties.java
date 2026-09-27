package com.fintech.platform.transaction.config;

import java.util.Currency;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Payment limits.
 *
 * <p>All of it has a default, because a limit that can be unset is a limit that will be unset in some
 * environment and unbounded there. The defaults are the ones that are safe when nobody has thought
 * about the deployment, and every one of them can be tightened by configuration.
 *
 * <p>Bound in application.yml under {@code app.payments}, and validated on binding: a limit of zero or
 * a negative one is a misconfiguration that should stop the service at startup rather than quietly
 * decline every payment or quietly permit them all.
 *
 * <p>The outbox relay's poll interval is deliberately not here. It used to be, and that was a lie in
 * three directions: it bound from {@code app.payments.outbox-poll-interval} while the YAML wrote
 * {@code app.payments.outbox.poll-interval-millis} and the relay read
 * {@code app.outbox.poll-interval-millis}, so no two of the three agreed and changing the field
 * changed nothing. A setting that appears configurable and is not gets changed during an incident by
 * someone who believes it worked. It lives under {@code app.outbox} now, in the one place the
 * {@code @Scheduled} placeholder that actually reads it can see.
 *
 * @param maxTransactionAmount the ceiling on a single payment
 * @param maxDailyAmount the ceiling on one customer's authorised-and-settled total per UTC day
 * @param supportedCurrencies the currencies this deployment will open accounts in
 */
@ConfigurationProperties(prefix = "app.payments")
public record PaymentProperties(
        Money maxTransactionAmount, Money maxDailyAmount, java.util.List<Currency> supportedCurrencies) {

    public PaymentProperties {
        if (maxTransactionAmount == null) {
            throw new IllegalArgumentException("app.payments.max-transaction-amount is required");
        }
        if (maxDailyAmount == null) {
            throw new IllegalArgumentException("app.payments.max-daily-amount is required");
        }
        // Parsed here, at binding time, so a limit written as "5,000" or "5000.005" or "fifty
        // thousand" stops the service at startup. Validated later it would be a limit that rejects
        // every payment in the deployment, discovered on the first request rather than at boot.
        com.fintech.platform.transaction.domain.Money perTransaction = maxTransactionAmount.toMoney();
        com.fintech.platform.transaction.domain.Money perDay = maxDailyAmount.toMoney();
        if (!perTransaction.isPositive()) {
            throw new IllegalArgumentException("app.payments.max-transaction-amount must be positive");
        }
        if (!perDay.isPositive()) {
            throw new IllegalArgumentException("app.payments.max-daily-amount must be positive");
        }
        if (perDay.minorUnits() < perTransaction.minorUnits()) {
            // A daily limit below the single-payment limit would make the larger of the two unreachable
            // and no payment would ever fail for exceeding it, so the setting would be a lie.
            throw new IllegalArgumentException(
                    "app.payments.max-daily-amount must be at least the max-transaction-amount");
        }
        if (supportedCurrencies == null || supportedCurrencies.isEmpty()) {
            throw new IllegalArgumentException("app.payments.supported-currencies must list at least one currency");
        }
    }

    /**
     * An amount from configuration, as a decimal string in a currency.
     *
     * <p>Configured as a decimal string rather than a {@code long} of minor units so that a limit reads
     * the way it is meant: {@code max-transaction-amount: "5000.00"} in GBP. Configuring it as
     * {@code 500000} would be off by a factor of a hundred the first time, and the value that looked
     * like a mistake would be the one in the file.
     */
    public record Money(String decimal, String currency) {

        public Money {
            if (decimal == null || decimal.isBlank()) {
                throw new IllegalArgumentException("a limit needs a decimal amount");
            }
            if (currency == null || currency.isBlank()) {
                throw new IllegalArgumentException("a limit needs a currency code");
            }
        }

        /** Parses into the domain type, which is where precision and range rules actually live. */
        public com.fintech.platform.transaction.domain.Money toMoney() {
            return com.fintech.platform.transaction.domain.Money.parse(
                    decimal, Currency.getInstance(currency.toUpperCase(java.util.Locale.ROOT)));
        }
    }

    /** Whether a payment in this currency can be accepted at all. */
    public boolean supports(Currency currency) {
        return supportedCurrencies.contains(currency);
    }

    /**
     * The limit that applies to a payment in the given currency.
     *
     * <p>Configured limits are stated in one currency. A payment in another supported currency is bounded
     * by the same <em>number of minor units</em> rather than by a converted amount, because this platform
     * has no exchange-rate source and inventing one would mean a limit that silently means something
     * different from what it says.
     */
    public com.fintech.platform.transaction.domain.Money transactionCeilingFor(Currency currency) {
        return com.fintech.platform.transaction.domain.Money.minor(
                maxTransactionAmount.toMoney().minorUnits(), currency);
    }

    public com.fintech.platform.transaction.domain.Money dailyCeilingFor(Currency currency) {
        return com.fintech.platform.transaction.domain.Money.minor(
                maxDailyAmount.toMoney().minorUnits(), currency);
    }
}
