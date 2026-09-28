# Runbook: payments failing

## Symptoms

- Customers report declined or erroring payments; the dashboard shows failed
  transactions.
- `ElevatedServerErrors` firing for `transaction-service` or `api-gateway`
  (5xx on >5% of requests for 5 minutes).
- `ThreadPoolExhaustion` on a service (request threads >90% for 10 minutes)
  — usually a slow downstream, not a lack of threads.

## Triage

**1. Scope it: whose error, and on which call.**

```bash
docker compose logs transaction-service 2>&1 | grep -o '"error":"[A-Z_]*"' | sort | uniq -c
```

Every refusal carries a machine code and a correlation id. A single code
(e.g. `IDEMPOTENCY_KEY_REUSED`) is a client bug, not an outage. Many codes,
or 5xx with no code at all, is the platform.

**2. Check the two synchronous hops first** — they are the only calls a
payment waits on:

```bash
docker compose ps customer-service card-service transaction-service dispute-service
```

If customer-service is down, card issuing 503s with
`ELIGIBILITY_UNAVAILABLE` (fail closed, by design). If
transaction-service is down, dispute opening 503s with
`TRANSACTION_UNAVAILABLE`. Both breakers count only transport failures and
5xx — a run of 4xx refusals never trips them, so check the codes from step
1 before suspecting the breaker.

**3. Breaker state and retry volume** (Prometheus):

```
outbound_guard_calls_total{result="rejected"}   # refused by an open breaker
outbound_guard_calls_total{result="failure"}    # failed after retries
outbound_guard_retries_total                    # retry attempts
```

Rejected climbing with no downstream errors means the breaker is open on
stale failures: it closes on the next successful half-open probe (30s
open), or restart the calling service to reset it immediately.

**4. The ledger, if money is doubted.** Journals must balance even during
an outage — an unbalanced entry is a data incident, not a traffic one:

```sql
-- against fintech_transactions: must return 0
SELECT count(*) FROM journal_entries e WHERE (
  SELECT COALESCE(SUM(CASE WHEN l.direction = 'DEBIT' THEN l.amount_minor ELSE 0 END),0)
       - COALESCE(SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount_minor ELSE 0 END),0)
  FROM journal_lines l WHERE l.entry_id = e.id) <> 0;
```

And confirm no customer account went negative (amounts are minor units):

```sql
-- against fintech_transactions: must return 0
SELECT count(*) FROM ledger_accounts WHERE balance_minor < 0;
```

## Common causes and actions

| Cause | Signal | Action |
| --- | --- | --- |
| Downstream service down | `ServiceDown` + fail-closed 503s with the right codes | Fix or restart it; callers recover without intervention (breaker half-opens) |
| Kafka down | payments still 201, fraud/scoring quiet | See [kafka-down.md](kafka-down.md) — do **not** treat authorised payments as lost |
| Pool exhaustion (`ConnectionPoolExhaustion`) | `hikaricp_connections_pending > 0` | Slow query or open transaction: Postgres logs statements over 500ms (`log_min_duration_statement`); `statement_timeout` (30s) and `idle_in_transaction_session_timeout` (30s) bound it — find the waiter, do not raise the pool first |
| Bad deploy | `ContainerRestarting`, errors start at a deploy time | Roll back; the error code in the log names the refusing layer |
| Client retry storm | traffic spike, no errors, idempotency 409s | Check the caller's retry behavior; the keys are absorbing it (that is their job) |

## Recovery check

```bash
./scripts/verify-payment-lifecycle.sh
```

A green healthcheck is not recovery: healthy processes serve wrong
answers too. The lifecycle script funds, pays, replays byte-identically,
and re-checks the ledger balances — that is the check that matters.

## Stop conditions

- If the journal-balance query returns non-zero, stop restarting things:
  that is a data incident. Preserve logs and the database volume (`make
  down`, never `make clean-all`) and investigate before any further writes.
- If a 503 names a code you do not recognise, read the service's error
  catalogue (`*ErrorCodes.java`) before acting: every refusal is documented
  where it is raised.
