package com.fintech.platform.settlement.web;

import com.fintech.platform.settlement.domain.SettlementBreak;
import com.fintech.platform.settlement.domain.SettlementCycle;
import com.fintech.platform.settlement.domain.SettlementLine;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What the settlement API returns.
 *
 * <p>Amounts are rendered as decimal strings, not numbers. The same reason transaction-service and
 * fraud-service do it: JSON numbers are doubles to most consumers, and a statement total that arrives as
 * {@code 1200.00} on one side of a log and {@code 1199.9999999999999} on the other is a reconciliation
 * difference nobody can account for.
 *
 * <p>A figure that does not exist is {@code null} and not a zero. "No actual has been declared yet" and
 * "the actual was zero" are different facts about a period, and a response that flattens them is a
 * response that will eventually be read as saying a cycle reconciled perfectly when nothing was ever
 * compared.
 */
public final class SettlementResponses {

    private SettlementResponses() {}

    /** A cycle's headline: enough to list and to decide whether it needs attention. */
    public record CycleSummary(
            UUID id,
            String reference,
            LocalDate businessDate,
            String currency,
            String status,
            String expected,
            String actual,
            String difference,
            int lineCount,
            int openBreaks,
            Instant closedAt,
            Instant settledAt) {

        /**
         * Renders a cycle for listing.
         *
         * @param cycle the cycle
         * @param lineCount how many statement lines it holds
         * @param openBreaks how many of its breaks are not yet resolved
         * @return the summary
         */
        public static CycleSummary of(SettlementCycle cycle, int lineCount, int openBreaks) {
            return new CycleSummary(
                    cycle.getId(),
                    cycle.getReference(),
                    cycle.getBusinessDate(),
                    cycle.getCurrencyCode(),
                    cycle.getStatus().name(),
                    cycle.expected().toDecimal(),
                    cycle.actual() == null ? null : cycle.actual().toDecimal(),
                    cycle.difference() == null ? null : cycle.difference().toDecimal(),
                    lineCount,
                    openBreaks,
                    cycle.getClosedAt(),
                    cycle.getSettledAt());
        }
    }

    /** A full statement: the cycle and every line on it, in the order they were recorded. */
    public record CycleDetail(CycleSummary cycle, List<LineView> lines, List<BreakView> breaks) {

        /**
         * Renders a cycle with its lines and breaks.
         *
         * @param cycle the cycle
         * @param lines its statement lines
         * @param breaks its reconciliation findings
         * @return the detail view
         */
        public static CycleDetail of(SettlementCycle cycle, List<SettlementLine> lines, List<SettlementBreak> breaks) {
            return new CycleDetail(
                    CycleSummary.of(cycle, lines.size(), (int) breaks.stream()
                            .filter(b -> b.getStatus() != com.fintech.platform.settlement.domain.BreakStatus.RESOLVED)
                            .count()),
                    lines.stream()
                            .map(line -> LineView.of(line, cycle.currency()))
                            .toList(),
                    breaks.stream().map(found -> BreakView.of(found, cycle)).toList());
        }
    }

    /** One statement line. */
    public record LineView(
            UUID id, UUID transactionId, String kind, String amount, LocalDate businessDate, Instant createdAt) {

        /**
         * Renders one line, in its cycle's currency.
         *
         * <p>The currency is passed rather than read from {@code line.amount()}, and that is not a
         * convenience. {@code SettlementLine} holds a lazy reference to its cycle and renders itself by
         * asking it for the currency, so mapping a line outside a transaction throws — and because this
         * service runs with {@code open-in-view: false}, "outside a transaction" is where a controller
         * maps. The failure was a 500 on every statement anybody asked to see.
         *
         * <p>The alternative — an {@code @EntityGraph} or a transaction opened in the controller — would
         * have hidden the problem behind a working query while leaving the line unable to say what
         * currency it is in. The caller already holds the cycle, so the parent is simply an argument and
         * the association is never dereferenced.
         *
         * @param line the line
         * @param currency the cycle's currency
         * @return the rendered line
         */
        static LineView of(SettlementLine line, java.util.Currency currency) {
            return new LineView(
                    line.getId(),
                    line.getTransactionId(),
                    line.getKind().name(),
                    new com.fintech.platform.settlement.domain.Money(line.getAmountMinor(), currency).toDecimal(),
                    line.getBusinessDate(),
                    line.getCreatedAt());
        }
    }

    /**
     * One reconciliation finding.
     *
     * <p>Carries both figures and the difference rather than only prose, so a break can be triaged without
     * going back to the cycle, and so an auditor can confirm the two numbers that disagreed.
     */
    public record BreakView(
            UUID id,
            UUID cycleId,
            String reference,
            String kind,
            String status,
            String expected,
            String actual,
            long difference,
            String detail,
            UUID transactionId,
            String acknowledgedBy,
            Instant acknowledgedAt,
            String resolution,
            Instant resolvedAt,
            Instant createdAt) {

        /**
         * Renders one finding against the cycle it belongs to.
         *
         * <p>The cycle is passed in rather than read through the break's lazy reference, for the same
         * reason as {@link LineView#of}: with {@code open-in-view: false}, a controller mapping entities
         * after their transaction has closed has no session, and the listing of findings was a 500 for
         * exactly that reason. Passing it also means a page of findings can be rendered from one
         * batch-loaded set of cycles instead of one query per row.
         *
         * @param found the break
         * @param cycle the cycle it belongs to
         * @return the rendered finding
         */
        static BreakView of(SettlementBreak found, SettlementCycle cycle) {
            // The break's figures are in its cycle's currency, so the rendering has to ask the cycle. A
            // hardcoded code here would render a EUR period's figures as pounds, which is the kind of
            // wrongness that only shows up in an audit.
            java.util.Currency currency = cycle.currency();
            return new BreakView(
                    found.getId(),
                    cycle.getId(),
                    cycle.getReference(),
                    found.getKind().name(),
                    found.getStatus().name(),
                    decimalOrNull(found.getExpectedMinor(), currency),
                    decimalOrNull(found.getActualMinor(), currency),
                    found.getDifferenceMinor(),
                    found.getDetail(),
                    found.getTransactionId(),
                    found.getAcknowledgedBy(),
                    found.getAcknowledgedAt(),
                    found.getResolution(),
                    found.getResolvedAt(),
                    found.getCreatedAt());
        }

        /** Renders a minor-unit figure, or null when the figure does not exist. */
        private static String decimalOrNull(Long minor, java.util.Currency currency) {
            return minor == null ? null : new com.fintech.platform.settlement.domain.Money(minor, currency).toDecimal();
        }
    }
}
