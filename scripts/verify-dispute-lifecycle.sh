#!/usr/bin/env bash
#
# Phase 10 live check: dispute a settled payment as its customer, plead in the case, and resolve it
# to a refund as a real support agent — watching the money come back through the ledger.
#
# Dev convenience only. Every assertion here is one the unit tests cannot make, and each one is
# about a seam between services rather than about dispute logic:
#
#   * the gateway routes /api/v1/disputes/** at all, and refuses it to an auditor. The service
#     enforces the same rule, but a gateway that let an AUDITOR token through to a case file would
#     still be forwarding customer grievances to a role that supervises rather than works;
#   * a case opens only on the caller's own settled payment. Ownership is verified against
#     transaction-service under the caller's forwarded identity, and only a live hop proves the
#     forwarding, the timeout and the fail-closed mapping all hold at once;
#   * the refund moves real money. Resolving is a row in dispute-service, but the customer is whole
#     only when transaction-service reverses the capture — and the two only ever meet through Kafka.
#     The balance is read back, because a 200 with no postings is a decision without a refund;
#   * one case per payment, and one outcome per case. A second open is refused, a second resolve is
#     refused, and evidence after the decision is refused — the state machine observed through the
#     API rather than asserted in a unit test;
#   * the case leaves an audit trail. Every action publishes an audit-events record, and the auditor
#     reads it back, because a chargeback workflow nobody recorded is a refund nobody can explain.
#
# Usage: ./scripts/verify-dispute-lifecycle.sh
#
# Why it provisions its own customers
# --------------------------------------------------------------------------------
# A dispute needs a settled payment, and the check must not disturb a balance anyone else is using,
# so it funds throwaway identities of its own -- the same reasoning as verify-payment-lifecycle.sh.
# Two customers, not one: the second exists so the check can prove a customer cannot open a case on
# somebody else's payment, which needs somebody else.
#
# The support agent is NOT provisioned here. SUPPORT_AGENT is a realm role, and the realm's own
# identities are one per role, so agent@fintech.test exists in the realm file and is the identity
# this check uses. Creating a second agent would mean granting a role to a throwaway user to test a
# policy the realm file already defines.
set -euo pipefail

readonly GATEWAY="http://localhost:8080"
readonly KEYCLOAK="http://localhost:8180"
readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ENV_FILE="${ROOT_DIR}/.env"
readonly DEV_PASSWORD="fintech-dev-only"
readonly BODY_FILE=/tmp/dispute-body.json

# Distinct identities per run, so a second run exercises fresh money rather than replaying the
# first run's and appearing to pass for the wrong reason.
readonly RUN_ID="disputecheck-${RANDOM}-${RANDOM}"
readonly PAYER="disputecheck-${RUN_ID}-a@fintech.test"
readonly OTHER="disputecheck-${RUN_ID}-b@fintech.test"

# The realm's support agent. See the note above: not created here.
readonly AGENT="agent@fintech.test"

# Seconds to wait for the refund to land. Consumption is asynchronous by design -- a case resolves
# before transaction-service has heard of it -- so a check that asserted the reversal the instant
# the resolve returned would be asserting the opposite of the architecture.
readonly REFUND_TIMEOUT=45

readonly CURRENCY="GBP"

