package com.fintech.platform.fraud.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.common.identity.CurrentCaller;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.common.pagination.PageResponse;
import com.fintech.platform.fraud.domain.FraudDecision;
import com.fintech.platform.fraud.domain.RiskBand;
import com.fintech.platform.fraud.error.FraudErrorCodes;
import com.fintech.platform.fraud.identity.SubjectDigester;
import com.fintech.platform.fraud.persistence.RiskDecisionEntity;
import com.fintech.platform.fraud.service.AlertService;
import com.fintech.platform.fraud.service.AnalyticsService;
import com.fintech.platform.fraud.service.DecisionService;
import com.fintech.platform.fraud.web.FraudRequests.CloseAlertRequest;
import com.fintech.platform.fraud.web.FraudRequests.ManualAdjustmentRequest;
import com.fintech.platform.fraud.web.FraudRequests.RescoreRequest;
import com.fintech.platform.fraud.web.FraudResponses.AlertDetailResponse;
import com.fintech.platform.fraud.web.FraudResponses.AlertEventResponse;
import com.fintech.platform.fraud.web.FraudResponses.AlertResponse;
import com.fintech.platform.fraud.web.FraudResponses.DecisionResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
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
 * The fraud analyst's API.
 *
 * <p><b>Every endpoint is staff-only and there is no customer-facing equivalent.</b> See
 * {@link FraudAuthorization} for why, and note in particular that this class has no branch which would
 * answer a customer: the absence of a rule is more robust than a rule that might be misconfigured.
 *
 * <p><b>Authorization is applied here rather than by annotation.</b> {@code @PreAuthorize} is used
 * elsewhere in this platform and is inert — method security is not enabled and the role names do not
 * match the realm's. Copying that pattern would produce a fraud API whose entire access control is a
 * comment. Every handler calls {@code requireRead} or {@code requireAction} before touching data, and
 * {@code FraudSecurityTest} asserts that each one does.
 *
 * <p><b>The identity is digested here, not read from a header.</b> A header carrying a subject digest
 * would let a caller present any digest they liked. The subject comes from the gateway-verified identity
 * and is digested under this service's key, so the only input is something the gateway signed.
 *
 * <p><b>Rescore is accepted, not performed.</b> It publishes a request and answers 202, because a re-score
 * reads the observation table and re-runs seven rules, and doing that inside a request that an analyst is
 * waiting on makes the API's latency depend on how many payments the engine has seen. The decision is
 * re-read after the event is processed. A synchronous version would be a smaller amount of code and would
 * report a result that is stale by the time it is sent.
 */
@RestController
@RequestMapping("/api/v1/fraud")
public class FraudController {

    private final DecisionService decisions;

    private final AlertService alertService;

    private final AnalyticsService analytics;

    private final FraudAuthorization authorization;

    private final CurrentCaller caller;

    private final SubjectDigester digester;

    private final ObjectMapper json;

    public FraudController(
            DecisionService decisions,
            AlertService alertService,
            AnalyticsService analytics,
            FraudAuthorization authorization,
            CurrentCaller caller,
            SubjectDigester digester,
            ObjectMapper json) {
        this.decisions = decisions;
        this.alertService = alertService;
        this.analytics = analytics;
        this.authorization = authorization;
        this.caller = caller;
        this.digester = digester;
        this.json = json;
    }

    // ------------------------------------------------------------------------ decisions

    /** One payment's decision, with the full explanation. */
    @GetMapping("/decisions/{transactionId}")
    public DecisionResponse decision(@PathVariable UUID transactionId) {
        InternalIdentity identity = caller.require();
        authorization.requireRead(identity);
        return DecisionResponse.of(requireDecision(transactionId), json);
    }

    /**
     * Decisions, filtered.
     *
     * <p>Every filter is optional and an absent one is not a filter at all, so a request with no
     * parameters returns the most recent page of everything. The alternative — treating an absent filter
     * as "matches null" — returns an empty list to a caller who asked for the list.
     */
    @GetMapping("/decisions")
    public PageResponse<DecisionResponse> decisions(
            @RequestParam(required = false) RiskBand band,
            @RequestParam(required = false) FraudDecision decision,
            @RequestParam(required = false) String ownerSubjectDigest,
            @RequestParam(required = false) String merchantReference,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        authorization.requireRead(caller.require());
        return PageResponse.from(
                searchDecisions(band, decision, ownerSubjectDigest, merchantReference, from, to, page, size),
                found -> DecisionResponse.of(found, json));
    }

