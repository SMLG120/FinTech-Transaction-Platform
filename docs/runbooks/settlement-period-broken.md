# Runbook: settlement period broken

## Read this first: a BROKEN period is a finding, not an error

A closed period whose declared actual does not match is `BROKEN`, and that
is the terminal state recording the disagreement — not a failure to fix.
The operational question is never "is it up" but "is what it already
published still true". Do not look for a re-close or edit endpoint: there
is none, and that absence is the contract (see "Settlement operations" in
docs/deployment.md).

## Symptoms

- Period stays `OPEN` long after its date, or sits `BROKEN` with open
  findings.
- Operator cannot confirm a period (refused while a finding is open).

## Triage (as the settlement operator)

**1. Read the period and its findings** — the API, not the database:

```bash
TOKEN=$(./scripts/get-token.sh settlement)
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/settlement/cycles | python3 -m json.tool
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v1/settlement/breaks?status=OPEN" | python3 -m json.tool
```

**2. Match the finding kind to the cause** (full table in
docs/deployment.md):

| Finding | Likely cause | Action |
| --- | --- | --- |
| `PERIOD_ALREADY_CLOSED` | Payment settled after the close — expected at a batch boundary | Acknowledge with the period reference; no code change |
| Expected/actual mismatch | Late or duplicate movement, or a real bank difference | Read the two figures on the break; acknowledge, investigate the difference, resolve with what the money is doing ("fixed" is rejected — minimum 12 characters for a reason) |
| No lines for real payments | Relay not publishing / consumer stalled | Outbox backlog first (see below), not the period logic |

**3. Outbox backlog** (payments exist but statement lines are missing is a
relay problem, not a period problem):

```sql
-- against fintech_settlement: unpublished backlog
SELECT count(*) FROM outbox_events WHERE published_at IS NULL;
```

Growing means Kafka is unreachable or the relay is off — see
[kafka-down.md](kafka-down.md). Note the documented trade-off: turning the
relay off also stops pruning.

**4. Dead letters.** A movement that cannot be parsed is republished with
the reason attached and its line is missing — the finding is that the line
is absent, not that the total is wrong. Check `dead-letter-events` in
Kafka UI (`:8081`) before concluding money is missing.

## Recovery check

```bash
SETTLEMENT_CHECK_CURRENCY=EUR ./scripts/verify-settlement-lifecycle.sh
```

A fresh currency's period proves the machinery; the broken period itself is
never re-run (GBP is spent once closed). Never `--reset-period` a closed
period — it refuses, and working around that refusal would be editing a
given-out statement.

## Stop conditions

- If two periods exist for one day and currency, the database unique
  constraint should have refused the second — investigate how it was
  created before touching either.
- If the declared actual came from anywhere but the independent source
  (the bank figure, not our arithmetic), the reconciliation checked
  nothing: re-declare from the real source.
