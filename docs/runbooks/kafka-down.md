# Runbook: kafka down

## Read this first: the payment path does not need the broker

Authorisation is ledger-local; fraud scores after the payment; notifications,
audit, settlement and disputes consume events. A dead broker means payments
still authorise while everything downstream waits — degraded, not dead. The
chaos script proves exactly this live; run it after recovery if you doubt
the claim.

## Symptoms

- `ServiceDown` will not fire (Kafka is not in the `platform-services`
  scrape job the same way) — instead: `NoKafkaEventsConsumed`,
  `ConsumerLagGrowing`, outbox relays logging
  `relay pass failed; pending events will be retried`, fraud dashboard
  static, no new notifications or audit rows.
- `docker compose ps kafka` not healthy, or the port closed:
  `KAFKA_PORT` (default 9092) unreachable on 127.0.0.1.

## Triage

**1. Confirm payments are unaffected** — authorise a small payment (or read
recent authorisations). If payments 5xx, the problem is not Kafka alone;
switch to [payments-failing.md](payments-failing.md).

**2. Measure the backlog per service** — these rows are work waiting, not
work lost:

```sql
-- per service database (fintech_transactions, fintech_fraud, ...):
SELECT count(*) FROM outbox_events WHERE published_at IS NULL;
```

Growing on every service is a broker problem. Growing on one service with
a healthy broker is that service's relay (check its logs for the relay
failure line and the reason attached).

**3. Check the broker, not the clients:**

```bash
docker compose logs --tail=50 kafka
docker compose ps kafka
```

Out-of-disk on the broker volume looks like a client problem from every
service at once — `docker system df` distinguishes it in seconds.

## Recovery

```bash
docker compose start kafka
# wait for the port, then for the backlog to drain:
```

Consumers reconnect on their own; relays retry with backoff and give up
only per-pass, never permanently. Poll the outbox counts from step 2 until
they drain, then confirm the blind window was scored: query
`risk_decisions` for the affected transactions (the fraud runbook shows
how), and run the affected lifecycle scripts. Exhausted relay rows are
visible for replay — a backlog that never drains is a poison payload, and
the dead-letter topic names it.

## Stop conditions

- If the broker lost its volume (`kafka-data` deleted or
  `make clean-all`), events published-but-unconsumed are gone: reconcile
  each consumer's position from the source of truth (ledger for payments,
  outbox tables for the rest) rather than assuming redelivery.
- Single broker, RF=1: this stack has no replica to fail over to. That is
  the local-dev concession, documented — do not rehearse production
  failover here.