pass() { printf '  \033[32mok\033[0m   %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; exit 1; }
step() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# ---- HTTP helpers ---------------------------------------------------------------------------

request() {
  local method="$1" url="$2" body="${3:-}" token="${4-$TOKEN}"
  local args=(-s -o "$BODY_FILE" -w '%{http_code}' -X "$method" "$url")
  if [[ -z "$token" ]]; then
    fail "${method} ${url} was called with an empty token; refusing to guess whose credentials to use"
  fi
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' -d "$body")
  curl "${args[@]}"
}

expect_status() {
  local want="$1" method="$2" url="$3" body="${4:-}" token="${5-$TOKEN}"
  local got
  got="$(request "$method" "$url" "$body" "$token")"
  if [[ "$got" != "$want" ]]; then
    printf '  expected %s, got %s\n' "$want" "$got"
    cat "$BODY_FILE"
    fail "$method $url"
  fi
}

# Reads a field out of the last response. A missing field raises here rather than returning an empty
# string, because under `set -e` a command substitution that fails takes the whole script down with
# no message at all -- which is indistinguishable from the check passing.
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

# ---- environment, database, Keycloak -------------------------------------------------------

env_value() { grep -E "^$1=" "${ENV_FILE}" | head -1 | cut -d= -f2- | tr -d '"'; }

# A scalar against the dispute database. The case is the platform's own record, so the assertions
# read it from the database as well as from the API: an API that rendered a state the database
# does not hold would pass every response-shape check while the rows underneath disagreed.
dispute_psql() {
  docker compose -f "${ROOT_DIR}/docker-compose.yml" exec -T postgres \
    psql -U "$(env_value POSTGRES_SUPERUSER)" -d fintech_disputes -tAc "$1" 2>/dev/null | tr -d ' \r'
}

# A scalar against the transaction database, for proving the refund moved money rather than
# decided it.
txn_psql() {
  docker compose -f "${ROOT_DIR}/docker-compose.yml" exec -T postgres \
    psql -U "$(env_value POSTGRES_SUPERUSER)" -d fintech_transactions -tAc "$1" 2>/dev/null | tr -d ' \r'
}

kc_token() {
  local username="$1"
  [[ "$username" == *@* ]] || username="${username}@fintech.test"
  local response
  response="$(curl -s -X POST "${KEYCLOAK}/realms/$(env_value KEYCLOAK_REALM)/protocol/openid-connect/token" \
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
 "firstName":"Dispute","lastName":"Check",
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

# ---- customer setup -------------------------------------------------------------------------

readonly FULL_NAME="Ada Lovelace"
readonly DOB="1815-12-10"
readonly ADDRESS='{"line1":"12 Analytical Engine Way","city":"London","postalCode":"EC1A 1BB","country":"GB"}'

register_body() {
  printf '{"fullName":"%s","dateOfBirth":"%s","nationality":"GB","email":"%s","phone":"%s","address":%s}' \
    "$1" "$2" "$3" "$4" "$5"
}

setup_customer() {
  local username="$1" name="$2" docref="$3" token status id
  token="$(kc_token "$username")"
  [[ -n "$token" ]] || return 1

  status="$(curl -s -o "$BODY_FILE" -w '%{http_code}' -X POST "${GATEWAY}/api/v1/customers" \
    -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' \
    -d "$(register_body "$name" "$DOB" "user-${username}" "+447700900${RANDOM:0:3}" "$ADDRESS")")"
  case "$status" in
    201) id="$(json 'd["id"]')" ;;
    409)
      status="$(curl -s -o "$BODY_FILE" -w '%{http_code}' "${GATEWAY}/api/v1/customers/me" \
        -H "Authorization: Bearer ${token}")"
      [[ "$status" == "200" ]] || return 1
      id="$(json 'd["id"]')" ;;
    *) return 1 ;;
  esac
  [[ -n "$id" ]] || return 1

  status="$(curl -s -o "$BODY_FILE" -w '%{http_code}' "${GATEWAY}/api/v1/customers/me" \
    -H "Authorization: Bearer ${token}")"
  if [[ "$(json 'd["kycStatus"]')" == "NOT_STARTED" ]]; then
    status="$(curl -s -o "$BODY_FILE" -w '%{http_code}' -X POST "${GATEWAY}/api/v1/customers/${id}/kyc" \
      -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' \
      -d "{\"documentReference\":\"${docref}\",\"printedName\":\"${name}\",\"expiryDate\":\"2035-01-01\",\"issuingCountry\":\"GB\",\"nationality\":\"GB\"}")"
    [[ "$status" == "200" ]] || return 1
    [[ "$(json 'd["status"]')" == "APPROVED" ]] || return 1
  fi
  printf '%s %s' "$token" "$id"
}

absorb() {
  local raw
  raw="$(setup_customer "$@")" || fail "could not set up ${1}"
  read -r ABSORBED_TOKEN ABSORBED_ID <<<"$raw"
  [[ -n "$ABSORBED_TOKEN" && -n "$ABSORBED_ID" ]] ||
    fail "setup for ${1} returned '${raw}', expected a token and a customer id"
}

