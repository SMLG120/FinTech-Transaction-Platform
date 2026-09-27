package com.fintech.platform.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.customer.kyc.Check;
import com.fintech.platform.customer.kyc.KycProvider;
import com.fintech.platform.customer.kyc.KycStatus;
import com.fintech.platform.customer.pii.PiiCipher;
import com.fintech.platform.customer.pii.PiiProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class KycCheckTest {

    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final String SUBJECT = "kyc-subject-under-test";
    private static final Instant SUBMITTED = Instant.parse("2026-06-15T12:00:00Z");
    private static final Instant DECIDED = Instant.parse("2026-06-15T12:00:04Z");
    private static final String KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private PiiCipher cipher;
    private KycCheck check;

    @BeforeEach
    void setUp() {
        cipher = new PiiCipher(new PiiProperties(KEY, 1));
        check = KycCheck.open(UUID.randomUUID(), CUSTOMER_ID, SUBMITTED);
    }

    private static KycProvider.Assessment approval(String reference) {
        return new KycProvider.Assessment(
                KycProvider.Decision.APPROVE,
                List.of(
                        new KycProvider.CheckResult(Check.NAME_MATCHES_DOCUMENT, true, null),
                        new KycProvider.CheckResult(Check.AGE_ELIGIBLE, true, null),
                        new KycProvider.CheckResult(Check.DOCUMENT_CURRENT, true, null)),
                reference,
                List.of());
    }

    private static KycProvider.Assessment rejection(String reference) {
        return new KycProvider.Assessment(
                KycProvider.Decision.REJECT,
                List.of(
                        new KycProvider.CheckResult(Check.AGE_ELIGIBLE, false, "applicant is under 18"),
                        new KycProvider.CheckResult(Check.DOCUMENT_CURRENT, true, null)),
                reference,
                List.of("applicant is under 18"));
    }

    @Nested
    @DisplayName("on submission")
    class OnSubmission {

        @Test
        @DisplayName("is pending with no provider handle, because no provider has seen it yet")
        void startsPendingWithoutAProviderReference() {
            assertThat(check.outcome()).isEqualTo(KycStatus.PENDING_REVIEW);
            assertThat(check.isDecided()).isFalse();
            assertThat(check.providerReference()).isNull();
            assertThat(check.submittedAt()).isEqualTo(SUBMITTED);
            assertThat(check.results()).isEmpty();
        }

        @Test
        @DisplayName("rejects a missing decision time, so an undecided check is never null-dated")
        void requiresASubmissionTime() {
            assertThatThrownBy(() -> KycCheck.open(UUID.randomUUID(), CUSTOMER_ID, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("submittedAt");
        }
    }

    @Nested
    @DisplayName("on approval")
    class OnApproval {

        @Test
        @DisplayName("concludes as approved with the provider's handle and no failure reasons")
        void recordsAnApproval() {
            check.decide(approval("SYNTH-REF-1"), DECIDED);

            assertThat(check.outcome()).isEqualTo(KycStatus.APPROVED);
            assertThat(check.isDecided()).isTrue();
            assertThat(check.decidedAt()).isEqualTo(DECIDED);
            assertThat(check.providerReference()).isEqualTo("SYNTH-REF-1");
            assertThat(check.failureReasons()).isEmpty();
        }

        @Test
        @DisplayName("stores each check as its own row, so a later report can group by check")
        void storesOneRowPerCheck() {
            check.decide(approval("SYNTH-REF-1"), DECIDED);

            assertThat(check.results()).hasSize(3);
            assertThat(check.results())
                    .extracting(KycCheckResult::checkName)
                    .containsExactlyInAnyOrder(Check.NAME_MATCHES_DOCUMENT, Check.AGE_ELIGIBLE, Check.DOCUMENT_CURRENT);
            assertThat(check.results()).allMatch(KycCheckResult::passed);
        }

        @Test
        @DisplayName("leaves no reason on a passed check, even if the provider supplied one")
        void passedChecksHaveNoReason() {
            KycProvider.Assessment verbose = new KycProvider.Assessment(
                    KycProvider.Decision.APPROVE,
                    List.of(new KycProvider.CheckResult(Check.AGE_ELIGIBLE, true, "age verified against document")),
                    "SYNTH-REF-1",
                    List.of());
            check.decide(verbose, DECIDED);

            assertThat(check.results())
                    .singleElement()
                    .extracting(KycCheckResult::reason)
                    .isNull();
        }
    }

    @Nested
    @DisplayName("on rejection")
    class OnRejection {

        @Test
        @DisplayName("concludes as rejected and keeps the applicant-facing reasons")
        void recordsARejection() {
            check.decide(rejection("SYNTH-REF-2"), DECIDED);

            assertThat(check.outcome()).isEqualTo(KycStatus.REJECTED);
            assertThat(check.failureReasons()).containsExactly("applicant is under 18");
        }

        @Test
        @DisplayName("keeps the failed check's reason and only on that check")
        void keepsReasonsOnTheFailedCheckOnly() {
            check.decide(rejection("SYNTH-REF-2"), DECIDED);

            assertThat(check.results())
                    .filteredOn(r -> !r.passed())
                    .singleElement()
                    .satisfies(result -> {
                        assertThat(result.checkName()).isEqualTo(Check.AGE_ELIGIBLE);
                        assertThat(result.reason()).isEqualTo("applicant is under 18");
                    });
            assertThat(check.results()).filteredOn(KycCheckResult::passed).allMatch(r -> r.reason() == null);
        }
    }

    @Nested
    @DisplayName("when the provider defers")
    class WhenTheProviderDefers {

        @Test
        @DisplayName("is under review rather than failed, and carries no failure reasons")
        void reviewIsNotARejection() {
            check.decide(
                    KycProvider.Assessment.review(
                            "SYNTH-REF-3", List.of(new KycProvider.CheckResult(Check.DOCUMENT_CURRENT, true, null))),
                    DECIDED);

            assertThat(check.outcome()).isEqualTo(KycStatus.UNDER_REVIEW);
            assertThat(check.failureReasons()).isEmpty();
        }

        @Test
        @DisplayName("leaves the check open, so a later decision can still complete it")
        void reviewLeavesTheCheckOpen() {
            check.decide(
                    KycProvider.Assessment.review(
                            "SYNTH-REF-3", List.of(new KycProvider.CheckResult(Check.DOCUMENT_CURRENT, true, null))),
                    DECIDED);

            // Left undecided on purpose: that is what keeps it the customer's current in-flight check.
            // Concluding it would strand the customer in UNDER_REVIEW with nothing left to finish, and
            // would release the partial unique index that stops a second submission arriving meanwhile.
            assertThat(check.isDecided()).isFalse();
            assertThat(check.providerReference()).isEqualTo("SYNTH-REF-3");
        }

        @Test
        @DisplayName("can be completed later, replacing the provisional results")
        void reviewCanBeFollowedByARealDecision() {
            check.decide(
                    KycProvider.Assessment.review(
                            "SYNTH-REF-3", List.of(new KycProvider.CheckResult(Check.DOCUMENT_CURRENT, true, null))),
                    DECIDED);
            check.decide(approval("SYNTH-REF-4"), DECIDED.plusSeconds(3600));

            assertThat(check.outcome()).isEqualTo(KycStatus.APPROVED);
            assertThat(check.isDecided()).isTrue();
            assertThat(check.decidedAt()).isEqualTo(DECIDED.plusSeconds(3600));
            assertThat(check.providerReference()).isEqualTo("SYNTH-REF-4");
            assertThat(check.results()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("immutability")
    class Immutability {

        @Test
        @DisplayName("refuses to be decided twice, so the first decision cannot be overwritten")
        void refusesASecondDecision() {
            check.decide(rejection("SYNTH-REF-2"), DECIDED);

            assertThatThrownBy(() -> check.decide(approval("SYNTH-REF-4"), DECIDED.plusSeconds(60)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already been decided");

            assertThat(check.outcome()).isEqualTo(KycStatus.REJECTED);
            assertThat(check.providerReference()).isEqualTo("SYNTH-REF-2");
        }

        @Test
        @DisplayName("rejects an assessment with a blank handle, which would break dispute lookup")
        void requiresAProviderReference() {
            assertThatThrownBy(() -> check.decide(approval("   "), DECIDED))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("assessment reference");
        }
    }

    @Nested
    @DisplayName("document evidence")
    class DocumentEvidence {

        @Test
        @DisplayName("is stored encrypted and decrypts with the customer's key")
        void encryptsEvidenceUnderTheCustomerKey() {
            check.encryptEvidence(SUBJECT, "SYNTH-0001", "Alice Chen", cipher);

            assertThat(stored(check, "documentReferenceEncrypted"))
                    .isNotEqualTo("SYNTH-0001")
                    .doesNotContain("SYNTH-0001");
            assertThat(check.documentReference(SUBJECT, cipher)).isEqualTo("SYNTH-0001");
            assertThat(check.printedName(SUBJECT, cipher)).isEqualTo("Alice Chen");
        }

        @Test
        @DisplayName("is unreadable under another customer's key")
        void evidenceIsBoundToOneCustomer() {
            check.encryptEvidence(SUBJECT, "SYNTH-0001", "Alice Chen", cipher);

            assertThatThrownBy(() -> check.documentReference("a-different-subject", cipher))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("stops decrypting once the customer is erased")
        void erasureRemovesTheEvidence() {
            check.encryptEvidence(SUBJECT, "SYNTH-0001", "Alice Chen", cipher);
            check.eraseEvidence();

            assertThat(stored(check, "documentReferenceEncrypted")).isNull();
            assertThat(stored(check, "printedNameEncrypted")).isNull();
            // A null subject is what an erased customer looks like, and asking for its evidence is a
            // legitimate audit question, so this returns null instead of throwing.
            assertThat(check.documentReference(null, cipher)).isNull();
        }

        @Test
        @DisplayName("leaves the decision itself intact, since a regulator still needs it")
        void erasureKeepsTheDecision() {
            check.decide(rejection("SYNTH-REF-2"), DECIDED);
            check.encryptEvidence(SUBJECT, "SYNTH-0001", "Alice Chen", cipher);
            check.eraseEvidence();

            assertThat(check.outcome()).isEqualTo(KycStatus.REJECTED);
            assertThat(check.decidedAt()).isEqualTo(DECIDED);
            assertThat(check.failureReasons()).containsExactly("applicant is under 18");
            assertThat(check.results()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("logging")
    class Logging {

        @Test
        @DisplayName("toString excludes ciphertext and the applicant's failure reasons")
        void toStringIsSafeToLog() {
            check.encryptEvidence(SUBJECT, "SYNTH-0001", "Alice Chen", cipher);
            check.decide(rejection("SYNTH-REF-2"), DECIDED);

            assertThat(check.toString())
                    .doesNotContain("SYNTH-0001")
                    .doesNotContain("Alice Chen")
                    .doesNotContain("under 18")
                    .contains("REJECTED");
        }
    }

    private static String stored(Object entity, String field) {
        return (String) ReflectionTestUtils.getField(entity, field);
    }
}
