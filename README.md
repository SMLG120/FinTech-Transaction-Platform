# Secure FinTech Transaction Platform

An event-driven payment platform built as a set of independently deployable Spring Boot services.

This repository is being built in sixteen phases. **Phases 1, 2 and 3 are complete**: the module structure,
nine service skeletons, local infrastructure, observability wiring and build pipeline are in place,
and customer-service now serves the customer profile and identity-check API with its personal data
encrypted at rest. What is not built yet is stated in the phase list below rather than left to be
discovered.

> **No real payment network, no real card numbers, no real money.** Every card number, token and
> identity in this repository is synthetic test data. Card data is tokenised at the edge and the
> platform never stores a PAN or a CVV. See [docs/security.md](docs/security.md).

---

## Quick start

```bash
# 1. Check the toolchain, and follow the remedy it prints if the JDK is too old.
make doctor

# 2. Generate .env with random secrets. Required before anything starts.
make setup

# 3. Start everything.
make up

# 4. Confirm it is healthy.
make ps
```

| What | Where |
| --- | --- |
| Gateway | http://localhost:8080 |
| Kafka UI | http://localhost:8081 |
| Keycloak | http://localhost:8180 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 |
| Individual services | http://localhost:9081–9088 |

Credentials for Keycloak, Grafana, Kafka UI and Postgres are the randomly generated values in
`.env`. That file is gitignored and readable only by you.

Stop the stack with `make down`. `make clean-all` also deletes the database volumes.

### Calling the API as a local user

Phase 2 gave the gateway real authentication, so there is a token to get. The realm ships five
synthetic users, one per role:

```bash
./scripts/get-token.sh customer      # or: agent, auditor, compliance, admin
./scripts/get-token.sh customer --decode   # just the claims, for reading
```

```bash
TOKEN=$(./scripts/get-token.sh customer)

# Authorised, so the gateway forwards it. 404 rather than 200 because the route does not exist yet.
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/accounts

# Wrong role: the gateway refuses before the request reaches a service.
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/admin/users
# HTTP/1.1 403
# {"error":{"code":"INSUFFICIENT_ROLE","message":"..."}}
```

The five users, and the paths each may reach:

| User | Role | May reach |
| --- | --- | --- |
| `customer@fintech.test` | `CUSTOMER` | `/api/**` |
| `agent@fintech.test` | `SUPPORT_AGENT` | `/api/**`, `/api/support/**` |
| `auditor@fintech.test` | `AUDITOR` | `/api/audit/**` |
| `compliance@fintech.test` | `COMPLIANCE_OFFICER` | `/api/audit/**`, `/api/compliance/**` |
| `admin@fintech.test` | `PLATFORM_ADMIN` | everything under `/api/**` |

Their passwords are in `infrastructure/keycloak/realm/README.md` and exist only in the local
development realm. Do not reuse them anywhere.

Reaching `/api/**` is necessary but not sufficient. The gateway answers the coarse question "is this
caller allowed in at all"; customer-service answers the real one, "is this caller *this* customer", and
masks the personal fields for everyone else. A support agent gets a 200 from the gateway and a masked
profile.

### Registering a customer and running an identity check

Every value below is synthetic. The subject comes from the token, so there is no field to set it with.

```bash
TOKEN=$(./scripts/get-token.sh customer)

curl -i -X POST http://localhost:8080/api/v1/customers \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"fullName":"Dana Okonkwo","dateOfBirth":"1991-04-17","nationality":"GB",
       "email":"dana@example.test","phone":"+447700900123",
       "address":{"line1":"12 Alder Way","city":"Manchester","postalCode":"M1 4BT","country":"GB"}}'
# HTTP/1.1 201 Created, Location: /api/v1/customers/1b1f...

# Your own profile, unmasked.
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/customers/me

# Submit a document. The provider is the synthetic one, so a British applicant with a current
# document and a matching name is approved and a mismatch is rejected.
curl -i -X POST http://localhost:8080/api/v1/customers/1b1f.../kyc \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"documentReference":"SYNTH-0001","printedName":"Dana Okonkwo",
       "expiryDate":"2031-04-17","issuingCountry":"GB","nationality":"GB"}'

# A different user's profile: masked, even for a support agent.
SUPPORT_TOKEN=$(./scripts/get-token.sh agent)
curl -s -H "Authorization: Bearer $SUPPORT_TOKEN" http://localhost:8080/api/v1/customers/1b1f...
# {"id":"...","fullName":"D*** O******","birthYear":"1991","email":"d***@example.test",...}

# Erasure. Irreversible: the ciphertext is nulled and the email/phone lookup keys are dropped, so
# the profile can no longer be found by the email that was given. HTTP 204.
curl -i -X DELETE -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/customers/me

# Asking again is 409, and reading the profile afterwards is 410 rather than 404, so a customer
# exercising a deletion right gets a definite answer instead of an ambiguous "not found".
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/customers/me
# HTTP/1.1 410 Gone
```