card_token() { printf 'tok_dsp%04x%09d' "$$" "$RANDOM"; }

fund() {
  local token="$1" amount="$2" key="$3"
  code="$(curl -s -o "$BODY_FILE" -w '%{http_code}' -X POST \
    "${GATEWAY}/api/v1/accounts/fund?amount=${amount}&currency=${CURRENCY}" \
    -H "Authorization: Bearer ${token}" -H "Idempotency-Key: ${key}")"
  [[ "$code" == "200" ]] || { cat "$BODY_FILE"; fail "funding ${amount} ${CURRENCY}"; }
}

pay() {
  local token="$1" card="$2" amount="$3" key="$4" payee="$5"
  code="$(curl -s -o "$BODY_FILE" -w '%{http_code}' -X POST "${GATEWAY}/api/v1/transactions" \
    -H "Authorization: Bearer ${token}" -H "Idempotency-Key: ${key}" -H 'Content-Type: application/json' \
    -d "$(printf '{"amount":"%s","currency":"%s","cardToken":"%s","payeeName":"%s","payeeReference":"disputecheck-%s"}' \
        "$amount" "$CURRENCY" "$card" "$payee" "$key")")"
  [[ "$code" == "201" ]] || { cat "$BODY_FILE"; fail "paying ${amount} ${CURRENCY}"; }
}

settle() {
  local token="$1" id="$2"
  code="$(curl -s -o "$BODY_FILE" -w '%{http_code}' -X POST "${GATEWAY}/api/v1/transactions/${id}/settle" \
    -H "Authorization: Bearer ${token}")"
  [[ "$code" == "200" ]] || { cat "$BODY_FILE"; fail "settling ${id}"; }
  [[ "$(json 'd["status"]')" == "SETTLED" ]] ||
    fail "settling ${id} reported status '$(json 'd["status"]')'"
}

# ---- the check ------------------------------------------------------------------------------

step "Checking the local realm has a support agent and an auditor"
[[ -f "${ENV_FILE}" ]] || fail "no .env found. Run ./scripts/bootstrap.sh first."

TOKEN="$(kc_token "$AGENT")"
[[ -n "$TOKEN" ]] ||
  fail "${AGENT} cannot obtain a token. If the stack was already up when Phase 10 was added, the realm \
was not re-imported: Keycloak imports a realm only when it does not exist. Recreate the realm per \
infrastructure/keycloak/realm/README.md."

step "The gateway routes cases, and refuses an auditor"
expect_status 200 GET "${GATEWAY}/api/v1/disputes" "" "$TOKEN"
pass "a support agent reaches the queue"

kc_ensure_user "$PAYER"
absorb "$PAYER" "$FULL_NAME" "SYNTH-0001"
PAYER_TOKEN="$ABSORBED_TOKEN"

kc_ensure_user "$OTHER"
absorb "$OTHER" "$FULL_NAME" "SYNTH-0002"
OTHER_TOKEN="$ABSORBED_TOKEN"

AUDITOR_TOKEN="$(kc_token auditor@fintech.test)"
[[ -n "$AUDITOR_TOKEN" ]] || fail "auditor@fintech.test cannot obtain a token; re-import the realm"
expect_status 403 GET "${GATEWAY}/api/v1/disputes" "" "$AUDITOR_TOKEN"
pass "an auditor is refused the case file — supervision reads the trail, not the queue"

step "Paying, settling, and opening a case on the customer's own payment"
CARD="$(card_token)"
fund "$PAYER_TOKEN" "100.00" "dispute-fund-${RUN_ID}"
pay "$PAYER_TOKEN" "$CARD" "25.00" "dispute-pay-${RUN_ID}" "Acme Books"
TXN_ID="$(json 'd["id"]')"
settle "$PAYER_TOKEN" "$TXN_ID"
pass "25.00 ${CURRENCY} settled as ${TXN_ID}"

expect_status 201 POST "${GATEWAY}/api/v1/disputes" \
  '{"transactionId":"'"$TXN_ID"'","reason":"NOT_RECEIVED","description":"The books never arrived"}' "$PAYER_TOKEN"
