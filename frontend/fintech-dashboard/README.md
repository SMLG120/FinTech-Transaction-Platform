# FinTech Dashboard — React + TypeScript Frontend

Development customer/staff dashboard for the Secure FinTech Transaction
Platform. Pages call the real gateway APIs; there is no fake backend. This
source app is not a production deployment: Compose does not build or serve it,
and its password-grant login must be replaced with Authorization Code + PKCE
before it is exposed outside local development.

> **Synthetic data only, no real money.** Card numbers are shown once at
> issue and never stored; the platform holds tokens and last-four digits.

## Why React + TypeScript

- The static UI had served its purpose (proving the gateway contract from a
  browser) but could not carry role-based staff views, validated forms,
  charts, or tests without becoming unmaintainable string-concat DOM code.
- TypeScript DTOs mirror the backend records field-for-field, so a backend
  contract change breaks the build instead of a page at runtime.
- The ecosystem pieces (Router, TanStack Query, RHF+Zod, Recharts) each own
  one concern instead of hand-rolled equivalents.

## Stack

| Concern | Choice |
| --- | --- |
| Build / language | Vite 6 + React 18 + TypeScript (strict, `noUnusedLocals`) |
| Routing | React Router 7, role-guarded routes |
| Server state | TanStack Query (caching, refetch, loading/error states) |
| Forms | React Hook Form + Zod (`@hookform/resolvers`) |
| Charts | Recharts, lazy-loaded in its own chunk |
| Icons | Lucide |
| Animation | Framer Motion (`MotionConfig reducedMotion="user"`, CSS kill-switch) |
| Tests | Vitest 4 + Testing Library + jsdom |

No Tailwind, no component-framework dependency — the design system is a
small set of CSS tokens + primitives, which keeps the bundle and the API
surface reviewable.

## Running the frontend

```bash
cd frontend/fintech-dashboard
cp .env.example .env   # public endpoint addresses only — never secrets
npm install
npm run dev            # http://localhost:5173
```

| Command | What |
| --- | --- |
| `npm run dev` | Vite dev server on `:5173` |
| `npm run typecheck` | `tsc --noEmit` |
| `npm run lint` | ESLint, zero warnings allowed |
| `npm test -- --run` | Vitest suite (42 tests, incl. wire-contract parsing) |
| `npm run build` | Typecheck + production bundle in `dist/` |
| `npm run preview` | Serve the production bundle locally |

The gate before any change: `npm run typecheck && npm run lint && npm test -- --run && npm run build`.

## Wire contracts

`src/api/contractSchemas.test.ts` parses the backend's canonical fixtures
(`contracts/src/main/resources/contracts/*.json`) with Zod response schemas.
Same files the backend producer/consumer tests use — read by path, no
copies. A backend field rename breaks this suite before it ships a page
rendering `undefined`. Run alone with
`npx vitest run src/api/contractSchemas.test.ts`, or everything via
`make contract` from the repo root.

## Environment variables

Only public endpoint addresses. The token is obtained at runtime via
Keycloak and kept in memory (dies with the tab — never localStorage,
cookie, or URL).

| Variable | Default | Meaning |
| --- | --- | --- |
| `VITE_GATEWAY_URL` | `http://localhost:8080` | API gateway |
| `VITE_KEYCLOAK_URL` | `http://localhost:8180` | Keycloak |
| `VITE_KEYCLOAK_REALM` | `fintech` | Realm |
| `VITE_KEYCLOAK_CLIENT_ID` | `fintech-web` | Public client |

## API configuration

One central client (`src/api/client.ts`) — the only place that touches
`fetch` for gateway calls. It attaches a fresh `X-Correlation-Id` per call
and an `Idempotency-Key` per mutating submit, and parses failures into
`ApiError{status, code, correlationId}`. Feature modules (`transactionApi`,
`fraudApi`, …) expose TanStack Query hooks; components never call `fetch`.

Amounts travel as decimal strings both ways (a JSON number arrives as a
double); scores are integers. 404 on a fraud decision means "not scored
yet" (scoring is async); a mismatch on settlement declare-actual is a 200
with a finding, never an error.

## Authentication flow

```
browser ──redirect──▶ Keycloak login page ──?code&state──▶ /auth/callback
        ──code + PKCE verifier──▶ Keycloak token endpoint ──JWT──▶ gateway ──signed headers──▶ service
```

1. `LoginPage` has no password field. "Sign in securely" calls `beginLogin()`
   (`src/api/authApi.ts`): a one-time `code_verifier`, `state` and `nonce` go to
   `sessionStorage` for this tab only, and the browser is sent to Keycloak with
   the S256 `code_challenge`. The dashboard never sees the password, so MFA,
   lockout and password policy all live in Keycloak.
2. Keycloak redirects to `/auth/callback`. `AuthCallbackPage` calls
   `completeLogin()`, which checks `state` (login CSRF), consumes the pending
   entry exactly once, redeems code + verifier, and checks the ID token's
   `nonce`, `iss` and `aud`. The callback URL is replaced in history.
