# Secure FinTech Transaction Platform

An event-driven payment platform built as a set of independently deployable Spring Boot services.

This repository is being built in sixteen phases. **Phases 1 through 11 are complete**: ten service
skeletons, local infrastructure, observability wiring and build pipeline are in place, and the
platform now carries a payment from authorisation through fraud scoring to a period statement that is
reconciled against a bank figure and cannot be edited once it has been given out — tells the
customer and the operator about each step, records who did what in a trail nobody can edit, takes
money back when a chargeback case says so, and shows a customer all of it in a browser. What is not
built
yet is stated in the phase list below rather than left to be discovered.

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
| Web UI | http://localhost:3001 |
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

### A statement that has been given out cannot be edited

Settlement is the one place in the platform where the answer is "we do not know yet" and that is
correct, because the honest answer to a bank's figure has not arrived. A period is counted from the
events, closed, given out, and then compared with a figure the platform is told from outside — because
a system that reconciles its own arithmetic against itself has not checked anything. The live check
goes after the parts that only exist once money has actually moved through a real topic:

```
./scripts/verify-settlement-lifecycle.sh
```

It needs a day-and-currency period of its own, because it closes one: a cycle is unique per
`(business_date, currency)`, so the default USD period is spent by a run. `SETTLEMENT_CHECK_CURRENCY=EUR`
runs it in another, and `--reset-period` clears an `OPEN` period left behind by an interrupted run —
never a closed one, which is the one thing this service will not edit.

It pays, waits for the payment to appear on a statement line, refunds it, and checks in SQL that the
refund carried as a **negative** line and that the pair nets to zero. Then it closes the period, and
makes a payment that lands *after* the close — the case the whole design is about. The money moved and
the period is final, so the platform must neither mutate the statement nor lose the payment: the frozen
total is unchanged, no line is added, and a `PERIOD_ALREADY_CLOSED` finding is recorded. Finally it
declares an actual a pound short, checks that a mismatch comes back as a `200` with a negative
difference rather than a `409`, that the period cannot be confirmed while a finding is open, and that
the finding carries the acknowledging operator.

Three things in there are worth more than the rest. The late payment is the immutability rule
observed across Kafka rather than asserted in a unit test. The `200` for a mismatch is the difference
between "your request was fine and the news is in the reply" and "your request was malformed" — the
latter sends an operator looking at their own keyboard instead of at the clearing file. And the
refund netting to zero in SQL is the check that a reversal was stored as a *movement in the opposite
direction* rather than as a positive amount with a label on it, which is a statement that adds money
nobody received. Design in [ADR-0009](docs/decisions/0009-settlement-cycles-and-reconciliation.md).

### The platform tells somebody, exactly once

Notification-service consumes the transaction, fraud and settlement topics and turns each fact into
one message — push for a payment, SMS for a fraud outcome worth acting on, email for a settlement
period — claimed with an insert so a redelivered event cannot tell the customer twice. An approved
payment notifies nobody: there is no human action in it, and a message per approval would be noise
at best and a per-payment SMS bill at worst. The live check goes after the parts that only exist
once a real topic is between the services:

```
./scripts/verify-notification-lifecycle.sh
```

It pays, waits for the message rather than assuming it has already been written, and checks the
things a service-level test cannot: that the gateway routes `/api/v1/notifications` to a support
agent and refuses it to a customer and an auditor, that the response names the recorded figure with
no recipient digest in it, that the database holds one row for the payment with no column a card
number could be stored in, that retrying the sent message is a `409 NOTIFICATION_ALREADY_SENT`
rather than a second delivery, and that a failed message retries to `SENT`.

Two things in there are worth more than the rest. The digest absence is the join key that must
never travel: the row holds the owner digest and the view renders everything except it, because a
digest in a support response joins across the fraud and notification databases for anyone who can
read both. And the `409` for a sent retry is the difference between "there is nothing to do" said
loudly and a duplicate delivery nobody ordered. Design in
[ADR-0010](docs/decisions/0010-notification-delivery-and-retry.md).

### Who did what is written where nobody can rewrite it

Audit-service consumes `audit-events` and keeps the platform's trail: who did what to which
resource, with what outcome. It is a separate service because an audit log written by the component
it audits records what that component chose to record. The table is append-only by database
trigger, not by convention — an `UPDATE` or `DELETE` issued straight at the database is refused —
and the API has no write endpoint at all, so a write is an unmapped route rather than a forbidden
action. The live check goes after the parts that only exist once a real analyst acts through a real
topic:

```
./scripts/verify-audit-lifecycle.sh
```

It raises an alert with a burst of payments, claims it as the realm's analyst, waits for the trail
rather than assuming it has already been written, and checks the things a service-level test
cannot: that the gateway routes `/api/audit/records` to an auditor and refuses it to a customer, a
support agent and a fraud analyst; that the row's actor matches the digest on the alert timeline
without naming the analyst's raw subject; that a refused second claim leaves no second row; and
that an `UPDATE` and a `DELETE` against the trail are refused by the database itself.

