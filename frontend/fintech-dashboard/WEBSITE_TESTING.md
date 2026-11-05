# React Dashboard Website Testing

This guide covers manual browser testing of the React dashboard and its customer registration flow. Use local development data only: **do not enter real names, email addresses, passwords, or financial information.** The dashboard is not configured for production use.

## 1. Start the local platform

From the repository root:

```bash
make doctor
make setup   # first run only; creates .env and does not overwrite an existing file
make up      # builds and starts the backend, Keycloak, and dependencies
make ps
```

Wait for the gateway and Keycloak to become healthy. You can check their endpoints with:

```bash
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8180/realms/fintech/.well-known/openid-configuration
```

### Enable customer registration in Keycloak

The imported development realm does not include the registration service-account client by default. Before testing successful account creation, follow the **Registration service account** steps in [`infrastructure/keycloak/realm/README.md`](../../infrastructure/keycloak/realm/README.md): create the confidential `fintech-registration` client, enable its service account, grant only the documented user-management roles, and set its generated secret as `KEYCLOAK_ADMIN_CLIENT_SECRET` in the local `.env`. Then rebuild/restart Auth Service:

```bash
docker compose up -d --build auth-service
```

Do not grant `realm-admin`. Never copy the local client secret into frontend configuration or source code.

## 2. Start the React dashboard

In a second terminal:

```bash
cd frontend/fintech-dashboard
test -f .env || cp .env.example .env
npm ci
npm run dev -- --host 127.0.0.1
```

Open <http://localhost:5173/login>. The `.env.example` defaults point the browser at the local gateway and Keycloak. `VITE_*` values are public browser settings; never put secrets in them.

## 3. Test registration and the email checks

1. On the sign-in page, choose **Create an account**.
2. Enter synthetic details and a unique test email such as `website-test-<timestamp>@example.test`. Use a past date of birth, two-letter country codes, and a password of at least 12 characters.
3. Try an invalid address such as `not-an-email`. The form should show a field error and must not submit the registration request.
4. Try a valid address with surrounding spaces or uppercase letters. The client normalizes it to trimmed lowercase before submission.
5. Submit the form. With the registration service account configured, account creation should return to sign-in and show **Account created**.
6. Click **Sign in securely**; on the Keycloak page, sign in with the normalized email and the password you entered. You return to the dashboard via `/auth/callback`. Open **Profile**; the customer details should load rather than showing the missing-profile state.
7. Sign out, return to registration, and submit the same email again. The server should reject the duplicate and the page should offer signing in or using another address.

For an additional check, use the browser Developer Tools **Network** tab: a successful registration should call `POST /register` and receive `201`; after sign-in, the profile request `GET /api/v1/customers/me` should succeed. Do not copy or share request bodies, since they contain the test password and personal details.

Email checking here validates address syntax and detects duplicates at submission time. It does **not** send a verification email or prove mailbox ownership. Duplicate detection is performed by the identity service during account creation; there is no separate availability endpoint.

## 4. Check sign-in, navigation, and role behavior

The local realm contains synthetic users. Their password is the random `SEED_USER_PASSWORD` value that `./scripts/bootstrap.sh` generated in the repository's `.env`:

| User | Expected access |
| --- | --- |
| `customer@fintech.test` | Customer dashboard and customer routes |
| `agent@fintech.test` | Support routes, including customer lookup and notifications |
| `auditor@fintech.test` | Audit and read-only staff routes |
| `analyst@fintech.test` | Fraud-analysis routes |
| `settlement@fintech.test` | Settlement routes |

For each role you test, sign out before switching users. Confirm that allowed pages load and that a customer cannot access staff-only pages such as `/audit`. A page being hidden or blocked in the UI is not a security control by itself; the gateway and services enforce authorization.

Also do a quick browser check:

- Refresh a page and confirm the app asks you to sign in again (the token is held in memory only).
- Use keyboard Tab/Enter to navigate the sign-in and registration forms; confirm focus is visible and errors are announced beside their fields.
- Resize to a narrow mobile viewport and check that the navigation, forms, and tables remain usable.
- Check the browser console and Network tab for failed requests or CORS errors.

## 5. Run automated frontend checks

From `frontend/fintech-dashboard`:

```bash
npm run typecheck
npm run lint
npm test
npm run build
```

These checks do not require the backend stack. The production build may report a bundle-size warning; that warning is separate from whether the build succeeds.

To run the focused Auth Service tests for registration and partial-account cleanup, use Java 21 from the repository root:

```bash
./mvnw -pl services/auth-service -am \
  -Dtest=RegistrationServiceTest,KeycloakRegistrationRollbackTest,KeycloakUserProvisioningServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The repository Maven build rejects Java versions below 21.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Gateway or Keycloak does not respond | Run `make ps`; inspect service logs with `docker compose logs --tail=100 api-gateway keycloak auth-service customer-service`. |
| Login fails with a browser CORS error | Use `http://localhost:5173` and confirm it is allowed by both the local gateway and the Keycloak `fintech-web` client. |
| Keycloak shows "Invalid parameter: redirect_uri" | The dashboard must run at exactly `http://localhost:5173`; the registered callback is `http://localhost:5173/auth/callback`. A realm imported before the PKCE change needs re-importing. |
| "This sign-in was not started here or has already been used" | The callback was opened in a different tab or reloaded. Click Sign in again. |
| Registration returns a server error | Confirm the `fintech-registration` service account and secret are configured as described above, then rebuild Auth Service. |
| Duplicate-email message appears | Expected when that email already has an identity; sign in with it or test with a new synthetic address. |
| A previously existing login has no customer profile | Do not try to create a second login with the same email. The account may need reconciliation; contact the platform maintainer. |

## Notes about other frontend scripts

`./scripts/verify-frontend-lifecycle.sh` targets the separate legacy static UI on port `3001`, not this React dashboard. It also creates a throwaway customer and leaves synthetic funds and an authorized payment in the local stack, so it is not a read-only browser smoke test.
