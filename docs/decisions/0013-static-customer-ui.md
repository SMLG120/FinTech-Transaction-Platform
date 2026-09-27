# ADR-0013: A static customer UI with no backend of its own

- Status: accepted, implemented in Phase 11
- Date: 2026-09-27
- Phase: 11

## Context

Phases 5 through 10 built an API-only platform verified exclusively by scripts that speak HTTP.
That is a complete platform with no way in for a human: every customer journey — register, fund,
pay, dispute — requires curl and a token script. Phase 11 is the browser that performs those
journeys, deliberately the thinnest client that can honestly do so.

Three questions have to be answered, and each has a convenient wrong answer.

**What serves the UI?** The obvious design is an eleventh Spring service rendering server-side
pages. It would need a session or token store, a template engine, and a second implementation of
every validation the API already owns — a backend in disguise, with its own database-shaped state
to secure. So the UI is static files on nginx: HTML, CSS and dependency-free JavaScript with no
build step. The browser talks to the gateway and Keycloak directly, which means the bytes served
are the bytes reviewed, and a compromised build cache cannot inject anything into them because
there is no build. The container holds no secret and takes no environment because there is nothing
to configure into it beyond public endpoint addresses.

**How does the browser authenticate?** The convenient answer is the password grant, and it is
accepted here — for local development only, and explicitly fenced as such. The dev realm's
`fintech-web` client is public with direct grants enabled, which is what `get-token.sh` and every
verify script already use. A public client cannot keep a secret, so in production the UI moves to
Authorization Code with PKCE and the password grant is disabled on the realm; the `ApiClient`
isolates the grant in one `login` method so that migration touches one function rather than every
call. The token lives in memory on the client object — never localStorage, never a cookie, never
the URL — because anything persisted outlives the session and becomes exfiltration bait for the
first XSS hole. The UI has no XSS of its own to offer: no framework, no `innerHTML` on server
data except the card number the platform shows exactly once... and even that is set through
`textContent`.

**What does the browser get to call?** The gateway admits the UI's origin and nothing else — one
enumerated loopback origin, never a wildcard — and answers preflights for exactly the methods and
headers the UI sends, including `Idempotency-Key`. The preflight rule permits all OPTIONS without
credentials because browsers send none with a preflight; the actual request underneath stays
authenticated, so permitting the preflight removes no check. Keycloak lists the same origin for
the token endpoint. Three places name the origin — nginx serves it, the gateway allows it,
Keycloak lists it — and the frontend verify script checks the agreement rather than any one of
them, because an origin allowed in one place and refused in another is a UI that loads and then
cannot do anything, which reads as broken APIs rather than a mismatched string.

## Decision

**Customer journeys only.** Overview, fund, pay, cards, activity, disputes, plus register with a
synthetic identity check. No staff surface: the fraud queue, settlement periods, the audit trail
and dispute resolution stay API-only, because a staff UI is a second authorization model to keep
in step with the first, and the gateway already keeps unauthenticated browsers off those paths.
An auditor opening the UI gets a login screen that cannot show them anything — which is the
property, not a missing feature.

**One key per click.** Every money-moving submit generates a fresh idempotency key, and the submit
button disables while the call is in flight. Double-clicking Pay cannot charge twice: the first
click's key makes the second click's replay byte-identical rather than a second payment. A retry
after a network failure is a conscious second click with a new key, not a silent replay — because
silently replaying a top-up the user may not have meant is worse than asking them to confirm.

**Pay-then-settle stays visible.** The UI captures immediately after authorising, but as two calls
rather than one hidden flow. A held-but-unsettled payment reads as what it is, and the two-step
shape mirrors the API instead of inventing a checkout abstraction the platform would then have to
keep honest.

**The card number appears once and the token never does.** Issue renders the number with a warning
to copy it; afterwards only the last four exist anywhere, including in this UI. Paying takes a
stand-in token generated client-side — the wallet token's understudy, like the API scripts' own
`tok_` values — because the list endpoint deliberately carries no token to display. A production
wallet stores tokens server-side; the browser never should, and this UI does not pretend
otherwise.

**Tests split by seam, like everything else in this repository.** Pure client logic (keys,
config, error parsing, header discipline) runs under `node --test` with stubbed fetch — no
browser, no server, no Docker. What only exists in a browser — the preflight agreement, the served
bytes, the grant with an Origin header — is asserted live by `verify-frontend-lifecycle.sh`
against the running stack.

## Consequences

The UI cannot do what the API cannot: no per-customer notification reads (there is no route by
design), no staff views, no file evidence on disputes (text only, same cap). Each of those reads
as a missing button rather than an error, which is honest — the button would call an endpoint
that refuses.

The password grant in a browser trains exactly one bad habit: credentials in a page. It is fenced
to local development by documentation and by the realm being local-only, but fencing by
documentation is the weakest kind. Disabling direct grants and moving to code+PKCE is
strategically placed work for the production-hardening phase, and the single `login` method is
where it lands.

`config.json` carries public endpoint addresses with `no-store` caching, because a cached config
from a previous environment boots the UI against the wrong gateway — a failure that reads as
broken APIs. It carries no secret, and the verify script greps the served files to keep it that
way rather than asserting it in a comment.

## Rejected

**A server-rendered frontend service.** A backend in disguise, with sessions to secure and
validations to duplicate. The platform's validations live in the API; the UI renders their
answers, including the correlation id on every refusal so a ticket can quote one id.

**A framework SPA with a build pipeline.** More machinery than six forms need, and a supply chain
the static files do not have. If the UI grows interactive enough to need one, that is a rewrite
with its own ADR, not an incremental drift.

**Wildcard CORS and credentialed preflight exceptions.** `allowedOrigins: "*"` answers every
site's preflight, and the failure is silent approval. The enumerated origin fails closed on a
typo, which is the direction a browser-facing allow-list must fail.

**Tokens in localStorage.** Persistent, exfiltratable, and the default in too many tutorials. The
price of in-memory tokens is re-login on refresh; the price of stored tokens is a session that
survives the tab it was made in.