The data in PostgreSQL is ciphertext. See
[ADR-0005](docs/decisions/0005-pii-encrypted-at-rest.md).

### Issuing a card

```
# A cardholder with an approved identity check issues their own card. The response is the only
# time the number exists outside the issuing process.
curl -i -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"customerId":"'"$CUSTOMER_ID"'","brand":"DEBIT"}' http://localhost:8080/api/v1/cards
# HTTP/1.1 201 Created
# {"cardNumber":"9240 7425 2668 2412","card":{"id":"...","last4":"2412","status":"ACTIVE",...}}

curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/cards
curl -i -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/cards/$CARD_ID/freeze
curl -i -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/cards/$CARD_ID/unfreeze
curl -i -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/cards/$CARD_ID/lost
curl -i -X DELETE -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/cards/$CARD_ID
```

The `cards` table holds a token, a last-four and a lifecycle. There is no column a number could be
stored in, encrypted or otherwise, and the number is not retrievable afterwards. See
[ADR-0006](docs/decisions/0006-card-tokenisation.md).

To run all of the above as an assertion rather than by hand, plus the refusals — reading somebody
else's card, issuing against somebody else's customer, issuing to a customer who has failed identity
checks:

```
./scripts/verify-card-lifecycle.sh
```

It provisions its own throwaway Keycloak identities, walks issue → list → freeze → unfreeze → lost →
cancel, asserts the number appears in exactly one response, and refuses to issue on a cardholder who
is not the caller. It needs the stack up (`make up`). The identities it creates are explained in
[infrastructure/keycloak/realm/README.md](infrastructure/keycloak/realm/README.md).

### Payments

A payment is the one operation in the platform where a bug is not a wrong answer but extra money, so
the live check goes after the paths that only exist over HTTP: that the gateway routes
`/api/v1/transactions` and `/api/v1/accounts`, that `Idempotency-Key` survives the gateway hop, and
that a replay is **byte-identical** to the original response rather than merely the same status code.

```
./scripts/verify-payment-lifecycle.sh
```

It provisions its own throwaway identities, funds an account, replays the top-up and diffs the bytes,
pays, replays the payment and asserts one row in the database, reuses a key with a different amount
and expects `409 IDEMPOTENCY_KEY_REUSED`, declines an unaffordable payment with a `422` and a
`DECLINED` body, settles, reverses, and checks in SQL that every journal entry balances, no customer
account is negative, and the only card reference stored is the opaque token.

The byte comparison is the part worth keeping. The first version of that check compared status codes
and passed while the service was returning a JSON-quoted string instead of an object — the payment
was made once, the status was 201, and only a client parsing `$.id` would have failed. Details in
[ADR-0007](docs/decisions/0007-double-entry-ledger-and-idempotency.md).

### Fraud is scored after the payment, not before it

A payment is authorised on transaction-service's own rules and scored afterwards, so fraud-service is
on no payment's critical path and cannot make payments fail. The live check goes after the parts that
only exist once both services are running and a real topic is between them:

```
./scripts/verify-fraud-lifecycle.sh
```

It funds two throwaway customers, pays twice with one card, and waits for the engine rather than
assuming it has already run — then checks the things a service-level test cannot: that the payment was
authorised *before* a decision existed, that the `ownerSubjectDigest` fraud-service holds is byte-equal
to the one transaction-service sent, that the digest is not the card token, that a second claim on a
claimed alert is refused, that an `AUDITOR` can read the dashboard and is refused an action, that a
manual score of 90 is a `422 MANUAL_SCORE_ABOVE_CAP` while 900 is a plain validation error and neither
moves the stored score, and that the alert timeline records the re-score *with its reason* and an actor.

The digest comparison is the one that earns its keep. If the two services disagree about the key or
the derivation, nothing errors: the engine raises an alert against a subject nobody recognises and
velocity silently resets. Only reading both databases shows it.

### How a request is authenticated

```
client ──JWT──▶ api-gateway ──signed headers──▶ service
                 │                                 │
                 │ verifies signature, issuer,     │ verifies HMAC over the identity,
                 │ audience, algorithm, freshness  │ then reads CurrentCaller
                 │ authorises the path against     │
                 │ realm_access.roles              │
```

The gateway is the only place a token is ever read. A service receives six `X-Internal-Identity-*`
headers, HMAC-signed, and either the signature verifies or the request is `401`. Headers a client
sends with those names are stripped at the gateway, so a caller cannot assert a role. See
[ADR-0004](docs/decisions/0004-jwt-verified-at-the-gateway.md) for why, and
[docs/security.md](docs/security.md) for the current gaps.

