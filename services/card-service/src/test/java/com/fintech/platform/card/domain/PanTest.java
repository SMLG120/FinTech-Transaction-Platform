package com.fintech.platform.card.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

@DisplayName("Pan")
class PanTest {

    @RepeatedTest(50)
    @DisplayName("mints a Luhn-valid 16-digit number")
    void mintsValidNumbers() {
        Pan pan = Pan.mint();
        assertThat(pan.digits()).hasSize(Pan.LENGTH).containsOnlyDigits();
        assertThat(Pan.isLuhnValid(pan.digits())).isTrue();
    }

    @RepeatedTest(50)
    @DisplayName("starts with 9, the ISO national-use range no card scheme is assigned")
    void mintsOutsideRealSchemeRanges() {
        // A number generated in the 4, 5 or 3 range could be a genuine account at a real issuer. The
        // platform never talks to a network, but the value of being unable to produce a real card
        // number is that the failure mode of pointing this build at a real acquirer is not "a live
        // card number turns up in a fixture".
        assertThat(Pan.mint().digits()).startsWith("9");
    }

    @Test
    @DisplayName("never repeats, so two issues cannot collide on a number")
    void mintsDistinctNumbers() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            seen.add(Pan.mint().digits());
        }
        // 10^11 possible values, so 2000 draws colliding is not a flake worth worrying about, but a
        // broken PRNG that returned a constant would fail here immediately.
        assertThat(seen).hasSize(2000);
    }

    @Test
    @DisplayName("reports the last four digits, which are the only part that survives")
    void exposesLast4() {
        Pan pan = Pan.mint();
        assertThat(pan.last4()).isEqualTo(pan.digits().substring(12));
    }

    @Test
    @DisplayName("formats in groups of four for display")
    void formatsForDisplay() {
        Pan pan = Pan.mint();
        String formatted = pan.formatted();
        assertThat(formatted).matches("\\d{4} \\d{4} \\d{4} \\d{4}");
        assertThat(formatted.replace(" ", "")).isEqualTo(pan.digits());
    }

    @Test
    @DisplayName("masks itself in toString, so no log line can disclose it")
    void masksItselfInToString() {
        // The control that cannot be forgotten. An @Exclude annotation or a discipline about logging
        // would both be one refactor away from a disclosure; overriding toString is not, because the
        // full number is not reachable from this method at all.
        Pan pan = Pan.mint();
        String rendered = pan.toString();
        assertThat(rendered).doesNotContain(pan.digits());
        assertThat(rendered).contains(pan.last4());
        assertThat(rendered).isEqualTo("Pan[**** **** **** " + pan.last4() + "]");
    }

    @Test
    @DisplayName("masks itself inside concatenation and debug formatting too")
    void masksItselfInEveryStringRendering() {
        Pan pan = Pan.mint();
        // The realistic leak paths, not just toString called directly.
        assertThat("card=" + pan).doesNotContain(pan.digits());
        assertThat(String.format(java.util.Locale.ROOT, "card %s", pan)).doesNotContain(pan.digits());
        assertThat(String.valueOf(pan)).doesNotContain(pan.digits());
        assertThat("" + pan).doesNotContain(pan.digits());
    }

    @Nested
    @DisplayName("Luhn")
    class Luhn {

        @Test
        @DisplayName("accepts the standard published test numbers")
        void acceptsPublishedVectors() {
            // 4111111111111111 and 4012888888881881 are the two numbers published as Luhn test vectors.
            // They are never minted here — minting is in the 9 range — but they pin the algorithm
            // against something other than this class's own output.
            assertThat(Pan.isLuhnValid("4111111111111111")).isTrue();
            assertThat(Pan.isLuhnValid("4012888888881881")).isTrue();
        }

        @Test
        @DisplayName("rejects a number whose check digit is wrong")
        void rejectsBadCheckDigit() {
            Pan valid = Pan.mint();
            String digits = valid.digits();
            char last = digits.charAt(15);
            String wrong = digits.substring(0, 15) + (last == '0' ? '1' : '0');
            assertThat(Pan.isLuhnValid(wrong)).isFalse();
        }

        @Test
        @DisplayName("rejects anything that is not sixteen digits")
        void rejectsWrongShape() {
            assertThat(Pan.isLuhnValid(null)).isFalse();
            assertThat(Pan.isLuhnValid("")).isFalse();
            assertThat(Pan.isLuhnValid("411111111111111")).isFalse();
            assertThat(Pan.isLuhnValid("41111111111111111")).isFalse();
            assertThat(Pan.isLuhnValid("4111 1111 1111 1111")).isFalse();
            assertThat(Pan.isLuhnValid("41111111111111x1")).isFalse();
        }
    }
}