DISPUTE_ID="$(json 'd["dispute"]["id"]')"
[[ -n "$DISPUTE_ID" ]] || fail "the open response names no case"
[[ "$(json 'd["dispute"]["status"]')" == "OPEN" ]] || fail "the new case is not OPEN"
pass "case ${DISPUTE_ID} opened on the customer's own settled payment"

step "Refusing what is not theirs, not settled, or already open"
expect_status 409 POST "${GATEWAY}/api/v1/disputes" \
  '{"transactionId":"'"$TXN_ID"'","reason":"FRAUD","description":"Trying again"}' "$PAYER_TOKEN"
[[ "$(json 'd["error"]')" == "DISPUTE_ALREADY_OPEN" ]] ||
  fail "a second case answered '$(json 'd["error"]')' rather than refusing the duplicate"
pass "a second case on the same payment is refused as ALREADY_OPEN"

pay "$PAYER_TOKEN" "$CARD" "5.00" "dispute-unsettled-${RUN_ID}" "Acme Books"
UNSETTLED_TXN="$(json 'd["id"]')"
expect_status 422 POST "${GATEWAY}/api/v1/disputes" \
  '{"transactionId":"'"$UNSETTLED_TXN"'","reason":"FRAUD","description":"Too soon"}' "$PAYER_TOKEN"
[[ "$(json 'd["error"]')" == "DISPUTE_PAYMENT_NOT_SETTLED" ]] ||
  fail "a case on a hold answered '$(json 'd["error"]')' rather than refusing it"
pass "a case on an authorised-but-unsettled payment is refused as NOT_SETTLED"

OTHER_CARD="$(card_token)"
fund "$OTHER_TOKEN" "50.00" "dispute-other-fund-${RUN_ID}"
pay "$OTHER_TOKEN" "$OTHER_CARD" "10.00" "dispute-other-pay-${RUN_ID}" "Acme Books"
OTHER_TXN="$(json 'd["id"]')"
settle "$OTHER_TOKEN" "$OTHER_TXN"
expect_status 404 POST "${GATEWAY}/api/v1/disputes" \
  '{"transactionId":"'"$OTHER_TXN"'","reason":"FRAUD","description":"Not mine"}' "$PAYER_TOKEN"
[[ "$(json 'd["error"]')" == "DISPUTE_PAYMENT_NOT_FOUND" ]] ||
  fail "a case on another customer's payment answered '$(json 'd["error"]')' rather than refusing it"
pass "a case on another customer's settled payment is refused as NOT_FOUND, revealing nothing"

step "Pleading in the case, as both sides"
expect_status 200 POST "${GATEWAY}/api/v1/disputes/${DISPUTE_ID}/evidence" \
  '{"body":"The tracking number has had no movement in three weeks"}' "$PAYER_TOKEN"
expect_status 200 POST "${GATEWAY}/api/v1/disputes/${DISPUTE_ID}/evidence" \
  '{"body":"Checking with the merchant about the shipment"}' "$TOKEN"
expect_status 200 GET "${GATEWAY}/api/v1/disputes/${DISPUTE_ID}" "" "$PAYER_TOKEN"
[[ "$(json 'len(d["evidence"])')" == "2" ]] || fail "the file does not hold both statements"
[[ "$(json 'd["evidence"][0]["submittedByMe"]')" == "True" ]] ||
  fail "the customer's own statement is not marked theirs"
pass "two statements filed, each marked for its reader"

expect_status 403 POST "${GATEWAY}/api/v1/disputes/${DISPUTE_ID}/resolve" \
  '{"outcome":"REFUND","resolution":"Give it back"}' "$PAYER_TOKEN"
pass "the customer cannot decide their own case"

step "Resolving to a refund, and watching the money come back"
expect_status 200 POST "${GATEWAY}/api/v1/disputes/${DISPUTE_ID}/resolve" \
  '{"outcome":"REFUND","resolution":"Merchant confirms the parcel was lost"}' "$TOKEN"
[[ "$(json 'd["dispute"]["status"]')" == "RESOLVED_REFUNDED" ]] ||
  fail "the case did not resolve as a refund"
