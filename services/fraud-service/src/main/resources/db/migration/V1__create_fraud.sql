-- fraud-service: risk decisions, the engine's observations, the analyst queue, event deduplication and
-- this service's outbox.
--
-- Four decisions in this schema are worth stating up front, because each of them is a place where the
-- database is the only component that can enforce the rule:
--
--   1. risk_decisions is keyed by transaction_id. One payment has one decision, and an analyst
--      re-score UPDATEs that row rather than inserting a second one. Two rows for one payment would
--      make "what did we decide about this" ambiguous, and the ambiguity would be resolved by whoever
--      read the table last.
--
--   2. fraud_observations has a four-column primary key — scope, kind, subject, observation. The scope is
--      in the key rather than in a column because the scopes answer different questions: a device seen
--      with a card answers "is this device new for this card", a device seen by a customer answers "does
--      this customer know this device". A nullable scope column would let a card-scoped row be read as a
--      customer-scoped one, and then every device would look new.
--
--   3. Every subject_digest and every observation_key that is not merchant text is a 64-character HMAC
--      digest, and the column lengths are what make an accidental raw value impossible to store. There is
--      no column anywhere in this schema that can hold a card number, a device identifier or an IP
--      address. The fraud engine is the table a data-export request will be aimed at.
--
--   4. processed_events is the at-least-once defence, and it is written in the same transaction as the
--      decision. The insert is ON CONFLICT DO NOTHING in application code, so the constraint here is what
--      makes that statement possible at all.

CREATE TABLE risk_decisions (
    -- The payment this is about. See note 1.
    transaction_id UUID PRIMARY KEY,

    -- Keyed digest of the payer's subject, produced by transaction-service. A digest and not a subject
    -- because this table is read by analysts and exported to reporting.
    owner_subject_digest VARCHAR(64) NOT NULL,

    amount_minor BIGINT NOT NULL,
    currency_code VARCHAR(3) NOT NULL,

    -- Payee display name is customer-supplied text and is therefore not trusted as an identity; the
    -- merchant_reference below is the one a merchant assigned, where there is one. See
    -- NewMerchantLargeAmountRule for why the difference is recorded rather than smoothed over.
    payee_name VARCHAR(256),
    merchant_reference VARCHAR(128),
    channel VARCHAR(16),

    -- HMAC digests, all 64 hex characters, all computed by the producing service under a purpose-prefixed
    -- key. There is deliberately no column for the values they stand for. See note 3.
    card_reference VARCHAR(64),
    device_reference VARCHAR(64),
    network_reference VARCHAR(64),

    -- The score, the band it falls in, and what was decided. The band is stored rather than derived on
    -- read because the band boundaries are part of this service's published contract, and a historical
    -- decision read after those boundaries moved should say what it said at the time.
    -- INTEGER, not SMALLINT. The score is bounded at 100, so smallint would fit, and smallint is the
    -- tidier-looking choice for a number in 0..100. But the Java side is an `int` and Hibernate maps that
    -- to `int4`, so smallint here fails `ddl-auto: validate` at startup with a schema error pointing at a
    -- column whose type looks perfectly correct. Same reasoning as transaction-service's
    -- V1__create_transactions.sql, and the CHECK constraint below still bounds the value: the range is
    -- enforced by the database either way, the width is not what enforces it.
    score INTEGER NOT NULL,
    CONSTRAINT risk_decisions_score_range CHECK (score >= 0 AND score <= 100),

    band VARCHAR(16) NOT NULL,
    CONSTRAINT risk_decisions_band_known CHECK (band IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),

    decision VARCHAR(16) NOT NULL,
    CONSTRAINT risk_decisions_decision_known CHECK (decision IN ('APPROVE', 'REVIEW', 'DECLINE')),

    -- Whether a human is expected to look. Stored rather than recomputed from the score because a single
    -- strong rule can require an alert on its own; see DecisionPolicy.
    alert_required BOOLEAN NOT NULL DEFAULT FALSE,

    -- Text, not JSONB, and the reason is worth repeating: nothing queries inside these documents. A
    -- decision is read whole or not at all, and every query that filters this table filters on the
    -- indexed scalar columns above, which were denormalised out of the documents precisely so they could
    -- be indexed.
    reasons TEXT NOT NULL,
    facts TEXT NOT NULL,

    occurred_at TIMESTAMPTZ NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    -- 1 unless an analyst re-scored. Kept so a re-score is visible on the row. INTEGER for the same
    -- reason as score: the entity field is an `int`, and a re-score count is the one number on this row
    -- with no upper bound worth reasoning about.
    attempt INTEGER NOT NULL DEFAULT 1,

    -- An analyst's override. The score they REPLACED is kept in manual_score, because an override that
    -- leaves no trace of what it replaced is indistinguishable from the engine having produced the new
    -- number — and "how often is the model wrong" is the question the next person will ask.
    -- INTEGER, like score above.
    manual_score INTEGER,
    manual_adjusted_by VARCHAR(64),
    manual_adjusted_at TIMESTAMPTZ,
    manual_reason TEXT,

    CONSTRAINT risk_decisions_manual_score_range CHECK (manual_score IS NULL OR (manual_score >= 0 AND manual_score <= 100))
);

