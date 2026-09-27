#!/usr/bin/env bash
#
# Phase 9 live check: work a fraud alert as a real analyst, and read the trail it leaves as a real
# auditor.
#
# Dev convenience only. Every assertion here is one the unit tests cannot make, and each one is
# about a seam between services rather than about audit logic:
#
#   * the gateway routes /api/audit/** at all, and refuses it to a customer, a support agent and a
#     fraud analyst. The service enforces the same rule, but a gateway that let an analyst token
#     through would still be forwarding the trail to the very role it records;
#   * the platform actually records a staff action taken in another service. This is the whole point
#     of the phase, and it is the one thing a test that calls the service directly never proves --
#     the claim and the trail row only ever meet through Kafka;
#   * the record says what fraud-service said. The actor digest in the trail is compared against the
#     digest on the alert timeline, because two services that disagree about the actor produce a
#     trail that names nobody;
#   * one claim is one row. Claiming twice is refused by fraud-service, and the trail must not hold
#     a second row for the refused attempt;
#   * the trail cannot be edited, end to end. An UPDATE issued straight at the database is refused
#     by the trigger, which is the append-only property observed rather than asserted in a unit
#     test.
#
# Usage: ./scripts/verify-audit-lifecycle.sh
#
# Why it provisions its own customer
# --------------------------------------------------------------------------------
# An alert needs payments, and the check must not disturb a balance anyone else is using, so it
# funds a throwaway identity of its own -- the same reasoning as verify-fraud-lifecycle.sh, and it
# reuses that script's burst shape rather than inventing another.
#
# The analyst and the auditor are NOT provisioned here. FRAUD_ANALYST and AUDITOR are realm roles,
# and the realm's own identities are one per role, so analyst@fintech.test and auditor@fintech.test
# exist in the realm file and are the identities this check uses. Creating either would mean
# granting a role to a throwaway user to test a policy the realm file already defines.
set -euo pipefail

readonly GATEWAY="http://localhost:8080"
readonly KEYCLOAK="http://localhost:8180"
readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ENV_FILE="${ROOT_DIR}/.env"
readonly DEV_PASSWORD="fintech-dev-only"
readonly BODY_FILE=/tmp/audit-body.json

# A distinct identity per run, so a second run exercises fresh money rather than replaying the first
# run's and appearing to pass for the wrong reason.
readonly RUN_ID="auditcheck-${RANDOM}-${RANDOM}"
readonly PAYER="auditcheck-${RUN_ID}@fintech.test"

# The realm's staff identities. See the note above: not created here.
readonly ANALYST="analyst@fintech.test"
readonly AUDITOR="auditor@fintech.test"

# Seconds to wait for the engine to score, and for the trail to record. Both are asynchronous by
# design -- an alert is raised after the payment, and recorded after the claim -- so a check that
# asserted either the instant the request returned would be asserting the opposite of the
# architecture.
readonly SCORE_TIMEOUT=45
readonly TRAIL_TIMEOUT=45

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

token_subject() {
  printf '%s' "$1" | python3 -c '
import base64, json, sys
payload = sys.stdin.read().strip().split(".")[1]
payload += "=" * (-len(payload) % 4)
print(json.loads(base64.urlsafe_b64decode(payload)).get("sub", ""))
'
}

# ---- environment, database, Keycloak -------------------------------------------------------

env_value() { grep -E "^$1=" "${ENV_FILE}" | head -1 | cut -d= -f2- | tr -d '"'; }

# A scalar against the audit database. The trail is the platform's own record, so the assertions
# read it from the database as well as from the API: an API that rendered a fact the database does
# not hold would pass every response-shape check while the rows underneath disagreed.
audit_psql() {
  docker compose -f "${ROOT_DIR}/docker-compose.yml" exec -T postgres \
    psql -U "$(env_value POSTGRES_SUPERUSER)" -d fintech_audit -tAc "$1" 2>/dev/null | tr -d ' \r'
}

# A scalar against the fraud database, for comparing the trail's actor with the timeline's.
fraud_psql() {
  docker compose -f "${ROOT_DIR}/docker-compose.yml" exec -T postgres \
    psql -U "$(env_value POSTGRES_SUPERUSER)" -d fintech_fraud -tAc "$1" 2>/dev/null | tr -d ' \r'
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
 "firstName":"Audit","lastName":"Check",
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

card_token() { printf 'tok_adt%04x%09d' "$$" "$RANDOM"; }

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
    -d "$(printf '{"amount":"%s","currency":"%s","cardToken":"%s","payeeName":"%s","payeeReference":"auditcheck-%s"}' \
        "$amount" "$CURRENCY" "$card" "$payee" "$key")")"
  [[ "$code" == "201" ]] || { cat "$BODY_FILE"; fail "paying ${amount} ${CURRENCY}"; }
}

