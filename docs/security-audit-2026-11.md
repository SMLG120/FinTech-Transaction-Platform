# Security & Correctness Audit — November 2026

Scope: all services under `services/`, `platform/`, the Keycloak realm, `docker-compose.yml`, the
static web UI (`web/`), Dockerfiles, Helm chart and scripts. Findings are ordered by risk (likelihood
× impact on money, identity or customer data). **Status** is the state of this branch.

| # | Risk | Area | Finding | Impact | Status / Fix |
|---|------|------|---------|--------|--------------|
| 1 | **Critical** | Identity (`fintech-realm.dev.json`, 11 scripts, 4 docs) | Every seeded user, **including `PLATFORM_ADMIN`**, shared the committed password `fintech-dev-only`. | Anyone with the repo can sign in as admin to any stack imported from this realm. | **Fixed.** Realm uses the Keycloak import placeholder `${SEED_USER_PASSWORD}`; `bootstrap.sh` generates a random 38-char value into `.env` (0600); scripts read it from `.env`; docs no longer print a password. |
| 2 | **High** | Payments (`TransactionService.reverse`) | A customer could reverse their own **settled (captured)** payment, which reverses the capture and refunds them with no merchant consent and no dispute. | Free refunds; bypasses the dispute workflow (ADR-0012). | **Fixed.** New `reverseRequestedByPayer` checks status inside the transaction: payers may only release an *authorised* hold; settled refunds go through a dispute (`409 SETTLED_REFUND_REQUIRES_DISPUTE`). Only allowed when `app.payments.sandbox-mode=true`. |
| 3 | **High** | Payments (`POST /api/v1/accounts/fund`) | Any customer could credit their own balance from the platform funding account, with no limit on how many times. | Unlimited money creation. | **Fixed.** Gated by `app.payments.sandbox-mode` (default **false**; only the local compose sets it). Production returns `403 SANDBOX_ONLY`. |
| 4 | **High** | Payments (daily limit) | `requireWithinLimits` read "spent today" *before* taking the ledger row lock (check-then-act). | N concurrent payments each pass the check and together exceed the daily ceiling. | **Fixed.** `LedgerService.lockCustomerAccounts` takes the same id-ordered `FOR UPDATE` lock before the limit is read; the later hold re-uses it. |
| 5 | **High** | Disputes (separation of duties) | `PLATFORM_ADMIN` may both open and resolve disputes, with no check that resolver ≠ opener. | One person can raise a dispute on their own payment and approve their own refund. | **Fixed.** `403 DISPUTE_SELF_RESOLUTION`; unit test added. |
| 6 | **High** | Disputes (race) | `resolve` / `addEvidence` read status without a lock and the entity has no `@Version`. | Two agents clicking REFUND and REJECT at the same time both publish an outcome. | **Fixed.** `findByIdForUpdate` (`PESSIMISTIC_WRITE`): the second caller waits, sees RESOLVED and gets `DISPUTE_NOT_OPEN`. |
| 7 | **High** | Front-ends (`authApi.ts`, `web/app/api.js`) | Browsers signed in with the OAuth **password grant (ROPC)** on a public client: the SPA handled raw passwords, and MFA/SSO were impossible. ROPC is removed in OAuth 2.1. | Credential phishing surface; no MFA. | **Fixed.** Both UIs use Authorization Code + PKCE (S256) with `state`/`nonce`/`iss`/`aud` checks, single-use callbacks, in-memory rotating refresh and RP-initiated logout. `fintech-web` has direct grants and implicit off, exact redirect URIs, no `offline_access`; scripts use a dev-only `fintech-cli` client. Verified live on Keycloak 26.7.4 (ADR-0013 amendment). Residual: dashboard self-registration still posts a new password to auth-service. |
| 8 | **Medium-High** | Gateway (`/register`) | Anonymous registration shared the platform limiter (100 req/s, burst 200). | Mass account creation; email enumeration through `409 EMAIL_ALREADY_REGISTERED`. | **Fixed.** `RegistrationThrottleFilter`: 5 attempts / 10 min per client address (Redis, shared across replicas, fails open like the platform limiter). |
| 9 | **Medium-High** | Gateway | `forward-headers-strategy: framework` trusted `X-Forwarded-For` from any client. | A caller can send a new spoofed IP on every request to dodge per-IP limits and poison logs. | **Fixed.** Default `none`; opt in with `SERVER_FORWARD_HEADERS_STRATEGY=framework` only behind an ingress that overwrites the header. |
| 10 | **Medium** | Identity | auth-service authenticates as `fintech-registration`, but that client **did not exist** in the realm. | Self-registration failed out of the box, which invites ad-hoc, over-privileged manual setup. | **Fixed.** Confidential, service-account-only client (`manage-users`, `view-realm`); its secret is a placeholder injected from `.env`. |
| 11 | **Medium** | Identity (realm) | No password policy, `sslRequired: none`, lockout only after 10 failures. | Weak passwords accepted; tokens over plain HTTP from non-local hosts. | **Fixed.** Policy (≥12 chars, upper/lower/digit, not the username/email, history 3), `sslRequired: external`, lockout after 5 failures. |
| 12 | **Medium** | Web UI (`web/nginx.conf`, Dockerfile) | No CSP, Permissions-Policy, COOP or HSTS; nginx version disclosed; ran as root; `/config.json` silently lost every security header (nginx drops inherited `add_header` in a location block that declares its own). | XSS has no second line of defence; container breakout runs as root. | **Fixed.** Shared `security-headers.conf` included at server level and per location, strict CSP, `server_tokens off`, `nginx-unprivileged` (uid 101, port 8080). Verified with curl. |
| 13 | **Medium** | Containers (`Dockerfile.service`) | `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp`. | An OOM writes decrypted PII, PANs in flight and all keys unencrypted to `/tmp`. | **Fixed.** Flag removed; take dumps deliberately with `jcmd` onto an encrypted volume. |
| 14 | **Medium** | Separation of duties (`TransactionAuthorization`) | `PLATFORM_ADMIN` could create payments, settle, reverse and fund, contradicting the realm's own role definition ("distinct from any role that moves money"). | One stolen operator session gives both control of the system and access to funds. | **Fixed.** Money movement is `CUSTOMER`-only. |
| 15 | **Medium** | Payments (card binding) | `POST /transactions` accepts any `cardToken`; transaction-service never checks that the card belongs to the payer or is `ACTIVE`. | Frozen, lost or cancelled cards can still be used to pay. | **Open.** Call card-service eligibility (as card-service already does with customer-service) behind `OutboundGuard`, failing closed. |
| 16 | **Medium** | Internal identity (ADR-0004) | One symmetric HMAC key is shared by every service; the signature does not cover method or path, and it can be replayed for 60 s. | Compromising any one service lets an attacker mint a `PLATFORM_ADMIN` identity for every other service. | **Open (architectural).** ADR-0016 mTLS migration; meanwhile, per-service asymmetric signing, or adding method + path + nonce to the canonical form. |
| 17 | **Medium** | Identity | The `manage-users` permission held by the registration service account can map *any* realm role. | A compromised auth-service could create `PLATFORM_ADMIN` users. | **Open.** Keycloak fine-grained admin permissions restricting role mapping to `CUSTOMER`. |
| 18 | **Low** | Payments API | `GET /accounts/balance?currency=XYZ` threw `IllegalArgumentException` → 500. | Noisy 5xx alerts; minor information leak. | **Fixed.** Returns `400 VALIDATION_ERROR`. |
| 19 | **Low** | Platform (`WebAutoConfiguration`) | A service started without `INTERNAL_IDENTITY_SIGNING_KEY` boots without the verification filter. | Fails closed (every request 500s) but is not caught at deploy time. | Open. Make the key mandatory outside tests, so a missing key fails startup instead of every request. |
| 20 | **Low** | Gateway | The audience-mismatch error echoes the expected audience. | Minor configuration disclosure. | Open. |
| 21 | **Low** | Identity | `verifyEmail: false`; registration does not prove mailbox ownership. | Accounts can be created with someone else's address. | Open (needs SMTP). |
| 22 | **Low** | Efficiency (auth-service) | `KeycloakUserProvisioningService` requests a new admin token on every call (up to 3 per registration). | Extra latency and load on Keycloak. | Open. Cache the token until shortly before `expires_in`. |

## Already strong (verified, no change needed)

- Double-entry ledger with id-ordered `SELECT … FOR UPDATE`, overdraft enforced under the lock,
  idempotency keys with request fingerprints, transactional outbox.
- PII is AES-256-GCM with per-customer HKDF keys and blind indexes; PANs are HMAC-tokenised with
  purpose-separated keys; subject digests are per-service, so services cannot be correlated.
- JWTs are verified at the gateway with RS256 only, plus issuer and **audience**; the internal
  identity is stripped and re-signed at the gateway and compared in constant time.
- All Compose ports are bound to `127.0.0.1`; secrets are generated by `bootstrap.sh` (`.env` is
  0600 and git-ignored); services run as non-root; error bodies never contain stack traces.

## Operational note

Keycloak imports a realm only if it does not already exist. A stack started before this change
keeps the **old** `fintech-dev-only` passwords in its `keycloak-data` volume until that volume is
recreated (`docker compose down -v` — destructive for local data — or remove just `keycloak-data`).
