-- transaction-service: payments, the double-entry ledger, idempotency and the outbox.
--
-- Four things in this file are load-bearing, and each of them is a place where the database is the only
-- component that can enforce the rule:
--
--   1. Every money column is a BIGINT of minor units, never a numeric and never a float. There is no
--      column that can hold 10.005 GBP, because the service refuses to construct such an amount and the
--      type makes storing one impossible. See ADR-0007.
--
--   2. The unique constraint on ledger_accounts is what makes "one available account per customer per
--      currency" a fact rather than a convention. Two concurrent funding requests for a customer with
--      no account yet would both find nothing and both insert; this constraint turns the second one
--      into a failure instead of a duplicate balance.
--
--   3. The CHECK constraints on balances are the overdraft rule, and they are per account type rather
--      than global. A global `balance_minor >= 0` would forbid the platform from being a source of
--      funds at all; these allow the platform accounts to go negative, which is what a source of funds
--      looks like from inside, while holding every customer account at or above zero no matter what the
--      service does.
--
--   4. The unique constraint on idempotency_keys is the whole mechanism. Two concurrent requests with
--      one key both do their work and both try to record it; without this the second overwrites the
--      first and the caller has been charged twice while the table says one of them won.

CREATE TABLE transactions (
    id UUID PRIMARY KEY,

    -- Keyed digest of the payer's subject, on the same reasoning as cards: a transaction row is read by
    -- auditors and support agents, and the raw sub is personal data in both those places.
    owner_subject_digest VARCHAR(64) NOT NULL,

    -- The card token this payment was made with. 64 hex characters, the same HMAC-SHA256 token
    -- card-service mints. A token and not a card number, and the schema offers no column for one: a
    -- transaction is the table a fraud investigation reads, and it must not be possible to read a card
    -- number out of it. See ADR-0006.
    card_token VARCHAR(64) NOT NULL,

    -- Minor units, and the currency that says which minor unit. Never a decimal column. The two together
    -- are the amount; storing one without the other would let 100 mean 100 pence in one row and 1 pound
    -- in another.
    amount_minor BIGINT NOT NULL,
    currency_code VARCHAR(3) NOT NULL,

    -- A payment of zero or less cannot be posted, since a journal leg's amount is positive and the sign
    -- lives in the direction. Refusing it here means the invariant holds even if a future caller reaches
    -- the table directly.
    CONSTRAINT transactions_amount_positive CHECK (amount_minor > 0),

    status VARCHAR(16) NOT NULL,
    CONSTRAINT transactions_status_known CHECK (status IN ('PENDING', 'AUTHORIZED', 'DECLINED', 'SETTLED', 'REVERSED')),

    -- A decline with no stated reason is one a merchant cannot reconcile and a support agent cannot act
    -- on, so the two columns are tied together: a reason without DECLINED would be the platform making a
    -- claim about money that has not moved, and DECLINED without a reason is a refusal that says nothing.
    decline_reason VARCHAR(64),
    CONSTRAINT transactions_decline_reason_iff_declined CHECK (
        (status = 'DECLINED' AND decline_reason IS NOT NULL) OR (status <> 'DECLINED' AND decline_reason IS NULL)
    ),

    payee_name VARCHAR(140) NOT NULL,
    payee_reference VARCHAR(64),

    created_at TIMESTAMPTZ NOT NULL,
    authorized_at TIMESTAMPTZ,
    settled_at TIMESTAMPTZ,
    reversed_at TIMESTAMPTZ,

    -- The timestamps are checked against the status rather than trusted, for the same reason the state
    -- machine checks them in the domain: a SETTLED row with no settled_at is a payment that claims to
    -- have been paid out and cannot say when, which is the shape of a reconciliation failure.
    --
    -- The check is one-directional, and deliberately so: it says a captured payment must record when it
    -- was captured, not that only a SETTLED payment may have been. REVERSED is included with SETTLED
    -- because a refund does not un-happen the capture — the money left the customer's account at
    -- settled_at and came back at reversed_at, and clearing the timestamp would make the refund
    -- unreconstructable. "Who captured this, and when?" is exactly the question an audit asks, and
    -- the answer has to survive the reversal. The same reasoning already appears in
    -- transactions_authorized_at_iff_authorized above, and in transactions_timeline_ordered below, which
    -- can only be satisfied by a REVERSED row that still carries settled_at.
    CONSTRAINT transactions_authorized_at_iff_authorized CHECK (
        (status IN ('AUTHORIZED', 'SETTLED', 'REVERSED') AND authorized_at IS NOT NULL)
        OR (status IN ('PENDING', 'DECLINED') AND authorized_at IS NULL)
    ),
    CONSTRAINT transactions_settled_at_iff_settled CHECK (
        (status IN ('SETTLED', 'REVERSED') AND settled_at IS NOT NULL)
        OR (status IN ('PENDING', 'DECLINED', 'AUTHORIZED') AND settled_at IS NULL)
    ),
    CONSTRAINT transactions_reversed_at_iff_reversed CHECK (
        (status = 'REVERSED' AND reversed_at IS NOT NULL) OR (status <> 'REVERSED' AND reversed_at IS NULL)
    ),
    -- Ordering, not just presence. A capture recorded before its authorisation, or a refund dated before
    -- the settlement it reverses, is a timeline the journal cannot be read against.
    CONSTRAINT transactions_timeline_ordered CHECK (
        (authorized_at IS NULL OR authorized_at >= created_at)
        AND (settled_at IS NULL OR (authorized_at IS NOT NULL AND settled_at >= authorized_at))
        AND (reversed_at IS NULL OR (settled_at IS NOT NULL AND reversed_at >= settled_at)
             OR (settled_at IS NULL AND authorized_at IS NOT NULL AND reversed_at >= authorized_at))
    ),

    version BIGINT NOT NULL DEFAULT 0
);

