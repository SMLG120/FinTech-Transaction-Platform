# Architecture

This document explains the shape of the platform and, more importantly, why it is that shape. A
diagram can be copied from a dozen blog posts; the reasoning is what has to survive the next
quarter of changes.

## The decision that shapes everything else

**Synchronous when the caller needs an answer now; asynchronous for everything else.**

A card authorisation is a request-response interaction with a human waiting: the answer either
exists or it does not. Modelling that as an event stream would mean a customer staring at a spinner
while a topic round trip completes, and it would remove the ability to say "declined, insufficient
funds". Synchronous is correct there.

Fraud scoring, notification delivery, audit recording and dispute opening are not. They either
should not be able to fail the payment, or they happen after the customer already has an answer.
Making them events means:

- a slow fraud model degrades the decision instead of failing the payment,
- the audit trail cannot be bypassed by an outage in an unrelated service,
- notification and dispute services can be deployed, restarted and scaled without touching the
  payment path.

The cost is eventual consistency, and it is paid deliberately: a fraudulent transaction is
authorised first and reversed later, rather than being declined by a model that was not consulted
in time. For a card-present payment that is the correct trade. It is *not* obviously correct for
every flow, so this is a per-flow decision, not a global rule — see
[ADR-0001](decisions/0001-modular-monorepo.md) for where the line sits.

## Service boundaries

Boundaries are drawn around **data ownership**, not around functionality. A service owns its
database and nothing else may write to it. Everything else follows from that:

- Two services never share a table, so a schema change in one cannot break another.
- Two services never join across databases, so no query can quietly depend on data from two owners.
- A service that is down degrades its callers rather than corrupting them.

The failure of the opposite design is well known and worth stating: a "customer accounts" service
that also reads transaction rows has not separated anything, it has only added a network hop. The
first time two services need the same join, the boundary is renegotiated under deadline pressure
and the data ownership goes with it.

### Why nine services and not fewer

The count is a consequence of the boundaries, not a target. Each of the nine exists because its
data has a different owner, a different change cadence, or a different scaling profile:

| Service | Owns | Scales with | Changes because |
| --- | --- | --- | --- |
| api-gateway | nothing | inbound request volume | routing and security policy |
| auth-service | credentials, sessions | login traffic | Keycloak and token policy |
| customer-service | profiles, KYC state | customer traffic | onboarding and compliance rules |
| card-service | cards, tokens | card operations | issuer and network rules |
| transaction-service | transactions, ledger | payment volume | ledger and settlement logic |
| fraud-service | rules, decisions, alert queue | scoring throughput | rule and threshold policy |
| notification-service | templates, delivery state | outbound message volume | channel providers |
| audit-service | the audit trail | event volume | regulatory obligation |
| dispute-service | disputes, evidence | dispute volume | chargeback rules |

A note on `audit-service`: in many designs the audit trail is a table in each service. Here it is a
separate service that consumes events, because a regulatory audit trail must be complete even when
a business service is compromised. An audit log written by the component it audits records what that
component chose to record.

### Why the gateway is the only place JWTs are verified

Verifying a token in every service would mean nine places to get issuer, audience and clock skew
right, and one of them would be wrong. The gateway verifies once and passes the result downstream.
Downstream services still need to know *who* the caller is, so the gateway forwards validated
identity in signed internal headers, and from Phase 15 those headers are only accepted on a
connection authenticated by mTLS. A public client cannot forge them because it never reaches a
service directly.

The trade-off is that the gateway becomes a availability dependency and a bottleneck. That is
accepted: it is stateless, it is the cheapest thing to scale horizontally, and a payment platform
without an ingress is not usable.

## Shared code, and where the line is

`platform/platform-common` holds what genuinely cannot be avoided: the error contract, the event
envelope, the correlation id, pagination, and the metric tags every service needs so a
cross-service query is possible. `platform/platform-common-web` holds servlet-only web behaviour,
and is deliberately *not* a dependency of the reactive gateway.

The rule for adding to `platform/`: **if fewer than two services need it, it does not go there.**
The cost of a premature shared module is that changing it means coordinating a release across every
consumer, which is the coupling a shared library is supposed to remove. A single service's
utilities belong to that service until a second caller actually appears.

## Data

One PostgreSQL database and one dedicated role per service, provisioned by
`infrastructure/docker/postgres/init/01-create-databases.sh`. See
[ADR-0002](decisions/0002-database-per-service.md) for why the roles are per-service even though
the local password is shared.

