# ADR-0004: JWT verified at the gateway only

- Status: accepted, implemented in Phase 2
- Date: 2026-09-26
- Phase: 2

## Context

Nine services need to know who is calling and what they are permitted to do. The token is a signed
JWT, so each service *could* verify it independently.

Nine independent verifications means nine places to get issuer URI, audience, clock skew, algorithm
allow-list and key rotation correct. Any one of them wrong is either an outage or, worse, an
incorrect acceptance. Nine services also means nine copies of the JWKS cache, nine refresh schedules
and nine places where a key rotation causes an incident.

## Decision

The gateway verifies the JWT. Once. It forwards the validated identity to downstream services in
signed internal headers.

Downstream services trust those headers **only** on a connection authenticated by mutual TLS, and
the network policy in Phase 15 prevents any client from reaching a service directly. The gateway is
the only ingress.

Until mTLS exists, the headers carry an HMAC-SHA256 signature over a canonical form of the identity,
verified by every service against a shared key. That is a weaker property than an authenticated
connection, and Phase 15 is what replaces it. A key that any one of the nine services can use to mint
an identity for any user is the specific gap, and it is a deployment fact rather than a design
choice.

## Consequences

**Good.** One verification implementation, one JWKS cache, one clock-skew policy, and one place to
audit. Rotating a signing key is a gateway change, not nine coordinated deploys. Services receive
identity as plain headers and need no JWT library at all.

**Bad, and accepted.**

- **The gateway is a single point of verification.** Its availability gates the whole platform. It is
  stateless and the cheapest component to scale, and a payment platform without an ingress is not
  usable, so this is an acceptable dependency — but it is a real one and it is monitored as one.
- **Header propagation is a trust boundary.** If a service is ever reachable without mTLS,
  the headers are forgeable and the entire authorisation model collapses. This is why the network
  policy is a Phase 15 requirement rather than a nice-to-have, and why the compose file binds
  service ports to `127.0.0.1` in the meantime.
- **A large token inflates every request.** Bounded by not putting per-request authorisation data
  in the token; a fat token is a cache problem across nine services.
- **Debugging crosses a boundary.** A request's identity is established in one process and consumed
  in nine, so the correlation id and the forwarded identity have to be traceable end to end. That
  is what the shared correlation id module is for.

### Why not pass the token through and verify everywhere

It is the more common design and it is defensible. It is rejected here because defence in depth is
already provided by the network layer, while the cost of nine verifiers is not theoretical: it is
nine chances to misconfigure an audience check, and an audience check that is too permissive is an
authorisation bypass rather than an outage.

### What Phase 2 built

**Gateway verification.** `RS256` only, issuer matched exactly, `fintech-api` in `aud`, expiry and
not-before checked. The audience is checked rather than inferred from the issuer, because the realm
issues tokens to other clients and a token minted for one of those is still a valid signature.

**Per-route authorisation.** Deny by default, by `realm_access.roles`:

| Path | Roles |
| --- | --- |
| `/api/admin/**` | `PLATFORM_ADMIN` |
| `/api/support/**` | `SUPPORT_AGENT`, `PLATFORM_ADMIN` |
| `/api/compliance/**` | `COMPLIANCE_OFFICER`, `PLATFORM_ADMIN` |
| `/api/audit/**` | `AUDITOR`, `COMPLIANCE_OFFICER`, `PLATFORM_ADMIN` |
| `/api/**` | `CUSTOMER`, `SUPPORT_AGENT`, `PLATFORM_ADMIN` |

Scopes never grant a role. Keycloak's `scope` claim and role names do not collide today, and a
mapping that treated a scope as an authority would turn a realm administrator's ability to add a
scope into an authorisation bypass.

**Identity forwarding.** Six `X-Internal-Identity-*` headers, signed, with roles in sorted order so
that the signature does not depend on ordering. Every header with that prefix is stripped from the
inbound request before the gateway adds its own — otherwise a client could set a header the gateway
does not itself write, and the service would have no way to tell the difference.

**Service enforcement.** `platform-common-web` verifies the signature, the freshness bound and the
correlation id, and exposes the result as `CurrentCaller`. `require()` throws rather than returning a
placeholder, so a background job cannot end up executing as an invented user.

**Two defects the tests found, both of which would have shipped.**

- Auditors and compliance officers were granted `/api/**` by a matcher written for the general case.
  The role hierarchy is not a prefix, so each path needs its own statement.
- The forwarding filter overwrote the headers it writes without removing the ones it does not,
  leaving a client-supplied `X-Internal-Identity-Issued-At` alongside a valid signature. A service
  reading any single header in isolation would have accepted it.

**Two defects the live stack found, which no unit test could.** Both are recorded here because the
lesson is the same as the Phase 1 Prometheus finding: the build was green and the platform was not
secure.

- A service with a correct signing key and no `enabled` flag came up with verification off, because a
  boolean nobody sets is `false`. The unit tests constructed the properties object and passed `true`,
  so they agreed with each other and disagreed with production. The flag is gone: a service either
  verifies or is not configured to receive identities at all.
- The key was written as a required YAML placeholder in every service, so `mvn test` failed on any
  machine without the environment variable, and the only way to make that work — an empty default —
  satisfies `@ConditionalOnProperty` while leaving nothing to verify against. Registering the filter
  in that state rejects every request rather than none. Hence `OnInternalIdentityKeyPresentCondition`,
  which requires a non-blank key.

### Operational endpoints are exempt, deliberately

A service answers `/actuator/health` and `/actuator/prometheus` with no signed identity, because
Docker cannot present one and neither can Prometheus. The alternative is a platform that is
unmonitorable while its dashboards stay green, which is the failure mode that gets discovered during
an incident rather than during a build.

The exemption is a list, not a prefix. `/actuator/env`, `/actuator/heapdump` and `/actuator/loggers`
all require a verified identity, because they disclose how the service is configured and secured.
The gateway's own operational endpoints are likewise the only paths it serves without a token, which
was measured during Phase 1: Spring Boot's default chain protects `/actuator/prometheus`, so a
correctly configured scrape gets nothing while still reporting the target as up.

## Alternatives considered

**Verify in every service.** Rejected above.

**A service mesh (Istio, Linkerd) terminating and verifying JWTs.** The more complete answer, and
the likely direction for production: it removes the concern entirely and gives mTLS, retries and
telemetry without application code. Rejected for Phase 1 because it introduces a substantial
operational surface before there is an application to protect, and because a mesh that is
misconfigured fails in ways that are very hard to diagnose. Worth revisiting in Phase 15.
