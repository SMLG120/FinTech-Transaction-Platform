# Contracts

Shared wire-contract fixtures for the platform's synchronous inter-service
calls. No code, only JSON under `src/main/resources/contracts/`.

## Discipline: one fixture, two sides

Each fixture is a canonical sample response, consumed **twice**:

- the **producer** test deserialises it into the real DTO and asserts the
  contracted values (a producer rename breaks here);
- the **consumer** test serves its bytes from a stub HTTP server and asserts
  the adapter behaviour (a consumer parsing break shows up here).

| Fixture | Producer | Consumers |
| --- | --- | --- |
| `eligibility-response.json` | customer-service `CustomerResponse` | card-service `CustomerServiceEligibility` (`kycStatus`); dashboard `eligibilityResponseSchema` |
| `transaction-view.json` | transaction-service `TransactionResponse` | dispute-service `TransactionLookup` (`status`, `amount`, `currency`); dashboard `transactionViewSchema` |

The dashboard consumes the same files by path
(`frontend/fintech-dashboard/src/api/contractSchemas.test.ts`) — no copies,
so a copy can never drift. Zod strips unknown keys: the frontend asserts the
fields it relies on and tolerates the rest.

## Events (`contracts/events/`)

The same discipline for Kafka payloads: the producer serialises its real
payload type and the consumer parses the same bytes with its real consumer
types plus business rules — minus the side effects (covered by service tests
and the live lifecycle scripts).

| Fixture | Producer | Consumer |
| --- | --- | --- |
| `events/dispute-status-changed.json` (+ `-rejected`) | dispute-service `DisputePayload` | transaction-service `StatusChangedPayload` (`outcome`, `transactionUuid`) |

Note the tests consume the **installed** contracts jar, so a fixture change
needs `mvn -pl contracts install` before the consumer modules see it — the
same reason a released contract change is a deliberate act.

The values are synthetic. Consumers read the contracted fields as strings
and compare them — never deserialise the producer's domain types — so an
unknown value refuses safely instead of 500ing (see the adapter javadoc).

## Adding a fixture

1. Add the JSON with the full realistic shape, not the minimal one: the
   consumer must prove it tolerates the real response, unknown fields and all.
2. Add the producer test (deserialize into the real DTO, assert values) and
   point the consumer stub at the fixture bytes.
3. Depend on this module with `<scope>test</scope>` and load via
   `new ClassPathResource("contracts/<name>.json")`.
