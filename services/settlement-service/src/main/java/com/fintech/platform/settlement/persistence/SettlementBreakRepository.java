package com.fintech.platform.settlement.persistence;

import com.fintech.platform.settlement.domain.BreakKind;
import com.fintech.platform.settlement.domain.BreakStatus;
import com.fintech.platform.settlement.domain.SettlementBreak;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementBreakRepository extends JpaRepository<SettlementBreak, UUID> {

    /**
     * Whether a cycle still has an unresolved break.
     *
     * <p>Re-read inside {@code reconcile}, and the reason a cycle with a break cannot be marked
     * reconciled: acknowledging a break is not resolving it, and a cycle whose difference is still open
     * is a period whose money has not been accounted for.
     */
    boolean existsByCycleIdAndStatusNot(UUID cycleId, BreakStatus status);

    Optional<SettlementBreak> findByCycleIdAndKind(UUID cycleId, BreakKind kind);

    Page<SettlementBreak> findByStatusOrderByCreatedAtDesc(BreakStatus status, Pageable pageable);

    Page<SettlementBreak> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<SettlementBreak> findByCycleIdOrderByCreatedAtAsc(UUID cycleId);
}
