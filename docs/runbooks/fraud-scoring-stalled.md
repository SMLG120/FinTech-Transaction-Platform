# Runbook: fraud scoring stalled

## Read this first: a quiet fraud dashboard is usually correct behavior

Fraud scores *after* the payment on no payment's critical path (ADR-0008).
A dashboard that stops moving while payments succeed is the architecture
working as designed under an upstream outage — not a second incident. Do
not "fix" scoring by making payments wait for it; that inverts the one
decision this design exists to protect.

## Symptoms

- Fraud queue and dashboard static; payments authorising normally.
- `NoKafkaEventsConsumed` for fraud-service topics, or `ConsumerLagGrowing`.
- Decisions carry `velocityAvailable=false` in their facts (Redis gap made
  visible in the data, not just the log).

## Triage

**1. Is anything supposed to be scoring?** If no payments are being made,
nothing should be scored. Check for recent payments first:

```sql
-- against fintech_transactions: recent payment count
SELECT count(*) FROM transactions WHERE created_at > now() - interval '15 minutes';
```

Zero payments means zero decisions is correct. Stop here.

**2. Where is the event stuck?** The path is transaction outbox → Kafka →
fraud consumer → decision row → outbox → `fraud-analysis-completed`:

```sql
-- against fintech_transactions: unpublished backlog (must drain, not grow)
SELECT count(*) FROM outbox_events WHERE published_at IS NULL;
-- latest decision the engine actually wrote (against fintech_fraud):
SELECT max(evaluated_at) FROM risk_decisions;
```

Backlog growing on the transaction side is a relay/producer problem (see
[kafka-down.md](kafka-down.md)). No backlog but no fresh decisions is a
fraud-consumer problem: check `docker compose logs fraud-service` for
rebalance lines before assuming the producer is at fault
(`NoKafkaEventsConsumed` says the same thing).

**3. Redis (velocity) or Postgres (decisions)?**

- `decisions with velocityAvailable=false` → Redis unreachable. Velocity
  rules are *skipped*, not treated as empty history — scores without memory
  are conservative by design. Fix Redis; no rescoring is needed for the
  gap, but note the window in the incident record.
- Fraud-service 5xx or `ServiceDown` → the service itself. Payments are
  unaffected; fix it and the relay catches up.

**4. Dead letters.** Anything the engine could not parse is republished to
`dead-letter-events` with the reason attached — never silently dropped.
Check the topic in Kafka UI (`:8081`) before concluding an event vanished.

## Recovery check

Pay once (any small amount) and poll for its decision the way the live
check does:

```bash
./scripts/verify-fraud-lifecycle.sh
```

The script provisions its own customers and waits for the engine rather
than assuming it — a green run proves scoring end to end, not just that
the process is up.

## Stop conditions

- If decisions exist for payments that never happened (rows with no
  matching transaction), stop: that is a data incident, not a stall.
  Preserve the fraud database volume and investigate before restarting
  anything.
