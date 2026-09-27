-- audit-service: the regulatory trail, event deduplication, and nothing else.
--
-- One row per auditable fact, and the table is a trail rather than a store: a row is written once,
-- with the event's own timestamp and the time it was received, and it is never updated or deleted.
-- The trigger below refuses both, so append-only is a property of the database rather than a promise
-- in a service class. A compromised or buggy service process can insert a false record — that is what
-- the separate-database-per-service boundary in ADR-0002 is for — but it cannot rewrite what the
-- trail already says, which is the property an auditor actually relies on.
--
-- Privacy, stated as columns rather than as policy. The actor is a digest — the analyst or operator
-- pseudonym the producing service computed — never a name, email or subject. There is no card token,
-- device identifier, network reference, PAN or PII column anywhere in this schema, for the same
-- reason there is no PAN column in card-service's schema: a trail that cannot hold an identifier
-- cannot leak one. The transaction id stays, because "what happened to this payment" is the question
-- the trail exists to answer, and a payment id without its owner is a fact without a person.
--
-- Actions are an open vocabulary on purpose. Each producing service names its own actions
-- (fraud-alert-claimed, and whatever settlement or transaction auditing follows), and a CHECK
-- constraint enumerating them here would make every new producer a migration on somebody else's
-- table. The trail records what happened; it does not grade the vocabulary.

CREATE TABLE audit_records (
    id UUID PRIMARY KEY,

    -- The event this record is about. Unique, so a redelivered event collides here instead of
    -- writing the same fact twice. A trail that counts one claim as two is a trail that cannot be
    -- reconciled against the service that produced it.
    event_id UUID NOT NULL UNIQUE,
    topic VARCHAR(128) NOT NULL,

    -- What happened, in the producer's own words. Past tense by platform convention: an audit
    -- record states that something happened, it does not ask anyone to do it.
    action VARCHAR(128) NOT NULL,

    -- What it happened to. The type keeps fraud-alert actions apart from whatever comes next, and
    -- the id is text rather than UUID because the next producer's resource may not be one.
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,

    -- The payment, when the fact is about one. NULL for facts that are not, so a query for one
    -- payment's trail is one indexed lookup rather than a scan with a filter.
    transaction_id UUID,

    -- Whose hands, as a digest. NULL for system facts with no human actor. Never a raw subject:
    -- the trail is auditor-readable, and an auditor-readable subject is a customer list.
    actor_digest VARCHAR(128),

    -- The outcome the producer reported. Short and producer-defined (SUCCESS, and whatever failure
    -- vocabularies follow); the trail does not interpret it, because interpreting it is how a
    -- recording service starts disagreeing with the service it records.
    result VARCHAR(32) NOT NULL,

    -- Ties the record back to the HTTP request that caused it, so an auditor can follow one call
    -- across every service it touched.
    correlation_id VARCHAR(64),

    -- The producer's own detail, as JSON text. Stored rather than shredded into columns for the
    -- same reason actions are open vocabulary: the next producer's detail has fields this schema
    -- has never heard of, and a trail that drops what it cannot name is editing the evidence.
    metadata TEXT,

    -- When the fact happened, by the producer's clock, and when this service recorded it, by its
    -- own. The gap between the two is the consumption lag, and keeping both is what makes that gap
    -- measurable instead of arguable.
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL
);

-- The trail's reads: newest first, one resource's history, one payment's trail, one actor's
-- actions, and one request traced across services.
CREATE INDEX audit_records_occurred_idx ON audit_records (occurred_at DESC);
CREATE INDEX audit_records_action_occurred_idx ON audit_records (action, occurred_at DESC);
CREATE INDEX audit_records_resource_idx ON audit_records (resource_type, resource_id, occurred_at);
CREATE INDEX audit_records_transaction_idx ON audit_records (transaction_id, occurred_at)
    WHERE transaction_id IS NOT NULL;
CREATE INDEX audit_records_actor_idx ON audit_records (actor_digest, occurred_at DESC)
    WHERE actor_digest IS NOT NULL;
CREATE INDEX audit_records_correlation_idx ON audit_records (correlation_id)
    WHERE correlation_id IS NOT NULL;

-- At-least-once delivery defence: the claim table behind INSERT ... ON CONFLICT DO NOTHING.
-- Same shape and same reasoning as the other services'.
CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    topic VARCHAR(128) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX processed_events_processed_at_idx ON processed_events (processed_at);

-- Append-only, enforced where it cannot be refactored away. A service method that refuses to
-- update is a convention; a trigger that raises is a constraint, and the difference is what shows
-- up the first time somebody connects to this database with a SQL client and a deadline. TRUNCATE
-- is not covered — it requires ownership-level privilege this service's role does not hold in any
-- environment that matters — and the role grants are what make that true rather than this comment.
CREATE OR REPLACE FUNCTION reject_audit_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_records is append-only: % is refused', TG_OP
        USING ERRCODE = '25001';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_records_no_update_or_delete
    BEFORE UPDATE OR DELETE ON audit_records
    FOR EACH ROW
    EXECUTE FUNCTION reject_audit_mutation();