-- "My transactions, newest first" is the only listing this service offers, and it is always scoped to a
-- payer, so the index is (owner, created_at DESC) rather than on created_at alone.
CREATE INDEX transactions_owner_created_at_idx ON transactions (owner_subject_digest, created_at DESC);

-- A payment is looked up by card when reconciling against a network, and that lookup is by token.
CREATE INDEX transactions_card_token_idx ON transactions (card_token);

-- --------------------------------------------------------------------------- ledger accounts

CREATE TABLE ledger_accounts (
    id UUID PRIMARY KEY,

    -- The customer whose money this is, as a keyed digest, or the fixed 'platform' code.
    --
    -- NOT NULL, and the reason is a Postgres detail worth stating: a unique constraint containing a
    -- nullable column does not constrain anything, because NULL is distinct from NULL. With owner_ref
    -- nullable, two rows for 'platform / PLATFORM_FUNDING / GBP' would both be accepted and the
    -- constraint that is supposed to make the funding account unique would be decorative. Putting a
    -- sentinel in the column makes the constraint real. See PlatformAccount.OWNER_REF.
    owner_ref VARCHAR(64) NOT NULL,

    type VARCHAR(32) NOT NULL,
    CONSTRAINT ledger_accounts_type_known CHECK (
        type IN ('CUSTOMER_AVAILABLE', 'CUSTOMER_RESERVED', 'PLATFORM_FUNDING', 'PLATFORM_CLEARING')
    ),

    currency_code VARCHAR(3) NOT NULL,

    -- The signed sum of every posting to this account. Signed because a source of funds is a credit
    -- balance and must be allowed to read as one; the per-type CHECK below is what stops a *customer*
    -- account from ever being negative.
    balance_minor BIGINT NOT NULL DEFAULT 0,

    version BIGINT NOT NULL DEFAULT 0,

    -- One account per owner, type and currency. This is the constraint that makes concurrent funding
    -- safe: two requests for a customer with no GBP account yet would otherwise both find nothing and
    -- both insert, and the customer would end up with two available balances that no single query sums.
    CONSTRAINT ledger_accounts_owner_type_currency_unique UNIQUE (owner_ref, type, currency_code)
);

-- The balance read on every authorisation, so it is covered rather than left to the unique index above.
CREATE INDEX ledger_accounts_owner_currency_idx ON ledger_accounts (owner_ref, currency_code);

-- The overdraft rule, enforced by the database rather than only by LedgerAccount.apply.
--
-- The per-type form matters: a single `balance_minor >= 0` would also forbid PLATFORM_FUNDING from
-- holding a credit balance, and a platform whose funding account cannot go negative has no way to
-- represent money coming into it. Two checks say exactly what is meant: customers may not overdraw,
-- the platform may.
ALTER TABLE ledger_accounts ADD CONSTRAINT ledger_accounts_customer_non_negative CHECK (
    type NOT IN ('CUSTOMER_AVAILABLE', 'CUSTOMER_RESERVED') OR balance_minor >= 0
);

