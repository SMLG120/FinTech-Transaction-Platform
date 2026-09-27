package com.fintech.platform.customer.pii;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PiiNormalizerTest {

    @Nested
    @DisplayName("email")
    class Email {

        @Test
        @DisplayName("case and surrounding whitespace do not create a second identity")
        void foldsCaseAndWhitespace() {
            // The reason this exists: a uniqueness constraint on the email column is only as good as
            // the definition of equality underneath it. Without folding, a customer could register
            // the same mailbox twice and the duplicate check would not fire.
            assertThat(PiiNormalizer.email("  Alice@Example.COM  ")).isEqualTo("alice@example.com");
        }

        @Test
        @DisplayName("blank and null have no canonical form")
        void blankIsNull() {
            assertThat(PiiNormalizer.email(null)).isNull();
            assertThat(PiiNormalizer.email("   ")).isNull();
        }

        @Test
        @DisplayName("the plus tag is preserved, because it is a real mailbox distinction")
        void preservesPlusTag() {
            assertThat(PiiNormalizer.email("alice+kyc@example.com")).isEqualTo("alice+kyc@example.com");
        }
    }

    @Nested
    @DisplayName("phone")
    class Phone {

        @Test
        @DisplayName("formatting is reduced to digits")
        void stripsFormatting() {
            assertThat(PiiNormalizer.phone("+44 7700 900123")).isEqualTo("447700900123");
            assertThat(PiiNormalizer.phone("+44-7700-900.123")).isEqualTo("447700900123");
            assertThat(PiiNormalizer.phone("447700900123")).isEqualTo("447700900123");
            // The + is dropped, so one subscriber cannot dodge a duplicate check by typing their
            // number a different way on a second visit.
            assertThat(PiiNormalizer.phone("+44 7700 900123")).isEqualTo(PiiNormalizer.phone("447700900123"));
            assertThat(PiiNormalizer.phone("+44 7700 900123")).isEqualTo(PiiNormalizer.phone("44-7700-900.123"));
        }

        @Test
        @DisplayName("a national-format number is left alone rather than guessed at")
        void doesNotGuessNationalFormat() {
            // Converting a national number requires knowing the country, and guessing the trunk prefix
            // wrong would merge two different people into one customer.
            assertThat(PiiNormalizer.phone("07700 900123")).isEqualTo("07700900123");
        }

        @Test
        @DisplayName("a string with no digits is not a phone number")
        void rejectsNonNumeric() {
            assertThat(PiiNormalizer.phone("not a number")).isNull();
            assertThat(PiiNormalizer.phone("+")).isNull();
            assertThat(PiiNormalizer.phone(null)).isNull();
        }
    }

    @Nested
    @DisplayName("name")
    class Name {

        @Test
        @DisplayName("composed and decomposed spellings of one name index identically")
        void foldsAccentsAndComposition() {
            // NFKD means "Zoë" written with a precomposed e-diaeresis and the same name typed as
            // e + combining diaeresis produce one index. Without that, the same person registers twice
            // on a keyboard difference alone.
            String composed = "Zoë Ångström";
            String decomposed = java.text.Normalizer.normalize(composed, java.text.Normalizer.Form.NFD);

            assertThat(composed).isNotEqualTo(decomposed);
            assertThat(PiiNormalizer.name(composed)).isEqualTo(PiiNormalizer.name(decomposed));
        }

        @Test
        @DisplayName("case and repeated whitespace are reduced")
        void foldsCaseAndWhitespace() {
            assertThat(PiiNormalizer.name("  ALICE   CHEN ")).isEqualTo("alice chen");
        }

        @Test
        @DisplayName("a name that is only whitespace has no canonical form")
        void blankIsNull() {
            assertThat(PiiNormalizer.name("   ")).isNull();
        }
    }

    @Nested
    @DisplayName("masking")
    class Masking {

        @Test
        @DisplayName("a name keeps its initials only")
        void masksName() {
            assertThat(PiiMasker.name("Alex Morgan")).isEqualTo("A*** M*****");
        }

        @Test
        @DisplayName("an email keeps the first character and the domain")
        void masksEmail() {
            // The domain survives because it identifies the provider, not the person, and a support
            // agent needs it to recognise the mailbox.
            assertThat(PiiMasker.email("alice.chen@example.com")).isEqualTo("a*********@example.com");
        }

        @Test
        @DisplayName("a phone keeps the last two digits")
        void masksPhone() {
            assertThat(PiiMasker.phone("+447700900123")).isEqualTo("**********23");
        }

        @Test
        @DisplayName("a date of birth keeps the year only")
        void reducesDateOfBirthToYear() {
            // The year is enough to recognise a customer and not enough to answer a
            // knowledge-based authentication question.
            assertThat(PiiMasker.birthYear(LocalDate.of(1987, 4, 2))).isEqualTo("1987");
        }

        @Test
        @DisplayName("an unrecognisable shape is fully masked rather than passed through")
        void masksUnparseableValues() {
            assertThat(PiiMasker.email("nodomain")).isEqualTo("********");
            assertThat(PiiMasker.phone("12")).isEqualTo("**");
        }

        @Test
        @DisplayName("a masked value never contains the original value")
        void neverLeaksTheOriginal() {
            assertThat(PiiMasker.email("alice.chen@example.com")).doesNotContain("lice.chen");
            assertThat(PiiMasker.name("Alex Morgan")).doesNotContain("lex");
        }

        @Test
        @DisplayName("null in, null out")
        void nullSafe() {
            assertThat(PiiMasker.name(null)).isNull();
            assertThat(PiiMasker.email(null)).isNull();
            assertThat(PiiMasker.phone(null)).isNull();
            assertThat(PiiMasker.birthYear(null)).isNull();
        }
    }
}
