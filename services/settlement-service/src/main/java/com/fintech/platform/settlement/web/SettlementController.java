package com.fintech.platform.settlement.web;

import com.fintech.platform.common.identity.CurrentCaller;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.common.pagination.PageResponse;
import com.fintech.platform.settlement.domain.BreakStatus;
import com.fintech.platform.settlement.domain.Money;
import com.fintech.platform.settlement.domain.SettlementBreak;
import com.fintech.platform.settlement.domain.SettlementCycle;
import com.fintech.platform.settlement.domain.SettlementCycleStatus;
import com.fintech.platform.settlement.domain.SettlementLine;
import com.fintech.platform.settlement.error.SettlementErrors;
import com.fintech.platform.settlement.persistence.SettlementBreakRepository;
import com.fintech.platform.settlement.persistence.SettlementCycleRepository;
import com.fintech.platform.settlement.persistence.SettlementLineRepository;
import com.fintech.platform.settlement.service.SettlementService;
import jakarta.validation.Valid;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The settlement API.
 *
 * <p>Four actions and a set of reads, and the split between them is the operational model. Reads are open
 * to auditors; the four actions — close a cycle, declare an actual, reconcile, work a break — change what
 * the platform believes about money that has already moved, and are narrower.
 *
 * <p><b>Every handler resolves the caller and asks the authorization bean first.</b> Not as an
 * annotation, because the annotations elsewhere in this platform are inert; see
 * {@link SettlementAuthorization}. The gateway already established the identity, so this is a second
 * opinion at a boundary the gateway cannot enforce, not a second source of truth.
 *
 * <p><b>The reference, not the id, is what appears in URLs.</b> A cycle's reference is what appears on a
 * statement, so an operator's request and the object it names are the same string. It also means a URL in
 * a support ticket is meaningful, which a UUID is not.
 */
@RestController
@RequestMapping("/api/v1/settlement")
public class SettlementController {

    private static final int MAX_PAGE_SIZE = 200;

    private final SettlementService settlement;

    private final SettlementCycleRepository cycles;

    private final SettlementLineRepository lines;

    private final SettlementBreakRepository breaks;

    private final SettlementAuthorization authorization;

    private final CurrentCaller caller;

    public SettlementController(
            SettlementService settlement,
            SettlementCycleRepository cycles,
            SettlementLineRepository lines,
            SettlementBreakRepository breaks,
            SettlementAuthorization authorization,
            CurrentCaller caller) {
        this.settlement = settlement;
        this.cycles = cycles;
        this.lines = lines;
        this.breaks = breaks;
        this.authorization = authorization;
        this.caller = caller;
    }

    /**
     * Lists cycles, newest business date first, optionally filtered by status.
     *
     * @param status the status to filter by, or null for all
     * @param page zero-based page index
     * @param size page size, capped
     * @return the page of cycles
     */
    @GetMapping("/cycles")
    public PageResponse<SettlementResponses.CycleSummary> listCycles(
            @RequestParam(required = false) SettlementCycleStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireRead();
        PageRequest window = page(page, size);
        Page<SettlementCycle> found =
                status == null ? cycles.findAllByOrderByBusinessDateDesc(window) : cycles.findByStatus(status, window);
        return PageResponse.from(found, cycle -> summaryOf(cycle));
    }

    /**
     * One cycle with its full statement.
     *
     * @param reference the cycle's reference
     * @return the cycle, its lines and its breaks
     */
    @GetMapping("/cycles/{reference}")
    public SettlementResponses.CycleDetail getCycle(@PathVariable String reference) {
        requireRead();
        SettlementCycle cycle = settlement.requireCycle(reference);
        return SettlementResponses.CycleDetail.of(
                cycle,
                lines.findByCycleIdOrderByCreatedAtAsc(cycle.getId()),
                breaks.findByCycleIdOrderByCreatedAtAsc(cycle.getId()));
    }

