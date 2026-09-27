package com.fintech.platform.customer.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.customer.domain.CustomerIdentity;
import com.fintech.platform.customer.domain.CustomerIdentity.ClaimedName;
import com.fintech.platform.customer.domain.CustomerIdentity.DateOfBirth;
import com.fintech.platform.customer.domain.CustomerIdentity.Email;
import com.fintech.platform.customer.domain.CustomerIdentity.IdentityDocument;
import com.fintech.platform.customer.domain.CustomerIdentity.Nationality;
import com.fintech.platform.customer.domain.CustomerIdentity.PostalAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SyntheticKycProviderTest {

    /** Fixed so every date-dependent rule is testable at its boundary. */
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    private final SyntheticKycProvider provider = new SyntheticKycProvider(FIXED);

    /** A document that is valid today and not on any watch list. */
    private static IdentityDocument validDocument() {
        return new IdentityDocument("SYNTH-VALID-0001", new ClaimedName("Alice Chen"), TODAY.plusYears(5), "GB");
    }

    private static CustomerIdentity applicant(CustomerIdentity identity, IdentityDocument document) {
        return new CustomerIdentity(
                identity.name(), identity.dateOfBirth(), identity.email(), identity.address(), document);
    }

    private static CustomerIdentity goodApplicant() {
        return new CustomerIdentity(
                new ClaimedName("Alice Chen"),
                new DateOfBirth(LocalDate.of(1990, 5, 17), Nationality.BRITISH),
                new Email("alice@example.com"),
                new PostalAddress("1 Test Street", "London", "SW1A 1AA", "GB"),
                validDocument());
    }

    @Nested
    @DisplayName("an applicant who satisfies every rule")
    class Approvable {

        @Test
        @DisplayName("is approved")
        void approves() {
            assertThat(provider.assess(goodApplicant()).decision()).isEqualTo(KycProvider.Decision.APPROVE);
        }

        @Test
        @DisplayName("has every check reported as passed, so an approver can see what was actually checked")
        void reportsEveryCheck() {
            KycProvider.Assessment assessment = provider.assess(goodApplicant());

            assertThat(assessment.checks())
                    .hasSize(Check.values().length)
                    .extracting(KycProvider.CheckResult::check)
                    .containsExactlyInAnyOrder(Check.values());
            assertThat(assessment.checks()).allMatch(KycProvider.CheckResult::passed);
        }

        @Test
        @DisplayName("carries no failure reasons, because it was not refused")
        void carriesNoFailureReasons() {
            assertThat(provider.assess(goodApplicant()).failureReasons()).isEmpty();
        }

        @Test
        @DisplayName("is approved regardless of how the name was capitalised or accented")
        void ignoresCosmeticNameDifferences() {
            // The name arrives normalised, so "ALICE  CHEN" and "Alice Chen" are the same applicant and
            // must not be treated as a mismatch between claim and document.
            IdentityDocument document =
                    new IdentityDocument("SYNTH-VALID-0001", new ClaimedName("ALICE   CHEN"), TODAY.plusYears(5), "GB");

            KycProvider.Assessment assessment = provider.assess(applicant(goodApplicant(), document));

            assertThat(resultFor(assessment, Check.NAME_MATCHES_DOCUMENT).passed())
                    .isTrue();
            assertThat(assessment.decision()).isEqualTo(KycProvider.Decision.APPROVE);
        }
    }

    @Nested
    @DisplayName("name matching")
    class NameMatching {

        @Test
        @DisplayName("refuses when the name printed on the document differs from the claim")
        void refusesMismatchedName() {
            IdentityDocument someoneElses =
                    new IdentityDocument("SYNTH-VALID-0001", new ClaimedName("Alice Smith"), TODAY.plusYears(5), "GB");

            KycProvider.Assessment assessment = provider.assess(applicant(goodApplicant(), someoneElses));

            assertThat(assessment.decision()).isEqualTo(KycProvider.Decision.REJECT);
            assertThat(resultFor(assessment, Check.NAME_MATCHES_DOCUMENT).passed())
                    .isFalse();
            assertThat(assessment.failureReasons())
                    .anyMatch(reason -> reason.contains("does not match the name claimed"));
        }

        @Test
        @DisplayName("is a real comparison, not one that passes by construction")
        void isNotTriviallyTrue() {
            // Guards the specific failure where a name check compares the claim to itself and therefore
            // approves every applicant. A check that cannot fail is worse than no check, because it
            // reports that the customer was verified.
            IdentityDocument mismatched =
                    new IdentityDocument("SYNTH-VALID-0001", new ClaimedName("Someone Else"), TODAY.plusYears(5), "GB");

            assertThat(provider.assess(applicant(goodApplicant(), mismatched)).decision())
                    .isEqualTo(KycProvider.Decision.REJECT);
        }
    }

    @Nested
    @DisplayName("age eligibility")
    class AgeEligibility {

        @Test
        @DisplayName("refuses an applicant below the minimum age, naming that check")
        void refusesUnderage() {
            CustomerIdentity child = new CustomerIdentity(
                    new ClaimedName("Bob Smith"),
                    new DateOfBirth(LocalDate.of(2015, 1, 1), Nationality.BRITISH),
                    new Email("bob@example.com"),
                    new PostalAddress("1 Test Street", "London", "SW1A 1AA", "GB"),
                    new IdentityDocument("SYNTH-VALID-0002", new ClaimedName("Bob Smith"), TODAY.plusYears(5), "GB"));

            KycProvider.Assessment assessment = provider.assess(child);

            assertThat(assessment.decision()).isEqualTo(KycProvider.Decision.REJECT);
            assertThat(resultFor(assessment, Check.AGE_ELIGIBLE).passed()).isFalse();
            assertThat(assessment.failureReasons()).anyMatch(reason -> reason.contains("under"));
        }

        @Test
        @DisplayName("accepts an applicant on the day they turn the minimum age, not the day after")
        void acceptsOnTheBoundaryItself() {
            // Exactly 18 on the fixed clock date. Using ">" instead of ">=" here would turn away a
            // customer on the one day they became eligible, with no way for them to tell why.
            CustomerIdentity onBoundary = withBirthDate(LocalDate.of(2008, 6, 15));

            assertThat(resultFor(provider.assess(onBoundary), Check.AGE_ELIGIBLE)
                            .passed())
                    .isTrue();
            assertThat(provider.assess(onBoundary).decision()).isEqualTo(KycProvider.Decision.APPROVE);
        }

        @Test
        @DisplayName("refuses one day short of the minimum age")
        void refusesOneDayShort() {
            CustomerIdentity justShort = withBirthDate(LocalDate.of(2008, 6, 16));

            assertThat(resultFor(provider.assess(justShort), Check.AGE_ELIGIBLE).passed())
                    .isFalse();
        }

        private CustomerIdentity withBirthDate(LocalDate born) {
            return new CustomerIdentity(
                    new ClaimedName("Cara Jones"),
                    new DateOfBirth(born, Nationality.BRITISH),
                    new Email("cara@example.com"),
                    new PostalAddress("1 Test Street", "London", "SW1A 1AA", "GB"),
                    new IdentityDocument("SYNTH-VALID-0003", new ClaimedName("Cara Jones"), TODAY.plusYears(5), "GB"));
        }
    }

    @Nested
    @DisplayName("document validity")
    class DocumentValidity {

        @Test
        @DisplayName("refuses an expired document")
        void refusesExpiredDocument() {
            IdentityDocument expired =
                    new IdentityDocument("SYNTH-VALID-0001", new ClaimedName("Alice Chen"), TODAY.minusDays(1), "GB");

            KycProvider.Assessment assessment = provider.assess(applicant(goodApplicant(), expired));

            assertThat(resultFor(assessment, Check.DOCUMENT_CURRENT).passed()).isFalse();
            assertThat(assessment.decision()).isEqualTo(KycProvider.Decision.REJECT);
        }

        @Test
        @DisplayName("accepts a document expiring today, since today is still valid")
        void acceptsDocumentExpiringToday() {
            IdentityDocument expiresToday =
                    new IdentityDocument("SYNTH-VALID-0001", new ClaimedName("Alice Chen"), TODAY, "GB");

            assertThat(resultFor(provider.assess(applicant(goodApplicant(), expiresToday)), Check.DOCUMENT_CURRENT)
                            .passed())
                    .isTrue();
        }

        @Test
        @DisplayName("refuses a document reported lost or stolen")
        void refusesReportedLostDocument() {
            IdentityDocument reported =
                    new IdentityDocument("SYNTH-LOST-0001", new ClaimedName("Alice Chen"), TODAY.plusYears(5), "GB");

            KycProvider.Assessment assessment = provider.assess(applicant(goodApplicant(), reported));

            assertThat(resultFor(assessment, Check.DOCUMENT_NOT_REPORTED_LOST).passed())
                    .isFalse();
            assertThat(assessment.decision()).isEqualTo(KycProvider.Decision.REJECT);
        }

        @Test
        @DisplayName("keys the lost-and-stolen list on the document, not on the applicant")
        void keysOnDocumentNotApplicant() {
            // The same applicant with a different document is not flagged: a bureau matches on the
            // document number, and conflating the two would refuse a legitimate replacement document.
            IdentityDocument replacement =
                    new IdentityDocument("SYNTH-VALID-9999", new ClaimedName("Alice Chen"), TODAY.plusYears(5), "GB");

            assertThat(resultFor(
                                    provider.assess(applicant(goodApplicant(), replacement)),
                                    Check.DOCUMENT_NOT_REPORTED_LOST)
                            .passed())
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("jurisdiction")
    class Jurisdiction {

        @Test
        @DisplayName("refuses an address in a country the platform does not serve")
        void refusesUnsupportedCountry() {
            CustomerIdentity abroad = new CustomerIdentity(
                    new ClaimedName("Dan Brown"),
                    new DateOfBirth(LocalDate.of(1985, 3, 3), Nationality.BRITISH),
                    new Email("dan@example.com"),
                    new PostalAddress("1 Test Street", "Springfield", "11111", "US"),
                    new IdentityDocument("SYNTH-VALID-0004", new ClaimedName("Dan Brown"), TODAY.plusYears(5), "GB"));

            assertThat(resultFor(provider.assess(abroad), Check.JURISDICTION_SUPPORTED)
                            .passed())
                    .isFalse();
        }

        @Test
        @DisplayName("serves every country it claims to")
        void servesEveryListedCountry() {
            for (String country : new String[] {"GB", "DE", "FR"}) {
                CustomerIdentity applicant = new CustomerIdentity(
                        new ClaimedName("Eve Frank"),
                        new DateOfBirth(LocalDate.of(1988, 7, 7), Nationality.BRITISH),
                        new Email("eve@example.com"),
                        new PostalAddress("1 Test Street", "City", "00000", country),
                        new IdentityDocument(
                                "SYNTH-VALID-0005", new ClaimedName("Eve Frank"), TODAY.plusYears(5), country));

                assertThat(resultFor(provider.assess(applicant), Check.JURISDICTION_SUPPORTED)
                                .passed())
                        .as("country %s", country)
                        .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("rejection reporting")
    class Rejection {

        @Test
        @DisplayName("names every failing check, not just the first")
        void reportsAllFailures() {
            CustomerIdentity failsSeveral = new CustomerIdentity(
                    new ClaimedName("Gil Hall"),
                    new DateOfBirth(LocalDate.of(2016, 1, 1), Nationality.BRITISH),
                    new Email("gil@example.com"),
                    new PostalAddress("1 Test Street", "Nowhere", "00000", "US"),
                    new IdentityDocument(
                            "SYNTH-LOST-0002", new ClaimedName("Hal Ilch"), LocalDate.of(2020, 1, 1), "GB"));

            KycProvider.Assessment assessment = provider.assess(failsSeveral);

            assertThat(assessment.decision()).isEqualTo(KycProvider.Decision.REJECT);
            // Name, age, expiry, jurisdiction and lost-and-stolen all fail here.
            assertThat(assessment.failureReasons()).hasSize(5);
        }

        @Test
        @DisplayName("refuses to construct a rejection with no reason, since that is not actionable")
        void refusesReasonlessRejection() {
            assertThatThrownBy(() ->
                            new KycProvider.Assessment(KycProvider.Decision.REJECT, List.of(), "SYNTH-1", List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one reason");
        }

        @Test
        @DisplayName("refuses to construct a failed check with no reason")
        void refusesReasonlessCheckFailure() {
            assertThatThrownBy(() -> new KycProvider.CheckResult(Check.AGE_ELIGIBLE, false, "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must carry a reason");
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        @DisplayName("gives the same answer for the same applicant, so a decision is reproducible")
        void isRepeatable() {
            assertThat(provider.assess(goodApplicant()).reference())
                    .isEqualTo(provider.assess(goodApplicant()).reference());
            assertThat(provider.assess(goodApplicant()).decision())
                    .isEqualTo(provider.assess(goodApplicant()).decision());
        }

        @Test
        @DisplayName("does not change with the passage of time for an applicant clear of every boundary")
        void isStableOverTime() {
            SyntheticKycProvider later =
                    new SyntheticKycProvider(Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));

            assertThat(later.assess(goodApplicant()).decision()).isEqualTo(KycProvider.Decision.APPROVE);
        }
    }

    @Test
    @DisplayName("reports itself available, so readiness does not flap")
    void isAvailable() {
        assertThat(provider.available()).isTrue();
    }

    private static KycProvider.CheckResult resultFor(KycProvider.Assessment assessment, Check check) {
        return assessment.checks().stream()
                .filter(result -> result.check() == check)
                .findFirst()
                .orElseThrow(() -> new AssertionError("check " + check + " was not performed"));
    }
}