# ---- the check ------------------------------------------------------------------------------

step "Checking the local realm has an analyst and an auditor"
[[ -f "${ENV_FILE}" ]] || fail "no .env found. Run ./scripts/bootstrap.sh first."

ANALYST_TOKEN="$(kc_token "$ANALYST")"
[[ -n "$ANALYST_TOKEN" ]] || fail "${ANALYST} cannot obtain a token; re-import the realm per infrastructure/keycloak/realm/README.md"
AUDITOR_TOKEN="$(kc_token "$AUDITOR")"
[[ -n "$AUDITOR_TOKEN" ]] || fail "${AUDITOR} cannot obtain a token; re-import the realm per infrastructure/keycloak/realm/README.md"
pass "analyst and auditor tokens carry their roles"

step "The gateway routes the trail, and refuses everyone but supervision"
expect_status 200 GET "${GATEWAY}/api/audit/records" "" "$AUDITOR_TOKEN"
pass "an auditor reaches the trail"

kc_ensure_user "$PAYER"
absorb "$PAYER" "$FULL_NAME" "SYNTH-0001"
PAYER_TOKEN="$ABSORBED_TOKEN"

expect_status 403 GET "${GATEWAY}/api/audit/records" "" "$PAYER_TOKEN"
pass "a customer is refused the trail before any fact exists to read"

AGENT_TOKEN="$(kc_token agent@fintech.test)"
[[ -n "$AGENT_TOKEN" ]] || fail "agent@fintech.test cannot obtain a token; re-import the realm"
expect_status 403 GET "${GATEWAY}/api/audit/records" "" "$AGENT_TOKEN"
expect_status 403 GET "${GATEWAY}/api/audit/records" "" "$ANALYST_TOKEN"
pass "a support agent and a fraud analyst are refused the trail they are supervised by"

step "Paying fast enough to raise an alert"
CARD="$(card_token)"
fund "$PAYER_TOKEN" "100.00" "audit-fund-${RUN_ID}"
ALERT_PAYEE="Burst-${RUN_ID}"
ALERT_TXN=""
for i in 1 2 3 4 5 6; do
  pay "$PAYER_TOKEN" "$CARD" "1.00" "${RUN_ID}-burst-${i}" "$ALERT_PAYEE"
  id="$(json 'd["id"]')"
  if [[ "$i" == "6" ]]; then
    ALERT_TXN="$id"
  fi
done
[[ -n "$ALERT_TXN" ]] || fail "the burst did not produce six distinct payments"

ALERT_ID=""
for _ in $(seq 1 "$SCORE_TIMEOUT"); do
  ALERT_ID="$(fraud_psql "select id from fraud_alerts where transaction_id='${ALERT_TXN}'")"
  if [[ -n "$ALERT_ID" ]]; then
    break
  fi
  sleep 1
done
[[ -n "$ALERT_ID" ]] || fail "six payments raised no alert on the sixth; the velocity rule is not escalating"
pass "the sixth of six payments raised alert ${ALERT_ID}"

step "Claiming the alert, and waiting for the trail to record it"
expect_status 200 POST "${GATEWAY}/api/v1/fraud/alerts/${ALERT_ID}/claim" "" "$ANALYST_TOKEN"
pass "the analyst holds the alert"

RECORD_ID=""
for _ in $(seq 1 "$TRAIL_TIMEOUT"); do
  RECORD_ID="$(audit_psql "select id from audit_records where action='fraud-alert-claimed' and resource_id='${ALERT_ID}'")"
  if [[ -n "$RECORD_ID" ]]; then
    break
  fi
  sleep 1
done
[[ -n "$RECORD_ID" ]] ||
  fail "no trail row for the claim after ${TRAIL_TIMEOUT}s; the analyst acted and the trail has a hole"
pass "the claim produced trail row ${RECORD_ID}"

step "The row says what fraud-service said, about whom it said it"
expect_status 200 GET "${GATEWAY}/api/audit/records/${RECORD_ID}" "" "$AUDITOR_TOKEN"
[[ "$(json 'd["action"]')" == "fraud-alert-claimed" ]] ||
  fail "the row's action is '$(json 'd["action"]')' rather than fraud-alert-claimed"
