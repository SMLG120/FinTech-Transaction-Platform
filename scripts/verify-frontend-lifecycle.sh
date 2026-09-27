#!/usr/bin/env bash
#
# Phase 11 live check: the static UI is served, the browser's preflight is answered, and the exact
# calls the UI makes move money through the gateway.
#
# Dev convenience only. Every assertion here is one the node unit tests cannot make, and each one
# is about the browser seam rather than about UI logic:
#
#   * the UI is served where the origins say it is. The gateway allows http://localhost:3001 and
#     Keycloak lists it, so the container answering there with the app — rather than a stale copy
#     or a 404 — is a three-way agreement worth checking in one place;
#   * the preflight is answered with the UI's origin and the headers the UI sends. A missing
#     Idempotency-Key in the allow-list fails silently in development (curl never preflights) and
#     loudly in every browser, so only an Origin-bearing request proves it;
#   * the UI's calls work end to end: password grant, balance, fund, pay with a fresh
#     Idempotency-Key. These are the same calls app.js makes, issued with the same headers and the
#     same Origin, so a green run means the buttons work rather than the endpoints do;
#   * the served files carry no secrets. The UI holds no key by design; grep proves the negative
#     rather than asserting it in a comment.
#
# Usage: ./scripts/verify-frontend-lifecycle.sh
set -euo pipefail

readonly UI="http://localhost:3001"
readonly GATEWAY="http://localhost:8080"
readonly KEYCLOAK="http://localhost:8180"
readonly ORIGIN="http://localhost:3001"
readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ENV_FILE="${ROOT_DIR}/.env"
readonly DEV_PASSWORD="fintech-dev-only"
readonly BODY_FILE=/tmp/frontend-body.json

readonly RUN_ID="webcheck-${RANDOM}-${RANDOM}"
readonly PAYER="webcheck-${RUN_ID}@fintech.test"

