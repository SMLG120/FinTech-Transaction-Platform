package com.fintech.platform.audit.web;

import com.fintech.platform.audit.error.AuditErrors;
import com.fintech.platform.audit.persistence.AuditRecordEntity;
import com.fintech.platform.audit.persistence.AuditRecordRepository;
import com.fintech.platform.audit.web.AuditResponses.AuditRecordView;
import com.fintech.platform.common.identity.CurrentCaller;
import com.fintech.platform.common.pagination.PageResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The trail an auditor reads.
 *
 * <p>Reads only, and the missing writes are the design rather than an omission. An audit API with a
 * correction endpoint is a service that edits the evidence it exists to keep; the only honest
 * correction is a new record stating that the earlier one was wrong, and that record arrives the
 * same way every other fact does — as an event from the service that knows.
 *
 * <p><b>Every handler resolves the caller and asks the authorization bean first.</b> Not as an
 * annotation, because the annotations elsewhere in this platform are inert; see {@link
 * AuditAuthorization}. The gateway already established the identity, so this is a second opinion at
 * a boundary the gateway cannot enforce, not a second source of truth.
 */
@RestController
@RequestMapping("/api/audit/records")
public class AuditController {

    private static final int MAX_PAGE_SIZE = 200;

    private final AuditRecordRepository records;

    private final AuditAuthorization authorization;

    private final CurrentCaller caller;

    public AuditController(AuditRecordRepository records, AuditAuthorization authorization, CurrentCaller caller) {
        this.records = records;
        this.authorization = authorization;
        this.caller = caller;
    }

    /**
     * The trail, newest first, optionally narrowed by action and by when the fact happened.
     *
     * @param action the action to filter by, or null for all
     * @param from only facts at or after this instant, or null
     * @param to only facts at or before this instant, or null
     * @param page zero-based page index
     * @param size page size, capped
     * @return the page of records
     */
    @GetMapping
    public PageResponse<AuditRecordView> list(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        authorization.requireRead(caller.require());
        // Non-null bounds, always. The repository query binds these as timestamp parameters, and
        // Postgres cannot infer the type of a null bind — `:from IS NULL` answers with "could not
        // determine data type of parameter $3" rather than with rows. The epoch-to-now window is
        // the same question as "no bound", asked in a way the database can type.
        Page<AuditRecordEntity> found = records.search(
                blankToNull(action),
                from == null ? Instant.EPOCH : from,
                to == null ? Instant.now() : to,
                page(page, size));
        return PageResponse.from(found, AuditRecordView::of);
    }

    /**
     * One record.
     *
     * @param id the record's id
     * @return the record
     */
    @GetMapping("/{id}")
    public AuditRecordView get(@PathVariable UUID id) {
        authorization.requireRead(caller.require());
        return AuditRecordView.of(require(id));
    }

    /**
     * One resource's history, in the order it happened.
     *
     * <p>A single list rather than a filtered page, because one alert's or one cycle's history is a
     * handful of rows, and paginating it would make the auditor ask for page two of a story.
     *
     * @param resourceType the resource type, as recorded
     * @param resourceId the resource id, as recorded
     * @return the resource's history
     */
    @GetMapping("/by-resource/{resourceType}/{resourceId}")
    public PageResponse<AuditRecordView> byResource(
            @PathVariable String resourceType, @PathVariable String resourceId) {
        authorization.requireRead(caller.require());
        List<AuditRecordView> views = records.historyOf(resourceType, resourceId).stream()
                .map(AuditRecordView::of)
                .toList();
        return PageResponse.single(views);
    }

    /**
     * One payment's trail, in the order it happened.
     *
     * @param transactionId the payment
     * @return the payment's trail
     */
    @GetMapping("/by-transaction/{transactionId}")
    public PageResponse<AuditRecordView> byTransaction(@PathVariable UUID transactionId) {
        authorization.requireRead(caller.require());
        List<AuditRecordView> views = records.findByTransactionIdOrderByOccurredAtAsc(transactionId).stream()
                .map(AuditRecordView::of)
                .toList();
        return PageResponse.single(views);
    }

    /**
     * One request traced across every service it touched.
     *
     * @param correlationId the correlation id
     * @return the request's trail
     */
    @GetMapping("/by-correlation/{correlationId}")
    public PageResponse<AuditRecordView> byCorrelation(@PathVariable String correlationId) {
        authorization.requireRead(caller.require());
        List<AuditRecordView> views = records.findByCorrelationIdOrderByOccurredAtAsc(correlationId).stream()
                .map(AuditRecordView::of)
                .toList();
        return PageResponse.single(views);
    }

    private AuditRecordEntity require(UUID id) {
        return records.findById(id).orElseThrow(() -> AuditErrors.NOT_FOUND.exception("No audit record with id " + id));
    }

    /**
     * A page window, with the size bounded.
     *
     * <p>No sort supplied, because the ordering is in the repository query and a sort added here
     * would fight it. The bound is on the upper side only: a caller asking for a million rows gets
     * the cap rather than a 500, because the cap is a policy statement and a 500 is a bug report.
     */
    private static PageRequest page(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
