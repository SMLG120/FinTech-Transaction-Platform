package com.fintech.platform.settlement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The money type and the line rules, tested without Spring.
 *
 * <p>These are the tests that matter most in this service, and that is not a stylistic preference. A
 * statement total that is wrong by a penny is a period that will not reconcile against a bank figure, and
 * the reconciliation break it produces will name a difference nobody can account for — so the arithmetic
 * and the sign rules are worth pinning down precisely rather than approximately.
 */
class MoneyAndLineTest {

    private static final Currency GBP = Currency.getInstance("GBP");

    private static final Currency USD = Currency.getInstance("USD");

    private static Money gbp(String decimal) {
        return Money.parse(decimal, GBP);
    }

    @Nested
    @DisplayName("parsing")
    class Parsing {

        @ParameterizedTest(name = "{0} GBP is {1} minor units")
        @CsvSource({
            "0.00, 0",
            "0.01, 1",
            "1.00, 100",
            "12.34, 1234",
            "5000.00, 500000",
            "99.99, 9999",
        })
        void convertsPoundsAndPenceToMinorUnits(String decimal, long expected) {
            assertThat(Money.parse(decimal, GBP).minorUnits()).isEqualTo(expected);
        }

        @Test
        @DisplayName("accepts an amount with no decimal point")
        void acceptsWholeNumbers() {
            assertThat(Money.parse("1200", GBP).minorUnits()).isEqualTo(120_000);
        }

        @Test
        @DisplayName("accepts trailing zeros beyond the currency's precision")
        void acceptsTrailingZeros() {
            // 5.5000 GBP is 5.50, and refusing it would be refusing a formatting choice rather than an
            // amount the currency cannot represent. The check is on significant digits, not on the count
            // of characters after the point.
            assertThat(Money.parse("5.5000", GBP).minorUnits()).isEqualTo(550);
        }