-- A platform account should actually be a platform account. Without this, a row could be created with
-- owner_ref = 'platform' and type = CUSTOMER_AVAILABLE, which would let one customer's request resolve
-- to an account another customer's funding is posted to.
ALTER TABLE ledger_accounts ADD CONSTRAINT ledger_accounts_owner_matches_type CHECK (
    (type IN ('CUSTOMER_AVAILABLE', 'CUSTOMER_RESERVED') AND owner_ref <> 'platform')
    OR (type IN ('PLATFORM_FUNDING', 'PLATFORM_CLEARING') AND owner_ref = 'platform')
);

-- --------------------------------------------------------------------------- the journal

-- The record of what happened. The balance columns above are derived from this table and written in the
-- same transaction, so a balance that disagrees with its journal is not a state a crash can leave
-- behind.
CREATE TABLE journal_entries (
    id UUID PRIMARY KEY,

    -- The payment this entry belongs to, or null for one that is not a payment. Nullable rather than
    -- "always a payment" because funding moves value with no transaction behind it, and forcing a fake
    -- id onto a deposit would be a lie in the one table an auditor reads closely.
    --
    -- The constraint itself is declared at the foot of the table rather than inline, so that it can be
    -- deferrable. See the note there.
    transaction_id UUID,

    kind VARCHAR(32) NOT NULL,
    CONSTRAINT journal_entries_kind_known CHECK (kind IN ('FUNDING', 'HOLD', 'CAPTURE', 'RELEASE', 'REVERSAL')),

    currency_code VARCHAR(3) NOT NULL,
    amount_minor BIGINT NOT NULL,
    CONSTRAINT journal_entries_amount_positive CHECK (amount_minor > 0),

    -- A REVERSAL names the entry it undoes, and nothing else may. This is what makes a correction
    -- auditable: a reversing entry with no target is indistinguishable from an ordinary movement once
    -- enough time has passed.
    reversal_of_entry_id UUID REFERENCES journal_entries (id),
    CONSTRAINT journal_entries_reversal_iff_reversal CHECK (
        (kind = 'REVERSAL' AND reversal_of_entry_id IS NOT NULL) OR (kind <> 'REVERSAL' AND reversal_of_entry_id IS NULL)
    ),
    -- A reversal cannot point at itself.
    CONSTRAINT journal_entries_reversal_not_self CHECK (reversal_of_entry_id IS NULL OR reversal_of_entry_id <> id),

    description VARCHAR(280),
    effective_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,

    -- DEFERRABLE, and deferred to the end of the transaction, because authorising a payment saves the
    -- transaction row and the journal entry in one commit without forcing a flush between them. An
    -- immediate foreign key would be checked per statement, and the order those two INSERTs happen to be
    -- issued in is not something the application should have to reason about: `JournalEntry` holds
    -- `transactionId` as a plain UUID rather than a JPA association, precisely so the journal cannot be
    -- cascaded from a transaction, and an unmodelled dependency is one the ORM is not obliged to order.
    -- Checking at commit makes the rule independent of that — by then the row is either there or the
    -- payment did not happen, which is the same answer either way.
    --
    -- Deferred is not unenforced. A journal entry naming a transaction that was never created still fails
    -- the commit; it fails at the end rather than mid-statement, which is also the more useful place for
    -- it to surface, because by then the rest of the posting has been applied and rolls back with it.
    CONSTRAINT journal_entries_transaction_fk FOREIGN KEY (transaction_id)
        REFERENCES transactions (id) DEFERRABLE INITIALLY DEFERRED
);

-- "What happened to this payment?" and "everything that moved in this window", which is what a daily
-- reconciliation and a spending-limit total both read.
CREATE INDEX journal_entries_transaction_idx ON journal_entries (transaction_id) WHERE transaction_id IS NOT NULL;
CREATE INDEX journal_entries_effective_at_idx ON journal_entries (effective_at DESC, kind);

-- --------------------------------------------------------------------------- journal lines

