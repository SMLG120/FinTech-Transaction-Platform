# Secure FinTech Transaction Platform

A synthetic, event-driven transaction platform implemented as a Java/Spring Boot service suite,
with a browser UI and a React/TypeScript operations dashboard. It is a development and review project:
it does **not** connect to a payment network, handle real cards, or move real money. KYC and message
delivery are simulated.

> **Production status: NOT READY.** The local stack is deliberately development-only. The Kubernetes
> chart deploys backend services but not their external dependencies, ingress, frontend, or a migration
> job. Service-to-service mTLS is designed but not implemented, local/dev authentication flows remain
> in the UIs, and the payment/KYC/notification integrations are simulations. See
> [Production deployment](#production-deployment-and-current-blockers).

## Architecture

```text
Browser ── JWT ──▶ API gateway ── signed internal identity ──▶ Spring services
                         │                                        │
                         ├── Keycloak (identity)                  ├── PostgreSQL
                         └── Redis (rate limits)                  └── Kafka events
                                                                    ├── fraud
                                                                    ├── audit
                                                                    ├── settlement
                                                                    ├── notifications
                                                                    └── disputes
```

The ten Spring Boot services are independently buildable and own separate PostgreSQL databases and
roles. The gateway validates JWT signature, issuer, audience, expiry, and realm roles. Services verify
the gateway's signed identity and apply resource-level authorization. Kafka carries asynchronous
events; the transaction service uses a double-entry ledger, database idempotency, and an outbox.
Amounts are integer minor units. Customer PII is encrypted at rest, and card numbers are never stored.

| Area | Implementation |
| --- | --- |
| Ingress and identity | `api-gateway`, `auth-service`, Keycloak |
| Customer and cards | `customer-service`, `card-service` |
| Money movement | `transaction-service`, double-entry ledger and idempotency |
| Risk and operations | `fraud-service`, `settlement-service`, `notification-service`, `audit-service`, `dispute-service` |
| Browser clients | `web/` static customer UI; `frontend/fintech-dashboard/` React + TypeScript dashboard |
| Local infrastructure | Docker Compose, PostgreSQL, Redis, Kafka, Keycloak, Prometheus, Grafana |
| Kubernetes | `infrastructure/helm/fintech/`; backend services only |

Further design details: [architecture](docs/architecture.md), [security](docs/security.md),
[deployment](docs/deployment.md), [runbooks](docs/runbooks/README.md), and
[architecture decisions](docs/decisions/).

## Technology and prerequisites

- JDK **21** and the included Maven wrapper (`./mvnw`, Maven 3.9.16).
- Docker Engine/Desktop with Docker Compose v2, for the local stack and Testcontainers tests.
- Node.js 20+ and npm for the browser apps and frontend tests.
- Helm 3 for chart validation. Python 3, `curl`, and OpenSSL are used by local scripts.

The repository's Maven enforcer rejects Java versions below 21. If the system default is older, set
`JAVA_HOME` to a JDK 21 installation before Maven commands. `make doctor` reports the selected Java
runtime and available tooling.

## Local setup

From the repository root:

```bash
make doctor
make setup       # creates .env with random local secrets; does not overwrite an existing file
make up          # builds and starts the local stack
make ps
```

Compose publishes host ports on `127.0.0.1` only. The application itself is not reachable from the
local network.

| Service | Local URL |
| --- | --- |
| API gateway | <http://localhost:8080> |
| Static customer UI (served by Compose) | <http://localhost:3001> |
| React dashboard dev server (start separately) | <http://localhost:5173> |
| Keycloak | <http://localhost:8180> |
| Kafka UI | <http://localhost:8081> |
| Prometheus / Grafana | <http://localhost:9090> / <http://localhost:3000> |
| Direct service ports (loopback only) | `9081`–`9089` |

Stop services and retain their data with `make down`. `make clean-all` removes the Compose data
volumes; it is destructive. Back up first if the local data matters.

### Local identities and customer registration

The imported development realm contains synthetic users. Their password is **not** committed: the
realm file references `${SEED_USER_PASSWORD}`, which `./scripts/bootstrap.sh` generates randomly into
`.env` (read it with `grep SEED_USER_PASSWORD .env`). An existing `keycloak-data` volume keeps the
password it was first imported with, so run `docker compose down -v` once after upgrading:

| Username | Role |
| --- | --- |
| `customer@fintech.test` | `CUSTOMER` |
| `agent@fintech.test` | `SUPPORT_AGENT` |
| `auditor@fintech.test` | `AUDITOR` |
| `compliance@fintech.test` | `COMPLIANCE_OFFICER` |
| `analyst@fintech.test` | `FRAUD_ANALYST` |
| `settlement@fintech.test` | `SETTLEMENT_OPERATOR` |
| `admin@fintech.test` | `PLATFORM_ADMIN` |

These credentials and the dev-only `fintech-cli` client (the password grant the scripts use) are **local-development fixtures only**. Never use
them in another environment. Get a local bearer token with `./scripts/get-token.sh customer` (replace
`customer` with a listed short name); add `--decode` to inspect claims instead of printing the token.

`POST /register` is the unauthenticated customer account-provisioning endpoint. It creates a Keycloak
user and customer profile. The imported realm intentionally does not contain the confidential
`fintech-registration` service-account client. To use registration locally, create that client in
Keycloak as described in [the realm guide](infrastructure/keycloak/realm/README.md), enable service
accounts, grant only the documented user/role management permissions, and configure its client secret
to match `KEYCLOAK_ADMIN_CLIENT_SECRET` in the local `.env` (or update `.env` to the client-generated
secret). Then recreate `auth-service`. Do not grant `realm-admin`.

The customer service also exposes `POST /api/v1/customers` for an already-authenticated customer whose
identity exists in Keycloak. It creates a profile only; it does not create an identity-provider user.

### Environment variables and secrets

`.env.example` documents local Compose variables. `make setup` generates `.env`; the generated file is
ignored by Git and restricted to the current user. Do not print it into logs or commit it. Required
generated local secrets include:

| Variable | Purpose |
| --- | --- |
| `POSTGRES_SUPERUSER_PASSWORD` | PostgreSQL bootstrap administrator; never passed to app services |
| `SERVICE_DB_PASSWORD` | Shared local-only password for separate service database roles |
| `REDIS_PASSWORD` | Local Redis authentication |
| `KEYCLOAK_ADMIN_PASSWORD`, `GRAFANA_ADMIN_PASSWORD` | Local operator accounts |
| `KEYCLOAK_ADMIN_CLIENT_SECRET` | Auth-service's dedicated Keycloak provisioning client |
| `INTERNAL_IDENTITY_SIGNING_KEY` | Interim gateway-to-service HMAC identity key (hex) |
| `CUSTOMER_PII_MASTER_KEY` | Customer PII encryption key (base64-encoded 32 bytes) |
| `CARD_TOKENISATION_KEY` | Card tokenisation key (base64-encoded 32 bytes) |
| `SUBJECT_DIGEST_KEY` | Subject digest key shared by transaction and fraud services (base64-encoded 32 bytes) |

Local ports, log levels, database pool sizes, and similar overrides are listed in `.env.example`.
Compose service URLs and local issuer/CORS defaults are declared in `docker-compose.yml`; production
must override the issuer, JWKS URI, API audience, allowed browser origins, service URLs, and database
endpoints for its environment. The React app's `frontend/fintech-dashboard/.env.example` contains only
public endpoint addresses; Vite `VITE_*` values are included in browser assets and must never contain
secrets.

For a non-local deployment, use distinct credentials per service and a managed secret-delivery
mechanism. The local `SERVICE_DB_PASSWORD` is not a production pattern. Never put real values in an
environment template, container image, Helm values committed to Git, frontend bundle, or command-line
arguments.

## Database initialization and backups

On the first start with an empty PostgreSQL volume, `infrastructure/docker/postgres/init/` creates one
database and least-privilege role per database-backed service. That init script runs only for an empty
data directory. Local services apply Flyway migrations at startup and validate the resulting schema;
production chart pods disable Flyway, but a separate production migration job has **not** been provided.

The local backup helper is:

```bash
./scripts/backup-postgres.sh
./scripts/backup-postgres.sh --list
./scripts/backup-postgres.sh --restore <backup-directory>
```

Backups are gzip-compressed SQL, not encrypted. Treat them as sensitive data. The repository excludes
`backups/` from Git and Docker build contexts, and new files use owner-only permissions; restrict any
copies made elsewhere. Do not use this script as a production backup/recovery plan.
Production needs encrypted backups, point-in-time recovery, retention controls, and regularly tested
restores.

## Browser applications

The Compose stack serves `web/`, a dependency-free static customer UI. The React/TypeScript dashboard
is a separate source application and is not built or served by Compose:

```bash
cd frontend/fintech-dashboard
npm ci
npm run dev
```

Its public endpoint settings are in `.env.example`. Both frontends sign in with OAuth Authorization Code + PKCE (S256) on the public `fintech-web`
client: the UIs never see a password, and tokens are kept in memory only. The browser client has
the password grant disabled; scripts use the separate dev-only `fintech-cli` client. The React
dashboard still has no production image/deployment configuration; do not expose it as a production
client. The static UI image is also a local Compose artifact, not a complete
production frontend deployment.

## API and authentication

API requests use `Authorization: Bearer <access-token>` at the gateway. The gateway verifies the JWT
using the configured issuer/JWKS and requires the `fintech-api` audience, then authorizes by realm
role. It strips client-supplied internal identity headers and signs the verified identity for the
service. Services verify that signature and enforce ownership and operation-specific roles. A caller
must not treat frontend role-based navigation as authorization.

Representative API routes (all business routes require a valid bearer token except `POST /register`):

| Service | Routes |
| --- | --- |
| Authentication | `POST /register` |
| Customers | `/api/v1/customers` (`GET/PUT/DELETE /me`, `GET /{customerId}`, KYC submit/history) |
| Cards | `/api/v1/cards` (issue/list/read, freeze/unfreeze/lost, cancel) |
| Transactions/accounts | `/api/v1/transactions` (create/list/read/settle/reverse), `/api/v1/accounts` (balance/fund) |
| Fraud | `/api/v1/fraud/decisions`, `/api/v1/fraud/alerts`, `/api/v1/fraud/summary` |
| Settlement | `/api/v1/settlement/cycles` and `/api/v1/settlement/breaks` |
| Notifications | `/api/v1/notifications` |
| Audit | `/api/audit/records` |
| Disputes | `/api/v1/disputes` |

Money-moving payment and funding requests require an `Idempotency-Key`. The gateway exposes only
`/actuator/health` and `/actuator/prometheus` without authentication; services have their own
network-restricted operational probes. See [the security review](docs/security.md) for the current
trust boundaries and gaps.

## Tests and verification

Frontend checks:

```bash
cd frontend/fintech-dashboard
npm ci
npm run typecheck
npm run lint
npm test -- --run
npm run build
```

The static UI's Node tests run with `make web-test`. Maven's `test` and `verify` include Testcontainers
tests, so a running Docker daemon is required even though a Compose stack is not:

```bash
./mvnw -B -ntp test
./mvnw -B -ntp verify
make contract          # Docker-backed contract tests plus dashboard schema tests
make quality           # verify plus formatting (formatter runs in Docker)
make check             # quality plus Compose secret-isolation checks
```

With the local stack running, the live lifecycle and load checks are:

```bash
make verify-live        # lifecycle checks plus degraded-mode checks
make load-smoke
```

These live checks create synthetic Keycloak users and application rows in the current Compose
volumes. They do not clean up transaction, audit, or closed-settlement history. Run them only against
local disposable/test data, never against a production environment. If you need a clean test database,
use a disposable stack; `make clean-all` deletes the current Compose volumes and is destructive.

`make helm-template` lints/renders the Helm chart using throwaway values. `make check-prometheus`
validates scrape and alert configuration using the Prometheus container. These are verification
commands, not production deployment approval.

## Docker Compose notes

Compose is for local development only: plaintext service traffic, a single Kafka broker, synthetic
Keycloak users, loopback-published ports, and local secrets. `make setup` is required before the first
start. `make down` retains data; `make clean-all` deletes Compose volumes. Do not use the local stack
as an internet-facing deployment.

## Production deployment and current blockers

The Helm chart deploys the ten backend services. PostgreSQL, Redis, Kafka, an identity provider,
monitoring, ingress/TLS, and the frontend are external or absent; the chart deliberately does not
create an ingress with a placeholder host. Chart validation is:

```bash
make helm-template
```

An eventual deployment command will take an externally managed values/secrets source, for example:

```bash
helm upgrade --install fintech infrastructure/helm/fintech \
  -f <externally-managed-production-values.yaml>
```

This command is **not** a safe production procedure yet. The chart currently emits Kubernetes Secret
resources from Helm values (which are also retained in Helm release state); it has no external-secret
reference integration. It also has no database migration job, ingress, production frontend, or
exercised rollback/restore procedure. Its optional PKI template provisions a self-signed issuer, but
the Java services do not yet use client certificates: the shared HMAC identity key remains
load-bearing. NetworkPolicy egress and Kafka/database/Redis transport security also require production
work.

Before any real deployment, an operator must at minimum:

1. Provide managed PostgreSQL databases/roles, Redis, Kafka with authentication/ACLs and TLS, and a
   production identity provider with the exact issuer, audience, JWKS, and registration-client setup.
2. Configure a secret manager/external-secret integration, distinct per-service database credentials,
   encryption keys, and Kubernetes secret-at-rest protections. Do not use local realm credentials.
3. Build and publish immutable images; configure registry and tags; run schema migrations as a
   controlled pre-deploy job; verify migration and rollback compatibility.
4. Implement gateway TLS termination, production CORS origins, rate limiting/WAF controls, and the
   production browser's Authorization Code + PKCE flow. Complete Java mTLS and restrict network
   egress before relying on the shared identity-signing key boundary.
5. Deploy/secure the frontend and monitoring, validate probes and graceful shutdown, configure alert
   routing, encrypted backups/PITR, and perform restore and incident-recovery exercises.
6. Run security review, dependency scanning, end-to-end tests against the intended infrastructure,
   and an approved deployment rehearsal. No live deployment has been performed by this repository.

## Troubleshooting

- **Maven reports Java 8 or an unsupported JDK:** point `JAVA_HOME` to JDK 21+ and rerun `make doctor`.
- **Docker commands cannot connect:** start Docker Engine/Desktop; Compose file validation alone does
  not mean the daemon is available.
- **Compose reports a required variable:** run `make setup`; inspect variable names in `.env.example`
  without pasting secret values into logs or issues.
- **PostgreSQL refuses a service after changing `.env`:** changing `SERVICE_DB_PASSWORD` does not
  rotate existing database roles. Use the documented rotation procedure; do not delete volumes as a
  first response.
- **JWT issuer/audience is rejected:** compare the token `iss`/`aud` with `KEYCLOAK_ISSUER`,
  `KEYCLOAK_API_AUDIENCE`, and the imported realm/client configuration. Realm import does not
  overwrite an existing realm.
- **Registration fails while login works:** check the `fintech-registration` client, its service
  account permissions, and the matching `KEYCLOAK_ADMIN_CLIENT_SECRET`; the client is intentionally
  not part of the committed development realm export.
- **A schema validation/migration error follows a clean start:** confirm the database is empty only
  when intended and inspect Flyway logs. The Postgres init scripts do not rerun against an existing
  volume.

## Repository map

```text
services/                 ten Spring Boot services
platform/                 shared contracts, identity, errors, and web behavior
contracts/                shared API/event fixtures
web/                      static customer UI served in local Compose
frontend/fintech-dashboard/ React + TypeScript dashboard source
infrastructure/docker/    service image and PostgreSQL bootstrap
infrastructure/helm/      backend Kubernetes chart
infrastructure/keycloak/  local-only realm and test users
infrastructure/kafka/     Kafka topic catalogue
infrastructure/prometheus/, infrastructure/grafana/  local observability
scripts/                  bootstrap, backup, lifecycle, and smoke checks
docs/                     architecture, security, deployment, runbooks, ADRs
```
