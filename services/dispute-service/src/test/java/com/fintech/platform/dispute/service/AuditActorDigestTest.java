package com.fintech.platform.dispute.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The actor digest, without a key, a broker or a database.
 *
 * <p>The digest is what travels to the audit trail in place of a subject, so its properties are
 * asserted as text: stable per subject, opaque, and distinct for distinct subjects. What is not
 * asserted is any joinability with the HMAC purpose digests — there is none by construction, and
 * asserting the absence of a relation would be asserting an implementation detail of two hash
 * functions rather than a property of this one.
 */
class AuditActorDigestTest {

    @Test
    @DisplayName("digests stably: the same subject always names the same actor")
    void digestsStably() {
        assertThat(AuditActorDigest.of("subject-1")).isEqualTo(AuditActorDigest.of("subject-1"));
    }

    @Test
    @DisplayName("renders 64 lowercase hex characters, a SHA-256 in text")
    void rendersHexSha256() {
        assertThat(AuditActorDigest.of("subject-1")).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("distinguishes subjects: two actors are two digests")
    void distinguishesSubjects() {
        assertThat(AuditActorDigest.of("subject-1")).isNotEqualTo(AuditActorDigest.of("subject-2"));
    }

    @Test
    @DisplayName("refuses a blank subject rather than digesting nothing into somebody")
    void refusesABlankSubject() {
        assertThatThrownBy(() -> AuditActorDigest.of("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditActorDigest.of(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
