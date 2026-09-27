#!/usr/bin/env bash
#
# Phase 8 live check: pay money, let notification-service turn the events into messages, and work
# the delivery log as a real support agent.
#
# Dev convenience only. Every assertion here is one the unit tests cannot make, and each one is
# about a seam between services rather than about notification logic:
#
#   * the gateway routes /api/v1/notifications/** at all, and refuses it to a customer and to an
#     auditor. The service enforces the same rule, but a gateway that let a CUSTOMER token through
#     would still be forwarding a support-only log to the one role that must never see it;
#   * the platform actually turns a settled payment into a message. This is the whole point of the
#     phase, and it is the one thing a test that calls the service directly never proves -- the
#     payment and the message only ever meet through Kafka;
#   * the response carries no recipient digest. The row holds the owner digest and the view must
#     not render it, because a digest in a support response is a join key into another service's
#     pseudonyms. Only reading the live response shows it;
#   * a retry of a SENT message is refused rather than resent. Resending is the duplicate the
#     event-id uniqueness was supposed to prevent, reached through the API instead of the consumer;
#   * one settled payment is one message. The claim is an insert, and the row count for the
#     payment is read back from the database rather than assumed from the API;
#
# Usage: ./scripts/verify-notification-lifecycle.sh
#
# Why it provisions its own customer
# --------------------------------------------------------------------------------
# A payment needs a funded account, and the check must not disturb a balance anyone else is using,
# so it funds a throwaway identity of its own -- the same reasoning as verify-payment-lifecycle.sh,
# and it reuses that provisioning shape rather than inventing another.
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
readonly BODY_FILE=/tmp/notification-body.json

# A distinct identity per run, so a second run exercises fresh money rather than replaying the first
# run's and appearing to pass for the wrong reason.
readonly RUN_ID="notifycheck-${RANDOM}-${RANDOM}"
readonly PAYER="notifycheck-${RUN_ID}@fintech.test"

# The realm's support agent. See the note above: not created here.
readonly AGENT="agent@fintech.test"

# Seconds to wait for a message to appear. Consumption is asynchronous by design -- a payment
# settles before notification-service has heard of it -- so a check that asserted a message the
# instant the payment returned would be asserting the opposite of the architecture.
readonly NOTIFY_TIMEOUT=45

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