3. `AuthProvider` holds the access, refresh and ID tokens **in memory only**,
   refreshes 30 s before expiry (rotating refresh tokens), and on sign-out
   calls Keycloak's end-session endpoint so SSO ends too. A reload costs one
   redirect, which Keycloak's SSO cookie answers without a prompt.
4. `AuthProvider` decodes `realm_access.roles` + username **for UI gating only**.
5. `RequireAuth` redirects to `/login` (remembering `from`); `RequireRole`
   renders an inline 403 explanation. The gateway and services enforce the
   real policy — the UI only decides what to show.

Local test logins: `customer@`, `agent@`, `auditor@`, `compliance@`,
`analyst@`, `settlement@`, `admin@fintech.test`, with the random `SEED_USER_PASSWORD`
that `./scripts/bootstrap.sh` wrote to the repository's `.env`.

## Route structure

| Path | Who | What |
| --- | --- | --- |
| `/login` | public | Sign in (RHF+Zod) |
| `/` | all | Dashboard: balance hero, icon stat cards, lazy charts, recent activity, security watch |
| `/transactions`, `/transactions/:id` | all | Toolbar (search/filter/sort/clear) + paginated ledger; investigation (summary, lifecycle timeline, risk decision, audit trail, messages) |
| `/cards` | all | Visual card previews, issue (number shown once), freeze/unfreeze/lost/close with modal confirm |
| `/pay` | customer, support, admin | Fund + pay forms; live payment summary; authorise→settle kept explicit |
| `/profile` | customer, support, admin | Own profile, KYC history, update, irreversible erasure |
| `/customers` | support, admin | Masked profile lookup |
| `/disputes`, `/disputes/:id` | customer, support, admin | Open (settled own payments), plead, staff refund/reject |
| `/fraud`, `/fraud/alerts/:id` | analyst, compliance, auditor, admin | SLA callout, risk-meter queue, claim/close (analyst+admin), overrule/re-score |
| `/audit` | auditor, compliance, admin | Trail + correlation/transaction/resource lookups |
| `/settlement`, `/settlement/cycles/:ref` | settlement, compliance, auditor, admin | Periods, statements, actuals, breaks |
| `/notifications`, `/notifications/:id` | support, admin | Delivery log + retry (FAILED only) |

Role lists mirror `GatewaySecurityConfiguration` and each service's
`*Authorization`; the backend stays authoritative.

## Component architecture

```
src/
  api/          client.ts (sole fetch owner) + per-service hooks
  types/        backend-mirroring DTOs
  components/ui Button, Card/StatCard/CardHeader, Badge, PageHeader/Breadcrumbs,
                states (Skeleton/TableSkeleton/Empty/Error), Toast (aria-live),
                FieldError patterns
  layouts/      AppShell (grouped sections, active indicator, user chip +
                sign-out, collapsible <900px) + Outlet
  routes/       AppRoutes + RequireAuth/RequireRole
  features/     auth, dashboard (+stats, lazy Charts), transactions,
                cards, payments (schemas), customers, disputes, fraud
                (DecisionPanel + RiskMeter shared), audit, settlement,
                notifications
  utils/        formatMoney, userMessage (status → friendly text + ref id)
```

## Design system

`src/index.css` owns every token and pattern — no page-level styling
decisions. Colours (single navy/blue accent, status softs that always pair
with a text label), type scale, spacing, radii, and shadows are variables;
pages compose `page-head` (eyebrow/title/sub/actions), `toolbar`,
`table-wrap` + `table.data` (sticky headers, tabular numerals), `alert`
(info/success/warning/danger with icon + text), `spec` definition lists,
`timeline`, `modal`, `hero`, and the shared state blocks. Charts use the
same status palette as the badges. Breakpoints at 1100/900/560px reorganise
(hero stacks, toolbar fields go full-width, tables scroll) rather than
shrinking the desktop layout.

Every API-driven view has loading / skeleton / empty / error / retry
states. Status badges pair text with color (never color alone). Focus is
always visible; skip-link, landmarks, labels, `aria-live` toasts, and
reduced-motion support are built in.

## Security notes

- No secrets in source; `.env` is gitignored, `.env.example` is addresses only.
- No PAN/CVV anywhere; last-four only; issue number shown once, never stored.
- No `dangerouslySetInnerHTML`; no sensitive data in URLs (IDs excepted).
- Dispute/fraud/audit views expose booleans and digests, never join keys or subjects.
- Backend `correlationId` surfaces on every error for support investigation.

## Screenshots

Run `npm run dev`, sign in as `customer@fintech.test`, fund, pay, and walk
Dashboard → Transactions → Cards → Disputes. Staff views: `analyst@` (fraud),
`auditor@` (audit), `settlement@` (settlement), `agent@` (notifications,
customers).

## Production cutover blockers

This project has not completed a production cutover. Browser sign-in already
uses Authorization Code + PKCE. Before serving this app outside local
development, register the production redirect URI on the client, configure a
production identity provider and exact gateway/IdP origins, build and publish
an immutable frontend image, serve it over HTTPS with an appropriate Content
Security Policy and security headers, and run browser end-to-end tests against
the production topology. The dev-only `fintech-cli` client (password
grant for scripts) and the `localhost` defaults must not be carried into production.