    /**
     * Closes a cycle, freezing its lines and total.
     *
     * @param request the reference to close
     * @return the closed cycle
     */
    @PostMapping("/cycles/close")
    public ResponseEntity<SettlementResponses.CycleSummary> closeCycle(
            @Valid @RequestBody SettlementRequests.CloseCycleRequest request) {
        requireAction();
        SettlementCycle cycle = settlement.close(request.reference());
        return ResponseEntity.ok(summaryOf(cycle));
    }

    /**
     * Declares the independently sourced actual for a period and compares it with the expected total.
     *
     * <p>Returns 200 with the cycle either way, and the difference in the body. A mismatch is a finding
     * that the caller asked for by supplying a number, not a failed request: the declaration succeeded,
     * and what it revealed is that the period does not balance. Answering 409 here would tell the operator
     * their request was malformed when the request was fine and the news is in the response.
     *
     * @param request the declared figure
     * @return the cycle, carrying both figures and the difference
     */
    @PostMapping("/cycles/actual")
    public ResponseEntity<SettlementResponses.CycleSummary> declareActual(
            @Valid @RequestBody SettlementRequests.DeclareActualRequest request) {
        requireAction();
        // Both the code and the figure are checked here rather than left to the domain, because these are
        // mistakes in the request rather than conflicts with the period. A caller who sent "XYZ" needs to
        // be told the currency is not real; a 409 about the cycle's state would send them to look at a
        // period that was never involved.
        Currency currency;
        try {
            currency = Currency.getInstance(request.currency());
        } catch (IllegalArgumentException e) {
            throw SettlementErrors.UNUSABLE_AMOUNT.exception(
                    "'" + request.currency() + "' is not a known ISO 4217 currency code");
        }
        Money actual;
        try {
            actual = Money.parse(request.actualAmount(), currency);
        } catch (IllegalArgumentException e) {
            // Unreachable through the bean validation above, which caps the fraction at the currency's
            // minor unit. Kept because a field constraint is a promise about today and this is a promise
            // about every future field, and a wrong amount silently coerced into a statement is worse
            // than a 400.
            throw SettlementErrors.UNUSABLE_AMOUNT.exception(e.getMessage());
        }
        SettlementCycle cycle = settlement.declareActual(request.reference(), actual);
        return ResponseEntity.ok(summaryOf(cycle));
    }

    /**
     * Confirms a cycle whose declared actual matched.
     *
     * @param reference the cycle's reference
     * @return the reconciled cycle
     */
    @PostMapping("/cycles/{reference}/reconcile")
    public ResponseEntity<SettlementResponses.CycleSummary> reconcile(@PathVariable String reference) {
        requireAction();
        return ResponseEntity.ok(summaryOf(settlement.reconcile(reference)));
    }

    /**
     * Lists reconciliation findings, most recent first, optionally filtered by status.
     *
     * @param status the status to filter by, or null for all
     * @param page zero-based page index
     * @param size page size, capped
     * @return the page of breaks
     */
    @GetMapping("/breaks")
    public PageResponse<SettlementResponses.BreakView> listBreaks(
            @RequestParam(required = false) BreakStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireRead();
        PageRequest window = page(page, size);
        Page<SettlementBreak> found = status == null
                ? breaks.findAllByOrderByCreatedAtDesc(window)
                : breaks.findByStatusOrderByCreatedAtDesc(status, window);
        Map<UUID, SettlementCycle> owning = cyclesById(found.getContent());
        return PageResponse.from(
                found,
                b -> SettlementResponses.BreakView.of(b, owning.get(b.getCycle().getId())));
    }

    /**
     * One finding.
     *
     * @param breakId the break's id
     * @return the break
     */
    @GetMapping("/breaks/{breakId}")
    public SettlementResponses.BreakView getBreak(@PathVariable UUID breakId) {
        requireRead();
        return viewOf(requireBreak(breakId));
    }

