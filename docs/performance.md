# Performance: what is measured, what it means, what it does not

`scripts/load-smoke.sh` drives a bounded burst — 200 pay→settle cycles
across 8 workers by default — through the gateway against a running stack
(`make up` first), then asserts: no 5xx, no 429, no declines, pay p95 within
bound, the balance exact to the penny, and zero unbalanced journal entries.
`make load-smoke` runs it. It is a smoke test, not a capacity plan.

## Measured baseline

Warm laptop stack, loopback networking, default profile (200 × £5.00,
8 workers), two consecutive runs:

| Run | pay p50 | pay p95 | pay max | settle p95 | 5xx / 429 / 422 | Ledger |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 41 ms | 123 ms | 182 ms | 123 ms | 0 / 0 / 0 | exact |
| 2 | 28 ms | 92 ms | 128 ms | 115 ms | 0 / 0 / 0 | exact |

The p95 bound defaults to 500 ms: roughly 4–5x the measured p95, so a cold
stack passes and a real regression does not. The bound is measured, not
predicted — if the stack gets faster, lower it and say when.

## Knobs

`LOAD_WORKERS`, `LOAD_PAYMENTS`, `LOAD_AMOUNT`, `LOAD_P95_BOUND_MS`.
Raise workers toward the 100/s identity limiter deliberately, never
accidentally: the zero-429 assertion is the canary that the run stayed a
smoke test rather than becoming a limiter test. Amounts stay far under the
5000.00 per-transaction ceiling so every payment is about the platform,
never about an empty account.

## Bottlenecks, as known rather than as guessed

- **Database pools** (`DB_POOL_MAX_SIZE=20`, min-idle 5, shared shape across
  services): the first ceiling any sustained load meets. The Hikari-pending
  alert fires before it becomes user-visible — watch it during any run
  bigger than smoke.
- **One broker, RF=1; one Postgres.** Throughput and durability numbers on
  this stack describe a laptop, not production. The chaos script already
  proved the payment path survives a dead broker; it says nothing about a
  slow one.
- **Breakers and limiter under sustained load.** Eight workers never open
  the eligibility/lookup breakers and never touch the rate limiter. A soak
  that trips either teaches something; a smoke that does is misconfigured.
- **Fraud is decoupled by design.** Authorisation latency does not include
  scoring (ADR-0008); the smoke asserts authorisation only. Scoring lag
  under load is a consumer-lag question for the existing Kafka alerts, not
  for this script.
- **Money math is integer pence end to end**, including the balance
  assertion. Floats never touch money, not even in a test.

## What local numbers do not prove

Latency over loopback, warm JVMs, empty tables, no neighbours. Production
predictions need production-shaped load: sustained soak (not 200 requests),
realistic table sizes, noisy neighbours, and TLS + multi-AZ hops. That is
profiling work against a staging environment, and this stack is not one.
What the smoke does prove — on every run — is that the code paths did not
get slower or looser: p95 within bound, zero errors, ledger exact.