CREATE TABLE journal_lines (
    id UUID PRIMARY KEY,

    entry_id UUID NOT NULL REFERENCES journal_entries (id) ON DELETE CASCADE,

    -- An id, not a foreign key to ledger_accounts.
    --
    -- A journal line is immutable history, and a live foreign key would make reading the history depend
    -- on a row that is still being updated today: every read of an old entry would contend with the
    -- posting writing to the same account. The id keeps the link and drops the coupling. The cost is
    -- that nothing stops a line naming an account that was never created, which is why the service is
    -- the only writer and why the conservation test sums this column against the balances.
    account_id UUID NOT NULL,

    direction VARCHAR(8) NOT NULL,
    CONSTRAINT journal_lines_direction_known CHECK (direction IN ('DEBIT', 'CREDIT')),

    -- Always positive. The sign lives in direction, so that "debit -100" and "credit 100" cannot both
    -- appear and silently halve a balance.
    amount_minor BIGINT NOT NULL,
    CONSTRAINT journal_lines_amount_positive CHECK (amount_minor > 0),

    -- The balance on the account immediately after this line applied. The column that makes the ledger
    -- auditable without replaying it: the balance_after values for one account are a running balance,
    -- so a reported total can be checked against the last line rather than by re-summing the table.
    balance_after_minor BIGINT NOT NULL
);

CREATE INDEX journal_lines_entry_idx ON journal_lines (entry_id);
-- "Every line on this account, oldest first" is how a single account's balance is verified.
CREATE INDEX journal_lines_account_idx ON journal_lines (account_id);

-- The double-entry invariant, in the database.
--
-- This is the constraint that makes the ledger trustworthy when the service is not: any insert, from
-- any code path, including a future migration or a hand-written fix, cannot record an entry whose
-- debits and credits differ. The domain enforces it in Posting.balanced and again in
-- JournalEntry.verifyBalanced; this is the third and last line, and it is the one that holds even if
-- both of those are bypassed.
--
-- Written as a trigger rather than a CHECK because a CHECK cannot reference other rows, and the thing
-- to be checked is that one entry's lines sum to zero. CONSTRAINT TRIGGER is deferred to the end of
-- the statement, so an entry's lines can be inserted in any order and are still checked as a set.
CREATE OR REPLACE FUNCTION journal_lines_must_balance() RETURNS TRIGGER AS $$
DECLARE
    target_entry UUID;
    debits BIGINT;
    credits BIGINT;
BEGIN
    -- The entry is named on the line, so a delete needs the value from the row being deleted.
    IF TG_OP = 'DELETE' THEN
        target_entry := OLD.entry_id;
    ELSE
        target_entry := NEW.entry_id;
    END IF;

    -- A line that is being deleted or updated away from an entry no longer has to make that entry
    -- balance, because the entry itself is going with it. Entries are never edited in this schema, and
    -- this is what makes ON DELETE CASCADE work without the cascade tripping this trigger on the way
    -- out.
    IF NOT EXISTS (SELECT 1 FROM journal_entries WHERE id = target_entry) THEN
        RETURN NULL;
    END IF;

    SELECT COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'CREDIT'), 0)
      INTO debits, credits
      FROM journal_lines
     WHERE entry_id = target_entry;

    IF debits <> credits THEN
        RAISE EXCEPTION 'journal entry % does not balance: debits % <> credits %', target_entry, debits, credits
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER journal_lines_must_balance
    AFTER INSERT OR UPDATE OR DELETE ON journal_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION journal_lines_must_balance();

-- --------------------------------------------------------------------------- idempotency

-- The record of a money-moving request, keyed by the caller's own key.
--
-- The key is scoped to the payer rather than global. Two customers who both chose "order-1234" must not
-- collide, and a globally unique key would turn two unrelated merchants' ordinary references into one
-- another's 409. Scoping the unique constraint to (owner, key) makes the key mean "one of this
-- customer's requests named this", which is what a client actually intends.
CREATE TABLE idempotency_keys (
    id UUID PRIMARY KEY,

    owner_subject_digest VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,

    -- The endpoint and key arguments this key was first used with.
    --
    -- A key reused against a different request is a client bug or a retry that was rewritten, and both
    -- are worth a loud 409 rather than a silent replay. Comparing the fingerprint is what distinguishes
    -- "the same request, sent again" from "a different request wearing an old key", and the second is
    -- the case that would otherwise hand a caller the first one's response for a payment that was never
    -- made.
    request_fingerprint VARCHAR(64) NOT NULL,

    -- The stored response, replayed verbatim on a repeat. Storing the body rather than a pointer means
    -- a replay is a read of this table, not a second run of the operation, which is the whole point.
    --
    -- NULL while the first attempt is still running. A repeat that finds NULL is told the original is
    -- in progress, rather than being answered with an empty body that the caller would have to guess
    -- the meaning of.
    -- INTEGER, not SMALLINT. An HTTP status fits in a smallint comfortably, and smallint is the tidier
    -- choice for it, but the Java side is an `Integer` and Hibernate maps that to `int4`. Writing smallint
    -- here would fail `ddl-auto: validate` at startup, and the failure would be a schema error pointing at
    -- a column whose type looked correct. A SMALLINT is what a future reader of this file would expect
    -- and an INT is what the application actually speaks.
    response_status INTEGER,
    response_body TEXT,

    -- Set when the operation completed. Left null on an unexpected failure, which frees the key for
    -- genuine reuse: a key pinned by a request that crashed would turn one transient error into a
    -- payment the customer could never make.
    completed_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT idempotency_owner_key_unique UNIQUE (owner_subject_digest, idempotency_key),

    -- A completed record has a response and a completion time; an in-flight one has neither. Stating it
    -- here means the replay path can trust that response_body is populated whenever completed_at is,
    -- instead of null-checking something that should have been impossible.
    CONSTRAINT idempotency_completed_iff_response CHECK (
        (completed_at IS NULL AND response_status IS NULL AND response_body IS NULL)
        OR (completed_at IS NOT NULL AND response_status IS NOT NULL AND response_body IS NOT NULL)
    )
);

