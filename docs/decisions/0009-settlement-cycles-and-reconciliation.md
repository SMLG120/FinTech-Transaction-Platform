# ADR-0009: Settlement cycles over consumed events, with reconciliation against an independently declared actual

- Status: accepted, implemented in Phase 7
- Date: 2026-09-27
- Phase: 7

## Context

Phase 5 gave the platform a double-entry ledger and a transaction state machine whose happy path
ends at `SETTLED`, with `SETTLED -> REVERSED` available for a refund after capture. That is a
*local* decision: this service decided that this payment's money is captured and that a customer
and platform hold the difference. It is not the same claim as "this money reached the merchant", and
the gap between the two is what Phase 7 is about.

Three questions have to be answered, and each has a convenient wrong answer.

**Does settlement read the ledger?** The obvious design is a settlement job inside
transaction-service, querying `journal_lines` for a date range. It is simpler, transactional,
consistent, and wrong for reasons that are structural rather than matters of taste. It puts a
date-range scan and an aggregate over the largest table in the platform on a schedule, inside the
service whose latency every payment depends on, so a growing ledger eventually makes authorisation
slow. It makes the ledger schema a contract that settlement is entitled to read, so every future
journal change has to be coordinated with a consumer nobody would think to look for. And it gives
reconciliation a single source of truth — which defeats the entire purpose, below.

**Where does reconciliation get its second number?** This is the crux. Reconciliation exists to
catch the case where the money and the record disagree, and it can only do that if the two sides are
*independently produced*. The wrong answer, which is very easy to build and hard to notice, is to
compute the expected total from the ledger, read the clearing account balance from the same
database, and compare. Those two numbers are derived from the same rows by the same code, so they
agree by construction. The reconciliation passes on every input, including every input it exists to
reject, and its green result carries no information at all. A reconciliation that cannot fail is
worse than none, because it is believed.

So the second number has to come from outside the platform: a bank or acquirer statement. In
production that is a feed. In this repository there is no bank, so the actual figure is *declared by
an operator* through the API, and the honest consequence is recorded in the tests: the service can
only be wrong in the way a real reconciliation is wrong, which is that the declared number and the
computed number genuinely can differ.

**What happens to a refund of money that has already been paid out?** This is the case that decides
the whole model. A transaction settles in cycle N. The cycle closes and a statement goes to the
merchant. Then the customer charges the card back and the money comes back — in cycle N+1. The
obvious answer is to go back and add a negative line to cycle N. That answer is wrong because cycle
N's statement has already been given to somebody, and a statement that changes after it is sent is
not a statement. The alternative answer — refuse the reversal — is worse, because the customer
keeps the money. So a reversal crossing a closed cycle has to be real money moving in a later
period, recorded as a line in the later cycle against the original transaction, with cycle N left
exactly as it was closed. This is why the cycle is the unit of immutability and not the
transaction: the transaction is already terminal at `REVERSED` in Phase 5's state machine, and
telling the platform that a customer is owed nothing back when they are owed a refund is exactly the
kind of error a ledger exists to prevent.

## Decision

**Settlement is its own service, `settlement-service`, fed by events and never by a cross-service
read.** It consumes `transaction-settled` and `transaction-reversed`, deduplicating on event id
through the same `ON CONFLICT` pattern ADR-0008 established, and builds its own statement view. It
holds no copy of the ledger and reads no table it does not own. Those events do carry an
`ownerSubjectDigest`, because transaction-service publishes the same payload shape on every
transaction event, but settlement parses it and discards it: a statement is about money moving between
a platform and a merchant, and a per-customer pseudonym is not an input to that question. Storing it
would be storing the platform's most sensitive derived identifier in the one service with a
staff-facing API, for no reason — which is the reasoning ADR-0008 gave for the fraud engine, arriving at
the same place from a different direction.

**A settlement cycle is the unit of both immutability and time.** A cycle covers one business date
in one currency, holds the lines gathered for that date, and moves `OPEN -> CLOSED -> RECONCILED`
or `OPEN -> CLOSED -> BROKEN`. Once a cycle is closed its lines, its expected total and its
reconciliation are frozen; the domain refuses to add a line to it, and a reversal arriving later is
posted to the cycle covering the reversal's own business date. Closing is therefore the moment the
period's contents become a fact, and the API has no endpoint that can change one afterwards.

**Reconciliation compares a computed expected total against a declared actual, and a difference
becomes a break rather than an error.** The expected total is the sum of the cycle's net lines. The
actual is posted separately by an operator, because a bank feed does not exist here and inventing a
second read of our own database would produce the vacuous check described above. When the two differ,
the cycle is `BROKEN` and a `settlement_breaks` row is written carrying the classification, both
figures and the difference. A cycle with an open break cannot be reconciled, so a break cannot be
closed by an operator who simply does not like it: the money has to be accounted for first, and the
acknowledgement is recorded separately from the resolution so that the audit trail distinguishes
"somebody has seen this" from "this is explained".