pass() { printf '  \033[32mok\033[0m   %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; exit 1; }
step() { printf '\n\033[1m%s\033[0m\n' "$1"; }

json() {
  python3 -c "
import json, sys
d = json.load(open('$BODY_FILE'))
try:
    print($1)
except Exception as e:
    print('RESPONSE HAD NO SUCH FIELD (%s): %s' % (e, json.dumps(d)[:300]), file=sys.stderr)
    raise SystemExit(1)
"
}

env_value() { grep -E "^$1=" "${ENV_FILE}" | head -1 | cut -d= -f2- | tr -d '"'; }

kc_token() {
  local username="$1"
  local response
  response="$(curl -s -X POST "${KEYCLOAK}/realms/$(env_value KEYCLOAK_REALM)/protocol/openid-connect/token" \
    -H "Origin: ${ORIGIN}" \
    -d grant_type=password -d client_id=fintech-web \
    --data-urlencode "username=${username}" --data-urlencode "password=${DEV_PASSWORD}")"
  python3 -c 'import json,sys
try:
    print(json.loads(sys.argv[1]).get("access_token", ""))
except Exception:
    print("")' "$response"
}

kc_admin_token() {
  curl -s -X POST "${KEYCLOAK}/realms/master/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=admin-cli \
    --data-urlencode "username=$(env_value KEYCLOAK_ADMIN_USERNAME)" \
    --data-urlencode "password=$(env_value KEYCLOAK_ADMIN_PASSWORD)" |
    python3 -c 'import json,sys; print(json.load(sys.stdin).get("access_token", ""))'
}

kc_user_id() {
  local admin realm
  admin="$(kc_admin_token)"
  realm="$(env_value KEYCLOAK_REALM)"
  curl -s "${KEYCLOAK}/admin/realms/${realm}/users?username=$1&exact=true" \
    -H "Authorization: Bearer ${admin}" |
    python3 -c 'import json,sys; u=json.load(sys.stdin); print(u[0]["id"] if u else "")'
}

kc_ensure_user() {
  local username="$1" admin realm status user_id
  admin="$(kc_admin_token)"
  [[ -n "$admin" ]] || fail "could not get a Keycloak admin token; is Keycloak healthy?"
  realm="$(env_value KEYCLOAK_REALM)"
  user_id="$(kc_user_id "$username")"
  if [[ -z "$user_id" ]]; then
    cat > /tmp/kc-new-user.json <<JSON
{"username":"${username}","enabled":true,"email":"${username}","emailVerified":true,
 "firstName":"Web","lastName":"Check",
 "credentials":[{"type":"password","value":"${DEV_PASSWORD}","temporary":false}]}
JSON
    status="$(curl -s -o /tmp/kc-body.json -w '%{http_code}' -X POST \
      "${KEYCLOAK}/admin/realms/${realm}/users" \
      -H "Authorization: Bearer ${admin}" -H 'Content-Type: application/json' \
      -d @/tmp/kc-new-user.json)"
    [[ "$status" == "201" ]] || { cat /tmp/kc-body.json; fail "creating local dev user ${username}"; }
    user_id="$(kc_user_id "$username")"
  fi
  [[ -n "$user_id" ]] || fail "could not resolve the id of ${username}"
  curl -s "${KEYCLOAK}/admin/realms/${realm}/roles/CUSTOMER" -H "Authorization: Bearer ${admin}" \
    -o /tmp/kc-role.json
  python3 -c "import json; json.dump([json.load(open('/tmp/kc-role.json'))], open('/tmp/kc-rolemap.json','w'))"
  curl -s -o /dev/null -X POST "${KEYCLOAK}/admin/realms/${realm}/users/${user_id}/role-mappings/realm" \
    -H "Authorization: Bearer ${admin}" -H 'Content-Type: application/json' -d @/tmp/kc-rolemap.json
  kc_token "$username" | grep -q . || fail "${username} cannot obtain a token after being created"
}

# ---- the check ------------------------------------------------------------------------------

step "Serving the UI where the origins say it is"
[[ -f "${ENV_FILE}" ]] || fail "no .env found. Run ./scripts/bootstrap.sh first."

code="$(curl -s -o "$BODY_FILE" -w '%{http_code}' "${UI}/index.html")"
[[ "$code" == "200" ]] || fail "the UI answers ${code} at ${UI}/index.html"
grep -q 'type="module" src="app.js"' "$BODY_FILE" ||
  fail "index.html does not load the app module; the container serves stale files"
frame="$(curl -s -D - -o /dev/null "${UI}/index.html" | grep -i '^x-frame-options:' | tr -d '\r')"
[[ "$frame" == *"DENY"* ]] || fail "the UI is served without X-Frame-Options: DENY"
pass "index.html served with framing refused"

code="$(curl -s -o "$BODY_FILE" -w '%{http_code}' "${UI}/config.json")"
[[ "$code" == "200" ]] || fail "config.json answers ${code}"
python3 -c "import json; json.load(open('$BODY_FILE'))" ||
  fail "config.json is not JSON; the UI boots to defaults that may be wrong"
pass "config.json served and parseable"

step "No secrets in the served files"
served="$(curl -s "${UI}/app.js")$(curl -s "${UI}/config.json")"
for pattern in 'PRIVATE KEY' 'fintech-dev-only' 'BEGIN RSA' 'api-secret' 'client_secret'; do
  if printf '%s' "$served" | grep -q "$pattern"; then
    fail "the served UI contains '${pattern}'; the UI holds no secret by design"
  fi
done
pass "served files carry no secret material"

step "The preflight is answered with the UI's origin and its headers"
headers="$(curl -s -D - -o /dev/null -X OPTIONS "${GATEWAY}/api/v1/transactions" \
  -H "Origin: ${ORIGIN}" \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: authorization,content-type,idempotency-key')"
echo "$headers" | grep -iq "access-control-allow-origin: ${ORIGIN}" ||
  fail "the preflight does not echo ${ORIGIN}: $(echo "$headers" | grep -i access-control || echo '(no CORS headers at all)')"
echo "$headers" | grep -iq "idempotency-key" ||
  fail "the preflight allow-list names no idempotency-key; every browser payment would fail before leaving the page"
pass "preflight echoes the origin and allows the payment headers"

step "The UI's calls move money: login, fund, pay"
kc_ensure_user "$PAYER"
TOKEN="$(kc_token "$PAYER")"
[[ -n "$TOKEN" ]] || fail "${PAYER} cannot obtain a token with an Origin header; Keycloak does not list ${ORIGIN}"
pass "password grant works with the UI's Origin header"

# Onboard the profile the UI's register form would create, through the same endpoints.
code="$(curl -s -o "$BODY_FILE" -w '%{http_code}' -X POST "${GATEWAY}/api/v1/customers" \
  -H "Origin: ${ORIGIN}" -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d '{"fullName":"Web Check","dateOfBirth":"1991-04-17","nationality":"GB","email":"user-'"$PAYER"'","phone":"+447700900123","address":{"line1":"12 Alder Way","city":"Manchester","postalCode":"M1 4BT","country":"GB"}}')"
[[ "$code" == "201" ]] || { cat "$BODY_FILE"; fail "registering through the UI's calls"; }
CUSTOMER_ID="$(json 'd["id"]')"
code="$(curl -s -o "$BODY_FILE" -w '%{http_code}' -X POST "${GATEWAY}/api/v1/customers/${CUSTOMER_ID}/kyc" \
  -H "Origin: ${ORIGIN}" -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d '{"documentReference":"SYNTH-0001","printedName":"Web Check","expiryDate":"2035-01-01","issuingCountry":"GB","nationality":"GB"}')"
[[ "$code" == "200" && "$(json 'd["status"]')" == "APPROVED" ]] ||
  fail "the synthetic identity check did not approve"
pass "registered and identity-checked through the UI's calls"

code="$(curl -s -o "$BODY_FILE" -w '%{http_code}' -X POST \
  "${GATEWAY}/api/v1/accounts/fund?amount=100.00&currency=GBP" \
  -H "Origin: ${ORIGIN}" -H "Authorization: Bearer ${TOKEN}" -H "Idempotency-Key: webcheck-fund-${RUN_ID}")"
[[ "$code" == "200" ]] || { cat "$BODY_FILE"; fail "funding through the UI's calls"; }

code="$(curl -s -o "$BODY_FILE" -w '%{http_code}' -X POST "${GATEWAY}/api/v1/transactions" \
  -H "Origin: ${ORIGIN}" -H "Authorization: Bearer ${TOKEN}" -H "Idempotency-Key: webcheck-pay-${RUN_ID}" \
  -H 'Content-Type: application/json' \
  -d '{"amount":"25.00","currency":"GBP","cardToken":"tok_webcheck'"${RANDOM}"'","payeeName":"Acme Books","payeeReference":"webcheck-'"$RUN_ID"'"}')"
[[ "$code" == "201" ]] || { cat "$BODY_FILE"; fail "paying through the UI's calls"; }
[[ "$(json 'd["status"]')" == "AUTHORIZED" ]] || fail "the payment is not AUTHORIZED"
pass "100.00 funded and 25.00 paid with UI-origin headers and fresh idempotency keys"

printf '\n\033[32mFrontend lifecycle verified.\033[0m The UI is served with framing refused and no\n'
printf 'secrets in its files, the preflight answers the UI origin with the payment headers, and the\n'
printf 'UI\u2019s exact calls — grant, register, check, fund, pay — move money through the gateway.\n'
printf 'Spend left behind: one throwaway customer and 100.00 GBP funded, 25.00 of it authorised.\n'