### A note on the JDK

The build requires **JDK 21 or newer** and refuses to run on anything older, with a message saying
so. If `make doctor` reports a lower version:

```bash
export JAVA_HOME=$(brew --prefix openjdk)/libexec/openjdk.jdk/Contents/Home
```

Do not rely on `/usr/libexec/java_home -v 21` on macOS when no matching JDK is registered with the
system — it will return a Java 8 path without complaining. `make doctor` checks what Maven will
actually use rather than what the system claims is installed.

---

## Repository layout

```
pom.xml                      Maven reactor, dependency and plugin management
platform/
  platform-common/           Transport-agnostic contracts: errors, events, pagination, metrics tags
  platform-common-web/       Servlet cross-cutting web behaviour: correlation id, error responses
services/
  api-gateway/               Reactive ingress (Netty). Routes, JWT verification, rate limiting
  auth-service/              Keycloak-backed authentication and token issuance
  customer-service/          Customer profiles, addresses, KYC state
  card-service/              Card and token lifecycle
  transaction-service/       Payments, transfers, ledger, settlement, idempotency
  fraud-service/             Rules, scoring, velocity checks, analyst alert queue
  notification-service/      Email, SMS and push delivery
  audit-service/             Append-only regulatory audit trail
  dispute-service/           Chargebacks and disputes
infrastructure/
  docker/                    Shared service image, Postgres bootstrap
  kafka/                     Topic catalogue (single source of truth)
  prometheus/                Scrape config and alert rules
  grafana/                   Provisioned datasource and dashboards
  sample-data/               Synthetic fixtures, loaded only when asked
docs/
  architecture.md            Why the system is shaped this way
  security.md                Threat model and the controls that answer it
  deployment.md              What changes between this compose file and production
  decisions/                 Architecture decision records
scripts/
  bootstrap.sh               Generates .env with random secrets
  check-secret-isolation.sh  Asserts no service is handed another component's secret
  get-token.sh               Prints an access token for a local identity
  verify-card-lifecycle.sh   Asserts the card lifecycle end to end against a running stack
  verify-payment-lifecycle.sh  Asserts funding, a payment, and replay, end to end
  verify-fraud-lifecycle.sh    Asserts scoring, the digest join, and the analyst queue, end to end
  wait-for-http.sh           Polls an endpoint until it answers
```

The whole build is one Maven reactor, but **nothing forces a service to be built or deployed
together**. Modules that more than one service needs live in `platform/`; anything used by exactly
one service stays in that service's module. That is the line between a shared library and a
distributed monolith.

---

## How a payment moves through the system

```
                 ┌──────────────┐
   client ──────▶│  api-gateway │  JWT verified here, nowhere else
                 └──────┬───────┘
                        │ routes by path
        ┌───────────────┼───────────────┬──────────────┐
        ▼               ▼               ▼              ▼
 ┌────────────┐  ┌──────────────┐  ┌───────────┐  ┌──────────────┐
 │   auth     │  │  customer    │  │   card    │  │ transaction  │
 └────────────┘  └──────────────┘  └───────────┘  └──────┬───────┘
                                                              │ publishes
                                                              ▼
                                                   ┌────────────────────┐
                                                   │       Kafka        │
                                                   └─────────┬──────────┘
                          ┌──────────────┬─────────────────┼──────────────┐
                          ▼              ▼                 ▼              ▼
                   ┌────────────┐ ┌─────────────┐ ┌──────────────┐ ┌────────────┐
                   │   fraud    │ │ notification│ │    audit     │ │  dispute   │
                   └────────────┘ └─────────────┘ └──────────────┘ └────────────┘
```

Synchronous calls where the caller needs an answer now. Everything else is an event, so that a slow
or unavailable fraud check degrades the decision rather than failing the payment outright, and so
that audit, notification and dispute handling cannot slow down the authorisation path.

Fraud is the sharpest case. A payment is authorised on Phase 5's rules and scored afterwards: the
fraud engine is on no payment's critical path, so it can be slow, down or deploying without payments
noticing. What that costs is that a payment can be authorised and then declined later, and what pays
for it is the analyst queue and the dashboard that make the late decision someone's job. See
[ADR-0008](docs/decisions/0008-asynchronous-advisory-fraud-scoring.md).

Each service owns its own PostgreSQL database and its own role. There are no cross-database joins,
and a service cannot read another service's tables even if it is fully compromised. See
[ADR-0002](docs/decisions/0002-database-per-service.md).

---

## Build and verification