    /**
     * Records that somebody has seen a break.
     *
     * <p>The actor comes from the verified identity, not the body. A body field would let a client
     * acknowledge a break in somebody else's name, and an acknowledgement is precisely the record of who
     * looked at a discrepancy.
     *
     * @param breakId the break's id
     * @return the acknowledged break
     */
    @PostMapping("/breaks/{breakId}/acknowledge")
    public ResponseEntity<SettlementResponses.BreakView> acknowledgeBreak(@PathVariable UUID breakId) {
        requireAction();
        InternalIdentity identity = caller.require();
        return ResponseEntity.ok(viewOf(settlement.acknowledgeBreak(breakId, authorization.actorOf(identity))));
    }

    /**
     * Records that the money is accounted for, and why.
     *
     * @param breakId the break's id
     * @param request the explanation
     * @return the resolved break
     */
    @PostMapping("/breaks/{breakId}/resolve")
    public ResponseEntity<SettlementResponses.BreakView> resolveBreak(
            @PathVariable UUID breakId, @Valid @RequestBody SettlementRequests.ResolveBreakRequest request) {
        requireAction();
        return ResponseEntity.ok(viewOf(settlement.resolveBreak(breakId, request.resolution())));
    }

    /**
     * A page window, with the size bounded.
     *
     * <p>No sort supplied, because the ordering is in the repository method names
     * ({@code findAllByOrderByBusinessDateDesc}) and a sort added here would fight them for a derived
     * query's single ordering clause. The bound is on the upper side only: a caller asking for a million
     * rows gets the cap rather than a 500, because the cap is a policy statement and a 500 is a bug
     * report.
     */
    private static PageRequest page(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE));
    }

    private SettlementResponses.CycleSummary summaryOf(SettlementCycle cycle) {
        List<SettlementLine> statementLines = lines.findByCycleIdOrderByCreatedAtAsc(cycle.getId());
        int openBreaks = (int) breaks.findByCycleIdOrderByCreatedAtAsc(cycle.getId()).stream()
                .filter(b -> b.getStatus() != BreakStatus.RESOLVED)
                .count();
        return SettlementResponses.CycleSummary.of(cycle, statementLines.size(), openBreaks);
    }

    /**
     * The cycles a page of findings belongs to, in one query.
     *
     * <p>A finding cannot say what currency its figures are in without its cycle, and rendering it inside
     * a session is not an option for a controller. Asking per row would work and would issue one query
     * per finding, so a page of twenty — a realistic queue, since these are the rows somebody triages
     * every morning — becomes twenty extra round trips. The ids are known from the page, so they are
     * fetched in one go.
     *
     * @param page the findings to be rendered
     * @return their cycles, keyed by id
     */
    private Map<UUID, SettlementCycle> cyclesById(List<SettlementBreak> page) {
        List<UUID> ids = page.stream().map(b -> b.getCycle().getId()).distinct().toList();
        Map<UUID, SettlementCycle> found = new HashMap<>();
        if (!ids.isEmpty()) {
            cycles.findAllById(ids).forEach(cycle -> found.put(cycle.getId(), cycle));
        }
        return found;
    }

    /**
     * Renders a finding, loading the cycle its figures are denominated in.
     *
     * <p>Needed on every path that returns a single finding, because the entity arrives from a service
     * transaction that has already closed and its cycle is an uninitialised proxy. Reading the id off
     * that proxy is fine — the identifier is not loaded state — and one more query fetches the cycle.
     *
     * @param found the finding
     * @return the rendered finding
     */
    private SettlementResponses.BreakView viewOf(SettlementBreak found) {
        return SettlementResponses.BreakView.of(
                found, requireCycleById(found.getCycle().getId()));
    }

    private SettlementCycle requireCycleById(UUID cycleId) {
        return cycles.findById(cycleId)
                .orElseThrow(() -> SettlementErrors.CYCLE_NOT_FOUND.exception(
                        "No settlement cycle with id " + cycleId + " for that break"));
    }

    private SettlementBreak requireBreak(UUID breakId) {
        return breaks.findById(breakId)
                .orElseThrow(
                        () -> SettlementErrors.CYCLE_NOT_FOUND.exception("No settlement break with id " + breakId));
    }

    private void requireRead() {
        authorization.requireRead(caller.require());
    }

    private void requireAction() {
        authorization.requireAction(caller.require());
    }
}
