# Service-level objectives

Two numbers define "healthy" for this platform. Both are measured, not
decreed: the error bound follows from what the correctness alert already
enforced, and the latency bound is the load-smoke bound from
[performance.md](performance.md).

## The objectives

| Objective | Window | Rationale |
| --- | --- | --- |
| 99.9% of requests per service are non-5xx | 30 days, rolling | A payment API that errors visibly fails customers; 5xx is never a refusal, only a bug |
| transaction-service p95 latency under 0.5s | 15-minute rate | Measured p95 is ~0.1s on a warm stack; 5x headroom catches regressions, not noise |

Deliberately absent: availability SLOs per dependency (Kafka, Postgres) —
their outages surface as latency and errors on the services, which is what
customers feel. And per-endpoint SLOs: the services are too small for endpoint
budgets to add information yet; add them when an endpoint's latency diverges
from its service's.

## Burn alerts

In `infrastructure/prometheus/alerts/platform-alerts.yml`, group
`slo_burn`:

- `ErrorBudgetFastBurn` (critical): 5xx above 10% for 40 minutes — 100x
  the 0.1% budget rate. Runs to [payments-failing.md](runbooks/payments-failing.md).
- `ErrorBudgetSlowBurn` (warning): 5xx above 1% for hours — 10x the budget
  rate. Catches what the 5-minute correctness window misses.
- `PaymentLatencyBreach` (warning): transaction-service p95 above 0.5s for
  15 minutes. Check pool/thread saturation on the same service first.

Thresholds are development values, like every rule in that file: on a quiet
local stack a ratio over sparse traffic is noisy, so the windows are long
and nothing here pages. Production thresholds follow from measured traffic.

## What this does not cover

Fraud-scoring lag has no SLO: scoring is advisory and asynchronous by
design, so "no decision in N seconds" is usually correct behavior (see the
fraud runbook). Settlement correctness has no rate to alert on either — a
statement is right or it is not, and the check is the reconciliation
finding, not a percentage. Both are supervised by their runbooks, not by
burn rates.
