# ADR-0015: Breakers, limits, and practiced outages

- Status: accepted, implemented in Phase 13
- Date: 2026-09-27
- Phase: 13

## Context

Twelve phases built a platform that fails closed and says so: timeouts on
both synchronous hops, fail-closed error codes with correlation ids, Kafka
consumers with backoff and dead letters, idempotency that turns retries into
replays. What it did not have is anything that notices a dependency has
stopped answering and stops asking. A timeout bounds how long one attempt
takes; it does nothing about the next hundred attempts, each holding a
request thread for the full budget until the pool is exhausted — one slow
dependency becomes a fully unavailable service, exactly the failure the
timeouts were meant to prevent. At the edge, one aggressive client got full
gateway throughput: the gateway pom even claimed per-route rate limiting
that did not exist. And no outage had ever been practiced: every degraded-
not-dead property was argued, never observed.

## Decision

**Breakers on the two synchronous hops, as a triple with the existing
timeout and retry.** `OutboundGuard` (platform-common) wraps each adapter
call in retry-outer, breaker-inner: one extra attempt on transport failure
or 5xx, then the circuit opens for thirty seconds. Three rules are the
design: only transport failures and 5xx retry and count (a 4xx is a
definitive answer — retrying a refusal turns one "no" into several, and a
caller hammering a wrong id must never trip the breaker for everyone
else); an open breaker speaks the caller's own fail-closed vocabulary
(`ELIGIBILITY_UNAVAILABLE`, `TRANSACTION_UNAVAILABLE`), so open and down
are indistinguishable downstream; and the wiring is programmatic, because
the annotations elsewhere in this platform are inert and a rule expressed
only as an annotation is not enforced. Counters are explicit Micrometer
counters (`outbound.guard.*`), not aspect magic. Production patience is
50%/10 calls/30s-open/2 attempts; tests use a 2-call window with the same
discipline.

**Rate limiting at the gateway, keyed by verified identity.** Redis-backed
(the gateway runs more than one replica anywhere past a laptop, and a
per-instance bucket multiplies with the replica count), one limiter on
every route via `default-filters` — per-route snowflakes are refused until
a route earns one with a known legitimate burst. Buckets belong to the JWT
subject, so one caller's burst never spends another's; requests with no
identity (preflights, probes) share a source-address bucket rather than
being refused, because preflights carry no credentials by specification. A
denial is 429 with the framework's `X-RateLimit-*` headers and deliberately
no `Retry-After`: tokens refill continuously, so any fixed delay would be
fiction — the published rate is the retry advice. Global httpclient
timeouts (connect 2s, response 30s) bound gateway threads, not SLAs.

**Outages practiced, not just argued.** `scripts/chaos-degraded-lifecycle.sh`
stops customer-service (card issuing must 503 in its own vocabulary, fast,
with a correlation id, then recover) and stops kafka (a payment must still
authorise, no decision may exist while down, the blind payment must be
scored after recovery). Stops are reverted on EXIT — including on failure —
so a failed run never leaves the stack degraded. The `pause`-based stall
variant, which exercises timeouts and trip dynamics rather than fast
refusal, is documented in the script header as a manual incident rehearsal:
it takes minutes and belongs there, not in automation. Breaker trip and
half-open behaviour stay in deterministic stub-server unit tests.

## Consequences

- A downed dependency costs microseconds per caller once the breaker opens,
  instead of a thread per caller for the full timeout each.
- Behaviour change, stated plainly: one extra attempt (≤200ms) on transport
  failure or 5xx, and fast 503s while open. The codes callers see are
  unchanged.
- `make verify-live` runs the chaos script last; `outbound.guard.*`
  counters flow to the existing Prometheus.
- Rollout pattern for further hops: guard construction from properties plus
  stub-server trip tests, no new decisions.

## Rejected

- **Annotation-driven breakers**: same objection as the inert
  `@PreAuthorize` rules — unenforced rules with enforced looks.
- **In-memory rate limiting**: a limit that multiplies with the replica
  count is not a limit.
- **Per-route timeouts and limits now**: unjustified precision; one
  reviewed pair of numbers beats eight copies until a route's real burst is
  known.
- **Full chaos automation** (stalls, partitions, clock skew): minutes per
  run, timing-sensitive assertions, and trip dynamics already proven
  deterministically — automation would test patience, not behaviour.
- **Fixed `Retry-After` on 429**: a fiction against a continuously
  refilling bucket.
