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

### Why ten services and not fewer

The count is a consequence of the boundaries, not a target. Each of the ten exists because its
data has a different owner, a different change cadence, or a different scaling profile:

| Service | Owns | Scales with | Changes because |
| --- | --- | --- | --- |
| api-gateway | nothing | inbound request volume | routing and security policy |
| auth-service | credentials, sessions | login traffic | Keycloak and token policy |
| customer-service | profiles, KYC state | customer traffic | onboarding and compliance rules |
| card-service | cards, tokens | card operations | issuer and network rules |
| transaction-service | transactions, ledger | payment volume | ledger and payment rules |
| settlement-service | periods, statement lines, findings | volume of money given out | bank cycle boundaries and reconciliation policy |
| fraud-service | rules, decisions, alert queue | scoring throughput | rule and threshold policy |
| notification-service | templates, delivery state | outbound message volume | channel providers |
| audit-service | the audit trail | event volume | regulatory obligation |
| dispute-service | disputes, evidence | dispute volume | chargeback rules |

The row worth pausing on is `settlement-service`, because it is the one whose data could have been
added to `transaction-service` with a single table and would have looked like the cheaper choice. It
is a separate service for one reason: **a statement that has been given to a merchant cannot be
edited, and that makes it a different kind of data from a transaction.** A transaction is a record
of an event and is correct the moment it is written. A statement is an account of a period, and
the only thing that makes it useful is that everybody was given the same one — which is a property
a ledger that is still accepting writes cannot offer. See
[ADR-0009](decisions/0009-settlement-cycles-and-reconciliation.md).

A note on `audit-service`: in many designs the audit trail is a table in each service. Here it is a
separate service that consumes events, because a regulatory audit trail must be complete even when
a business service is compromised. An audit log written by the component it audits records what that
component chose to record.

### Why the gateway is the only place JWTs are verified

Verifying a token in every service would mean ten places to get issuer, audience and clock skew
right, and one of them would be wrong. The gateway verifies once and passes the result downstream.
Downstream services still need to know *who* the caller is, so the gateway forwards validated
identity in signed internal headers. The planned end state accepts those headers only on a connection
authenticated by mTLS, but the Java migration is not implemented: today the gateway and services use
the shared HMAC key, and network reachability is an additional boundary rather than mutual
authentication.

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

### Settlement learns about money through events, not through the ledger

settlement-service consumes `transaction-settled` and `transaction-reversed` and never reads
transaction-service's tables. That is not a stylistic preference, it is what makes a settlement bug
unable to become a payments outage: the worst a wrong statement can do is be wrong, and the worst a
join into somebody else's ledger can do is take a payment path down with it.

It also means the two services can disagree, and the design assumes they will. A statement is
reconciled against an **actual declared from outside the platform** — a bank or scheme clearing
figure — rather than against the ledger that produced it, because a system that reconciles its own
arithmetic against itself has not checked anything. The gap between the two is the product: a
mismatch is a first-class, acknowledged and resolved record rather than an error, because the money
has already moved and the only remaining job is to account for it honestly.

**A period is immutable once closed**, and the reason is not conservatism. A statement is given to a
merchant; a statement that later changes is a statement that was wrong when it was read, and nobody
can tell which. So a movement arriving for a period that has already been given out does not mutate
it and does not get thrown away either: it becomes a finding naming the period it settled in. A
payment that settles in the same instant its period closes will miss that period — a batch boundary
is a real boundary — and what is not acceptable is missing it *quietly*.

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

### Notification turns consumed facts into messages, exactly once each

notification-service consumes the transaction, fraud and settlement topics and keeps its own
delivery log — one row per fact worth telling somebody about, claimed with an insert so a
redelivered event cannot tell the customer twice. A send failure never fails the event: the event
was fine and the channel was not, so the row stays `FAILED` with a next attempt and the scheduler
re-sends only the send. The sender is simulated; the interface is the seam a real provider plugs
into. There is no customer route to the log, because this service correlates on a digest it cannot
map back to a token — the absence of the route is the control. See
[ADR-0010](decisions/0010-notification-delivery-and-retry.md).

### The audit trail is a separate service that cannot rewrite history

