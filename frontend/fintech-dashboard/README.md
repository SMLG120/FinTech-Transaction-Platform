# FinTech Dashboard — React + TypeScript Frontend

The customer/staff dashboard for the Secure FinTech Transaction Platform.
Replaces the dependency-free static UI in `web/` with a production-style
React application. Every page reads from the real gateway APIs — no fake
backends, no fabricated figures.

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
| Build / language | Vite 5 + React 18 + TypeScript (strict, `noUnusedLocals`) |
| Routing | React Router 6, role-guarded routes |
| Server state | TanStack Query (caching, refetch, loading/error states) |
| Forms | React Hook Form + Zod (`@hookform/resolvers`) |
| Charts | Recharts, lazy-loaded in its own chunk |
| Icons | Lucide |
| Animation | Framer Motion (`MotionConfig reducedMotion="user"`, CSS kill-switch) |
| Tests | Vitest + Testing Library + jsdom |

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
| `npm test -- --run` | Vitest suite (40 tests, incl. wire-contract parsing) |
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
browser ──password grant (dev only)──▶ Keycloak ──JWT──▶ gateway ──signed headers──▶ service
```

1. `LoginPage` posts `grant_type=password + client_id=fintech-web` to the
   realm token endpoint (see `scripts/get-token.sh` for the same call).
   Production moves to Authorization Code + PKCE in one `login` method
   (ADR-0013).
2. `AuthProvider` holds the token in `useState`, wires it into `ApiClient`,
   and decodes `realm_access.roles` + username **for UI gating only**.
3. `RequireAuth` redirects to `/login` (remembering `from`); `RequireRole`
   renders an inline 403 explanation. The gateway and services enforce the
   real policy — the UI only decides what to show.

Local test logins: `customer@`, `agent@`, `auditor@`, `compliance@`,
`analyst@`, `settlement@`, `admin@fintech.test` / `fintech-dev-only`.

## Route structure

| Path | Who | What |
| --- | --- | --- |
| `/login` | public | Sign in (RHF+Zod) |
| `/` | all | Dashboard: stats, lazy charts, recent activity |
| `/transactions`, `/transactions/:id` | all | Search/filter/sort/paginate + investigation (timeline, risk decision, audit trail, messages) |
| `/cards` | all | Issue (number shown once), freeze/unfreeze/lost/close |
| `/pay` | customer, support, admin | Fund + pay forms; authorise→settle kept explicit |
| `/profile` | customer, support, admin | Own profile, KYC history, update, irreversible erasure |
| `/customers` | support, admin | Masked profile lookup |
| `/disputes`, `/disputes/:id` | customer, support, admin | Open (settled own payments), plead, staff refund/reject |
| `/fraud`, `/fraud/alerts/:id` | analyst, compliance, auditor, admin | Queue, claim/close (analyst+admin), overrule/re-score |
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
  components/ui Button, Card/StatCard, Badge, states (Skeleton/Empty/Error),
                Toast (aria-live), FieldError patterns
  layouts/      AppShell (sidebar + topbar + Outlet, collapsible <900px)
  routes/       AppRoutes + RequireAuth/RequireRole
  features/     auth, dashboard (+stats, lazy Charts), transactions,
                cards, payments (schemas), customers, disputes, fraud
                (DecisionPanel shared), audit, settlement, notifications
  utils/        formatMoney, userMessage (status → friendly text + ref id)
```

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

## Cutover notes (when this replaces `web/`)

Config-only, no service changes:

1. Gateway: add `http://localhost:3001` (prod origin) alongside the dev
   origin in `GATEWAY_CORS_ALLOWED_ORIGINS`.
2. Keycloak realm: keep `http://localhost:3001` in `fintech-web.webOrigins`
   (dev `:5173` needed only for local `npm run dev`).
3. `web-frontend` image: build `frontend/fintech-dashboard` (`npm run build`)
   and serve `dist/` from nginx with the existing `nginx.conf` security
   headers; SPA routing needs the existing `try_files … /index.html`.
4. Re-run `./scripts/verify-frontend-lifecycle.sh` against the new origin.
