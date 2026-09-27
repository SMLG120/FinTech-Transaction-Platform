package com.fintech.platform.settlement.persistence;

import com.fintech.platform.settlement.domain.SettlementLine;
import com.fintech.platform.settlement.domain.SettlementLineKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementLineRepository extends JpaRepository<SettlementLine, UUID> {

    List<SettlementLine> findByCycleIdOrderByCreatedAtAsc(UUID cycleId);

    /**
     * Whether this payment settled into any cycle at all.
     *
     * <p>The orphan check, and it is a lookup across every cycle rather than one. A refund for a payment
     * that settled yesterday is not an orphan, and a query scoped to the current cycle would call it one on
     * every refund that crosses midnight — which, since the alternative is recording a break nobody can
     * explain, is the worst kind of false positive this service could produce.
     */
    boolean existsByTransactionIdAndKind(UUID transactionId, SettlementLineKind kind);

    /**
     * This payment's line of one kind, in whichever cycle it landed.
     *
     * <p>Used to name the other period in a {@code CYCLE_ALREADY_SETTLED} break: a break saying "this refund
     * has no capture here" is a question, and one naming the period it settled in is a statement somebody can
     * act on.
     */
    Optional<SettlementLine> findByTransactionIdAndKind(UUID transactionId, SettlementLineKind kind);
}
