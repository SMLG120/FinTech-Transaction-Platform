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

**Roles** — `PLATFORM_ADMIN`, `CUSTOMER`, `SUPPORT_AGENT`, `COMPLIANCE_OFFICER`, `AUDITOR`,
`FRAUD_ANALYST`, `SETTLEMENT_OPERATOR`. The gateway maps `realm_access.roles` onto Spring authorities,
so these names are the authorisation vocabulary of the whole platform; adding one here without a matching
route rule grants nothing. The last two exist because a staff surface that reuses a customer role is not a
staff surface, and each is the one identity that can change rather than only read: an analyst works the
fraud queue, a settlement operator closes periods and declares actuals, and neither of the reader roles
(`AUDITOR`, `COMPLIANCE_OFFICER`) may do either.

**Clients** — `fintech-api` is a bearer-only client whose client id is the token audience, and
`fintech-web` is the public client local tooling uses to obtain tokens. The audience mapper on
`fintech-web` is what puts `fintech-api` in `aud`; without it every token this realm issues is
audience-agnostic and would be accepted by any service that only checks the issuer.

**Users** — seven synthetic identities, one per role, all with the password `fintech-dev-only`. They
exist so authorisation can be exercised end to end; they are not credentials for anything.
`analyst@fintech.test` holds `FRAUD_ANALYST` and is the identity that can work the fraud queue;
`auditor@fintech.test` and `compliance@fintech.test` can read it and cannot change it, which is the
separation `FraudAuthorization` enforces and the reason it is a separate role rather than another
`COMPLIANCE_OFFICER`. `settlement@fintech.test` holds `SETTLEMENT_OPERATOR` and is the identity
`scripts/verify-settlement-lifecycle.sh` closes periods as.

**Adding a role or user here does nothing to a running stack.** Keycloak imports a realm only when it
does not exist, so the file's contents apply to a fresh `bootstrap.sh` and to nothing else. The recovery is
the delete-and-restart above, and the symptom is worth recognising: the token is issued, it authenticates,
and it carries no role, so the request is refused by the gateway as `INSUFFICIENT_ROLE`. That reads like a
gateway misconfiguration and is usually a realm that was never re-imported. The two live checks that need a
staff identity say which one they wanted.

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

## Two import facts that bite later

Both of these were found by deleting the realm and re-importing it, which is the only time the
file is read in full.

- **Role descriptions must fit in 255 characters.** Keycloak stores them in a `VARCHAR(255)`, and
  a longer description fails the whole import with `Value too long for column "DESCRIPTION"` —
  leaving no realm at all rather than a realm with a truncated description. The failure only
  appears on a fresh import, so a description that grows past the limit sits in the file silently
  until the next delete-and-restart. Count before committing.
- **`webOrigins` on `fintech-web` lists every browser origin that may call the token endpoint,
  currently `http://localhost:3000`, `http://localhost:3001` (the web UI),
  `http://localhost:8081` and `http://localhost:5173` (the React dashboard dev
  server).** Without its origin here, a UI gets no `Access-Control-Allow-Origin`
  on the token response and the browser blocks reading it, which surfaces as
  a bare "fail to fetch" rather than a login error. An origin added to the gateway's CORS allow-list but not here gets
  `{"error":"Invalid origin"}` from the token endpoint — a login that fails before any password
  is checked, which reads as broken credentials rather than a mismatched string. Like every realm
  change, an addition here does nothing to a running stack until the realm is deleted and
  re-imported.
