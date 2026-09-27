package com.fintech.platform.dispute.web;

import com.fintech.platform.common.identity.CurrentCaller;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.common.pagination.PageResponse;
import com.fintech.platform.dispute.domain.DisputeStatus;
import com.fintech.platform.dispute.error.DisputeErrors;
import com.fintech.platform.dispute.persistence.DisputeEntity;
import com.fintech.platform.dispute.persistence.DisputeEvidenceEntity;
import com.fintech.platform.dispute.persistence.DisputeEvidenceRepository;
import com.fintech.platform.dispute.persistence.DisputeRepository;
import com.fintech.platform.dispute.service.DisputeService;
import com.fintech.platform.dispute.web.DisputeRequests.AddEvidenceRequest;
import com.fintech.platform.dispute.web.DisputeRequests.OpenDisputeRequest;
import com.fintech.platform.dispute.web.DisputeRequests.ResolveDisputeRequest;
import com.fintech.platform.dispute.web.DisputeResponses.DisputeDetailView;
import com.fintech.platform.dispute.web.DisputeResponses.DisputeView;
import jakarta.validation.Valid;
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
 * The chargeback workflow: open a case, plead in it, decide it.
 *
 * <p>Customers open their own cases and plead in them; staff read the queue, plead, and decide. A
 * customer who could decide their own case holds a refund button, and a support agent who could
 * open a case on a payment they cannot see would break the ownership every other service keeps.
 *
 * <p><b>Every handler resolves the caller and asks the authorization bean first.</b> Not as an
 * annotation, because the annotations elsewhere in this platform are inert; see {@link
 * DisputeAuthorization}. Ownership — "is this the opener" — is then checked against the row, which
 * only this service can do: the gateway has no idea whose case an id names.
 */
@RestController
@RequestMapping("/api/v1/disputes")
public class DisputeController {

    private static final int MAX_PAGE_SIZE = 200;

    private final DisputeService cases;

    private final DisputeRepository disputes;

    private final DisputeEvidenceRepository file;

    private final DisputeAuthorization authorization;

    private final CurrentCaller caller;

    public DisputeController(
            DisputeService cases,
            DisputeRepository disputes,
            DisputeEvidenceRepository file,
            DisputeAuthorization authorization,
            CurrentCaller caller) {
        this.cases = cases;
        this.disputes = disputes;
        this.file = file;
        this.authorization = authorization;
        this.caller = caller;
    }

    /**
     * Opens a case on a settled payment.
     *
     * <p>Returns 201 with a Location, because a case is a created resource with an id worth
     * linking. The payment must be settled and, for a customer opener, theirs — verified against
     * transaction-service under their own identity before the row exists.
     */
    @PostMapping
    public ResponseEntity<DisputeDetailView> open(@Valid @RequestBody OpenDisputeRequest request) {
        InternalIdentity identity = caller.require();
        authorization.requireOpen(identity);
        DisputeEntity dispute = cases.open(request.transactionId(), request.reason(), request.description(), identity);
        DisputeDetailView view = detailOf(dispute, identity.subject());
        return ResponseEntity.created(java.net.URI.create("/api/v1/disputes/" + dispute.getId()))
                .body(view);
    }

    /**
     * Cases, newest first: the opener's own for a customer, the queue for staff.
     *
     * <p>One endpoint with two answers, split by who asks. A customer asking for "my cases" and
     * receiving everyone's would be the breach this service exists to prevent; staff asking for the
     * queue and receiving one customer's would be a workflow that cannot work.
     */
    @GetMapping
    public PageResponse<DisputeView> list(
            @RequestParam(required = false) DisputeStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        InternalIdentity identity = caller.require();
        PageRequest window = page(page, size);
        if (authorization.isStaff(identity)) {
            Page<DisputeEntity> found = status == null
                    ? disputes.findAllByOrderByCreatedAtDesc(window)
                    : disputes.findByStatusOrderByCreatedAtAsc(status, window);
            return PageResponse.from(found, this::viewOf);
        }
        // A customer outside the opener set has no cases by construction — but the opener check
        // below still applies per row, because "no cases" must come from the data rather than from
        // assuming who the caller is.
        authorization.requireOpen(identity);
        return PageResponse.from(
                disputes.findByOpenedBySubjectOrderByCreatedAtDesc(identity.subject(), window), this::viewOf);
    }

    /**
     * One case with its file.
     *
     * @param id the case's id
     * @return the case and its evidence, in order
     */
    @GetMapping("/{id}")
    public DisputeDetailView get(@PathVariable UUID id) {
        InternalIdentity identity = caller.require();
        DisputeEntity dispute = require(id);
        authorization.requireParty(identity, dispute);
        return detailOf(dispute, identity.subject());
    }

    /**
     * Appends a statement to an open case file.
     *
     * @param id the case's id
     * @param request the statement
     * @return the stored statement
     */
    @PostMapping("/{id}/evidence")
    public ResponseEntity<DisputeResponses.EvidenceView> plead(
            @PathVariable UUID id, @Valid @RequestBody AddEvidenceRequest request) {
        InternalIdentity identity = caller.require();
        DisputeEntity dispute = require(id);
        authorization.requireParty(identity, dispute);
        DisputeEvidenceEntity stored = cases.addEvidence(id, request.body(), identity);
        return ResponseEntity.ok(DisputeResponses.EvidenceView.of(
                stored, stored.getSubmittedBySubject().equals(identity.subject())));
    }

    /**
     * Decides a case.
     *
     * <p>A refund announces the outcome on {@code dispute-status-changed} and transaction-service
     * moves the money; a rejection ends the case with the reason given. Either way the decision is
     * announced in the same transaction that records it, so a decided case without its announcement
     * cannot exist.
     */
    @PostMapping("/{id}/resolve")
    public ResponseEntity<DisputeDetailView> resolve(
            @PathVariable UUID id, @Valid @RequestBody ResolveDisputeRequest request) {
        InternalIdentity identity = caller.require();
        authorization.requireDecide(identity);
        boolean refund = request.outcome() == ResolveDisputeRequest.Outcome.REFUND;
        DisputeEntity dispute = cases.resolve(id, refund, request.resolution(), identity);
        return ResponseEntity.ok(detailOf(dispute, identity.subject()));
    }

    private DisputeEntity require(UUID id) {
        return disputes.findById(id).orElseThrow(() -> DisputeErrors.NOT_FOUND.exception("No dispute with id " + id));
    }

    private DisputeView viewOf(DisputeEntity dispute) {
        return DisputeView.of(dispute, (int) file.countByDisputeId(dispute.getId()));
    }

    private DisputeDetailView detailOf(DisputeEntity dispute, String callerSubject) {
        return DisputeDetailView.of(dispute, cases.fileOf(dispute.getId()), callerSubject);
    }

    /**
     * A page window, with the size bounded.
     *
     * <p>No sort supplied, because the ordering is in the repository method names and a sort added
     * here would fight them for a derived query's single ordering clause. The bound is on the upper
     * side only: a caller asking for a million rows gets the cap rather than a 500, because the cap
     * is a policy statement and a 500 is a bug report.
     */
    private static PageRequest page(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE));
    }
}