```bash
make test          # unit tests only, no Docker needed
make verify        # compile, test, and write the coverage report
make quality       # verify, plus assert formatting
make check         # what a change must pass before it is proposed
make coverage      # coverage summary per module
make format        # reformat sources
```

`make check` is the gate. It runs `quality` and then `check-secrets`, which renders the compose file
and asserts that no application container is given the Postgres superuser password, the Keycloak
admin password or the Grafana password — the mistake that `env_file: .env` on a shared service
anchor would introduce silently.

Formatting runs inside the pinned `maven:3.9.16-eclipse-temurin-21` image rather than on your
host JDK. The formatter reaches into javac internals, and the current release does not understand
the compiler in a current JDK; running it in the same image the Docker build uses also means it
cannot reformat code differently on one machine than on another.

```bash
make check-secrets        # secret isolation invariants
make check-prometheus     # promtool validates scrape config and alert rules
```

---

## Local infrastructure notes

Things that are deliberately *not* what production looks like, so nobody mistakes them later:

- **Plaintext everywhere.** No TLS between containers. Production terminates TLS at the ingress and
  uses mTLS service to service. See [docs/deployment.md](docs/deployment.md).
- **Bound to `127.0.0.1` only.** Every published port is loopback, so the stack is not reachable
  from the local network.
- **One Kafka broker, replication factor 1.** Production uses three brokers across availability
  zones. The `1`s in the compose file are local-dev concessions.
- **One shared local password for the service database roles.** The isolation that matters is the
  separate roles, not the password. In Kubernetes each role gets its own secret, so revoking one
  service cannot be undone by knowing another's credentials.
- **Postgres and Redis exporters are not wired up yet.** Declaring scrape jobs for containers that
  do not exist would create permanently firing targets, and alerts that always fire are alerts
  everybody ignores.
- **A shared signing key, not mTLS.** The gateway signs the identity it forwards with an HMAC key
  that every service holds, so any service can mint an identity for any user. That is a deliberate
  interim step: Phase 15 replaces it with mutual TLS between the gateway and each service, at which
  point the key stops being load-bearing. See [ADR-0004](docs/decisions/0004-jwt-verified-at-the-gateway.md).
- **Operational endpoints skip identity verification.** A service answers `/actuator/health` and
  `/actuator/prometheus` without a signed identity, because Docker cannot present one and Prometheus
  cannot either. Everything else on a service, including `/actuator/env` and `/actuator/heapdump`,
  requires a verified identity and returns `401` without it.
- **A missing signing key means a service trusts nobody.** It does not mean a service trusts
  everybody: the verification filter is not registered at all, so every protected path is `401`. There
  is no setting that turns verification off while leaving the service serving.

---

## The sixteen phases

| # | Phase | State |
| --- | --- | --- |
| 1 | Monorepo, module structure, CI skeleton, local stack, observability foundations | **done** |
| 2 | Keycloak realm, JWT issuance, gateway verification, per-route authorisation | **done** |
| 3 | Customer and KYC domain, PII handling | **done** |
| 4 | Card issuing, tokenisation, PAN never stored | **done** |
| 5 | Transaction domain, double-entry ledger, idempotency | **done** |
| 6 | Fraud engine, rules and scoring | **done** |
| 7 | Settlement, reconciliation, reversals | |
| 8 | Notifications | |
| 9 | Audit trail and regulatory reporting | |
| 10 | Disputes and chargebacks | |
| 11 | Web frontend | |
| 12 | Contract and integration testing | |
| 13 | Resilience: circuit breakers, retries, chaos | |
| 14 | Performance and load testing | |
| 15 | Kubernetes, Helm, network policy, secrets | |
| 16 | Production hardening, runbooks, SLOs | |

---

## Documentation

- [docs/architecture.md](docs/architecture.md) — the shape of the system and why
- [docs/security.md](docs/security.md) — threat model and controls
- [docs/decisions/0005-pii-encrypted-at-rest.md](docs/decisions/0005-pii-encrypted-at-rest.md) —
  why personal data is encrypted per customer, and what that costs
- [docs/decisions/0006-card-tokenisation.md](docs/decisions/0006-card-tokenisation.md) —
  why card-service's database cannot hold a card number, and what that costs
- [docs/decisions/0007-double-entry-ledger-and-idempotency.md](docs/decisions/0007-double-entry-ledger-and-idempotency.md)
- [docs/decisions/0008-asynchronous-advisory-fraud-scoring.md](docs/decisions/0008-asynchronous-advisory-fraud-scoring.md)
  — why fraud scores after the payment rather than before it, and what that costs
- [docs/deployment.md](docs/deployment.md) — local versus production
- [docs/decisions/](docs/decisions/) — architecture decision records
