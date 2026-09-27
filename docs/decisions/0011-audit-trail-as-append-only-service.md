# ADR-0011: The audit trail as a separate append-only service

- Status: accepted, implemented in Phase 9
- Date: 2026-09-27
- Phase: 9

## Context

Phases 5 through 8 gave the platform money that moves, a score that follows it, a statement that
cannot be edited once given out, and messages that tell somebody. None of those answers the
question a regulator asks first: who did what, to which resource, with what outcome — and can the
answer be trusted. Phase 9 is the service that keeps that answer.

Three questions have to be answered, and each has a convenient wrong answer.

**Where does the trail live?** The obvious design is a table in each service: fraud-service keeps
its analyst actions, settlement-service keeps its closes and acknowledgements. It is cheaper, local,
transactional — and it records what each component chose to record, which is the failure the
architecture note on `audit-service` names outright: an audit log written by the component it
audits is complete exactly when that component is honest and available. A compromised or merely
buggy service edits the evidence of its own failure. So the trail is a separate service that
consumes `audit-events` and holds no other state. It cannot be bypassed by an outage in an
unrelated service, and a business service cannot reach its tables even if fully compromised — the
same database-per-service boundary ADR-0002 drew, now load-bearing for compliance rather than for
operability.

**What may the trail hold about people?** The convenient answer is subjects: the analyst's user id
on every action, joinable to HR, searchable by person. It would also make the trail a customer
list the moment a customer action is ever recorded, and an analyst roster today. So the trail holds
digests — the same pseudonyms fraud-service holds — never a name, email or subject. There is no
card token, device identifier, network reference or PAN column anywhere in its schema, for the same
reason there is no PAN column in card-service's: a trail that cannot hold an identifier cannot leak
one. The cost is accepted and named in the responses documentation: an auditor who needs the person
behind a staff digest follows the producing service's own audited join, and this API does not do it
for them — which is what keeps the join audited rather than convenient.

**How is append-only enforced?** The convenient answer is a service with no update endpoints, and
it is worth stating plainly why that is not enough: a convention survives until the first deadline
that argues against it, and then somebody connects to the database with a SQL client. So
append-only is four layers saying the same thing — the entity has no setters, the repository
exposes no mutating query, the controller exposes no write mapping, and a `BEFORE UPDATE OR
DELETE` trigger raises on both. The trigger is the layer that matters, because it is the one a
refactor cannot silently drop. `TRUNCATE` is not covered by the trigger; it requires
ownership-level privilege the service role does not hold, and the role grants are what make that
true.

## Decision

**One topic, one listener, open vocabulary.** Every producer writes the same `AuditEvent` contract
— action, resource, transaction, actor digest, result, metadata — onto `audit-events`, and
audit-service consumes it with a single listener. The envelope's event type is the action and the
aggregate names the resource, and the consumer cross-checks the two: they left the producer's
outbox together, so a mismatch is a foreign or corrupted event, and recording it under either name
would file the fact where no auditor will look. Actions are not enumerated in the schema, because
enumerating them would make every new producer a migration on somebody else's table. The trail
records what happened; it does not grade the vocabulary.

**Exactly once, by the same claim the other services use.** The claim is an `INSERT ... ON
CONFLICT DO NOTHING` in the same transaction that writes the row, so a redelivery collides instead
of duplicating. A trail that counts one claim as two cannot be reconciled against the service that
produced it. The save uses the managed copy Spring Data returns — Phase 8 proved what ignoring it
costs when the id is assigned in the constructor — so the lesson is now written down in the one
place the next service will look.

**Reporting is filtered reads over a stable shape.** The list endpoint narrows by action and by
when the fact happened; dedicated endpoints answer "this resource's history", "this payment's
trail" and "this request across services". Amounts never appear because producers never send them;
timestamps appear twice — `occurredAt` by the producer's clock, `receivedAt` by this service's —
and the gap between them is the consumption lag, kept measurable instead of arguable. There is no
export format in this phase: a paginated JSON API over a documented shape is the machine-readable
report, and a CSV dump would prove the serializer rather than the trail.

**Readers are supervision and nobody else.** `AUDITOR`, `COMPLIANCE_OFFICER` and
`PLATFORM_ADMIN` — the same set in the gateway's `/api/audit/**` rule and in the service, pinned
from opposite sides by the two test suites. No customer, support, analyst or operator access: a
trail the supervised can shape is a press release, and the boundary is drawn one step earlier than
necessary so it never has to be argued per endpoint. A write is not a forbidden trail action but an
unmapped route — a 405, because claiming a rule decided would imply a rule could allow it.

## Consequences

The trail is only as complete as the events producers publish, and today exactly one producer
writes — fraud-service, for analyst actions on alerts. That is a real limitation and it is recorded
rather than hidden: settlement closes, declarations and acknowledgements, and transaction
reversals, are auditable facts with no producer yet. The consumer is ready for them — one topic,
one contract, no migration — and each new producer is a small, reviewable change rather than a
redesign. Until they exist, an auditor asking "who closed this period" is answered by
settlement-service's own rows, which is exactly the self-auditing the separate service exists to
end. Closing that gap is the highest-value follow-up this phase leaves behind.

A poison event parks on `dead-letter-events` after bounded retries, like every other service. The
difference is what a hole means here: a dropped audit record is a staff action the trail cannot
account for. The dead-letter topic is therefore part of the completeness story, not an operational
detail — an auditor who counts the trail against the dead-letter queue is doing the job this
service exists for.

## Rejected

**Per-service audit tables.** Cheaper and transactional, and complete exactly when each component
is honest. A compromised service edits the evidence of its own compromise, which is the case the
trail exists for.

**Subjects and identifiers in the trail.** Searchable and joinable, and a customer list the first
time it is breached. Pseudonyms cost a join and buy the property that the join is audited.

**Convention-only append-only.** A service with no update endpoints is one refactor away from
having one. The trigger is the constraint; the missing endpoints are the documentation of it.

**Enumerated actions in the schema.** It would grade the vocabulary and couple every producer to
this service's migrations. The trail files what producers state; a new action is a new row, not a
schema change.
