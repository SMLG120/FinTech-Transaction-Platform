# ADR-0012: Disputes decided by support, refunded by the ledger

- Status: accepted, implemented in Phase 10
- Date: 2026-09-27
- Phase: 10

## Context

Phases 5 through 9 gave the platform payments, scores, statements, messages and a trail. What none
of them answers is the customer saying "that payment should not have happened" — the one flow where
the platform takes money back on purpose rather than recording that it moved. Phase 10 is the
chargeback workflow: open a case, plead in it, decide it, and refund when the decision says so.

Three questions have to be answered, and each has a convenient wrong answer.

**Who moves the money?** The obvious design is dispute-service calling transaction-service's
reversal endpoint when an agent clicks refund. It is synchronous, legible, and wrong for the reason
the forwarded identity exists: the call would need an identity entitled to move somebody else's
money. Forwarding the agent's identity fails ownership — the agent never owned the payment — and
minting any other identity at that hop turns a compromised dispute-service into a reversal oracle
for every payment on the platform. So the money is moved the other way round: dispute-service
announces the decision on `dispute-status-changed`, and transaction-service consumes it and
reverses out of its own stored owner digest, which never leaves that service. The direction of the
dependency is the control. A rejection moves nothing and is read, validated and ignored — the same
silencing shape notification-service uses for fraud approvals, for the same reason: no human
action, no row, no message.

**Who may open, and on what?** The convenient answer is support-opened cases: an agent files on the
customer's behalf during the call. It breaks the ownership every other service keeps, because an
agent cannot see a payment to verify it — transaction-service answers the forwarded agent identity
with a refusal, correctly. The alternative, a staff override in the ownership rule, would be a hole
drilled for convenience in the exact wall Phase 5 built. So only the customer opens, and only
their own settled payment, verified synchronously under their own forwarded identity before the row
exists. The customer opens, the agent decides. Only settled payments are disputable: a hold is
money reserved rather than moved, and disputing it demands a refund of a payment that never
completed.

**What does the trail get?** Every dispute action publishes an `audit-events` record — opened,
evidence, resolved — which closes the highest-value gap ADR-0011 left open: the trail's second
producer. The actor is a bare SHA-256 of the verified subject, computed keylessly in
dispute-service. Not the shared HMAC purpose digests, deliberately: a shared key would make every
audit row correlatable with the fraud database and put this service in the blast radius ADR-0008
confines to two services. A keyless hash is stable per subject and joinable with nothing — which
is exactly the property the trail wants, and the reason the settlement precedent of storing a raw
staff subject is not followed here. Those digests stayed in a staff-facing view; these travel to
another service's database, where a raw subject would be a customer list the first time a
customer-initiated action is recorded.

## Decision

**One open case per payment, enforced twice.** The service pre-checks and the partial unique index
refuses — partial, because resolved cases are history that must accumulate. A second concurrent
case on the same money is a second queue working the same refund. Losing the race answers 409
rather than 500, because a concurrent case is a conflict rather than a bug.

**Cases resolve exactly once, to a refund or a rejection with a reason.** A resolution without a
reason is an unaccountable refund or an unexplained refusal, so the row constraint requires one.
Evidence lands only while open: a file decided before it was complete was not a decision. There is
no reopen — a decided case is history, not a lock.

**Refunds are exactly once by claim, convergent by state.** The consumer claims the resolution id
in the same transaction that reverses, so a redelivery collides instead of refunding twice. An
already-reversed payment — redelivery past the claim, or a customer who reversed directly while
the case was open — converges to a logged no-op rather than a dead-lettered failure, because the
outcome is already correct and calling it a failure would page somebody over a refund that
happened. A payment in any other state is refused loudly: dispute cases exist only on settled
payments, so anything else is a corrupted or foreign event, and releasing a hold on a dispute's
say-so would be a different act with a different name.

**Evidence is text, capped, unattributed in the response.** Case files hold arguments, not
attachments — files are a storage, malware-scanning and retention problem this phase does not take
on. The response marks each statement mine-or-theirs rather than naming the submitter: the reader
can tell their own words from the staff's without a per-statement roster of everyone who touched
the case travelling in the response. Amounts never appear anywhere in this service: an amount
recorded here would be a second ledger disagreeing with the first during a fight about money.

**Reads split by role at the row, not the path.** Staff see the queue; a customer sees only cases
they opened, checked per row against the opener subject. "No cases" for a customer comes from the
data rather than from assuming who the caller is.

## Consequences

Transaction-service has its first consumer, which means its first dead-letter policy, its first
claim table, and a metrics-context test that grew one mock — the tax its own comment anticipated.
The consumer group is new, so deployment starts it at the tip of the topic: past resolutions do
not exist, and there is nothing to replay.

Notification knows nothing of disputes: no message tells the customer their case opened or
resolved. The case API is the reporting surface for this phase, and a customer who never polls it
learns nothing — which is a real gap in a workflow whose whole purpose is telling somebody an
answer. Wiring dispute events into notification-service is the highest-value follow-up, and it
needs no changes here: the topics and the outbox rows already exist.

Settlement learns of dispute refunds the way it learns of every refund: the reversal publishes
`transaction-reversed`, which carries as a negative line in whatever cycle is open. A refund that
crosses a closed cycle becomes a `PERIOD_ALREADY_CLOSED` finding by the existing rule — the
immutability ADR-0009 defends extends to chargebacks without a single new line of settlement code,
which is the strongest evidence in this repository that the event boundaries are drawn correctly.

## Rejected

**Synchronous reversal by dispute-service.** It needs an identity entitled to move another
person's money, which is the confused deputy the forwarded identity exists to prevent. Announcing
the decision and letting the ledger react keeps every money movement inside the service that owns
the money.

**Support-opened cases.** It breaks ownership or drills a staff override into it. The customer
opens; the agent decides; the boundary holds.

**Raw subjects in dispute audit events.** The settlement precedent stays in a staff view; these
digests travel to another database. A raw customer subject in the trail is a customer list.

**Reopening resolved cases.** A re-decidable case is a refund that can be un-refunded, and the
ledger downstream does not do that. History accumulates; locks do not persist.
