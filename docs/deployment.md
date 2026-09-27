# Deployment

The Compose stack in this repository is a **development environment**. It is built to make the
platform runnable and reviewable on one machine. It is not a deployment target, and the differences
below are deliberate rather than oversights to be tidied away later.

## What the local stack is

- Sixteen long-running containers: nine application services, PostgreSQL, Redis, Kafka, Keycloak,
  Prometheus and Grafana, plus a one-shot `kafka-init` that creates topics.
- Every published port binds to `127.0.0.1`. Nothing is reachable from the network.
- Secrets are generated into a local `.env` by `scripts/bootstrap.sh`.
- Images are built locally and tagged `:local`.

```bash
make setup    # generate .env
make up       # build and start everything
make ps       # container status
make down     # stop, keep data
make clean-all # stop and delete data volumes
```

## Local versus production

| Concern | Local Compose | Production intent |
| --- | --- | --- |
| Image tag | `fintech/<service>:local`, rebuilt on change | Immutable, content-addressed, promoted unchanged between environments |
| Configuration | `.env` on the developer's disk | Per-deployment secret store; no shared file, no shared values |
| Service credentials | One `SERVICE_DB_PASSWORD` for all services | A distinct credential per service |
| Database | One PostgreSQL instance, one database per service | Same isolation model, managed instances |
| Migrations | Flyway runs inside the application on startup | Applied by a separate job, before rollout; `FLYWAY_ENABLED=false` on the service |
| Service discovery | Compose DNS | Cluster DNS |
| Traffic between services | Cleartext, with the identity HMAC-signed and shared-key verified | mTLS and authenticated callers |
| Identity | One `INTERNAL_IDENTITY_SIGNING_KEY` in `.env`, held by the gateway and all eight services | Per-service certificate, so a compromised service cannot forge identity for the others |
| Scaling | Fixed replica count of one | Scaled per service, driven by load and by which events each consumer actually needs |
| Secrets rotation | Delete `.env`, re-run `make setup`, restart | Rotated without a rebuild; long-lived and revocable |

Two rows deserve emphasis. The shared local `SERVICE_DB_PASSWORD` exists so that a developer does not
have to manage eight credentials to read a log line, and it has no production counterpart — a
deployed service gets its own. And Flyway running in-process is convenient locally and wrong in
production, because a rolling deployment would otherwise run migrations concurrently from several
replicas; `spring.flyway.enabled` is set to `false` for deployed services and migrations become a
pre-deploy job.

## Configuration and secrets

Every value that differs between environments arrives through the environment. No committed file
contains a usable credential, and no application configuration file contains a default password —
a service missing `SERVICE_DB_PASSWORD` fails at startup rather than falling back to a known value.

`scripts/check-secret-isolation.sh` asserts this against the rendered Compose configuration and is
part of `make check`. It catches the class of mistake that is easy to make and hard to see: adding a
convenient shared variable to a service that has no business holding it.

### One exception, and it is deliberate

`INTERNAL_IDENTITY_SIGNING_KEY` is the single value the gateway and all eight services share. It is
the one shared secret in the platform, and it is shared because the alternative is worse: a service
that cannot verify the gateway's identity either trusts the headers unverified or refuses every
request. The property resolves to empty by default, and an empty key means the verification filter is
not registered at all, so a service with no key protects every endpoint rather than trusting them.

Its two weaknesses are both real and neither is mitigated here. Any one service that is compromised
can forge an identity for any user, and rotating the key is a coordinated restart of nine services
rather than a gateway-only change. Phase 15 replaces it with a per-service certificate; until then
this is a known, documented gap, not an oversight.

### The PII master key is not shared, and has no default

`CUSTOMER_PII_MASTER_KEY` is held by customer-service alone. Unlike `INTERNAL_IDENTITY_SIGNING_KEY`,
it is not a coordination problem: no other service needs it, and one fewer holder is one fewer way to
lose it.

It is a base64-encoded 32-byte key, written by `scripts/bootstrap.sh` into `.env` alongside the other
generated secrets. Unlike the signing key, the Compose entry has **no default and no empty-string
fallback**, and `PiiProperties` decodes and length-checks it in its own constructor. Both halves are
deliberate:

- Compose refuses to start the container with the variable unset, naming `bootstrap.sh` in the error.
- A base64 value of the wrong length, or a key that is not base64 at all, fails at startup rather than
  at the first read of a customer's name.

