# ADR-0007: Integer minor units, a balanced double-entry ledger, and database-enforced idempotency

- Status: accepted, implemented in Phase 5
- Date: 2026-09-26
- Phase: 5

## Context

Phase 5 is the first phase that moves money, and it makes three decisions that the rest of the
platform inherits. Every later phase that touches an amount — settlement in 7, disputes in 10, the
ledger reports in 9 — reads the structures fixed here, so these are worth arguing explicitly rather
than inheriting from whatever the first service happened to do.

Three questions had to be answered before any code was written.

**How is an amount represented?** The obvious answer is a decimal, and the obvious implementation of
a decimal is `double` or `BigDecimal`. Both are wrong here for different reasons. `double` cannot
represent `0.10` exactly, so a ledger built on it accumulates an error that no amount of care at the
call sites will fix, and the error appears as a reconciliation break months later when nobody
remembers the code that caused it. `BigDecimal` is exact but makes every caller choose a scale, and a
scale chosen per call site is a scale that eventually disagrees with itself.

**What holds a balance?** A `balance` column on a customer is the simplest thing that works, and it
is where a payment platform starts losing money. The balance is derived state — it is the sum of
everything that has happened to the account — and a derived value stored as if it were a fact has no
way to prove it is still true. There is no query that answers "is this balance correct?" because the
balance *is* the answer.

**What happens when a payment request arrives twice?** A client retries. A proxy retries. An operator
double-clicks. Mobile networks duplicate. This is not an edge case to be handled gracefully, it is the
normal operating condition of an endpoint that moves money, and a duplicated payment is not a bug
report, it is a customer complaint and a support cost.

## Decision

### Amounts are a `long` count of minor units, paired with an explicit currency

A `Money` value is `(long minorUnits, Currency currency)`. There is no floating-point money anywhere
in this service, and no `BigDecimal` in the persistence path or the domain.

Currency is part of the value rather than an attribute of the account, because a payment crosses
currencies and inferring one from the account is how an amount gets converted by accident. Arithmetic
between two amounts of different currencies is refused rather than converted: this platform has no
foreign-exchange rate source, and inventing a rate at the call site is worse than refusing.

On the wire an amount is a decimal **string** plus a currency code (`"10.50"`, `"GBP"`), not a JSON
number. A JSON number is parsed into a double by most clients before the string ever reaches the
service, which reintroduces the exact error the representation exists to prevent, and it means the
client and the server can disagree about the value of the same payment. A string is parsed once, by
code that is obliged to be exact.

**A request carrying more precision than the currency has is rejected, not rounded.** `"10.501"` in
GBP is a 400. Silently truncating it to `1050` minor units means the amount a customer agreed to pay
and the amount charged are different numbers, and the difference is found by the customer.

### Balances live in a double-entry journal, and the balance column is a cache of it

Every movement of value is a `JournalEntry` of two or more `JournalLine`s. Each line names an account
and a direction, and **the sum of the debits equals the sum of the credits within an entry**. This is
enforced when the entry is constructed, not by a reconciler that runs later.

`ledger_accounts` carries a `balance` column, and it is important to be honest about what that column
is: it is a materialised total, maintained in the same transaction as the journal lines, whose only
purpose is to make "may this customer spend £50?" answerable with one indexed read instead of an
aggregate over an unbounded table. The journal is the record; the column is a cache of it.

This gives the property that makes a ledger worth having:

> The sum of every account balance in the platform is always exactly zero.

Money is not created or destroyed by a hold, a capture, a release, a reversal or a funding, because
each of those is a balanced set of lines. The invariant is not a convention that careful code
maintains; it is checked by a test that runs the full lifecycle and sums the column.

A customer's spendable money is the balance of their `CUSTOMER_AVAILABLE` account. Money that is
authorised but not yet captured is moved to `CUSTOMER_RESERVED`, so it is no longer spendable without
being debited from anywhere. The two accounts are why "available" and "held" are separate numbers
rather than one number and a flag.