pass "the agent resolved the case as RESOLVED_REFUNDED"

REVERSED=""
for _ in $(seq 1 "$REFUND_TIMEOUT"); do
  code="$(curl -s -o "$BODY_FILE" -w '%{http_code}' "${GATEWAY}/api/v1/transactions/${TXN_ID}" \
    -H "Authorization: Bearer ${PAYER_TOKEN}")"
  if [[ "$code" == "200" && "$(json 'd["status"]')" == "REVERSED" ]]; then
    REVERSED=yes
    break
  fi
  sleep 1
done
[[ -n "$REVERSED" ]] ||
  fail "the payment is not REVERSED after ${REFUND_TIMEOUT}s; the case says refunded and the money never moved"
pass "the payment reversed through the ledger"

BALANCE="$(curl -s "${GATEWAY}/api/v1/accounts/balance?currency=${CURRENCY}" \
  -H "Authorization: Bearer ${PAYER_TOKEN}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["available"])')"
[[ "$BALANCE" == "95.00" ]] ||
  fail "available balance is ${BALANCE} ${CURRENCY}; 100.00 funded, 25.00 paid, 5.00 held, 25.00 refunded"
pass "available balance is 95.00 ${CURRENCY}: the refund landed and the hold stayed held"

step "One outcome per case, and no evidence after it"
expect_status 409 POST "${GATEWAY}/api/v1/disputes/${DISPUTE_ID}/resolve" \
  '{"outcome":"REJECT","resolution":"Changed my mind"}' "$TOKEN"
[[ "$(json 'd["error"]')" == "DISPUTE_NOT_OPEN" ]] ||
  fail "a second decision answered '$(json 'd["error"]')' rather than refusing it"
expect_status 409 POST "${GATEWAY}/api/v1/disputes/${DISPUTE_ID}/evidence" \
  '{"body":"One more thing"}' "$PAYER_TOKEN"
pass "a second decision and late evidence are both refused as NOT_OPEN"

DB_STATUS="$(dispute_psql "select status from disputes where id='${DISPUTE_ID}'")"
[[ "$DB_STATUS" == "RESOLVED_REFUNDED" ]] ||
  fail "the row is '${DB_STATUS}' while the API says RESOLVED_REFUNDED"
EVIDENCE_ROWS="$(dispute_psql "select count(*) from dispute_evidence where dispute_id='${DISPUTE_ID}'")"
[[ "$EVIDENCE_ROWS" == "2" ]] || fail "the file holds ${EVIDENCE_ROWS} statements rather than two"
pass "the database agrees: one refunded row, two statements"

step "The case leaves an audit trail"
TRAIL=""
for _ in $(seq 1 "$REFUND_TIMEOUT"); do
  expect_status 200 GET "${GATEWAY}/api/audit/records/by-resource/dispute/${DISPUTE_ID}" "" "$AUDITOR_TOKEN"
  if [[ "$(json 'len(d["content"])')" -ge 3 ]]; then
    TRAIL=yes
    break
  fi
  sleep 2
done
[[ -n "$TRAIL" ]] ||
  fail "fewer than three trail rows (open, evidence, resolve) name the case after ${REFUND_TIMEOUT}s"
[[ "$(json 'len([r for r in d["content"] if r["action"]=="dispute-resolved"])')" == "1" ]] ||
  fail "the resolution is not on the trail"
pass "open, evidence and resolution all recorded against the case"

printf '\n\033[32mDispute lifecycle verified.\033[0m A customer disputed their own settled payment over\n'
printf 'Kafka-verified ownership, duplicates and foreign cases were refused, both sides pleaded, an\n'
printf 'agent resolved to a refund, the ledger reversed the capture, and the trail recorded it all.\n'
printf 'Spend left behind: two throwaway customers, 100.00 %s funded to the first (95.00 back, 5.00\n' "$CURRENCY"
printf 'held) and 50.00 to the second (40.00 back, 10.00 settled). The case %s is left\n' "$DISPUTE_ID"
printf 'RESOLVED_REFUNDED, which is terminal — a decided case is history, not a lock.\n'
