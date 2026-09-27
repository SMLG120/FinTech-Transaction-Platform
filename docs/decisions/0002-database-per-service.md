# ADR-0002: One database and one role per service

- Status: accepted
- Date: 2026-09-26
- Phase: 1

## Context

The original specification listed a shared set of schemas (`auth`, `customers`, `cards`,
`transactions`, `fraud`, `notifications`, `audit`, `disputes`) served by one PostgreSQL instance,
with a single application role.

Taken literally that means one credential valid for every service, so a compromise of the
notification service — the one most likely to be breached, because it handles third-party
delivery providers and therefore the largest external attack surface — yields read and write access
to the ledger.

Running one PostgreSQL *instance* and one *database per service* is compatible with the spirit of
the original request while removing the shared-credential problem. The operational simplicity of a
single instance is retained; the privilege coupling is not.

## Decision

One PostgreSQL instance in development. Inside it, one database and one dedicated role per service:

| Service | Database | Role |
| --- | --- | --- |
| auth-service | `fintech_auth` | `fintech_auth` |
| customer-service | `fintech_customers` | `fintech_customers` |
| card-service | `fintech_cards` | `fintech_cards` |
| transaction-service | `fintech_transactions` | `fintech_transactions` |
| fraud-service | `fintech_fraud` | `fintech_fraud` |
| notification-service | `fintech_notifications` | `fintech_notifications` |
| audit-service | `fintech_audit` | `fintech_audit` |
| dispute-service | `fintech_disputes` | `fintech_disputes` |

Each role is granted privileges only on its own database. Cross-service joins are impossible, and
no service can read another's tables. `api-gateway` has no database and is given no database
credentials at all.

Locally all roles share one generated password, because the isolation that matters comes from the
roles being separate. In production each role's password is a separate secret.

## Consequences

**Good.** A compromised service cannot reach another service's data. Dropping a database is
possible per service. Per-service connection pool and query limits are expressible. Backups,
restore points and migrations are per service.

**Bad, and accepted.** No cross-service join. When a report genuinely needs data from several
services it is built from the event stream or in the audit service, which is more work and usually
the right answer anyway. Referential integrity across services is not enforced by the database, so
referential contracts must be stated in events and validated by consumers. Data that is genuinely
duplicated — a customer name displayed in a transaction — is denormalised, and the two copies can
diverge; the transaction service's copy is a historical record and is not expected to track later
edits.

The roles were provisioned as `fintech_<service>` while the compose file initially handed every
service a shared `fintech_app` username. That mismatch meant no service could have connected, and
it is exactly the kind of error that a documentation comment does not prevent. It is now asserted by
`scripts/check-secret-isolation.sh`, which renders the compose file and fails if any service's
database username does not match its database, if two services share a database, or if any
application container receives an infrastructure secret.

## Alternatives considered

**One shared role, as originally specified.** Rejected. The blast radius of the least-protected
service becomes the whole system.

**Separate PostgreSQL instance per service.** Rejected for local development on resource grounds;
it is the production target in Phase 15, where the isolation benefit justifies the operational
cost. The per-database role boundary means this change does not alter any application code.

**Shared schema, table-per-service, one role.** Rejected. Tables in one schema are one namespace
with no privilege boundary, so the isolation is not real, and a single `search_path` mistake
reaches everything.