Card data never reaches the platform's databases. card-service mints a synthetic PAN, exchanges it
for an HMAC token in the same method call, and stores only the token, so a database disclosure does
not become a card breach. The `cards` table has no PAN, CVV, track-data or PIN column at all, which
is the control rather than a convention: a schema that cannot hold a number has no future migration
that will eventually put one in it.

The token swap is deliberately **inside** card-service and not at the gateway. A gateway that tokenised
would have to hold the tokenisation key, and a compromise of the one component that fronts every
service would then yield the ability to test guessed card numbers against every stored token in the
platform. Keeping it in card-service means the key is held by one service, which has one database.

This is the single most consequential security decision in the system and it constrains card-service's
schema permanently. It is in [ADR-0006](decisions/0006-card-tokenisation.md), and it was recorded
before the first card column was written rather than retrofitted once a real card integration
appeared and made it inconvenient.

Money is recorded as **integer minor units**, never as a decimal. Floating point cannot represent
`0.10` exactly, and a ledger that cannot represent a cent correctly is not a ledger. Currency is an
explicit field on every amount, because a payment crosses currencies and inferring one from the
account is how an amount gets converted by accident.

## Messaging

Topics are named in the past tense and carry immutable facts, never commands:
`transaction-created` states that something exists; it does not ask anyone to create it. This is
what makes a topic safe to replay and safe for a second consumer to attach to later.

Ordering is achieved by partitioning on `aggregateId`, so every event for one transaction lands on
one partition and is consumed in publish order with no distributed coordination. The cost is that
partition count caps consumer parallelism, so it is a deliberate, reviewable number
(`KafkaTopics.DEFAULT_PARTITIONS`) rather than whatever the broker default happens to be.

`dead-letter-events` exists because a consumer that keeps failing on a poison message must neither
block its partition forever nor silently drop the message. After a bounded number of attempts the
record is republished there with the reason attached.

The catalogue exists twice on purpose: as constants in `KafkaTopics.ALL` and as
`infrastructure/kafka/topics.txt`. `TopicCatalogueTest` compares them, because a topic present in
one and absent from the other is neither a compile error nor a startup error — it is discovered
when the first event is published somewhere nobody is listening.

### Why fraud is not on the payment's critical path

Every other service in the diagram is an event consumer, and fraud could have been the same. It is
worth being explicit about why it is not also a synchronous dependency of a payment.

A payment must be authorised by rules that are local, fast and always available. If the fraud engine
were in that path, three things would follow, and all three are unacceptable in a platform whose
whole purpose is moving money: every payment's latency would include a rules evaluation, a Redis
round trip and a database write; the engine's availability would become the platform's
availability; and there would be a latency budget on the one component whose correct behaviour is to
do more work when it is worried.

So a payment is authorised on transaction-service's own rules and scored afterwards. The engine
consumes `transaction-created`, records one decision per payment, and publishes
`fraud-analysis-completed`. The cost is real and is stated in
[ADR-0008](decisions/0008-asynchronous-advisory-fraud-scoring.md): a payment can be authorised and
then declined later.

What makes that cost acceptable is that the late decision has somewhere to go. It raises an alert in
a claimable analyst queue, it is visible on a dashboard with its window attached, and every decision
carries a `stepDownRecommended` flag that is recorded but not yet enforced. Step-down enforcement is
a switch on data that is already being written, not a redesign — and the switch is off while the
alert queue is the enforcement point.

### What the engine is allowed to know

The engine's value is recognising a card, a device and a customer it has seen before, and
recognising means comparing. It does not get the identifiers it compares.

transaction-service computes purpose-separated HMACs — a card digest, a device digest, a network
digest and a customer digest, each under a different purpose prefix so that holding one does not let
you correlate it with another — and puts them in the event. fraud-service stores the digests and has
no column for what they stand for. A PAN, card token, device identifier or IP address never enters
the service, so its database is not a place that a breach turns into a card-fingerprint breach.

The cost is accepted and named: a shared key is what makes a digest joinable across two databases
that share no rows, and joinability is the engine's entire function. See
[security.md](security.md) for the key's blast radius and the phase that breaks the join.

## Consistency

The transaction path uses a **transactional outbox**. Writing a row and publishing an event in one
database transaction is not possible without two-phase commit; a two-phase commit across Postgres
and Kafka is worse than either alternative. So the state change and the event are written in the
same transaction, and a separate publisher drains the outbox. The event cannot be lost, and it may
be published twice, which is why every event carries an `eventId` and every consumer is idempotent.

Idempotency keys are checked in the database under a unique constraint, not in Redis. Redis is the
fast path that avoids the work; the database constraint is what actually guarantees correctness. A
cache that is briefly unavailable must slow the endpoint down, not let a duplicate payment through.