Two things in there are worth more than the rest. The digest comparison is the join that must
agree: fraud-service computed the actor and the trail stored what the event carried, and if the two
disagreed about who acted, nothing would error — the trail would simply name nobody. And the
refused `UPDATE` is the append-only property observed rather than asserted: a trail a SQL client
can edit is a second draft of history. Design in
[ADR-0011](docs/decisions/0011-audit-trail-as-append-only-service.md).

### Taking money back on purpose

Dispute-service owns the chargeback workflow: a customer opens a case on their own settled payment,
both sides plead in the file, and a support agent decides — refund or reject, with a reason either
way. A refund is announced on `dispute-status-changed` and transaction-service reverses the capture
out of its own stored owner digest, which never leaves that service: dispute-service holds no key
and mints no identity, so it cannot move money itself without becoming the confused deputy the
forwarded identity exists to prevent. The live check goes after the parts that only exist once a
real case moves real money through real topics:

```
./scripts/verify-dispute-lifecycle.sh
```

It pays, settles, opens a case as the customer, and checks the things a service-level test cannot:
that a second case on the same payment is refused, that a case on an unsettled hold and on another
customer's payment are refused with different codes, that neither side's pleading nor the customer's
own resolve button moves the case past its parties, and that the agent's refund actually reverses
the capture — read back as `REVERSED` with the balance to prove it. Then that a second decision
and late evidence are refused, and that open, evidence and resolution all reached the audit trail.

Two things in there are worth more than the rest. The balance is the refund observed rather than
decided: a 200 on resolve with no postings is a decision without a refund, and only reading the
ledger shows it. And the ownership refusals are the forwarded identity working across a service
boundary — transaction-service applying its own rule to a real caller's request, with this service
translating the answer into its own vocabulary. Design in
[ADR-0012](docs/decisions/0012-disputes-decided-by-support-refunded-by-ledger.md).

### The browser a customer can use

`http://localhost:3001` serves a static customer UI — no framework, no build step, no backend of
its own. Sign in with a local login, register a profile with a synthetic identity check, fund,
pay, issue and freeze cards, and open disputes: every button calls the gateway the verify scripts
call, with a fresh idempotency key per click and the correlation id on every refusal. The token
lives in memory and dies with the tab; staff views do not exist here by design. The live check
goes after the parts that only exist in a browser:

```
./scripts/verify-frontend-lifecycle.sh
```

It proves the three-way origin agreement (nginx serves it, the gateway allows it, Keycloak lists
it), that the preflight answers the UI's origin with the payment headers, that the UI's exact
calls move money, and that the served files carry no secrets. The password grant it uses is fenced
to local development — production moves to Authorization Code with PKCE in a single `login`
method. Design in [ADR-0013](docs/decisions/0013-static-customer-ui.md).

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
  verify-settlement-lifecycle.sh  Asserts the period statement, its immutability, and a worked finding, end to end
  verify-notification-lifecycle.sh  Asserts the message, the delivery log's access rules, and the retry, end to end
  verify-audit-lifecycle.sh  Asserts the trail row, its access rules, and its immutability, end to end
  verify-dispute-lifecycle.sh  Asserts the case, the refund through the ledger, and the trail, end to end
  verify-frontend-lifecycle.sh  Asserts the served UI, the preflight, and the UI's calls, end to end
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
| 7 | Settlement, reconciliation, reversals | **done** |
| 8 | Notifications | **done** |
| 9 | Audit trail and regulatory reporting | **done** |
| 10 | Disputes and chargebacks | **done** |
| 11 | Web frontend | **done** |
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
- [docs/decisions/0010-notification-delivery-and-retry.md](docs/decisions/0010-notification-delivery-and-retry.md)
  — why a consumed fact becomes exactly one message, why a failed send never fails the event, and
  why there is no customer route to the delivery log
- [docs/decisions/0011-audit-trail-as-append-only-service.md](docs/decisions/0011-audit-trail-as-append-only-service.md)
  — why the trail is a separate service, why append-only is a database trigger rather than a
  convention, and why the trail holds digests instead of subjects
- [docs/decisions/0012-disputes-decided-by-support-refunded-by-ledger.md](docs/decisions/0012-disputes-decided-by-support-refunded-by-ledger.md)
  — why the refund is announced rather than called, why only the customer opens, and why the audit
  actor is a keyless hash instead of a shared digest
- [docs/decisions/0013-static-customer-ui.md](docs/decisions/0013-static-customer-ui.md)
  — why the UI is static files with no backend, why the password grant is fenced to local
  development, and why the token lives in memory
- [docs/deployment.md](docs/deployment.md) — local versus production
- [docs/decisions/](docs/decisions/) — architecture decision records