-- The dashboard's filters. Ordered so that band-only queries (the alert queue) hit band first, then
-- owner, then time.
CREATE INDEX risk_decisions_band_occurred_idx ON risk_decisions (band, occurred_at DESC);
CREATE INDEX risk_decisions_owner_idx ON risk_decisions (owner_subject_digest, occurred_at DESC);
CREATE INDEX risk_decisions_merchant_idx ON risk_decisions (merchant_reference, occurred_at DESC)
    WHERE merchant_reference IS NOT NULL;
CREATE INDEX risk_decisions_decision_occurred_idx ON risk_decisions (decision, occurred_at DESC);
CREATE INDEX risk_decisions_occurred_idx ON risk_decisions (occurred_at DESC);

CREATE TABLE fraud_observations (
    -- See notes 2 and 3. The composite key is the whole design: what was observed, of which kind, about
    -- whom, by whom.
    scope VARCHAR(32) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    subject_digest VARCHAR(64) NOT NULL,
    observation_key VARCHAR(256) NOT NULL,

    -- first_seen_at is never rewritten. The recent-activation and rapid-network-change rules are stated
    -- in terms of it, and a fact that moves every time it is read is not a fact.
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    hit_count INTEGER NOT NULL DEFAULT 1,

    CONSTRAINT fraud_observations_pk PRIMARY KEY (scope, kind, subject_digest, observation_key),
    CONSTRAINT fraud_observations_scope_known CHECK (scope IN (
        'CUSTOMER_DEVICE', 'CARD_DEVICE', 'CUSTOMER_MERCHANT', 'CUSTOMER_NETWORK', 'CARD')),
    CONSTRAINT fraud_observations_kind_known CHECK (kind IN ('DEVICE', 'MERCHANT', 'NETWORK', 'CARD'))
);

-- The shared-device rule's query: distinct subjects for one device.
CREATE INDEX fraud_observations_device_idx ON fraud_observations (scope, kind, observation_key);
-- The pruning job's query, per scope so a scope's retention can differ.
CREATE INDEX fraud_observations_last_seen_idx ON fraud_observations (scope, last_seen_at);
-- The rapid-network-change query: one customer's networks, newest first.
CREATE INDEX fraud_observations_subject_idx ON fraud_observations (scope, kind, subject_digest, first_seen_at DESC);