-- The in-flight case is the rare one, and it is the one a support agent needs to find: at scale the
-- table is overwhelmingly completed records and this finds the handful that are not.
CREATE INDEX idempotency_keys_in_flight_idx ON idempotency_keys (created_at) WHERE completed_at IS NULL;

-- --------------------------------------------------------------------------- outbox

-- Events written in the same transaction as the state change that caused them.
--
-- This is what makes "the payment committed but the event was lost" impossible. The alternative is
-- publishing to Kafka after committing, which has a window between the commit and the publish where a
-- crash loses the event, and a payment that is SETTLED with nobody told. Here the event is a row, and
-- the row commits or rolls back with the payment.
--
-- Delivery is at least once, not exactly once: the relay publishes and then marks published, and a
-- crash in between republishes. That is the correct trade because a duplicate event can be
-- deduplicated by (aggregate, version) and a missing one cannot be recovered, so the failure mode is
-- pushed onto consumers, which can handle it, rather than onto the ledger, which cannot.
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,

    -- The payment this event concerns, and the version of it at the moment the event was recorded. The
    -- version is the deduplication key: a consumer that has seen aggregate version N ignores N again, so
    -- at-least-once delivery costs it a redundant message rather than a double-applied state change.
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,

    topic VARCHAR(128) NOT NULL,
    event_key VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,

    payload TEXT NOT NULL,

    -- NULL until published. The relay's work queue, and the index that makes it cheap: at-least-once
    -- delivery means the common case is reading a handful of pending rows, not scanning a table that
    -- grows forever.
    published_at TIMESTAMPTZ,

    -- Transport failures are worth seeing and worth retrying, and a row that fails silently forever is
    -- a payment whose event nobody will ever receive.
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,

    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,

    -- One event per version of an aggregate. If the relay were somehow to enqueue the same event twice,
    -- this turns the second attempt into a constraint failure rather than a third duplicate.
    CONSTRAINT outbox_aggregate_version_event_unique UNIQUE (aggregate_type, aggregate_id, aggregate_version, event_type)
);

CREATE INDEX outbox_unpublished_idx ON outbox_events (created_at) WHERE published_at IS NULL;

-- The whole ledger sums to zero. Stated on the accounts table as a deferred trigger, for the same
-- reason the per-entry trigger exists in code: an invariant the application enforces can be bypassed by
-- the application, and an invariant only the application enforces cannot survive a new writer.
--
-- Checked at commit, so a posting that is correct in the middle of a transaction and incorrect at the
-- end of it is caught before it becomes visible.
CREATE OR REPLACE FUNCTION ledger_accounts_must_conserve() RETURNS TRIGGER AS $$
DECLARE
    total BIGINT;
BEGIN
    SELECT COALESCE(SUM(balance_minor), 0) INTO total FROM ledger_accounts;
    IF total <> 0 THEN
        RAISE EXCEPTION 'ledger does not conserve value: accounts sum to %, expected 0', total
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- A deferred constraint trigger on the table whose rows are read most often, and which fires at commit
-- time rather than per statement. Deliberately cheap to trigger and slightly expensive to evaluate: a
-- full sum of accounts at commit is a sequential scan, and on a ledger of this size that is far cheaper
-- than a reconciliation that finds a missing penny a month later and cannot say when it appeared.
CREATE CONSTRAINT TRIGGER ledger_accounts_must_conserve
    AFTER INSERT OR UPDATE OR DELETE ON ledger_accounts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_accounts_must_conserve();
