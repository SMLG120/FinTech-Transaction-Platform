-- Settlement cycles, statement lines, and reconciliation breaks.
--
-- The unit of immutability is the cycle, not the transaction. A transaction is already terminal at
-- REVERSED in transaction-service; what a statement needs is for the period it was issued in to stay
-- exactly as it was issued, so that a refund of money already paid out is recorded as a movement in a
-- later period rather than as a change to a period somebody has already been sent. See ADR-0009.
--
-- Every money column is BIGINT minor units for the same reason transaction-service uses them:
-- 5000 is five thousand pounds and five hundred thousand cents, and a settlement statement that
-- confuses the two is not a rounding error. The currency is on the cycle, so a line cannot be read
-- without its cycle and cannot belong to a cycle in another currency.

CREATE TABLE settlement_cycles (
    id UUID PRIMARY KEY,

    -- Human-facing and unique, because this is what appears on a statement, in a break report and in a
    -- support conversation, and an operator looking up a cycle by a UUID is an operator who cannot find
    -- the cycle. Derived from the business date rather than generated, so the same day always names the
    -- same cycle and a retry cannot create a second one by accident.
    reference VARCHAR(64) NOT NULL UNIQUE,

    -- The business date this cycle settles, as a date and not a timestamp. A cycle covers one day in one
    -- currency, and a cycle covering "a day" has to be nameable without a timezone, because the business
    -- date an acquirer uses is not the UTC date.
    business_date DATE NOT NULL,
    currency_code VARCHAR(3) NOT NULL,

    -- OPEN -> CLOSED -> RECONCILED, or CLOSED -> BROKEN. BROKEN is terminal for the cycle: a break is
    -- resolved by accounting for the money, not by reopening a period and editing its totals.
    status VARCHAR(16) NOT NULL,
    CONSTRAINT settlement_cycles_status_ck
        CHECK (status IN ('OPEN', 'CLOSED', 'RECONCILED', 'BROKEN')),

    -- Sum of the cycle's net lines, in minor units, frozen when the cycle closes. A denormalised total on
    -- the row is the point of the table: a statement has to have a single number that was true at a
    -- moment, not a query that returns whatever the lines say today.
    expected_minor BIGINT NOT NULL DEFAULT 0,

    -- The clearing figure declared for this period, posted by an operator because a bank feed does not
    -- exist here. NULL until declared, which is what makes "not yet reconciled" distinguishable from
    -- "reconciled to zero": a cycle that reconciled perfectly legitimately has actual = expected = 0.
    actual_minor BIGINT,
    difference_minor BIGINT,

    opened_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    settled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- One cycle per business date per currency. The unique index is the real guard against a second cycle for
-- a day: a check in Java can be raced by two concurrent closes, and the loser must fail on the database
-- rather than quietly produce two statements for the same money.
CREATE UNIQUE INDEX settlement_cycles_date_currency_uq
    ON settlement_cycles (business_date, currency_code);

CREATE INDEX settlement_cycles_status_idx ON settlement_cycles (status, business_date DESC);

CREATE TABLE settlement_lines (
    id UUID PRIMARY KEY,
    cycle_id UUID NOT NULL REFERENCES settlement_cycles (id),

    -- The payment this line is about. CAPTURE lines always have one. A REVERSAL line has one too, and it
    -- points at the transaction that is being refunded -- which may be in a different, already closed
    -- cycle. That mismatch between cycle_id and transaction_id is the whole cross-cycle case, so neither
    -- column is unique on its own and neither is redundant.
    transaction_id UUID NOT NULL,

    -- CAPTURE or REVERSAL. Only two kinds, because only two things happen to money between a payment
    -- settling and the next cycle: it is captured, or it comes back.
    kind VARCHAR(16) NOT NULL,
    CONSTRAINT settlement_lines_kind_ck CHECK (kind IN ('CAPTURE', 'REVERSAL')),

    -- What the payment was for, signed. Positive for a capture, negative for a reversal, so a statement
    -- sums this column and gets the right answer without special-casing the kind -- which is the property
    -- that makes a refund safe to add to a later period.
    --
    -- There is deliberately no fee column. No fee exists anywhere in this platform: the ledger has no fee
    -- posting and `transaction-settled` carries no fee, so a fee column here would be a column that is
    -- always zero and a second place for a fee to be forgotten. A statement line is an amount and a
    -- direction, and merchant fees belong to a phase that can actually produce one.
    amount_minor BIGINT NOT NULL,

    -- The business date this line belongs to, denormalised from the cycle. Reconciliation and reporting
    -- both ask "what moved on the 14th" often enough, and answering it by joining through a cycle keyed
    -- on a reference is the kind of join that gets a timezone wrong in one caller and not the others.
    business_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

-- A transaction contributes at most one line per cycle per kind. This is what makes consuming a
-- redelivered event idempotent at the statement level: the retry collides here instead of adding a second
-- capture line. It is deliberately not unique on transaction_id alone, because a capture in one cycle and
-- its later reversal in another is the case the schema exists to support.
CREATE UNIQUE INDEX settlement_lines_cycle_transaction_uq
    ON settlement_lines (cycle_id, transaction_id, kind);

CREATE INDEX settlement_lines_transaction_idx ON settlement_lines (transaction_id);

CREATE TABLE settlement_breaks (
    id UUID PRIMARY KEY,
    cycle_id UUID NOT NULL REFERENCES settlement_cycles (id),
    transaction_id UUID,

    -- WHY the difference exists, classified rather than left as prose. AMOUNT_MISMATCH is the declared
    -- actual disagreeing with the computed expected total. ORPHAN_REVERSAL is a refund for a payment this
    -- service never saw settle. PERIOD_ALREADY_CLOSED is a movement that arrived for a period that had
    -- already been closed and given out -- a capture that settled as the period was closing, or a refund
    -- whose own period was already final. One kind for both, because the condition and the remedy are the
    -- same; the detail column says which movement it was. Recorded rather than dropped because it is money
    -- that moved without a statement line to say so.
    kind VARCHAR(32) NOT NULL,
    CONSTRAINT settlement_breaks_kind_ck
        CHECK (kind IN ('AMOUNT_MISMATCH', 'ORPHAN_REVERSAL', 'PERIOD_ALREADY_CLOSED')),

    expected_minor BIGINT,
    actual_minor BIGINT,
    difference_minor BIGINT NOT NULL,
    detail VARCHAR(500) NOT NULL,

    -- OPEN until somebody has looked at it, then ACKNOWLEDGED, then RESOLVED once the money is accounted
    -- for. Acknowledgement and resolution are separate states on purpose: "somebody has seen this" and
    -- "this is explained" are different facts, and collapsing them loses the second one.
    status VARCHAR(16) NOT NULL,
    CONSTRAINT settlement_breaks_status_ck CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),

    acknowledged_by VARCHAR(64),
    acknowledged_at TIMESTAMPTZ,
    resolution VARCHAR(500),
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- One break per kind per cycle, so re-running a reconciliation cannot multiply the finding. A
-- reconciliation that reports the same missing money five times has not reported it five times.
CREATE UNIQUE INDEX settlement_breaks_cycle_kind_uq ON settlement_breaks (cycle_id, kind);

CREATE INDEX settlement_breaks_status_idx ON settlement_breaks (status, created_at DESC);

-- Event ids already applied, so a redelivered transaction-settled event does not add a second line. The
-- same ON CONFLICT claim the fraud consumer uses, for the same reason: dedup belongs in the database,
-- because two consumers of the same partition must not be able to both win the race in memory.
CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    topic VARCHAR(128) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);

-- Identical to the other services' tables, on purpose. A third copy of this shape is a debt recorded in
-- ADR-0009; the alternative was a shared class, and the two existing copies have since diverged enough
-- that lifting them is its own change rather than a side effect of Phase 7.
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

-- The relay's query. Partial because published rows are the overwhelming majority once the service has
-- been running, and an index over them is pure write cost.
CREATE INDEX outbox_events_pending_idx ON outbox_events (created_at, id) WHERE published_at IS NULL;
