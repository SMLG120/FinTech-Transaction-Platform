package com.fintech.platform.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    private static final Currency GBP = Currency.getInstance("GBP");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency JPY = Currency.getInstance("JPY");

    @Nested
    @DisplayName("parsing")
    class Parsing {

        @ParameterizedTest
        @CsvSource({
            "10.50, 1050",
            "10.5, 1050",
            "0.01, 1",
            "0.1, 10",
            "0, 0",
            "0.00, 0",
            "1234567.89, 123456789",
        })
        @DisplayName("reads a decimal string as an exact count of minor units")
        void parsesExactly(String decimal, long expected) {
            assertThat(Money.parse(decimal, GBP).minorUnits()).isEqualTo(expected);
        }

        @Test
        @DisplayName("treats a zero-decimal currency as having no minor units at all")
        void parsesZeroDecimalCurrency() {
            assertThat(Money.parse("1000", JPY).minorUnits()).isEqualTo(1000);
            assertThat(Money.parse("1000", JPY).toDecimalString()).isEqualTo("1000");
        }

        @ParameterizedTest
        @ValueSource(strings = {"10.501", "0.001", "10.5555"})
        @DisplayName("refuses more precision than the currency has, rather than rounding it away")
        void refusesExcessPrecision(String decimal) {
            // Rounding here would mean the platform silently deciding what the customer owes, and the
            // response would still look well formed so nobody would notice.
            assertThatIllegalArgumentException().isThrownBy(() -> Money.parse(decimal, GBP));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "  ", "ten", "10.5.5", "1e3", "GBP10", "+-5.00", "10,50"})
        @DisplayName("refuses text that is not a plain positive decimal")
        void refusesNonDecimal(String text) {
            assertThatIllegalArgumentException().isThrownBy(() -> Money.parse(text, GBP));
        }

        @Test
        @DisplayName("refuses a negative amount")
        void refusesNegative() {
            assertThatIllegalArgumentException().isThrownBy(() -> Money.parse("-10.50", GBP));
        }

        @Test
        @DisplayName("tolerates surrounding whitespace, because a trimmed input is not an error worth raising")
        void trimsWhitespace() {
            assertThat(Money.parse("  10.50  ", GBP).minorUnits()).isEqualTo(1050);
        }

        @Test
        @DisplayName("refuses a currency with no defined minor unit instead of guessing a scale")
        void refusesPrivateUseScaleCurrency() {
            Currency odd = Currency.getInstance("XBA");
            // XBA is a private-use code with no fraction digits defined. Guessing two for it is how an
            // amount ends up out by a factor of a hundred.
            assertThatIllegalArgumentException().isThrownBy(() -> Money.parse("10.50", odd));
        }
    }

    @Nested
    @DisplayName("rendering")
    class Rendering {

        @ParameterizedTest
        @CsvSource({
            "1050, 10.50",
            "1, 0.01",
            "10, 0.10",
            "0, 0.00",
            "100, 1.00",
            "123456789, 1234567.89",
            "-1050, -10.50",
        })
        @DisplayName("renders minor units in the currency's own scale")
        void renders(long minorUnits, String expected) {
            assertThat(Money.minor(minorUnits, GBP).toDecimalString()).isEqualTo(expected);
        }

        @Test
        @DisplayName("round-trips through parse without drift")
        void roundTrips() {
            for (long units : new long[] {0, 1, 9, 10, 99, 100, 1050, 999_999_999}) {
                Money original = Money.minor(units, GBP);
                assertThat(Money.parse(original.toDecimalString(), GBP)).isEqualTo(original);
            }
        }

        @Test
        @DisplayName("is not locale-dependent, so the same amount renders identically everywhere")
        void isLocaleIndependent() {
            java.util.Locale original = java.util.Locale.getDefault();
            try {
                java.util.Locale.setDefault(java.util.Locale.GERMANY);
                // A locale that formats 1234.5 as "1.234,50" would corrupt the value if this used
                // NumberFormat, and a payments API that renders amounts by locale is a bug report.
                assertThat(Money.minor(123_450, GBP).toDecimalString()).isEqualTo("1234.50");
            } finally {
                java.util.Locale.setDefault(original);
            }
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        @DisplayName("adds and subtracts exactly, which is the whole reason for minor units")
        void addsAndSubtractsExactly() {
            Money tenPence = Money.minor(10, GBP);
            Money twentyPence = Money.minor(20, GBP);
            assertThat(tenPence.plus(twentyPence).minorUnits()).isEqualTo(30);
            assertThat(tenPence.plus(twentyPence).minus(tenPence).minorUnits()).isEqualTo(20);
        }

        @Test
        @DisplayName("stays exact where a double would not")
        void staysExactWhereDoubleWouldNot() {
            // 0.1 + 0.2 == 0.30000000000000004 in binary floating point. This is the reason the type
            // exists, so it is asserted rather than described.
            Money sum = Money.parse("0.10", GBP).plus(Money.parse("0.20", GBP));
            assertThat(sum.minorUnits()).isEqualTo(30);
            assertThat(sum.toDecimalString()).isEqualTo("0.30");
        }

        @Test
        @DisplayName("refuses to combine two currencies rather than converting one")
        void refusesMixedCurrencies() {
            Money pounds = Money.minor(100, GBP);
            Money dollars = Money.minor(100, USD);
            // There is no FX rate source in this platform, so a conversion here would be a rate
            // invented at the call site.
            assertThatIllegalArgumentException().isThrownBy(() -> pounds.plus(dollars));
            assertThatIllegalArgumentException().isThrownBy(() -> pounds.minus(dollars));
            assertThatIllegalArgumentException().isThrownBy(() -> pounds.compareTo(dollars));
        }

        @Test
        @DisplayName("negates and takes an absolute value, for journal lines")
        void negates() {
            assertThat(Money.minor(1050, GBP).negated().minorUnits()).isEqualTo(-1050);
            assertThat(Money.minor(-1050, GBP).negated().minorUnits()).isEqualTo(1050);
            assertThat(Money.minor(-1050, GBP).abs()).isEqualTo(Money.minor(1050, GBP));
        }

        @Test
        @DisplayName("fails loudly on overflow instead of wrapping into a wrong balance")
        void failsOnOverflow() {
            Money nearMax = Money.minor(Money.MAX_MINOR_UNITS, GBP);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> nearMax.plus(Money.minor(Money.MAX_MINOR_UNITS, GBP)));
        }

        @Test
        @DisplayName("refuses a magnitude that is a bug rather than a large payment")
        void refusesAbsurdMagnitude() {
            assertThatIllegalArgumentException().isThrownBy(() -> new Money(Long.MAX_VALUE, GBP));
            assertThatIllegalArgumentException().isThrownBy(() -> new Money(Long.MIN_VALUE, GBP));
        }
    }

    @Nested
    @DisplayName("predicates")
    class Predicates {

        @Test
        @DisplayName("distinguishes zero, positive and negative")
        void distinguishesSigns() {
            assertThat(Money.zero(GBP).isZero()).isTrue();
            assertThat(Money.zero(GBP).isPositive()).isFalse();
            assertThat(Money.minor(1, GBP).isPositive()).isTrue();
            assertThat(Money.minor(-1, GBP).isNegative()).isTrue();
        }

        @Test
        @DisplayName("requires a positive amount only when the operation needs one")
        void requiresPositiveOnDemand() {
            assertThat(Money.minor(1, GBP).requirePositive()).isNotNull();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Money.zero(GBP).requirePositive());
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Money.minor(-1, GBP).requirePositive());
        }

        @Test
        @DisplayName("orders amounts within one currency")
        void orders() {
            Money small = Money.minor(100, GBP);
            Money large = Money.minor(200, GBP);
            assertThat(small.isLessThan(large)).isTrue();
            assertThat(large.isGreaterThan(small)).isTrue();
            assertThat(small.compareTo(Money.minor(100, GBP))).isZero();
        }
    }

    @Test
    @DisplayName("is a value: two equal amounts are equal regardless of how they were built")
    void isAValue() {
        assertThat(Money.parse("10.50", GBP)).isEqualTo(Money.minor(1050, GBP));
        assertThat(Money.parse("10.5", GBP)).isEqualTo(Money.parse("10.50", GBP));
        assertThat(Money.parse("10.50", GBP)).isNotEqualTo(Money.minor(1050, USD));
        assertThat(Money.minor(1050, GBP)).hasToString("10.50 GBP");
    }
}