audit-service consumes `audit-events` and keeps one row per auditable fact — who did what to which
resource, with what outcome. It is separate because an audit log written by the component it audits
records what that component chose to record. The table is append-only by database trigger rather
than by convention, the API has no write endpoint, and the rows hold digests rather than subjects:
a trail that cannot hold an identifier cannot leak one. Reporting is filtered reads over a stable
shape — by action, by resource, by payment, by request. See
[ADR-0011](decisions/0011-audit-trail-as-append-only-service.md).

### Disputes are decided by support and refunded by the ledger

dispute-service owns the chargeback case — open, plead, decide — and announces the decision on
`dispute-status-changed`. Transaction-service consumes resolutions and reverses out of its own
stored owner digest, which never leaves that service: the direction of the dependency is what keeps
a compromised dispute-service from becoming a reversal oracle. Only the customer opens, only on
their own settled payment verified synchronously under their forwarded identity; only staff
decide. See [ADR-0012](decisions/0012-disputes-decided-by-support-refunded-by-ledger.md).

### The browser is a static client with no backend of its own

The customer UI is files on nginx, not a service: no sessions, no templates, no validations of its
own. The browser talks to the gateway and Keycloak directly, the token lives in memory, and every
money-moving click carries a fresh idempotency key. Customer journeys only — staff views would be
a second authorization model to keep in step with the first. See
[ADR-0013](decisions/0013-static-customer-ui.md).

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
`MeterFilter` rather than at each call site so it cannot be forgotten. The services expose health,
info, Prometheus, and metrics endpoints; health/info/Prometheus skip internal identity verification
for probes and scrapes, while metrics remains protected. The gateway permits only health and
Prometheus without a JWT. Endpoints such as `env`, `configprops`, `heapdump`, and `loggers` are not
exposed because they disclose configuration and secrets.

Prometheus scrape configuration is a static list, matching the static compose file. Native
service discovery would require mounting the Docker socket into the Prometheus container, which is
a privilege escalation for a development tool. The Helm chart does not deploy Prometheus or a
Kubernetes service-discovery configuration; production monitoring must supply and validate that
configuration separately.

Alerts are derived from failure modes a payment platform actually has — consumer lag, connection
pool exhaustion, growing lag, 5xx ratios — rather than from generic CPU and memory thresholds. The
Postgres and Redis exporter jobs are not declared yet, deliberately: a scrape target that is
permanently down is an alert that always fires, and always-firing alerts are how a monitoring
system gets muted.

## Deliberately deferred and production blockers

The business APIs, authentication, database migrations, Kafka outboxes/consumers, and both browser
clients exist. Their existence does not make the current Compose stack or Helm chart a production
deployment. Work still required before production includes:

- **Real payment, identity verification, and notification providers.** All are simulated; no live
  money or identity decisions are made.
- **Service-to-service mTLS.** The gateway and services still share an HMAC signing key. The PKI
  chart and migration sequence are design/provisioning work only; Java does not yet verify client
  certificates. See [ADR-0016](decisions/0016-mtls-migration-and-pki.md).
- **Transport security and broker access control.** Compose uses plaintext; Kafka has no
  authentication or topic ACLs. Production TLS, Kafka ACLs, and datastore transport security must be
  configured and exercised.
- **A complete production deployment path.** The Helm chart expects PostgreSQL, Redis, Kafka, an
  identity provider, and monitoring to exist already. It has no ingress, frontend deployment,
  external-secret integration, or pre-deploy migration job. Its native Kubernetes Secrets are
  rendered from Helm values and stored in Helm release state; protect that state or replace it with
  an external secret reference design.
- **Browser OIDC in production.** Both UIs use Authorization Code + PKCE against `fintech-web`;
  production still needs its own redirect URIs and IdP, and the React dashboard has no release image.
- **PII operations.** There is no key re-encryption job and no audit record for who read a customer
  profile. The PII master key remains a high-impact secret.
- **Customer-domain event policy.** Payment, fraud, dispute, and settlement flows publish events via
  outboxes. Customer registration, KYC, and erasure do not currently emit domain events; decide and
  implement the audit/erasure requirements before adding consumers.
- **Disaster recovery.** The local backup helper is not an encrypted, point-in-time production backup
  system. Configure managed backups, retention, recovery objectives, and tested restore procedures.

### Historical migration issue

An earlier version set `baseline-on-migrate: true` for empty databases. Flyway then recorded a
baseline at version 1, causing the first migration to be skipped. It is now `false` for every
database-backed service, because databases created by the PostgreSQL init script start empty. Keep
that invariant: baseline-on-migrate is only appropriate when deliberately adopting an existing schema.
