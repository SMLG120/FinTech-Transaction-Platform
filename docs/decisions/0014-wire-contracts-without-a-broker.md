# ADR-0014: Wire contracts as shared fixtures, without a broker

- Status: accepted, implemented in Phase 12 (pattern established; rollout ongoing)
- Date: 2026-09-27
- Phase: 12

## Context

Eleven phases built a platform whose service boundaries are crossed in three
ways — synchronous HTTP (card→customer eligibility, dispute→transaction
lookup), Kafka events (dispute→ledger refunds, among others), and browser
calls (the dashboard) — and whose test suite verified each side of every
boundary in isolation. The consumer adapters are tested against stub HTTP
servers, the producers against their own DTOs, the event payloads per
service, the topics as a catalogue. What nothing tested is the agreement
itself: a renamed `kycStatus`, a reshaped resolution outcome, or a drifted
dashboard DTO passes every unit test and breaks in production — as money
silently not moving, which is the worst place to discover a contract.

The standard answer is a broker (Pact) or generated stubs (Spring Cloud
Contract). Both assume a piece of infrastructure the platform does not have
and a multi-repo workflow it does not use: one repository, one review, one
merge. A broker here would be a server that must run for tests to run,
holding contracts that could live in the same diff as the code they govern.

## Decision

**One fixture, two sides, no broker.** The `contracts/` module holds
canonical sample bytes — full realistic shapes, never minimal ones — and
each fixture is consumed twice:

- the **producer** test deserialises it into the real DTO (or serialises the
  real payload type) and pins the contracted values;
- the **consumer** test serves the same bytes from a stub HTTP server (or
  parses them with the real consumer types) and asserts the behaviour.

A rename breaks the producer side first, where the fix belongs. Consumers
keep the wire-string discipline they already had — reading `kycStatus`,
`status`, `outcome` as strings and refusing the unknown — so the fixture
proves tolerance of the real shape rather than of a minimal one.

**OpenAPI documents, not snapshots.** Services expose `/v3/api-docs` via
the long-pinned springdoc starter, and contract tests assert the paths and
property names consumers rely on — never a full-document snapshot, which
fails on cosmetic change, and never `required`, which springdoc does not
emit for unannotated records (annotating production DTOs to please a test
would invert the relationship). The docs endpoints inherit the service's
secure default: 401 without a verified identity, pinned by test, and
unreachable through the gateway in any case.

**The frontend reads the same files by path.** The dashboard's Vitest suite
parses the backend fixtures with Zod schemas, so there are no copies to
drift; Zod's key-stripping is the same assert-what-you-rely-on discipline.

**Installed-jar semantics are load-bearing.** Contract tests consume the
installed `contracts` artifact, so a fixture edit needs `mvn -pl contracts
install` before consumers see it — a released contract change is a
deliberate act, and the README says so. (An `mv`-restored file keeps its
old mtime and defeats incremental resource copying; `touch` or `clean`
after manual fixture surgery.)

## Consequences

- The two synchronous calls and one event pair are contracted on both ends;
  the dispute lookup adapter gained its first tests (it had none).
- `make contract` runs every `*ContractTest`, the two stub-server adapter
  suites, and the frontend fixture tests in one command.
- Rollout to the remaining services (fraud, audit, settlement,
  notification OpenAPI; further event pairs) follows the established
  pattern without new decisions.
- What this does not do: cross-version compatibility (no consumer supports
  two shapes yet, because no shape has changed twice), or performance
  assertions on the synchronous calls (Phase 14's problem).

## Rejected

- **Pact broker / Spring Cloud Contract**: infrastructure for a workflow
  this repo does not have; the same guarantees from files in the same diff.
- **Full-spec snapshots**: fail on cosmetic change, training reviewers to
  re-bless diffs they have not read.
- **Shared Java DTOs across services**: a rename becomes a compile break
  that drags one service's classpath into another's, and defeats the
  fail-safe string comparisons the adapters were written with.