[[ "$(json 'd["resourceType"]')" == "fraud-alert" ]] ||
  fail "the row's resource type is '$(json 'd["resourceType"]')'"
[[ "$(json 'd["result"]')" == "SUCCESS" ]] ||
  fail "the row's result is '$(json 'd["result"]')' rather than SUCCESS"
[[ -n "$(json 'd["actorDigest"]')" ]] || fail "the row names no actor"
[[ -n "$(json 'd["occurredAt"]')" && -n "$(json 'd["receivedAt"]')" ]] ||
  fail "the row carries no receipt of when the fact happened and when it was recorded"

# The digest join, across two databases. Fraud-service computed the analyst digest and put it on the
# alert timeline; audit-service stored what the event carried. If the two disagree about the actor,
# the trail names nobody — and nothing errors, which is why the comparison is read back and made
# here rather than assumed.
TIMELINE_DIGEST="$(fraud_psql "select actor_digest from fraud_alert_events where alert_id='${ALERT_ID}' and action='CLAIMED'")"
[[ -n "$TIMELINE_DIGEST" ]] || fail "the alert timeline has no CLAIMED entry to compare against"
[[ "$(json 'd["actorDigest"]')" == "$TIMELINE_DIGEST" ]] ||
  fail "the trail's actor '$(json 'd["actorDigest"]')' is not the timeline's '${TIMELINE_DIGEST}'"
ANALYST_SUBJECT="$(token_subject "$ANALYST_TOKEN")"
[[ "$(json 'd["actorDigest"]')" != "$ANALYST_SUBJECT" ]] ||
  fail "the trail names the analyst's raw subject, which is a person where a pseudonym belongs"
pass "the trail's actor matches the timeline's digest, and is not the raw subject"

step "One claim is one row, and the trail cannot be edited"
SECOND_STATUS="$(request POST "${GATEWAY}/api/v1/fraud/alerts/${ALERT_ID}/claim" "" "$ANALYST_TOKEN")"
[[ "$SECOND_STATUS" == "409" ]] || fail "a second claim answered ${SECOND_STATUS} rather than 409"
sleep 3
CLAIM_ROWS="$(audit_psql "select count(*) from audit_records where action='fraud-alert-claimed' and resource_id='${ALERT_ID}'")"
[[ "$CLAIM_ROWS" == "1" ]] ||
  fail "${CLAIM_ROWS} rows name the one claim; the refused attempt was recorded as a fact"
pass "the refused second claim left no second row"

# Append-only, observed rather than asserted in a unit test. An UPDATE issued straight at the
# database must be refused by the trigger: a trail that a SQL client can edit is a second draft of
# history, and a second draft is what a regulator assumes is hiding the first.
if audit_psql "update audit_records set result='FAILURE' where id='${RECORD_ID}'" >/dev/null 2>&1; then
  fail "an UPDATE against the trail succeeded; append-only is a comment rather than a constraint"
fi
if audit_psql "delete from audit_records where id='${RECORD_ID}'" >/dev/null 2>&1; then
  fail "a DELETE against the trail succeeded; append-only is a comment rather than a constraint"
fi
pass "UPDATE and DELETE against the trail are refused by the database"

step "The trail answers an auditor's questions"
expect_status 200 GET "${GATEWAY}/api/audit/records?action=fraud-alert-claimed" "" "$AUDITOR_TOKEN"
[[ "$(json 'len([r for r in d["content"] if r["id"]=="'"$RECORD_ID"'"])')" == "1" ]] ||
  fail "the claim is not under its own action filter"
expect_status 200 GET "${GATEWAY}/api/audit/records/by-transaction/${ALERT_TXN}" "" "$AUDITOR_TOKEN"
[[ "$(json 'len([r for r in d["content"] if r["id"]=="'"$RECORD_ID"'"])')" == "1" ]] ||
  fail "the claim is not on its payment's trail"
pass "the claim appears under its action and on its payment's trail"

printf '\n\033[32mAudit lifecycle verified.\033[0m An analyst claimed an alert over Kafka, the trail\n'
printf 'recorded the fact with the actor fraud-service named, the digest matched the timeline without\n'
printf 'naming the subject, a refused second claim left no second row, and the database refused to let\n'
printf 'the row be edited. Spend left behind: one throwaway customer and 100.00 %s funded, 6.00 of it\n' "$CURRENCY"
printf 'paid in the burst that raised the alert. The row %s stays, because a trail that can be edited\n' "$RECORD_ID"
printf 'after the fact is a second draft of history.\n'
