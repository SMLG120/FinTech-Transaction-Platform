# Keycloak realm

`fintech-realm.dev.json` is the realm this platform runs against. It is imported automatically at
startup, so the roles, clients and test users that Phase 2's authorisation depends on live in a file
that can be reviewed in a diff — rather than in one person's browser session, where they are
invisible, unreviewable, and unreproducible.

Edit the JSON and restart Keycloak. Keycloak skips a realm that already exists, so after changing the
file delete the realm first:

```bash
# delete the realm, then
docker compose restart keycloak
```

> **This realm is for local development only.** It contains fixed passwords and
> `directAccessGrantsEnabled`, and it runs with `sslRequired: none`. It must never be imported into
> an environment reachable from anywhere but a developer's own machine.

## What it defines

**Roles** — `PLATFORM_ADMIN`, `CUSTOMER`, `SUPPORT_AGENT`, `COMPLIANCE_OFFICER`, `AUDITOR`. The
gateway maps `realm_access.roles` onto Spring authorities, so these names are the authorisation
vocabulary of the whole platform; adding one here without a matching route rule grants nothing.

**Clients** — `fintech-api` is a bearer-only client whose client id is the token audience, and
`fintech-web` is the public client local tooling uses to obtain tokens. The audience mapper on
`fintech-web` is what puts `fintech-api` in `aud`; without it every token this realm issues is
audience-agnostic and would be accepted by any service that only checks the issuer.

**Users** — six synthetic identities, one per role, all with the password `fintech-dev-only`. They
exist so authorisation can be exercised end to end; they are not credentials for anything.
`analyst@fintech.test` holds `FRAUD_ANALYST` and is the identity that can work the fraud queue;
`auditor@fintech.test` and `compliance@fintech.test` can read it and cannot change it, which is the
separation `FraudAuthorization` enforces and the reason it is a separate role rather than another
`COMPLIANCE_OFFICER`.

**Throwaway identities** — `scripts/verify-card-lifecycle.sh` creates `cardcheck-a@fintech.test`,
`cardcheck-b@fintech.test` and `cardcheck-c@fintech.test` through the admin API on first run, and
leaves them there. They are in Keycloak and nowhere else; no fixture file lists them.

They exist because the five identities above cannot support a two-customer test. Only `CUSTOMER` and
`SUPPORT_AGENT` may hold a customer profile, so there is exactly one spare registrar, and Phase 3's
erasure check spends it permanently: `DELETE /api/v1/customers/me` scrubs the subject, after which
that identity answers 409 on register and 410 on `/me` for good. A live check that hardcoded an
identity would therefore stop working the first time erasure was tested. Adding them to
`fintech-realm.dev.json` would not help either, because import skips a realm that already exists, so
the file's contents only ever apply to a fresh `bootstrap.sh`.

Two details cost a debugging round each and are recorded so the next person does not repeat them:
roles cannot be set in the create payload (`realmRoles` there is ignored, and the separate role
assignment wants the full role representation rather than a `{id, name}` pair, or it answers
"Role not found"), and a user created without `firstName`/`lastName` cannot log in at all, failing
with "Account is not fully set up" rather than a permissions error.

## Two settings that look optional and are not

Both of these were found by issuing a token and reading it, because neither produces an error — a
token with no roles still looks like a valid token.

- **`defaultClientScopes` must list `roles` explicitly.** Realm import does not apply the realm's
  default client scopes to an imported client, so a client that omits the list gets none of them and
  its tokens carry no `realm_access` claim at all.
- **`fullScopeAllowed` must be `true`.** Setting it to `false` on the client, which reads like a
  tightening, leaves the client with the explicitly listed scopes only — and in this realm that
  combination produced tokens with no roles even though `roles` was listed.

A client created through the admin API behaves differently from an imported one, which is what makes
this so easy to get wrong: the API path applies the defaults automatically. Verify changes by
decoding a token, not by reading the JSON back:

```bash
./scripts/get-token.sh customer --decode | jq .realm_access
```
