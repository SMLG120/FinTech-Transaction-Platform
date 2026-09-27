-- card-service: cards and their lifecycle.
--
-- The defining property of this schema is a column that does not exist. There is no pan, no cvv, no
-- track data, no pin and no cardholder name here, and the absence is the control rather than a
-- consequence of convention. Tokenisation happens in the service before the row is built, so the only
-- representation of the card number this database ever receives is the token in `token`.
--
-- Consequences of that, worth stating because they constrain everything else in this file:
--
--   * There is no encrypted PAN column, and no column for a future one. A schema that can hold a
--     number has a future migration that will eventually put one in it; a schema that cannot has no
--     such migration to write. This is why ADR-0006 insists the decision be made now rather than
--     retrofitted when the first real card integration appears.
--   * There is no cvv column either, which is stricter than PCI DSS requires. PCI permits a
--     tokenised vault to hold encrypted cardholder data and forbids the CVV after authorisation; this
--     platform holds neither, so there is no state in which a CVV would be allowed to exist.
--   * `last4` is present and is not a control. Four digits is not sensitive authentication data, is
--     what every receipt already shows, and is useless for authorising a payment.
--
-- Ownership is `owner_subject_digest`, a keyed one-way handle on the cardholder's Keycloak subject
-- rather than the subject itself. Two reasons: the platform does not keep a second plaintext copy of
-- an identity mapping whose design goal in customer-service was to avoid exactly that, and the digest
-- cannot be joined to customer-service's own subject digest because the two services derive theirs
-- under different keys. Joining them would quietly defeat ADR-0002's per-service isolation without
-- either database being read directly.

CREATE TABLE cards (
    id UUID PRIMARY KEY,

    -- The tokenised card number, and the only representation of the number this database holds.
    --
    -- 64 hex characters: HMAC-SHA256 under the card tokenisation key. Unique, and not decorative:
    -- tokenisation is deterministic, so the same number would mint the same token, and this
    -- constraint is what stops a second card being issued against a number already in use. That is
    -- the check that makes "issue a replacement" mean a new number, not a second row for the old one.
    token VARCHAR(64) NOT NULL UNIQUE,

    -- Keyed digest of the cardholder's subject. Indexed, because it is the only lookup a cardholder
    -- makes: listing their own cards, and resolving a card id they present.
    owner_subject_digest VARCHAR(64) NOT NULL,

    -- The customer this card belongs to. A reference, not a foreign key: ADR-0002 gives every service
    -- its own database and cross-database constraints are not possible. Indexed because the
    -- per-customer card limit is checked by counting this customer's live cards.
    customer_id UUID NOT NULL,

    -- Display only. Never used in an authorisation decision and never joined on.
    last4 VARCHAR(4) NOT NULL,

    -- See CardBrand. Simulated schemes, not real networks.
    brand VARCHAR(16) NOT NULL,

    -- The last day the card is valid, rather than a month and year, so the "valid through the end of
    -- the month" rule lives in one place instead of in every comparison.
    expires_on DATE NOT NULL,

    -- See CardStateMachine. The values allowed here are the enum's, and the check exists so that a
    -- bad write is rejected by the database as well as by the aggregate.
    status VARCHAR(16) NOT NULL,

    -- Lifecycle timestamps, all NULL until the corresponding transition happens. Retained after
    -- cancellation because "when was this card closed" is a question a dispute or a regulator may ask
    -- months later, and it is the answer that distinguishes a closure from a card that vanished.
    frozen_at TIMESTAMPTZ,
    lost_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    -- Optimistic locking. Two concurrent unfreezes of one card must not both succeed, and without this
    -- the second would silently overwrite the first.
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT cards_last4_is_four_digits CHECK (last4 ~ '^[0-9]{4}$'),
    CONSTRAINT cards_status_is_known CHECK (status IN ('ACTIVE', 'FROZEN', 'LOST', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT cards_brand_is_known CHECK (brand IN ('DEBIT', 'CREDIT')),

    -- A card is never issued already expired.
    CONSTRAINT cards_expiry_after_issue CHECK (expires_on >= created_at::date),

    -- The lifecycle timestamps must be consistent with the status, in one direction only.
    --
    -- Deliberately asymmetric: they say "if this timestamp is set, the status must be at least this
    -- far along", never the reverse. The reverse is wrong, because a card that was frozen and then
    -- reported lost legitimately carries frozen_at with a status of LOST, and a check that demanded
    -- frozen_at imply FROZEN would reject that perfectly valid row. Monotonicity is what makes the
    -- one-way form sound: statuses never return to ACTIVE, so once a timestamp is set the status can
    -- only move further from ACTIVE, and the check can never become false.
    --
    -- What this does catch is the contradiction an audit query would eventually find: a row claiming
    -- a card was reported lost while its status still reads ACTIVE.
    CONSTRAINT cards_frozen_at_requires_at_least_frozen
        CHECK (frozen_at IS NULL OR status IN ('FROZEN', 'LOST', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT cards_lost_at_requires_at_least_lost
        CHECK (lost_at IS NULL OR status IN ('LOST', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT cards_cancelled_at_requires_cancelled
        CHECK (cancelled_at IS NULL OR status = 'CANCELLED')
);

CREATE INDEX cards_owner_subject_digest_idx ON cards (owner_subject_digest);
CREATE INDEX cards_customer_id_idx ON cards (customer_id);

-- Partial index on the lapsed-but-not-yet-retired cards. The batch job that moves them to EXPIRED scans
-- for exactly this set: cards still live by status but past their date. The predicate is on status
-- rather than on expires_on because a date comparison cannot be indexed usefully, so the index narrows
-- to the small set of candidates first and filters on the date within it. Indexing all cards, or
-- indexing on expires_on alone, would make the job re-read every cancelled card in the table each run.
CREATE INDEX cards_lapsed_idx ON cards (expires_on, id) WHERE status IN ('ACTIVE', 'FROZEN');