An empty default would have satisfied the Compose requirement while leaving the service unable to
decrypt anything, which is the same fail-open shape the signing-key condition exists to prevent.

### The card tokenisation key is also unshared and also has no default

`CARD_TOKENISATION_KEY` is held by card-service alone and follows the same discipline as the PII
master key: base64, 32 bytes, generated by `scripts/bootstrap.sh`, **no default in Compose**. The
reasoning is the same shape and it is worth stating because the consequence is different — a default
here would not make card-service unable to start, it would make every token in the `cards` table
derivable by anyone who has read this repository. See
[ADR-0006](decisions/0006-card-tokenisation.md).

The other two card settings are ordinary operational configuration and do have defaults:
`CARD_VALIDITY_MONTHS` (36) and `CARD_MAX_PER_CUSTOMER` (5). `card-service` validates both at
startup, so a zero or absurd value fails the deploy rather than minting cards that are expired on
arrival.

`CUSTOMER_SERVICE_URL` points card-service at customer-service for the eligibility check, and
`CUSTOMER_SERVICE_TIMEOUT_MS` (2000) bounds it. The timeout is the important half: a connect
timeout alone only covers a peer that is not listening, and without a read timeout one stalled
dependency exhausts card-service's request threads and turns a slow dependency into an unavailable
card API.

Losing the key is unrecoverable: every stored value becomes permanently unreadable, and no rotation
path avoids needing it. That is a consequence of the design and is recorded in
[ADR-0005](decisions/0005-pii-encrypted-at-rest.md).

### The subject digest key is held by exactly two services, on purpose

`SUBJECT_DIGEST_KEY` is base64, 32 bytes, generated by `scripts/bootstrap.sh`, with **no default in
Compose**. It is held by transaction-service and fraud-service, and by no other service.

transaction-service stores *no* subject identifier at all, only `owner_ref` — a keyed HMAC of the
verified subject, used as the `owner_subject_digest` half of the idempotency key's unique constraint
and as the owner column on `transactions`, `ledger_accounts` and `idempotency_keys`. A database dump
of the transaction schema yields account ownership as digests and nothing else.

Phase 6 added the second holder. transaction-service sends purpose-separated HMACs of the card, the
device, the network and the customer in `transaction-created`, and fraud-service stores them and
compares them over time. Those two services share no rows and have no cross-database join, so the
digest is the *only* thing that lets the engine recognise a customer it has seen before. Two
databases, one key: that is the join, and it is the reason exactly two services hold the value
rather than one.

The consequences are accepted rather than overlooked. Holding this key plus either database is
enough to confirm a *guessed* subject, though not to recover one, so it is the key whose compromise
would be felt as a correlation problem rather than a disclosure one. It must still be distinct from
every other key in the platform: reusing the identity-signing or PII key would make the transaction
and fraud schemas joinable to the credential and PII tables, which is the separation the
per-service keys exist to create. `scripts/check-secret-isolation.sh` asserts both the no-default
property and the two-holder set.

Both services refuse to start if the key decodes to fewer than 32 bytes — `FraudDigestKey` throws
rather than run under a key that reduces the HMAC's strength — so a truncated or placeholder value
fails the deploy instead of quietly weakening every digest in the platform. An older `.env`
predating this variable is backfilled by `scripts/bootstrap.sh`; without that, Compose fails
interpolation with `required variable SUBJECT_DIGEST_KEY is missing a value`.

The gateway forwards `/api/v1/transactions` and `/api/v1/accounts` to this service via
`TRANSACTION_SERVICE_URL` (the service name and internal port, not the published host port). The
`Idempotency-Key` header both money-moving endpoints require is forwarded untouched — the gateway
applies no default filters — and `scripts/verify-payment-lifecycle.sh` asserts that end to end, since
a stripped header would surface only as a `400` on the first keyed request.

## Identity provider

Keycloak runs in the Compose stack with a realm imported from
`infrastructure/keycloak/realm/fintech-realm.dev.json` and its hostname pinned to
`http://localhost:8180`, so the issuer is identical whether a client reaches it as `localhost` or
`127.0.0.1`. A token whose issuer disagrees with the gateway's configured issuer is rejected, and
that is the first thing to check when the gateway and Keycloak are on different hosts.