        @ParameterizedTest(name = "rejects {0}")
        @ValueSource(
                strings = {
                    "-12.34", // a sign is this type's job to add, not the caller's to smuggle in
                    "1e3", // BigDecimal would accept it; a formatting mistake is not an amount
                    "1,234.00", // separators are a presentation concern
                    "£12.34", // a symbol is a presentation concern
                    " 12.34", // leading whitespace is a bug in the caller
                    "12.34 ",
                    "abc",
                    "",
                    ".",
                    "12.",
                    "NaN",
                    "Infinity",
                })
        void refusesTextThatIsNotAPlainDecimal(String decimal) {
            // Type only, not message: the list mixes text that is not a number at all with text that is a
            // number the currency cannot hold, and those deserve different explanations. The over-precision
            // case has its own test below with the message it should produce.
            assertThatThrownBy(() -> Money.parse(decimal, GBP)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("refuses a penny amount carrying more precision than a penny")
        void refusesOverPrecision() {
            // 12.345 GBP is not a number of pounds and pence. Rounding it to 12.34 or 12.35 would put a
            // half-penny error into a statement, and half a penny is invisible on the statement and
            // unaccountable for on a bank figure -- which is the same class of problem as a fee that
            // somebody forgot, and the reason this is refused rather than rounded.
            assertThatThrownBy(() -> Money.parse("12.345", GBP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("more precise amount than the currency can represent");
        }

        @Test
        @DisplayName("refuses a zero-decimal currency carrying decimals")
        void refusesPrecisionACurrencyCannotRepresent() {
            // JPY has no minor unit, so 100.50 JPY is not a number of yen. Rounding it to 100 would
            // quietly invent a figure, and the rounding error would surface later as a reconciliation
            // difference against a bank statement that has no decimal places at all.
            Currency jpy = Currency.getInstance("JPY");
            assertThatThrownBy(() -> Money.parse("100.50", jpy))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("more precise amount than the currency can represent");
        }

        @Test
        @DisplayName("refuses null")
        void refusesNull() {
            assertThatThrownBy(() -> Money.parse(null, GBP)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("refuses a currency with no minor unit for a whole amount, and keeps it whole")
        void handlesZeroDecimalCurrency() {
            assertThat(Money.parse("5000", Currency.getInstance("JPY")).minorUnits())
                    .isEqualTo(5000);
        }
    }

    @Nested
    @DisplayName("rendering")
    class Rendering {

        @ParameterizedTest(name = "{0} renders as {1}")
        @CsvSource({"0, 0.00", "1, 0.01", "100, 1.00", "1234, 12.34", "-1234, -12.34"})
        void rendersMinorUnitsAsADecimalString(long minor, String expected) {
            assertThat(gbp("0.00").minorUnits()).isZero();
            assertThat(new Money(minor, GBP).toDecimal()).isEqualTo(expected);
        }

        @Test
        @DisplayName("renders a zero-decimal currency without a decimal point")
        void rendersZeroDecimalCurrencyWithoutAPoint() {
            assertThat(new Money(5000, Currency.getInstance("JPY")).toDecimal()).isEqualTo("5000");
        }

        @Test
        @DisplayName("round-trips through parse and render")
        void roundTrips() {
            for (String decimal : new String[] {"0.00", "0.01", "1.00", "12.34", "5000.00"}) {
                assertThat(gbp(decimal).toDecimal()).isEqualTo(decimal);
            }
            // Negatives are built with negate() rather than parsed, because parse refuses a sign on
            // purpose: an amount arriving on the wire is unsigned and the direction is the consumer's
            // to apply. Constructing a refund by negating makes that decision visible at the call site.
            assertThat(gbp("99.99").negate().toDecimal()).isEqualTo("-99.99");
            assertThat(gbp("99.99").negate().negate().toDecimal()).isEqualTo("99.99");
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        @DisplayName("adds and subtracts within a currency")
        void addsAndSubtracts() {
            assertThat(gbp("12.34").add(gbp("7.66")).toDecimal()).isEqualTo("20.00");
            assertThat(gbp("20.00").subtract(gbp("7.66")).toDecimal()).isEqualTo("12.34");
        }

        @Test
        @DisplayName("negates a refund")
        void negates() {
            assertThat(gbp("12.34").negate().toDecimal()).isEqualTo("-12.34");
            assertThat(gbp("12.34").negate().negate().toDecimal()).isEqualTo("12.34");
        }

        @Test
        @DisplayName("refuses to add across currencies")
        void refusesMixedCurrencyAddition() {
            Money pounds = gbp("12.34");
            Money dollars = Money.parse("12.34", USD);
            assertThatThrownBy(() -> pounds.add(dollars))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("one currency");
        }

        @Test
        @DisplayName("refuses to subtract across currencies")
        void refusesMixedCurrencySubtraction() {
            assertThatThrownBy(() -> gbp("12.34").subtract(Money.parse("1.00", USD)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("refuses an amount past the sanity bound")
        void refusesAbsurdAmounts() {
            assertThatThrownBy(() -> new Money(Money.MAX_MINOR_UNITS + 1, GBP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sanity bound");
            assertThatThrownBy(() -> new Money(-(Money.MAX_MINOR_UNITS + 1), GBP))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("refuses a null currency")
        void refusesNullCurrency() {
            assertThatThrownBy(() -> new Money(100, null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("statement line signs")
    class LineSigns {

        private SettlementCycle openCycle() {
            return SettlementCycle.open(
                    LocalDate.of(2026, 9, 27), GBP, java.time.Instant.parse("2026-09-27T10:00:00Z"));
        }

        @Test
        @DisplayName("a capture carries a positive amount")
        void captureIsPositive() {
            SettlementLine line = SettlementLine.capture(
                    openCycle(), java.util.UUID.randomUUID(), gbp("12.34"), java.time.Instant.now());
            assertThat(line.getKind()).isEqualTo(SettlementLineKind.CAPTURE);
            assertThat(line.amount().toDecimal()).isEqualTo("12.34");
            assertThat(line.amount().isNegative()).isFalse();
        }

        @Test
        @DisplayName("a reversal carries a negative amount")
        void reversalIsNegative() {
            SettlementLine line = SettlementLine.reversal(
                    openCycle(), java.util.UUID.randomUUID(), gbp("12.34").negate(), java.time.Instant.now());
            assertThat(line.getKind()).isEqualTo(SettlementLineKind.REVERSAL);
            assertThat(line.amount().isNegative()).isTrue();
        }

        @Test
        @DisplayName("a capture with a negative amount is refused")
        void refusesNegativeCapture() {
            // A negative capture would credit a period for a payment that moved money the other way, and
            // the sign is the only thing distinguishing the two — so this is checked at the line rather
            // than trusted from the event, where it would be a silent arithmetic error in a statement.
            assertThatThrownBy(() -> SettlementLine.capture(
                            openCycle(),
                            java.util.UUID.randomUUID(),
                            gbp("12.34").negate(),
                            java.time.Instant.now()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("non-negative");
        }

        @Test
        @DisplayName("a reversal with a positive amount is refused")
        void refusesPositiveReversal() {
            // The single most consequential sign bug available here: it would credit a period instead of
            // debiting it, and the cycle would balance beautifully while being wrong.
            assertThatThrownBy(() -> SettlementLine.reversal(
                            openCycle(), java.util.UUID.randomUUID(), gbp("12.34"), java.time.Instant.now()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("non-positive");
        }

        @Test
        @DisplayName("a zero amount is legal for either kind")
        void zeroIsLegalEitherWay() {
            // A zero capture is odd but harmless; a zero reversal is a payment that was captured and
            // refunded for nothing. Both leave the total unchanged, and refusing them would be refusing
            // arithmetic that is not wrong.
            assertThat(SettlementLineKind.CAPTURE.agreesWithSign(0)).isTrue();
            assertThat(SettlementLineKind.REVERSAL.agreesWithSign(0)).isTrue();
        }

        @Test
        @DisplayName("a line in the wrong currency for its cycle is refused")
        void refusesForeignCurrency() {
            SettlementCycle cycle = openCycle();
            assertThatThrownBy(() -> SettlementLine.capture(
                            cycle, java.util.UUID.randomUUID(), Money.parse("12.34", USD), java.time.Instant.now()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("settles in GBP");
        }
    }
}