# A scalar against the notification database. The delivery log is the platform's own record, so the
# assertions read it from the database as well as from the API: an API that rendered a figure the
# database does not hold would pass every response-shape check while the rows underneath disagreed.
notify_psql() {
  docker compose -f "${ROOT_DIR}/docker-compose.yml" exec -T postgres \
    psql -U "$(env_value POSTGRES_SUPERUSER)" -d fintech_notifications -tAc "$1" 2>/dev/null | tr -d ' \r'
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
 "firstName":"Notify","lastName":"Check",
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

card_token() { printf 'tok_ntf%04x%09d' "$$" "$RANDOM"; }

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
    -d "$(printf '{"amount":"%s","currency":"%s","cardToken":"%s","payeeName":"%s","payeeReference":"notifycheck-%s"}' \
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

step "Checking the local realm has a support agent"
[[ -f "${ENV_FILE}" ]] || fail "no .env found. Run ./scripts/bootstrap.sh first."

TOKEN="$(kc_token "$AGENT")"
[[ -n "$TOKEN" ]] ||
  fail "${AGENT} cannot obtain a token. If the stack was already up when Phase 8 was added, the realm \
was not re-imported: Keycloak imports a realm only when it does not exist. Recreate the realm per \
infrastructure/keycloak/realm/README.md."

step "The gateway routes the delivery log, and refuses a customer and an auditor"
expect_status 200 GET "${GATEWAY}/api/v1/notifications" "" "$TOKEN"
pass "a support agent reaches the delivery log"

kc_ensure_user "$PAYER"
absorb "$PAYER" "$FULL_NAME" "SYNTH-0001"
PAYER_TOKEN="$ABSORBED_TOKEN"

expect_status 403 GET "${GATEWAY}/api/v1/notifications" "" "$PAYER_TOKEN"
pass "a customer is refused the delivery log before any payment exists to read"

AUDITOR_TOKEN="$(kc_token auditor@fintech.test)"
[[ -n "$AUDITOR_TOKEN" ]] || fail "auditor@fintech.test cannot obtain a token; re-import the realm"
expect_status 403 GET "${GATEWAY}/api/v1/notifications" "" "$AUDITOR_TOKEN"
pass "an auditor is refused the delivery log -- supervision reads the audit trail, not this queue"

step "Paying, and waiting for the message to be written"
CARD="$(card_token)"
FUND_KEY="notify-fund-${RUN_ID}"
PAY_KEY="notify-pay-${RUN_ID}"
fund "$PAYER_TOKEN" "100.00" "$FUND_KEY"
pay "$PAYER_TOKEN" "$CARD" "25.00" "$PAY_KEY" "Acme Books"
TXN_ID="$(json 'd["id"]')"
settle "$PAYER_TOKEN" "$TXN_ID"
pass "25.00 ${CURRENCY} settled as ${TXN_ID}"

NOTICE_ID=""
deadline=$((SECONDS + NOTIFY_TIMEOUT))
while [[ $SECONDS -lt $deadline ]]; do
  if [[ "$(curl -s -o "$BODY_FILE" -w '%{http_code}' \
      "${GATEWAY}/api/v1/notifications/by-transaction/${TXN_ID}" \
      -H "Authorization: Bearer ${TOKEN}")" == "200" ]]; then
    # The settled message, not the first one: a payment that is authorised and then settled is two
    # facts and therefore two messages, and waiting for the first would assert the opposite of the
    # payment's own state machine.
    NOTICE_ID="$(json '[m["id"] for m in d["content"] if m["kind"]=="PAYMENT_SETTLED"][0]' 2>/dev/null)" 2>/dev/null || true
    [[ -n "$NOTICE_ID" ]] && break
  fi
  sleep 2
done
[[ -n "$NOTICE_ID" ]] ||
  fail "no notification for ${TXN_ID} after ${NOTIFY_TIMEOUT}s; the payment settled and nobody was told"
pass "the settled payment produced message ${NOTICE_ID}"

step "The message says what was recorded, and names no recipient"
expect_status 200 GET "${GATEWAY}/api/v1/notifications/${NOTICE_ID}" "" "$TOKEN"
[[ "$(json 'd["kind"]')" == "PAYMENT_SETTLED" ]] ||
  fail "the message kind is '$(json 'd["kind"]')' rather than PAYMENT_SETTLED"
[[ "$(json 'd["amount"]')" == "25.00" ]] ||
  fail "the message amount is '$(json 'd["amount"]')' rather than the 25.00 that was paid"
[[ "$(json 'd["subject"]')" == "Payment completed" ]] ||
  fail "the message subject is '$(json 'd["subject"]')'"
python3 -c "
import json, sys
d = json.load(open('$BODY_FILE'))
assert 'recipientDigest' not in d, 'the response names a recipient digest: %s' % json.dumps(d)[:300]
" || fail "the message names its recipient digest, which is a join key into another service's pseudonyms"
pass "PAYMENT_SETTLED for 25.00 ${CURRENCY}, with no digest in the response"

step "The database agrees with the API, and holds no card material"
DB_KIND="$(notify_psql "select kind from notifications where id='${NOTICE_ID}'")"
[[ "$DB_KIND" == "PAYMENT_SETTLED" ]] ||
  fail "the row is '${DB_KIND}' while the API says PAYMENT_SETTLED"
DB_COUNT="$(notify_psql "select count(*) from notifications where transaction_id='${TXN_ID}' and kind='PAYMENT_SETTLED'")"
[[ "$DB_COUNT" == "1" ]] ||
  fail "${DB_COUNT} settled messages name ${TXN_ID}; one capture is one message"
TOKEN_COLS="$(notify_psql "select count(*) from information_schema.columns where table_name='notifications' and (column_name like '%token%' or column_name like '%pan%' or column_name like '%cvv%')")"
[[ "$TOKEN_COLS" == "0" ]] ||
  fail "the notifications table has a card-material column; a log that cannot hold a card number cannot leak one"
pass "one settled row for the payment, and no column a card number could be stored in"

step "A sent message is not retried, and a failed one is"
expect_status 409 POST "${GATEWAY}/api/v1/notifications/${NOTICE_ID}/retry" "" "$TOKEN"
[[ "$(json 'd["error"]')" == "NOTIFICATION_ALREADY_SENT" ]] ||
  fail "retrying a sent message answered '$(json 'd["error"]')' rather than refusing the duplicate"
pass "retrying the sent message is refused as ALREADY_SENT, not resent"

# The scheduler's path, driven by hand: mark the row failed and retry it through the API. The send
# itself is simulated, so this proves the state machine rather than a provider.
notify_psql "update notifications set status='FAILED', attempts=1, next_attempt_at=now() where id='${NOTICE_ID}'" >/dev/null
expect_status 200 POST "${GATEWAY}/api/v1/notifications/${NOTICE_ID}/retry" "" "$TOKEN"
[[ "$(json 'd["status"]')" == "SENT" ]] ||
  fail "retrying the failed message left it '$(json 'd["status"]')' rather than SENT"
pass "a failed message retries to SENT"

printf '\n\033[32mNotification lifecycle verified.\033[0m A payment settled into a message over Kafka,\n'
printf 'the delivery log answered a support agent and refused everyone else, the message named the\n'
printf 'recorded figure with no recipient digest, a sent message refused its retry, and a failed one\n'
printf 'retried to SENT. Spend left behind: one throwaway customer and 100.00 %s funded, 25.00 of it\n' "$CURRENCY"
printf 'paid to Acme Books. The message %s is left SENT, which is the correct end state for a message\n' "$NOTICE_ID"
printf 'that went out -- and it stays, because the delivery log is a log rather than a queue.\n'
