-- dispute-service: disputes, their evidence, and the outbox that announces them.
--
-- A dispute is a customer saying "that payment should not have happened" and the platform deciding
-- the matter. The row holds the claim and its outcome; the evidence table holds what each side said
-- along the way. Both are written once and then only appended to: a dispute moves OPEN ->
-- RESOLVED_REFUNDED or OPEN -> RESOLVED_REJECTED and never back, and evidence is never edited or
-- deleted. A chargeback case whose history can be rewritten is not evidence, it is a draft.
--
-- Privacy, stated as columns rather than as policy. The opener is a subject — the Keycloak user id
-- the gateway verified — because "this customer's disputes" is a lookup this service must perform,
-- and a digest it cannot map back to a token cannot answer it. There is no card token, device
-- identifier, network reference or PAN column anywhere in this schema: a dispute names the payment
-- (transaction_id) and the payment's owner is established through transaction-service, not stored
-- here. The description and evidence texts are customer-supplied display text, length-capped so a
-- case file cannot become a blob store.

CREATE TABLE disputes (
    id UUID PRIMARY KEY,

    -- The payment under dispute. One open dispute per payment: a second concurrent case on the same
    -- money is a second queue working the same refund, and the partial unique index below refuses
    -- it. A later dispute after resolution is a new row, because the earlier case and its outcome
    -- are history worth keeping, not a lock worth holding.
    transaction_id UUID NOT NULL,

    -- Why the customer says the payment should not have happened. A closed vocabulary, because
    -- chargeback reasons are a finite set the support workflow triages on — an open text field here
    -- would push the triage onto whoever reads the description.
    reason VARCHAR(32) NOT NULL,
    CONSTRAINT disputes_reason_known CHECK (reason IN (
        'FRAUD', 'NOT_RECEIVED', 'DUPLICATE', 'DEFECTIVE', 'OTHER')),

    -- The customer's own words, required at opening. A dispute with no stated grievance is a refund
    -- button, and a refund button does not need a case.
    description VARCHAR(2000) NOT NULL,

    status VARCHAR(16) NOT NULL,
    CONSTRAINT disputes_status_known CHECK (status IN ('OPEN', 'RESOLVED_REFUNDED', 'RESOLVED_REJECTED')),

    -- Who opened the case, as the verified subject. Needed for "this customer's disputes", which a
    -- digest could not answer. Staff-opened cases record the agent, customer-opened ones the
    -- customer — either way the actor the audit event names.
    opened_by_subject VARCHAR(512) NOT NULL,

    -- Who decided the case and why. NULL while open: a resolution without a reason is an
    -- unaccountable refund or an unexplained refusal, and neither belongs in a case file.
    resolved_by_subject VARCHAR(512),
    resolution VARCHAR(2000),

    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,

    CONSTRAINT disputes_resolution_complete CHECK (
        (status = 'OPEN' AND resolved_by_subject IS NULL AND resolution IS NULL AND resolved_at IS NULL)
        OR (status <> 'OPEN' AND resolved_by_subject IS NOT NULL AND resolution IS NOT NULL
            AND resolved_at IS NOT NULL))
);

-- One open case per payment. Partial, because resolved cases are history and must accumulate: a
-- unique index over all rows would forbid a second dispute ever, including a legitimate repeat
-- disagreement a year later.
CREATE UNIQUE INDEX disputes_one_open_per_transaction ON disputes (transaction_id)
    WHERE status = 'OPEN';

-- The case queue's reads: open cases oldest first (the ones waiting longest are worked first), one
-- customer's cases newest first, one payment's history.
CREATE INDEX disputes_open_created_idx ON disputes (created_at)
    WHERE status = 'OPEN';
CREATE INDEX disputes_opener_created_idx ON disputes (opened_by_subject, created_at DESC);
CREATE INDEX disputes_transaction_idx ON disputes (transaction_id);

-- What each side said, in the order they said it. Append-only by design: rows are inserted and
-- never updated or deleted, so the case file is the conversation rather than its latest edit.
CREATE TABLE dispute_evidence (
    id UUID PRIMARY KEY,
    dispute_id UUID NOT NULL REFERENCES disputes (id),

    -- Who submitted it, as the verified subject. The customer, the agent, or the administrator who
    -- decided — the file says who spoke, not just what was said.
    submitted_by_subject VARCHAR(512) NOT NULL,

    -- The statement itself. Text, capped: case files hold arguments, not attachments. Files are a
    -- storage, malware-scanning and retention problem this phase does not take on.
    body VARCHAR(4000) NOT NULL,

    submitted_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX dispute_evidence_dispute_idx ON dispute_evidence (dispute_id, submitted_at);

-- Transactional outbox. The state change and its announcement are written in the same transaction,
-- and a relay publishes the stored bytes: dispute-created, dispute-status-changed, and the
-- audit-events records that keep the Phase 9 trail complete for this service's actions.
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    topic VARCHAR(128) NOT NULL,
    event_key VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX outbox_events_pending_idx ON outbox_events (created_at, id)
    WHERE published_at IS NULL;
