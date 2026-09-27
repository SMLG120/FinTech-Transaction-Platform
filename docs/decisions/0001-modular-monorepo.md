# ADR-0001: Modular monorepo with a single Maven reactor

- Status: accepted
- Date: 2026-09-26
- Phase: 1

## Context

The platform has nine services that will be deployed independently. The build tool has to support
that without either (a) nine repositories that drift apart, or (b) a shared library that forces all
nine to release together.

Two further constraints shaped this: the project is being built in phases by a small number of
people, so a change spanning several services must be one commit rather than a coordinated set of
pull requests; and the deliverable has to be reproducible from a single command on a fresh machine.

## Decision

One repository, one Maven reactor, twelve modules: `platform-common`, `platform-common-web` and nine
services. The reactor builds all of them together, but the modules share no lifecycle — a service
is packaged and deployed on its own, and nothing requires two services to be released at once.

Two shared modules exist rather than a library of everything:

- `platform-common` — transport-agnostic. Error contract, event envelope, topic catalogue,
  correlation id, pagination, common metric tags.
- `platform-common-web` — servlet-specific. Correlation id filter, centralized exception handling.
  The gateway is reactive, so it does not depend on this module, and keeping the split means the
  servlet stack cannot leak into the Netty service.

## Consequences

**Good.** A cross-service change is atomic and reviewable as one diff. A fresh clone builds with
`./mvnw verify`. Versions cannot drift between services, because there is only one place to
declare them. Git history tells the story of the whole system, and `git log -S` finds every use of a
changed contract.

**Bad, and accepted.** The reactor's build time grows with the number of modules. Enforced
boundaries are social rather than technical: nothing prevents a service from depending on another
service's module. The test is whether a build fails when an individual service cannot be built,
and the answer is that it should — that is a signal the dependency is in the wrong direction.

**Guardrails added.** The rule for what may enter `platform/` is "at least two services need it, and
not for a single service's convenience." That rule is what keeps the shared modules from becoming
the place every shortcut goes. A `jdeps`-style check in the quality profile flags a service
depending on another service.

## Alternatives considered

**Nine repositories.** Rejected. Cross-service contract changes become multi-repository
coordination, which is the failure mode most likely to leave the platform inconsistent, and the
thing a monorepo exists to prevent.

**A single deployable with internal modules.** Rejected. It satisfies every build requirement and
fails the deployment requirement: one slow or unstable service takes the payment path down with it,
and the transaction service ends up scaled for the notification service's volume.

**Gradle instead of Maven.** Rejected. The Spring ecosystem's tooling and documentation assume
Maven, and Spring Boot's own parent POM is a Maven artifact. The configuration-cache build
performance argument is real but not decisive at this size.
