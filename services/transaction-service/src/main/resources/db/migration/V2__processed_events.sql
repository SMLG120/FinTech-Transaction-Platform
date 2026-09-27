-- Phase 10: the claim table behind exactly-once dispute resolution.
--
-- Dispute resolutions arrive as events and move money, so consuming one twice would refund twice.
-- The claim is an INSERT ... ON CONFLICT DO NOTHING in the same transaction that reverses the
-- payment, so a redelivered resolution collides here instead of reversing again. Same shape and
-- same reasoning as the other services' claim tables.

CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    topic VARCHAR(128) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX processed_events_processed_at_idx ON processed_events (processed_at);
