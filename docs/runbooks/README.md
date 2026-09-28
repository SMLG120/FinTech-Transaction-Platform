# Runbooks

Incident procedures for the platform, ordered by how you notice the problem.
Every command here is copy-paste runnable against the local stack, and every
signal it reads exists: an alert name from
`infrastructure/prometheus/alerts/`, a metric from the Grafana dashboard, a
log line the services actually emit, or SQL against a table the migrations
create. A runbook that invents a signal sends an incident down a path that
does not exist, so anything not verifiable locally is marked as production
intent rather than written as a step.

- [payments-failing.md](payments-failing.md) — 5xx on the payment path,
  ledger doubts, breaker behavior.
- [fraud-scoring-stalled.md](fraud-scoring-stalled.md) — the dashboard goes
  quiet while payments succeed (usually correct behavior, read first).
- [settlement-period-broken.md](settlement-period-broken.md) — a period that
  will not confirm, findings to work.
- [kafka-down.md](kafka-down.md) — broker outage: what keeps working, what
  waits, how recovery catches up.
- [postgres-down.md](postgres-down.md) — database outage and disk pressure.
- [secret-rotation.md](secret-rotation.md) — which secrets rotate, in what
  order, and which two cannot be rotated at all.

Conventions used throughout:

- Container names are `fintech-<service>` (e.g. `fintech-transaction-service`).
- `psql` reaches a database with
  `docker compose exec -T postgres psql -U "$POSTGRES_SUPERUSER" -d <db>`,
  where the user comes from `.env` (`POSTGRES_SUPERUSER`, set by
  `scripts/bootstrap.sh`). Databases are `fintech_transactions`,
  `fintech_fraud`, `fintech_customers`, `fintech_cards`, `fintech_disputes`,
  `fintech_settlement`, `fintech_notifications`, `fintech_audit`.
- Tokens come from `./scripts/get-token.sh <customer|agent|analyst|…>`.
- Logs are ECS JSON: `docker compose logs <service> | python3 -m json.tool`
  is readable; grepping the raw stream for a correlation id works too.
- After any restart, `scripts/verify-payment-lifecycle.sh` (or the narrower
  check each runbook names) confirms recovery better than a green
  healthcheck does: healthy processes serve wrong answers too.