**Customer accounts may not go negative; platform accounts may.** `CUSTOMER_AVAILABLE` and
`CUSTOMER_RESERVED` reject a posting that would leave them below zero, which is the limit that
actually matters and the one that must hold under concurrency. `PLATFORM_FUNDING` and
`PLATFORM_CLEARING` represent the world outside the platform and are expected to carry a credit
balance, so the rule is per account type rather than global. A single global rule would either permit
a customer to overdraw or forbid the platform from being a source of funds at all.

`PLATFORM_CLEARING` is where captured money goes, and that is deliberate: settlement (Phase 7) is what
moves it out to a real rail, so before settlement the platform holds it and can say so.

### Concurrency is resolved by locking the account row, not by retrying

A hold reads the available balance and writes a smaller one. Two concurrent holds that both read the
same balance both authorise, and the customer spends money they do not have. The read is therefore
`SELECT ... FOR UPDATE` on the affected account rows, taken in a deterministic order (by account id)
so that two transactions touching the same two accounts cannot deadlock by grabbing them in opposite
orders.

The optimistic `@Version` column is kept as well, and it is not redundant with the lock: the lock
protects a multi-statement posting inside one transaction, and the version catches a lost update on a
path that reads and writes outside that discipline.

### Idempotency is a row with a unique constraint, and the response is stored beside it

`POST` endpoints that move money require an `Idempotency-Key`. The key is stored in
`idempotency_keys` under a unique constraint on `(owner_subject_digest, idempotency_key)`, together
with a **fingerprint** of the request and the **response that was returned**.

Three rules, and the second is the one usually missed:

1. **Same key, same request** → the stored response is replayed verbatim and no second payment
   happens. The client cannot tell the difference, which is the point.
2. **Same key, different request** → `409 IDEMPOTENCY_KEY_REUSED`. Replaying a key with a changed
   amount is a client bug that would otherwise be silently resolved in favour of the *first* request,
   so the customer pays the amount the server happened to see first. Refusing is the only answer that
   does not quietly pick a winner.
3. **No key on a money-moving POST** → `400`. Not optional, not defaulted to a generated value. A
   generated key makes the endpoint idempotent against nobody.

"Verbatim" is load-bearing and was a real bug. The stored response is **text**, not an object, so
returning it through a JSON message converter as an ordinary body encodes it a second time: a client
retrying a successful payment receives `"{\"id\":\"…\"}"` — a quoted string where the first attempt
received an object. The payment is made exactly once either way, the status code is 201 either way,
and nothing in the ledger or the idempotency table looks wrong, so only the client's parser notices,
when it asks for `$.id` and finds a string. A replay is therefore written out with an explicit
`application/json` content type, which selects the string converter and lets the bytes through
untouched. `PaymentFacade.Result.Answer` has two named constructors, `fresh` and `replayed`, rather
than one constructor plus a boolean, so a replayed answer cannot be built without its stored text.

The record is written **in the same database transaction as the payment**. This is the part that is
easy to get wrong: storing the idempotency record after the work commits means a crash in between
leaves a payment that the client will retry into a second payment, which is the exact failure the
mechanism was added to prevent.

#### The key is claimed with `ON CONFLICT DO NOTHING`, not by catching the violation

The obvious way to claim a key is to insert it and catch the unique-constraint exception when someone
else got there first. **On PostgreSQL that does not work**, and it fails in a way that looks like a
platform outage rather than a lost race: a constraint violation aborts the *entire* transaction, so
the catch block's own re-read — the code that is supposed to turn the race into a clean
`409 IDEMPOTENT_REQUEST_IN_PROGRESS` — fails with `current transaction is aborted, commands ignored
until end of transaction block`.

In a test of eight concurrent requests sharing one key, that produced exactly one success and seven
`JpaSystemException`s. The duplicate-payment guarantee still held: one request, one payment. But seven
clients were told the platform was broken when the truth was that their request was already in hand
and they should retry, and a client that believes the platform is broken does not retry politely.

So the claim is a single statement whose row count is the answer:

```sql
INSERT INTO idempotency_keys (id, owner_subject_digest, idempotency_key, request_fingerprint, …)
VALUES (?, ?, ?, ?, …)
ON CONFLICT ON CONSTRAINT idempotency_owner_key_unique DO NOTHING
```

One row inserted means this request owns the key and proceeds. Zero means it does not, and the
service re-reads the row to decide between a replay and an in-flight response. `ON CONFLICT` blocks
until the winning transaction commits, which is what makes the count trustworthy: there is no window
in which both requests believe they own the key.

Redis is **not** in the path. It was considered as a fast path in front of the constraint, and it
would still not be the authority: a cache that is briefly unavailable must slow the endpoint down, not
authorise a duplicate payment, so correctness would continue to rest on the unique constraint either
way. A Redis outage would degrade latency and nothing else. No such fast path is implemented, and
the code says so rather than implying otherwise.

Keys are scoped to the authenticated caller — the `owner_subject_digest` half of the constraint is a
keyed HMAC of the verified subject, not a caller-supplied value — so one customer's key cannot collide
with another's and a caller cannot read back a response recorded for someone else.

### Events go through a transactional outbox

A state change and its event are written in the same database transaction, and a scheduled publisher
drains the outbox to Kafka afterwards. Writing a row and publishing an event in one transaction is not
possible without two-phase commit, and a two-phase commit across Postgres and Kafka is worse than
either alternative.

The consequence is accepted rather than solved away: an event may be published twice, so every
`EventEnvelope` carries an `eventId` and every consumer is idempotent. At-least-once delivery with
idempotent consumers, not exactly-once, and the outbox is what makes the *first* at-least honest.

## Consequences

**What this costs**

- More tables than a payment prototype needs. `journal_lines` is the table a `balance` column would
  have let us skip, and it exists because the balance column cannot be audited.
- Writes are serialised per account. Two payments from one customer queue behind each other. That is
  the intended behaviour, and it is also a real throughput ceiling that a sharded ledger would be
  needed to lift. It is a ceiling we can name, which is better than one we discover.
- Amounts are strings on the wire, which every client has to parse. This is a real ergonomic cost
  paid to keep a `double` from entering the system at the boundary.
- The idempotency response body has to be stored as text, and it is a snapshot: if the response shape
  changes between versions, a replay returns the old shape. That is correct — the client is owed the
  answer it already received — but it means response-shape changes need a version, not an edit.

**What it buys**

- A reconciliation query that is one `sum(balance)` and whose answer is zero. Any other number is a
  bug, and it is checked on every build.
- A duplicate payment that is structurally impossible on a retry rather than unlikely.
- Amounts that a customer can be charged exactly, with no rounding rule to remember.

**Naming**

The aggregate is called `Transaction`, matching the service, the database, the topics and the routes.
Inside a service whose entire job is database transactions the word is ambiguous — `@Transactional`
is not, being an annotation, but `TransactionStatus` is a Spring type — so any Spring type of that
name is referred to by its full name. Renaming the aggregate to `Payment` would remove the ambiguity
and introduce a worse one: two words for one thing, in a platform whose service, topic and route names
are already published and consumed by other services.

## Rejected

- **`BigDecimal` for money.** Exact, and the wrong tool here: it pushes a scale decision onto every
  call site, and a ledger whose arithmetic is correct but whose callers disagree about scale produces
  the same reconciliation breaks as `double`, only later and harder to diagnose.
- **A `balance` column with no journal.** The fast, obvious design. It cannot answer "sum the
  transactions" without a second source of truth, so the balance is unfalsifiable, and a wrong
  balance is discovered by an auditor rather than by a test.
- **Idempotency keys in Redis only.** A cache eviction or a failover silently permits a duplicate
  payment, and the failure is invisible until a customer notices.
- **`SELECT FOR UPDATE` on the customer row only.** The posting touches two accounts, and locking one
  of them still allows two postings to interleave between the second account's read and write.
- **Rounding excess precision to the currency's scale.** A payment for `10.501` becoming `10.50` is
  the platform deciding, silently, what the customer owes.