**A reversal is a negative line, and it is never a mutation.** A `REVERSAL` line carries a negative
amount, so a statement sums one signed column and a refund is just a movement in the opposite
direction, and it points at the original transaction while belonging to a possibly different cycle.
Because a reversal of a transaction that was never settled has nothing to reverse in a statement, a
reversal event for a payment this service never saw settle is recorded as a break rather than as a line
with no counterpart: an orphan is a reconciliation finding, not a negative number.

**A movement that arrives for a period already closed becomes a break, and that covers captures as
well as refunds.** A cycle closed in the same instant a payment settles will miss that payment, and
that is a batch boundary rather than a defect — what is not acceptable is missing it quietly. So a
capture whose period is closed is recorded exactly as a refund whose period is closed is, under one
`PERIOD_ALREADY_CLOSED` kind, because the condition and the remedy are the same: the money moved, the
statement is immutable, and somebody has to account for it by hand. Recording it rather than throwing
matters as much. A throw would park the event on the dead-letter topic, where it reads as a message
about parsing, when the real problem is that a period somebody was already given is now short — which is
a question for finance and not for the consumer. And a cycle cannot be reopened, so the two choices on
offer were to lose the payment silently or to make the period's total depend on when somebody ran the
job.

**A statement line is an amount and a direction, with no fee column.** This is worth stating because a
settlement statement that omits a fee looks like an oversight. It is not: the ledger has no fee
posting and `transaction-settled` carries no fee, so a fee column would be a column that is always zero
and a second place for a fee to be quietly forgotten. A gross statement is a correct statement of what
this platform actually does. Merchant fees belong to whichever phase can produce one, and adding the
column then is a migration, not a repair.

**A period announces every outcome that stops it changing, on one topic named for that.** Publishing
`settlement.cycle.closed`, `settlement.cycle.reconciled` and `settlement.cycle.broken` on
`settlement-cycle-finalised` is not a naming preference. A reporting consumer cannot read the
settlement database, so this event is the only thing that tells it a period's money is final, and the
case that is easiest to omit is the one that matters most: a cycle that breaks rather than reconciles
is final too, and it is final *badly*. The first version of this published a `closed` event at close
time and a `reconciled` event on the reconciling path and nothing at all when a cycle broke, so the
most consequential outcome a consumer could be told about was the one it was never told about. Closed
travels on the topic because a reporting consumer also needs to know when a period stops *growing*
even though its money is not yet final — otherwise it publishes a figure the platform contradicts a
day later. The event type distinguishes the two; the topic is about "stop watching this period".

**Roles split on the same line Phase 6 drew for fraud.** `SETTLEMENT_OPERATOR` may close a cycle,
declare an actual and acknowledge a break; `COMPLIANCE_OFFICER`, `AUDITOR` and `PLATFORM_ADMIN` may
read. Reading a settlement statement is how an auditor establishes that money reached a merchant, and
it is not a customer-facing capability, so no customer or support role can see any of it.

## Consequences

The ledger scan that settlement does not do now happens as event consumption instead, so the cost
moves off the authorisation path and onto a service whose latency nothing depends on. The price is
the one this platform has already accepted twice: settlement's view of a payment is eventually
consistent with the ledger, so a cycle closed in the same instant a payment settles can miss it.
That is not a defect to engineer away — it is the definition of a batch boundary — and it is why
closing a cycle is an explicit, dated act rather than a side effect of a payment.

Reconciliation is only as good as the independence of the number it is given, and in this
repository the number is typed in by a human. That is a real limitation and it is recorded rather
than hidden: the classification, the break rows and the refusal to reconcile a broken cycle are all
exercised against an actual that genuinely disagrees, because the tests declare one. Swapping the
declared figure for a bank feed changes where the number arrives and nothing else.

A break is a first-class row rather than an exception, so the operational question "did anything not
reconcile, and who has seen it" is a query. The cost is that a cycle can sit `BROKEN` indefinitely,
and nothing in the platform treats that as urgent — which is a gap Phase 16's alerting and runbooks
have to close.

## Rejected

**A settlement job inside transaction-service.** Cheaper to build and transactional, and it puts a
date-range aggregate over the largest table in the platform on the critical path of every payment.
It also makes reconciliation self-referential in the worst way, because the job would compute both
sides of the comparison from tables it owns.

**Mutating a closed cycle to absorb a later reversal.** It keeps the cycle's total equal to the sum
of its lines at the cost of the statement being true only until the next write. A statement that
changes after it is sent cannot be reconciled against, which defeats the reason for having one.

**Treating a reconciliation difference as an error and rolling back.** The difference is the finding.
Rejecting it hides the one fact the process exists to surface, and there is nothing to roll back:
the money has already moved, so the platform's job is to record the gap accurately and escalate it,
not to refuse to admit it happened.

**Deriving the "actual" side from our own clearing account.** Rejected above and worth restating,
because it is the version that looks most professional: two independent queries over the same rows,
agreeing by construction, reporting a green reconciliation forever. The comparison has to cross a
boundary, or it proves nothing.

**Refusing a reversal that crosses a closed cycle.** It would leave a customer in possession of money
the platform has already paid out, recorded nowhere. The correct answer is to pay it back in a later
period and let the two periods tell that story between them.