CREATE TABLE fraud_alerts (
    id UUID PRIMARY KEY,

    -- One alert per decision, and the decision is keyed by the payment: an alert IS the decision plus a
    -- lifecycle. A separate identity here would allow two open alerts for one payment, and two analysts
    -- each resolving "the same" suspicious payment is how one of them resolves it without reading it.
    transaction_id UUID NOT NULL,
    owner_subject_digest VARCHAR(64) NOT NULL,

    -- Copied from the decision rather than joined, so the queue renders without a join and so a queue
    -- entry is a snapshot of what was known when the alert was raised.
    -- INTEGER, as on risk_decisions above, because the entity field is an `int`.
    score INTEGER NOT NULL,
    CONSTRAINT fraud_alerts_score_range CHECK (score >= 0 AND score <= 100),
    band VARCHAR(16) NOT NULL,
    CONSTRAINT fraud_alerts_band_known CHECK (band IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    decision VARCHAR(16) NOT NULL,
    CONSTRAINT fraud_alerts_decision_known CHECK (decision IN ('APPROVE', 'REVIEW', 'DECLINE')),

    -- The four states, and the database is what makes an illegal transition impossible to write. There is
    -- no path from RESOLVED or DISMISSED back to OPEN: a re-opened alert looks like a new finding to a
    -- queue that has already been worked through. A finding that needs a second look is a new decision.
    state VARCHAR(16) NOT NULL,
    CONSTRAINT fraud_alerts_state_known CHECK (state IN ('OPEN', 'CLAIMED', 'RESOLVED', 'DISMISSED')),

    summary VARCHAR(512) NOT NULL,
    reasons TEXT NOT NULL,

    amount_minor BIGINT NOT NULL,
    currency_code VARCHAR(3) NOT NULL,
    payee_name VARCHAR(256),
    merchant_reference VARCHAR(128),

    -- The analyst who has it, and the one who closed it. Digests, for the same reason as everywhere else
    -- in this schema.
    claimed_by VARCHAR(64),
    claimed_at TIMESTAMPTZ,
    closed_by VARCHAR(64),
    closed_at TIMESTAMPTZ,

    resolution VARCHAR(64),
    resolution_note TEXT,

    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fraud_alerts_claimed_consistent CHECK (
        (state = 'OPEN' AND claimed_by IS NULL)
        OR (state <> 'OPEN' AND claimed_by IS NOT NULL)
    ),
    CONSTRAINT fraud_alerts_closed_consistent CHECK (
        (state IN ('RESOLVED', 'DISMISSED') AND closed_at IS NOT NULL AND resolution IS NOT NULL)
        OR (state IN ('OPEN', 'CLAIMED') AND closed_at IS NULL)
    )
);

-- The queue's ORDER BY: score DESC, created_at. A partial index on the two unclosed states, so the queue
-- query stays small as closed alerts accumulate.
CREATE INDEX fraud_alerts_queue_idx ON fraud_alerts (score DESC, created_at)
    WHERE state IN ('OPEN', 'CLAIMED');
CREATE INDEX fraud_alerts_transaction_idx ON fraud_alerts (transaction_id);
CREATE INDEX fraud_alerts_owner_idx ON fraud_alerts (owner_subject_digest, created_at DESC);
CREATE INDEX fraud_alerts_claimed_by_idx ON fraud_alerts (claimed_by) WHERE claimed_by IS NOT NULL;

CREATE TABLE fraud_alert_events (
    -- Append-only. No updated_at, and no entity method that could set one, so "this timeline was
    -- edited" is not a state the type can represent.
    id UUID PRIMARY KEY,
    alert_id UUID NOT NULL,
    action VARCHAR(16) NOT NULL,
    CONSTRAINT fraud_alert_events_action_known CHECK (action IN ('RAISED', 'CLAIMED', 'RESOLVED', 'DISMISSED', 'RESCORED')),
    actor_digest VARCHAR(64),
    note TEXT,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX fraud_alert_events_alert_idx ON fraud_alert_events (alert_id, occurred_at DESC);

-- At-least-once delivery defence. See note 4.
CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    topic VARCHAR(128) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);

-- The pruning job's query. Without this the deduplication table grows without bound and every consumer
-- write contends on a growing index.
CREATE INDEX processed_events_processed_at_idx ON processed_events (processed_at);

-- This service's own outbox, identical in shape to transaction-service's. See OutboxEventEntity for why it
-- is a copy rather than a shared class.
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

-- The relay's work queue, and a PARTIAL index: at-least-once delivery means the steady state is a handful
-- of unpublished rows, so this must stay small as the table grows forever. A plain index on published_at
-- would still be scanned over every published row, which is the entire table.
CREATE INDEX outbox_events_pending_idx ON outbox_events (created_at, id) WHERE published_at IS NULL;