    /**
     * Overrules the engine's score for a payment.
     *
     * <p>Refused above the configured cap, and the refusal names what is missing — a second approver —
     * rather than being a bare 422. A limit an operator cannot understand is a limit they will raise.
     */
    @PostMapping("/decisions/{transactionId}/adjust")
    public DecisionResponse adjust(@PathVariable UUID transactionId, @Valid @RequestBody ManualAdjustmentRequest body) {
        InternalIdentity identity = caller.require();
        authorization.requireAction(identity);
        return adjustInternal(transactionId, body, analystDigest(identity));
    }

    /**
     * The same override, without the request-scoped identity.
     *
     * <p>Separate so the transactional service method can be called from a test or a future non-HTTP
     * caller with an explicit actor, and so the {@code @Transactional} boundary sits in the service rather
     * than being implied by a controller method.
     */
    DecisionResponse adjustInternal(UUID transactionId, ManualAdjustmentRequest body, String analystDigest) {
        try {
            return DecisionResponse.of(
                    decisions.manualAdjust(transactionId, body.score(), analystDigest, body.reason()), json);
        } catch (DecisionService.ManualScoreAboveCapException e) {
            throw FraudErrorCodes.MANUAL_SCORE_ABOVE_CAP.exception(
                    "A manual score of " + e.requested() + " is above the cap of " + e.cap()
                            + ". Above the cap a decision needs a second approver, which this deployment does not have.");
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().contains("no decision")) {
                throw FraudErrorCodes.DECISION_NOT_FOUND.exception();
            }
            throw FraudErrorCodes.MANUAL_REASON_REQUIRED.exception(e.getMessage());
        }
    }

    /** Asks for a re-score. 202, because the work happens on the topic. */
    @PostMapping("/decisions/{transactionId}/rescore")
    public ResponseEntity<Void> rescore(@PathVariable UUID transactionId, @Valid @RequestBody RescoreRequest body) {
        InternalIdentity identity = caller.require();
        authorization.requireAction(identity);
        requireDecision(transactionId);
        decisions.requestRescore(transactionId, analystDigest(identity), body.reason());
        return ResponseEntity.accepted().build();
    }

    // ------------------------------------------------------------------------ alerts

    /** The unworked queue, highest risk first. */
    @GetMapping("/alerts")
    public PageResponse<AlertResponse> alerts(
            @RequestParam(required = false) RiskBand band,
            @RequestParam(required = false) String ownerSubjectDigest,
            @RequestParam(required = false) String claimedBy,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        authorization.requireRead(caller.require());
        return PageResponse.from(
                alertService.queue(band, ownerSubjectDigest, claimedBy, clampPage(page), clampSize(size)),
                AlertResponse::of);
    }

    /** One alert with its timeline. */
    @GetMapping("/alerts/{alertId}")
    public AlertDetailResponse alert(@PathVariable UUID alertId) {
        authorization.requireRead(caller.require());
        try {
            AlertService.AlertDetail detail = alertService.detail(alertId);
            return new AlertDetailResponse(
                    AlertResponse.of(detail.alert()),
                    detail.timeline().stream().map(AlertEventResponse::of).toList());
        } catch (AlertService.AlertNotFoundException e) {
            throw FraudErrorCodes.ALERT_NOT_FOUND.exception();
        }
    }

    /**
     * Takes an alert.
     *
     * <p>409 when somebody else has it, with the current owner in the details, because the loser of a race
     * between two analysts is better served by knowing who to ask than by a bare conflict.
     */
    @PostMapping("/alerts/{alertId}/claim")
    public AlertResponse claim(@PathVariable UUID alertId) {
        InternalIdentity identity = caller.require();
        authorization.requireAction(identity);
        String digest = analystDigest(identity);
        return alertService
                .claim(alertId, digest)
                .map(AlertResponse::of)
                .orElseThrow(() -> FraudErrorCodes.ALERT_NOT_CLAIMABLE.exception(
                        "That alert is no longer open. Another analyst may have claimed it."));
    }

    /**
     * Closes an alert the caller has claimed.
     *
     * @param dismiss true to dismiss as a false positive, false to resolve as a genuine finding. Two
     *     endpoints rather than a boolean field, because the false-positive rate is a number the fraud
     *     team is measured on and it should be impossible to record one by accident
     */
    @PostMapping("/alerts/{alertId}/resolve")
    public AlertResponse resolve(@PathVariable UUID alertId, @Valid @RequestBody CloseAlertRequest body) {
        return close(alertId, body, true);
    }

    /** Dismisses an alert as a false positive. */
    @PostMapping("/alerts/{alertId}/dismiss")
    public AlertResponse dismiss(@PathVariable UUID alertId, @Valid @RequestBody CloseAlertRequest body) {
        return close(alertId, body, false);
    }

    private AlertResponse close(UUID alertId, CloseAlertRequest body, boolean resolved) {
        InternalIdentity identity = caller.require();
        authorization.requireAction(identity);
        return alertService
                .close(alertId, analystDigest(identity), resolved, body.resolution(), body.note())
                .map(AlertResponse::of)
                .orElseThrow(() -> FraudErrorCodes.ALERT_NOT_CLAIMABLE.exception(
                        "That alert is not yours to close: claim it first, or it is already closed."));
    }

    // ------------------------------------------------------------------------ dashboard

    /**
     * The dashboard, over a window.
     *
     * <p>The window is a parameter and is echoed in the response, so a figure cannot be read out of the
     * period it covers. Clamped to a day at the bottom and ninety at the top: below a day the aggregates
     * are noise, and above ninety the query is a report rather than a dashboard panel.
     */
    @GetMapping("/summary")
    public AnalyticsService.DashboardSummary summary(@RequestParam(defaultValue = "PT24H") Duration window) {
        authorization.requireRead(caller.require());
        Duration requested = window == null || window.isNegative() ? DEFAULT_WINDOW : window;
        Duration clamped = requested.compareTo(MIN_WINDOW) < 0 ? MIN_WINDOW : requested;
        if (clamped.compareTo(MAX_WINDOW) > 0) {
            clamped = MAX_WINDOW;
        }
        return analytics.summary(clamped);
    }

    // ------------------------------------------------------------------------ helpers

    /**
     * The window a dashboard request covers when it does not say.
     *
     * <p>A day, because a day is a working shift and a fraud team reads this panel at the start and end
     * of one. Not an hour, which is too short to see a pattern, and not a week, which is a report.
     */
    private static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

    /** Below an hour the aggregates are noise — one payment can be 100% of them. */
    private static final Duration MIN_WINDOW = Duration.ofHours(1);

    /** Above ninety days this is a report, and it is a report tool's job, not an API's. */
    private static final Duration MAX_WINDOW = Duration.ofDays(90);

    private RiskDecisionEntity requireDecision(UUID transactionId) {
        return decisions.findDecision(transactionId).orElseThrow(() -> FraudErrorCodes.DECISION_NOT_FOUND.exception());
    }

    private Page<RiskDecisionEntity> searchDecisions(
            RiskBand band,
            FraudDecision decision,
            String ownerSubjectDigest,
            String merchantReference,
            Instant from,
            Instant to,
            int page,
            int size) {
        return decisions.search(
                band,
                decision,
                ownerSubjectDigest,
                merchantReference,
                from,
                to,
                PageRequest.of(clampPage(page), clampSize(size)));
    }

    /**
     * The caller's own digest, for a timeline entry and an audit event.
     *
     * <p>Digested under the analyst purpose rather than the owner purpose, so an analyst who also holds a
     * customer account has two different digests and cannot appear in their own queue as a customer.
     */
    private String analystDigest(InternalIdentity identity) {
        return digester.analystDigestOf(identity.subject());
    }

    private static int clampPage(int page) {
        return Math.max(page, 0);
    }

    private static int clampSize(int size) {
        return Math.clamp(size, 1, 200);
    }
}