## Reliability

Synchronous calls between services use timeouts that are shorter than the caller's own timeout, and
retries only for idempotent operations. A retry that is not idempotent is a duplicated payment.
Circuit breakers exist so that a slow dependency fails fast and predictably instead of exhausting
the caller's thread pool and taking the caller down with it.

The transaction service is the one place where being unavailable is unacceptable, so it holds no
in-memory state that a restart would lose. Redis is a cache and an idempotency fast path, never the
system of record.

## Observability

Every metric carries `application`, `environment` and `version` tags, applied through a
`MeterFilter` rather than at each call site so it cannot be forgotten. Health and Prometheus are the
only actuator endpoints exposed, and both are reachable without a token because Prometheus and the
container health check cannot present one; they are network-restricted instead. `env`, `configprops`,
`heapdump` and `loggers` stay closed — they disclose configuration and secrets.

Prometheus scrape configuration is a static list, matching the static compose file. Native
service discovery would require mounting the Docker socket into the Prometheus container, which is
a privilege escalation for a development tool. Phase 15 replaces it with Kubernetes service
discovery.

Alerts are derived from failure modes a payment platform actually has — consumer lag, connection
pool exhaustion, growing lag, 5xx ratios — rather than from generic CPU and memory thresholds. The
Postgres and Redis exporter jobs are not declared yet, deliberately: a scrape target that is
permanently down is an alert that always fires, and always-firing alerts are how a monitoring
system gets muted.

## What is deliberately deferred

Being explicit about what is missing matters more than listing what is present:

- **No business endpoints.** Phase 1 services start, serve health and metrics, and nothing else.
- **No authentication.** The gateway is fail-closed: it serves its operational endpoints and
  returns 401 with the platform error contract for everything else. Phase 2 adds JWT verification.
- **No Keycloak realm.** The server runs; the realm, clients, roles and mappers are Phase 2.
- **No schema migrations.** Only customer-service has any, from Phase 3. The other seven databases
  are created empty and are validated against an empty schema.
- **No TLS, no network policy, no secrets manager.** Phase 15.
- **No frontend.** Phase 11.

### Added in Phase 3, and still open

- **No events are published.** `EventEnvelope` and `KafkaTopics` exist and `TopicCatalogueTest` keeps
  the catalogue honest, but no service produces anything, so the transactional outbox described
  under [Consistency](#consistency) is still unbuilt. Phase 3 was completed without it, and the
  decision is recorded here rather than left as an omission: the customer path's event requirements
  are genuinely unsettled, not merely unstarted.

  What is not settled, and should be before any event is emitted:

  - **Does the customer path need the outbox at all?** The outbox is specified for the *transaction*
    path. Registration, erasure and a KYC outcome are not part of a money movement, and paying for
    a publisher, a drain loop and a background re-encryption job to announce them may not be worth it.
  - **A lost erasure event is worse than a lost transaction event.** `audit-service` consumes
    `audit-events` and a regulatory trail has to be complete. If customer erasure emits an event,
    best-effort publishing is not good enough, which argues for the outbox for *that* event at least.
  - **A KYC decision is a compliance decision.** If it is announced to a second service, a
    subscriber may act on it, and the subscriber then needs idempotency on `eventId` and the
    same version byte the ciphertext uses.

  Publishing straight to Kafka before this is settled would contradict the outbox decision above, so
  it is left undone on purpose.

### A bug this phase found by running it

- **`baseline-on-migrate` was suppressing the first migration in all eight services.** Set to `true`
  with Flyway's default baseline version of 1, it recorded a baseline *at* version 1 on the empty
  database, which marks `V1` as already applied, so the migration never ran. It only became visible
  by running the service: the tables had to be created by hand to get past Hibernate's schema
  validation, which is not a symptom anyone would trace back to a Flyway setting.

  It is now `false` everywhere, with the reasoning in each `application.yml`. The lesson is that
  `baseline-on-migrate` is only correct for a database that *already has* a schema this repository
  does not know about, and every database here is created empty by the Postgres init script. It is
  the kind of setting that looks like a safety net and is a loaded gun.
- **No re-encryption job.** Rotation is a deploy that changes the key version; records written under
  the previous version stop being readable until they are rewritten. The background re-encryption
  that closes that window is not built, so a rotation is currently only safe once every row has been
  touched by an ordinary write.
- **No PII access logging beyond masking.** Reads are masked by the service, but there is no record of
  *who* read *which* customer. For a support caller reading a profile, that is the access log an
  auditor would ask for, and it does not exist yet.
