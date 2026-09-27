package com.fintech.platform.settlement.persistence;

import com.fintech.platform.settlement.domain.SettlementCycle;
import com.fintech.platform.settlement.domain.SettlementCycleStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettlementCycleRepository extends JpaRepository<SettlementCycle, UUID> {

    Optional<SettlementCycle> findByReference(String reference);

    /**
     * The cycle for a business date and currency, if one exists.
     *
     * <p>Looked up by date rather than by derived reference on purpose. The reference is derived, so
     * asking for it and finding nothing is not proof that no cycle exists — a caller that only ever asked
     * by reference would be unable to tell "this day was never opened" from "this day was opened under a
     * reference I did not guess", and the first is a normal situation while the second is a bug.
     */
    Optional<SettlementCycle> findByBusinessDateAndCurrencyCode(LocalDate businessDate, String currencyCode);

    Optional<SettlementCycle> findByBusinessDateAndCurrencyCodeAndStatus(
            LocalDate businessDate, String currencyCode, SettlementCycleStatus status);

    Page<SettlementCycle> findByStatus(SettlementCycleStatus status, Pageable pageable);

    Page<SettlementCycle> findAllByOrderByBusinessDateDesc(Pageable pageable);

    /** Cycles for a date range, for the reporting queries that ask "what moved in this window". */
    @Query("""
            SELECT c FROM SettlementCycle c
             WHERE c.businessDate BETWEEN :from AND :to
             ORDER BY c.businessDate DESC, c.currencyCode
            """)
    List<SettlementCycle> findByDateRange(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