The realm is a development artefact. It contains six users with published passwords, and it exists so
that the authorisation matrix can be exercised end to end without provisioning anything by hand. It
has no production counterpart: production expects an external IdP, and the gateway is configured by
issuer, audience and JWKS URI rather than by anything Keycloak-specific.

Phase 6 added the `FRAUD_ANALYST` realm role and `analyst@fintech.test`, who works the alert queue.
It is a separate role from `COMPLIANCE_OFFICER` rather than an extension of it, so "work the queue"
and "supervise the queue" are separately grantable; `AUDITOR` and `COMPLIANCE_OFFICER` read the fraud
surface and cannot act on it. `./scripts/get-token.sh analyst` prints a token for it. Note that
**adding a user or role to the realm file only affects a fresh import** — `bootstrap.sh` skips a realm
that already exists, so an existing local realm needs the role added through the admin console or the
admin API. `verify-fraud-lifecycle.sh` fails with that instruction rather than a confusing 403.

## Fraud operations

Fraud-service is the one service that is deployed without being on anyone's critical path, and that is
a deliberate property rather than an accident of wiring: no payment waits for it. Its outage shows up
as *no scoring*, not as *no payments*, and the two must be alerted on differently.

- **Scoring stopped.** The consumer is not processing `transaction-created`, or the relay is not
  publishing. Every payment is still authorised, so the symptom is a dashboard that quietly stops
  moving. This is the failure mode the architecture accepts, and it is the reason the check script
  polls for a decision rather than assuming one.
- **Velocity degraded.** `REDIS_HOST` unreachable. Velocity rules are skipped, not treated as empty
  history — the alternative disables the only rules with memory exactly when a fraud burst would be
  arriving. Decisions carry `velocityAvailable=false` while this holds, so the gap is visible in the
  data and not only in a log line.
- **The outbox is backing up.** `outbox_events` grows when Kafka is unreachable. The relay retries
  with a backoff and gives up after a bounded number of attempts, and exhausted rows are visible for
  replay. A growing table is the signal that decisions exist but were not published — which matters,
  because a consumer of `fraud-analysis-completed` is entitled to them.

Two switches are worth knowing before an incident, and both default to on:

| Variable | Effect when false |
| --- | --- |
| `app.outbox.relay-enabled` | Stops the scheduler entirely: the outbox relay and the retention jobs both stop. |
| `app.background-jobs-enabled` | Stops the retention jobs only; the relay keeps publishing. |

Turning the relay off also stops pruning, which is the more common surprise. A separate switch for
each job is the obvious design and is not this one, because a `@EnableScheduling` flag is a
class-level decision: there is no way to keep the scheduler running for one job and stop it for
another without two schedulers. Retention is bounded and cheap, so it runs with everything else; the
outbox relay is the switch an operator reaches for when Kafka is misbehaving, and it is the one that
takes pruning with it.

`infrastructure/keycloak/realm/README.md` documents the two import settings that are easy to get
wrong — an explicit default-scope list including `roles`, and `fullScopeAllowed` — either of which
produces tokens that verify correctly and carry no roles at all.

## Health checks and rollout

Each service image runs as a non-root user and ships a `curl`-based healthcheck against its own
`/actuator/health`. The gateway's health endpoint additionally reflects Redis, because a gateway that
cannot reach Redis cannot serve traffic.

A deployment should become ready only when the service reports healthy, and should be removed from
traffic when it reports unhealthy. Compose expresses this with `depends_on: condition:
service_healthy`; a production orchestrator expresses it with readiness and liveness probes on the
same endpoint.

## Observability

Prometheus scrapes all nine services plus itself; Grafana provisions a datasource and a dashboard
from files in `infrastructure/grafana/`. Alert rules live in
`infrastructure/prometheus/alerts/platform-alerts.yml` and are validated with `promtool`:

```bash
make check-prometheus
```

The rules are written against series that Phase 1 does not yet emit — transaction correctness and
event-streaming rules describe behaviour that arrives in later phases. They are loaded and valid, and
they are expected to stay in a non-firing state until the services that produce those series exist.

## Deliberately not here yet

- Kubernetes manifests or Helm charts.
- CI/CD pipelines.
- TLS termination, ingress certificates, and network policies.
- Backups, restore procedures, and disaster recovery.

Phases 2 through 16 add these in order; the architecture rationale is in
[architecture.md](architecture.md) and the sequencing in the README.
