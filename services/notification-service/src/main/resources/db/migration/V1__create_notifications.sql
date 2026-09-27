-- notification-service: the delivery log, event deduplication, and nothing else.
--
-- One row per fact worth telling somebody about, and the table is a log rather than a queue: a SENT row
-- is the record that a message went out, and it is never deleted or rewritten. A FAILED row carries the
-- attempts so far and when to try again, so a restart cannot lose a notification that was mid-retry.
--
-- Privacy, stated as columns rather than as policy. The recipient is an ownerSubjectDigest — the same
-- pseudonym fraud-service holds — never a name, email or subject. There is no card token, device
-- identifier, network reference or PAN column anywhere in this schema, for the same reason there is no
-- PAN column in card-service's schema: a notification that cannot hold a card number cannot leak one.
-- The payeeName is customer-supplied display text and is what appears in the message itself, so it is
-- stored; the card it was paid with is not, and the message templates never interpolate it.
--
-- Money is BIGINT minor units with a currency code, like every other money column on the platform.

CREATE TABLE notifications (
    id UUID PRIMARY KEY,

    -- The event this notification is about. Unique, so a redelivered event collides here instead of
    -- sending the same message twice. A customer told twice about one payment has been told wrong.
    event_id UUID NOT NULL UNIQUE,
    topic VARCHAR(128) NOT NULL,

    kind VARCHAR(32) NOT NULL,
    CONSTRAINT notifications_kind_known CHECK (kind IN (
        'PAYMENT_AUTHORIZED', 'PAYMENT_DECLINED', 'PAYMENT_SETTLED', 'PAYMENT_REFUNDED',
        'FRAUD_REVIEW', 'FRAUD_DECLINED',
        'SETTLEMENT_CLOSED', 'SETTLEMENT_RECONCILED', 'SETTLEMENT_BREAK')),

    channel VARCHAR(8) NOT NULL,
    CONSTRAINT notifications_channel_known CHECK (channel IN ('EMAIL', 'SMS', 'PUSH')),

    status VARCHAR(8) NOT NULL,
    CONSTRAINT notifications_status_known CHECK (status IN ('PENDING', 'SENT', 'FAILED')),

    -- Whose payment this is about, as a digest. NULL for settlement kinds, which are about a cycle
    -- rather than a customer. Never a raw subject: the delivery log is staff-readable, and a
    -- staff-readable subject is a customer list.
    recipient_digest VARCHAR(64),

    -- The payment, for payment kinds. NULL for settlement kinds, which name a cycle instead.
    transaction_id UUID,

    -- The cycle, for settlement kinds. NULL for payment kinds.
    cycle_reference VARCHAR(64),

    amount_minor BIGINT,
    currency_code VARCHAR(3),

    -- Display text from the payment, rendered into the message. Customer-supplied and untrusted, so it
    -- is rendered as text and never as an identity.
    payee_name VARCHAR(256),

    subject VARCHAR(256) NOT NULL,
    body TEXT NOT NULL,

    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,

    -- When a FAILED notification becomes due again. NULL once SENT: a sent message has no next attempt.
    next_attempt_at TIMESTAMPTZ,

    sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT notifications_payment_has_transaction CHECK (
        (kind IN ('PAYMENT_AUTHORIZED', 'PAYMENT_DECLINED', 'PAYMENT_SETTLED', 'PAYMENT_REFUNDED',
                  'FRAUD_REVIEW', 'FRAUD_DECLINED') AND transaction_id IS NOT NULL)
        OR (kind IN ('SETTLEMENT_CLOSED', 'SETTLEMENT_RECONCILED', 'SETTLEMENT_BREAK'))),
    CONSTRAINT notifications_settlement_has_cycle CHECK (
        (kind IN ('SETTLEMENT_CLOSED', 'SETTLEMENT_RECONCILED', 'SETTLEMENT_BREAK')
            AND cycle_reference IS NOT NULL)
        OR (kind IN ('PAYMENT_AUTHORIZED', 'PAYMENT_DECLINED', 'PAYMENT_SETTLED', 'PAYMENT_REFUNDED',
                     'FRAUD_REVIEW', 'FRAUD_DECLINED'))),
    CONSTRAINT notifications_sent_consistent CHECK (
        (status = 'SENT' AND sent_at IS NOT NULL AND next_attempt_at IS NULL)
        OR (status <> 'SENT'))
);

-- The delivery log's reads: newest first, the failed queue's due query, and one payment's messages.
CREATE INDEX notifications_created_idx ON notifications (created_at DESC);
CREATE INDEX notifications_status_created_idx ON notifications (status, created_at DESC);
CREATE INDEX notifications_retry_due_idx ON notifications (next_attempt_at)
    WHERE status = 'FAILED' AND next_attempt_at IS NOT NULL;
CREATE INDEX notifications_transaction_idx ON notifications (transaction_id)
    WHERE transaction_id IS NOT NULL;

-- At-least-once delivery defence: the claim table behind INSERT ... ON CONFLICT DO NOTHING.
-- Same shape and same reasoning as the other services'.
CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    topic VARCHAR(128) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX processed_events_processed_at_idx ON processed_events (processed_at);
