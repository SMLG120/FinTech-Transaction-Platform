# ADR-0003: Keycloak as the identity provider

- Status: accepted
- Date: 2026-09-26
- Phase: 1

## Context

The platform authenticates customers and staff, issues tokens, and needs roles, MFA, session
management, account recovery and an audit trail of authentication events. The gateway must verify
tokens; services must know the caller's identity.

Building this means implementing password storage, token issuance, key rotation, revocation and
MFA correctly — a large amount of security-critical code that is not the platform's actual
business.

## Decision

Use Keycloak as the identity provider. It runs in the local compose stack from Phase 1 as a
reachable, healthy server; the realm, clients, roles and protocol mappers are configured in Phase 2.

Tokens are OIDC, and the gateway is the only component that verifies them. Validated identity
travels to downstream services in signed internal headers rather than as a token each service
re-verifies — see [ADR-0004](0004-jwt-verified-at-the-gateway.md).

Keycloak runs with `start-dev` locally: no TLS, an in-memory-ish dev profile, permissive hostname
handling. Production runs `start` behind TLS with a fixed hostname.

## Consequences

**Good.** No custom credential handling. Roles, MFA, password policy, session limits and login
audit events come from a project that has been doing this for a decade. Keycloak's database can be
the one component whose schema this project does not own. Tokens are standard OIDC, so services
written in other languages can verify them.

**Bad, and accepted.** An external dependency the team does not control, and a large one to
operate. A Keycloak outage blocks new logins — existing valid tokens keep working until they
expire, which is why access token lifetime is kept short and refresh is handled explicitly. Keycloak
is a JVM application, so it needs the same resource discipline as the services. The realm
configuration is itself a deliverable that must be version controlled, since a realm configured by
clicking is not reproducible.

### Ports and local development

Keycloak listens on **8180** rather than the usual 8080, because 8080 is the gateway. The management
port 9000 carries the health endpoint, which is what the compose health check probes: the main port
serves the application and would answer a request from anything that reached it.

## Alternatives considered

**Hand-rolled authentication with Spring Security.** Rejected. Password hashing, token rotation,
revocation and MFA are all easy to get subtly wrong, and a subtle wrong answer here is a breach.

**A third-party hosted identity provider (Auth0, Cognito).** Rejected for now. A managed provider
reduces operational burden further, but it puts customer authentication data outside the
platform's control, which carries regulatory weight in financial services. Reconsider if the
operational cost proves dominant.

**Spring Authorization Server.** The closest alternative — it is a Spring project, so it fits the
stack. Rejected because it is a library to secure and operate rather than a product to configure,
which means most of ADR-0003's argument for Keycloak applies to it with none of the maturity.
