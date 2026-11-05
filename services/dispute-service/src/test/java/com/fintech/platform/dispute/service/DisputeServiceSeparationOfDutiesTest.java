package com.fintech.platform.dispute.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fintech.platform.common.error.ApiException;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.dispute.domain.DisputeReason;
import com.fintech.platform.dispute.domain.DisputeStatus;
import com.fintech.platform.dispute.persistence.DisputeEntity;
import com.fintech.platform.dispute.persistence.DisputeEvidenceRepository;
import com.fintech.platform.dispute.persistence.DisputeRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A refund is money leaving the platform on one person's decision, so the person deciding must not be
 * the person asking. PLATFORM_ADMIN holds both the open and the decide permission, which is exactly
 * the case this guards.
 */
class DisputeServiceSeparationOfDutiesTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final String OPENER = "admin-subject";

    private final DisputeRepository disputes = mock(DisputeRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private DisputeService service;
    private DisputeEntity dispute;

    @BeforeEach
    void setUp() {
        service = new DisputeService(
                disputes,
                mock(DisputeEvidenceRepository.class),
                mock(TransactionLookup.class),
                outbox,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SimpleMeterRegistry());
        dispute = DisputeEntity.open(UUID.randomUUID(), DisputeReason.NOT_RECEIVED, "never arrived", OPENER, NOW);
        when(disputes.findByIdForUpdate(any())).thenReturn(Optional.of(dispute));
    }

    @Test
    @DisplayName("the opener cannot decide their own dispute, and nothing is published")
    void openerCannotResolve() {
        InternalIdentity opener = identity(OPENER, "PLATFORM_ADMIN");

        assertThatThrownBy(() -> service.resolve(dispute.getId(), true, "approve my own refund", opener))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("someone other than");

        assertThat(dispute.getStatus()).isEqualTo(DisputeStatus.OPEN);
        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("a different staff member can decide it")
    void otherStaffCanResolve() {
        DisputeEntity resolved =
                service.resolve(dispute.getId(), true, "carrier confirms loss", identity("agent", "SUPPORT_AGENT"));

        assertThat(resolved.getStatus()).isNotEqualTo(DisputeStatus.OPEN);
    }

    private static InternalIdentity identity(String subject, String role) {
        return new InternalIdentity(subject, subject, List.of(role), "corr-1", NOW);
    }
}
