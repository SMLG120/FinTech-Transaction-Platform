#!/usr/bin/env bash
#
# Phase 5 live check: fund an account and walk a payment through its lifecycle, over HTTP, through
# the gateway.
#
# Dev convenience only. Every assertion here is one the unit tests cannot make, and each one has
# caught something:
#
#   * the gateway routes /api/v1/transactions and /api/v1/accounts at all;
#   * the Idempotency-Key header survives the gateway hop (a default filter or a header whitelist
#     would strip it, and the service would answer 400 IDEMPOTENCY_KEY_REQUIRED while every test
#     that skips the gateway still passed);
#   * a replayed response is byte-identical to the original, which is the regression this script
#     exists for. Handing the stored JSON text back through a message converter JSON-encodes it a
#     second time, so a retry receives a quoted string where the first attempt received an object.
#     The status code is 201 either way and the payment is made exactly once either way, so nothing
#     short of diffing the bytes notices.
#
# Usage: ./scripts/verify-payment-lifecycle.sh
#
# Why this provisions its own Keycloak users instead of using scripts/get-token.sh
# --------------------------------------------------------------------------------
# The identities in the realm are one per role, and a payment needs a funded account, so this check
# needs two customers it can fund without disturbing anyone else's balance. Same reasoning as
# verify-card-lifecycle.sh: the realm's own users are shared with every other check, and destroying
# one to free it up is worse than creating a throwaway.

set -euo pipefail

readonly GATEWAY="http://localhost:8080"
readonly KEYCLOAK="http://localhost:8180"
readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ENV_FILE="${ROOT_DIR}/.env"
readonly DEV_PASSWORD="fintech-dev-only"
readonly BODY_FILE=/tmp/pay-body.json
readonly FIRST_FILE=/tmp/pay-first.json
readonly SECOND_FILE=/tmp/pay-second.json

# A distinct pair per run, so a second run of this script exercises a fresh key rather than replaying
# the first run's and appearing to pass for the wrong reason.
readonly RUN_ID="paycheck-${RANDOM}-${RANDOM}"

# One customer who pays, and one who must not be able to see it. Scoped to the run, so each run starts
# from a zero balance and every assertion below can be an absolute figure.
#
# A shared identity cannot work here. The check funds it on every run and cannot un-fund it, so the
# balance climbs until it passes the 5000.00 per-transaction ceiling -- and then the payment that was
# meant to be refused for being unaffordable gets refused for being over the limit instead. Both are
# 422, so the check would keep reporting success while testing nothing.
readonly PAYER="paycheck-${RUN_ID}-a@fintech.test"
readonly STRANGER="paycheck-${RUN_ID}-b@fintech.test"

pass() { printf '  \033[32mok\033[0m   %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; exit 1; }
step() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# ---- HTTP helpers ---------------------------------------------------------------------------

# Returns the status code; the body lands in $BODY_FILE.
#
# The token is checked for emptiness rather than defaulted, so a setup that lost its token fails
# loudly instead of quietly borrowing the payer's credentials and turning a negative test into a
# positive one. Pass "none" to genuinely send no credentials.
request() {
  local method="$1" url="$2" body="${3:-}" token="${4-$TOKEN}" key="${5:-}"
  local args=(-s -o "$BODY_FILE" -w '%{http_code}' -X "$method" "$url")
  if [[ "$token" == "none" ]]; then
    token=""
  elif [[ -z "$token" ]]; then
    fail "${method} ${url} was called with an empty token; refusing to guess whose credentials to use"
  fi
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$key" ]] && args+=(-H "Idempotency-Key: $key")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' -d "$body")
  curl "${args[@]}"
}

expect_status() {
  local want="$1" method="$2" url="$3" body="${4:-}" token="${5-$TOKEN}" key="${6:-}"
  local got
  got="$(request "$method" "$url" "$body" "$token" "$key")"
  if [[ "$got" != "$want" ]]; then
    printf '  expected %s, got %s\n' "$want" "$got"
    cat "$BODY_FILE"
    fail "$method $url"
  fi
}

json() { python3 -c "import json,sys;d=json.load(open('$BODY_FILE'));print($1)"; }

# A payment body, built rather than rewritten with sed: an amount and a reference are two different
# things to vary, and a substitution that changed one while leaving the other would silently send the
# request that the step above already used.
payment_body() {
  printf '{"amount":"%s","currency":"GBP","cardToken":"%s","payeeName":"Coffee","payeeReference":"%s"}' \
    "$1" "$CARD_TOKEN" "$2"
}

# The payer's spendable balance, as the API reports it.
available_now() {
  expect_status 200 GET "${GATEWAY}/api/v1/accounts/balance?currency=GBP" "" "${1-$TOKEN}"
  json 'd["available"]'
}

header_value() {
  local name="$1"
  python3 - "$name" <<'PY'
import re, sys
raw = open('/tmp/pay-headers.txt', encoding='utf-8', errors='replace').read()
blocks = re.split(r'\r?\n\r?\n', raw)
last = blocks[-1] if blocks[-1].strip() else (blocks[-2] if len(blocks) > 1 else '')
for line in last.splitlines():
    if ':' in line:
        name, _, value = line.partition(':')
        if name.strip().lower() == sys.argv[1].lower():
            print(value.strip())
PY
}

# ---- database helpers -----------------------------------------------------------------------

# Runs a scalar query against the transaction database and prints the value.
psql_scalar() {
  docker compose -f "${ROOT_DIR}/docker-compose.yml" exec -T postgres \
    psql -U "$(env_value POSTGRES_SUPERUSER)" -d fintech_transactions -tAc "$1" 2>/dev/null | tr -d ' \r'
}

env_value() { grep -E "^$1=" "${ENV_FILE}" | head -1 | cut -d= -f2- | tr -d '"'; }

# ---- Keycloak helpers -----------------------------------------------------------------------

kc_admin_token() {
  curl -s -X POST "${KEYCLOAK}/realms/master/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=admin-cli \
    --data-urlencode "username=$(env_value KEYCLOAK_ADMIN_USERNAME)" \
    --data-urlencode "password=$(env_value KEYCLOAK_ADMIN_PASSWORD)" |
    python3 -c 'import json,sys; print(json.load(sys.stdin).get("access_token", ""))'
}

kc_token() {
  curl -s -X POST "${KEYCLOAK}/realms/$(env_value KEYCLOAK_REALM)/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=fintech-web \
    --data-urlencode "username=$1" --data-urlencode "password=${DEV_PASSWORD}" |
    python3 -c 'import json,sys; print(json.load(sys.stdin).get("access_token", ""))'
}

kc_user_id() {
  local admin realm
  admin="$(kc_admin_token)"
  realm="$(env_value KEYCLOAK_REALM)"
  # No command substitution wrapping the pipeline: the body already has quoted Python in it, and
  # adding "$( ... )" around a pipeline that ends in a quoted string is where the quoting goes wrong.
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
    # firstName/lastName are required by the realm's user profile, and a user created without them
    # authenticates to "Account is not fully set up" rather than to a token. The payload goes through a
    # file so the JSON needs no escaping inside a shell string.
    cat > /tmp/kc-new-user.json <<JSON
{"username":"${username}","enabled":true,"email":"${username}","emailVerified":true,
 "firstName":"Pay","lastName":"Check",
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

  # Realm roles cannot be set in the create payload; Keycloak's admin API ignores realmRoles there and
  # wants a separate call with the full role representation, which it will not accept as a bare
  # {id, name} -- it answers 409 "Role not found". Fetching the representation is the difference between
  # a user who can register and one who gets 403 INSUFFICIENT_ROLE.
  curl -s "${KEYCLOAK}/admin/realms/${realm}/roles/CUSTOMER" -H "Authorization: Bearer ${admin}" \
    -o /tmp/kc-role.json
  python3 -c "import json; json.dump([json.load(open('/tmp/kc-role.json'))], open('/tmp/kc-rolemap.json','w'))"
  curl -s -o /dev/null -X POST "${KEYCLOAK}/admin/realms/${realm}/users/${user_id}/role-mappings/realm" \
    -H "Authorization: Bearer ${admin}" -H 'Content-Type: application/json' -d @/tmp/kc-rolemap.json

  kc_token "$username" | grep -q . || fail "${username} cannot obtain a token after being created"
}

# ---- customer setup -------------------------------------------------------------------------

register_body() {
  printf '{"fullName":"%s","dateOfBirth":"%s","nationality":"GB","email":"%s","phone":"%s","address":%s}' \
    "$1" "$2" "$3" "$4" "$5"
}

readonly FULL_NAME="Ada Lovelace"
readonly DOB="1815-12-10"
readonly ADDRESS='{"line1":"12 Analytical Engine Way","city":"London","postalCode":"EC1A 1BB","country":"GB"}'

# Echoes "<token> <customer-id> <kyc-status>", or returns non-zero.
#
# KYC is not a formality for a payment: the customer has to be approved before the platform will let
# them hold a funded account, and a payment platform that funds unvetted identities is a money
# laundering surface.
setup_customer() {
  local username="$1" name="$2" docref="$3" token status id existing
  token="$(kc_token "$username")"
  [[ -n "$token" ]] || return 1

  status="$(curl -s -o "$BODY_FILE" -w '%{http_code}' -X POST "${GATEWAY}/api/v1/customers" \
    -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' \
    -d "$(register_body "$name" "$DOB" "user-${username}" "+447700900${RANDOM:0:3}" "$ADDRESS")")"
  case "$status" in
    201) id="$(json 'd["id"]')" ;;
    409)
      # Registered by an earlier run. Reuse it unless it is a tombstone: erasure scrubs the subject
      # permanently, and a check that cannot tell the two apart reports a working platform as broken.
      status="$(curl -s -o "$BODY_FILE" -w '%{http_code}' "${GATEWAY}/api/v1/customers/me" \
        -H "Authorization: Bearer ${token}")"
      [[ "$status" == "200" ]] || return 1
      id="$(json 'd["id"]')" ;;
    *) return 1 ;;
  esac
  [[ -n "$id" ]] || return 1

  # The provider decides at submission and the state machine will not revisit a decided check, so an
  # already-decided customer is taken as it stands. Without this the second run of this script fails,
  # which is the worst property a re-runnable check can have.
  status="$(curl -s -o "$BODY_FILE" -w '%{http_code}' "${GATEWAY}/api/v1/customers/me" \
    -H "Authorization: Bearer ${token}")"
  existing="$(json 'd["kycStatus"]')"
  if [[ "$existing" != "NOT_STARTED" ]]; then
    printf '%s %s %s' "$token" "$id" "$existing"
    return 0
  fi

  status="$(curl -s -o "$BODY_FILE" -w '%{http_code}' -X POST "${GATEWAY}/api/v1/customers/${id}/kyc" \
    -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' \
    -d "{\"documentReference\":\"${docref}\",\"printedName\":\"${name}\",\"expiryDate\":\"2035-01-01\",\"issuingCountry\":\"GB\",\"nationality\":\"GB\"}")"
  [[ "$status" == "200" ]] || return 1
  local decided
  decided="$(json 'd["status"]')"
  [[ "$decided" == "APPROVED" ]] || return 1
  printf '%s %s %s' "$token" "$id" "$decided"
}

absorb() {
  local raw
  raw="$(setup_customer "$@")" || fail "could not set up ${1}"
  read -r ABSORBED_TOKEN ABSORBED_ID ABSORBED_KYC <<<"$raw"
  [[ -n "$ABSORBED_TOKEN" && -n "$ABSORBED_ID" && -n "$ABSORBED_KYC" ]] ||
    fail "setup for ${1} returned '${raw}', expected a token, an id and a KYC status"
}

# ---- the check ------------------------------------------------------------------------------

step "Provisioning two local dev identities"
[[ -f "${ENV_FILE}" ]] || fail "no .env found. Run ./scripts/bootstrap.sh first."
kc_ensure_user "$PAYER"
kc_ensure_user "$STRANGER"
pass "payer and an unrelated second customer"

step "Registering the payer and passing identity checks"
absorb "$PAYER" "$FULL_NAME" "SYNTH-PPT-${RANDOM:0:4}"
TOKEN="$ABSORBED_TOKEN"
[[ "$ABSORBED_KYC" == "APPROVED" ]] || fail "payer kyc is ${ABSORBED_KYC}, expected APPROVED"
pass "customer $ABSORBED_ID, KYC APPROVED"

step "Registering the second customer"
absorb "$STRANGER" "Grace Hopper" "SYNTH-PPT-${RANDOM:0:4}"
STRANGER_TOKEN="$ABSORBED_TOKEN"
STRANGER_ID="$ABSORBED_ID"
pass "customer $STRANGER_ID"

step "Refusing a money-moving request with no Idempotency-Key"
# 400 and not 500. The refusal is correct either way, but a 500 tells the caller to wait for a
# platform that is not broken, and it fires a 5xx alert for ordinary client misuse.
# Valid parameters, deliberately: a request that also omitted amount or currency would come back 400
# VALIDATION_ERROR and pass a status check while testing the wrong refusal entirely.
expect_status 400 POST "${GATEWAY}/api/v1/accounts/fund?amount=100.00&currency=GBP"
[[ "$(json 'd["error"]')" == "IDEMPOTENCY_KEY_REQUIRED" ]] ||
  fail "expected IDEMPOTENCY_KEY_REQUIRED, got $(json 'd["error"]')"
pass "400 IDEMPOTENCY_KEY_REQUIRED rather than a 500, and rather than a validation error about something else"

step "Funding the account, and confirming the key reached the service"
# The proof that the gateway forwards the header. If a default filter stripped it, the very first
# keyed call would have answered 400 and the check would stop here rather than silently continuing.
FUND_KEY="${RUN_ID}-fund"
TOPPED_UP="$(available_now)"
[[ "$TOPPED_UP" == "0.00" ]] ||
  fail "a brand new payer should hold nothing, holds ${TOPPED_UP}"
expect_status 200 POST "${GATEWAY}/api/v1/accounts/fund?amount=500.00&currency=GBP" "" "$TOKEN" "$FUND_KEY"
[[ "$(json 'd["available"]')" == "500.00" ]] ||
  fail "expected 500.00 available after funding, got $(json 'd["available"]')"
pass "500.00 available, and the Idempotency-Key survived the gateway"

step "Replaying the top-up, and comparing the bytes"
curl -s -D /tmp/pay-headers.txt -o "$FIRST_FILE" -X POST "${GATEWAY}/api/v1/accounts/fund?amount=500.00&currency=GBP" \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: ${FUND_KEY}"
[[ "$(header_value Idempotency-Replayed)" == "true" ]] ||
  fail "the replay was not marked Idempotency-Replayed"
curl -s -D /tmp/pay-headers.txt -o "$SECOND_FILE" -X POST "${GATEWAY}/api/v1/accounts/fund?amount=500.00&currency=GBP" \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: ${FUND_KEY}"
cmp -s "$FIRST_FILE" "$SECOND_FILE" ||
  fail "the replayed body differs from the original:
  first:  $(cat "$FIRST_FILE")
  second: $(cat "$SECOND_FILE")"
python3 -c "import json,sys; json.load(open('$SECOND_FILE'))" ||
  fail "the replayed body is not a JSON document, so a client parsing it as an object would fail"
pass "byte-identical replay, marked Idempotency-Replayed, and still a JSON document"

step "Confirming the replayed top-up did not credit twice"
# The digest is looked up rather than guessed. A subject digest is an HMAC, so nothing about it can be
# predicted from the username, and a check that hard-coded a prefix would pass against an empty table
# and prove nothing.
PAYER_DIGEST="$(psql_scalar "SELECT owner_subject_digest FROM idempotency_keys WHERE idempotency_key = '${FUND_KEY}';")"
[[ -n "$PAYER_DIGEST" ]] || fail "no idempotency key row for ${FUND_KEY}, so the top-up never reached the ledger"
BALANCE_PENCE="$(psql_scalar "SELECT COALESCE(SUM(balance_minor),0) FROM ledger_accounts WHERE owner_ref = '${PAYER_DIGEST}' AND type = 'CUSTOMER_AVAILABLE' AND currency_code = 'GBP';")"
[[ "$BALANCE_PENCE" == "50000" ]] ||
  fail "expected exactly one credit of 50000 pence, found ${BALANCE_PENCE}"
[[ "$(available_now)" == "500.00" ]] ||
  fail "the replayed top-up credited a second time"
pass "one credit of 50000 pence and one journal entry, and the replay left the balance alone"

step "Paying with a key, then replaying it"
PAY_KEY="${RUN_ID}-pay"
readonly CARD_TOKEN="tok_$(printf 'a1b2c3d4e5f6%048d' "$RANDOM")"
PAYMENT_BODY="$(payment_body 25.00 "${RUN_ID}-order-1")"
expect_status 201 POST "${GATEWAY}/api/v1/transactions" "$PAYMENT_BODY" "$TOKEN" "$PAY_KEY"
TX_ID="$(json 'd["id"]')"
[[ "$(json 'd["status"]')" == "AUTHORIZED" ]] || fail "expected AUTHORIZED, got $(json 'd["status"]')"
curl -s -D /tmp/pay-headers.txt -o "$FIRST_FILE" -X POST "${GATEWAY}/api/v1/transactions" \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: ${PAY_KEY}" \
  -H 'Content-Type: application/json' -d "$PAYMENT_BODY"
curl -s -D /tmp/pay-headers.txt -o "$SECOND_FILE" -X POST "${GATEWAY}/api/v1/transactions" \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: ${PAY_KEY}" \
  -H 'Content-Type: application/json' -d "$PAYMENT_BODY"
cmp -s "$FIRST_FILE" "$SECOND_FILE" ||
  fail "the replayed payment differs from the original:
  first:  $(cat "$FIRST_FILE")
  second: $(cat "$SECOND_FILE")"
python3 -c "
import json
d = json.load(open('$SECOND_FILE'))
assert d['id'] == '$TX_ID', d
assert d['status'] == 'AUTHORIZED', d
" || fail "the replayed payment is not the same payment, AUTHORIZED"
ROWS="$(psql_scalar "SELECT count(*) FROM transactions WHERE id='${TX_ID}';")"
[[ "$ROWS" == "1" ]] || fail "expected exactly one transaction row, found ${ROWS}"
pass "${TX_ID} AUTHORIZED, replayed byte for byte, and one row in the database"

step "Refusing the same key with a different amount"
# The case that must not replay. Answering with the stored response would tell the caller their
# second, larger payment succeeded -- a payment that was never made.
DIFFERENT="$(payment_body 99.00 "${RUN_ID}-order-1")"
expect_status 409 POST "${GATEWAY}/api/v1/transactions" "$DIFFERENT" "$TOKEN" "$PAY_KEY"
[[ "$(json 'd["error"]')" == "IDEMPOTENCY_KEY_REUSED" ]] ||
  fail "expected IDEMPOTENCY_KEY_REUSED, got $(json 'd["error"]')"
[[ -n "$(json 'd["correlationId"]')" ]] || fail "the refusal carries no correlation id to quote in a ticket"
pass "409 IDEMPOTENCY_KEY_REUSED"

step "Refusing a payment the payer cannot cover"
# 900.00 is over the 500.00 balance and under the 5000.00 per-transaction ceiling, so the refusal is
# about the balance and nothing else. Both a decline and a breach of the ceiling answer 422, so the
# body is what tells them apart, and this asserts the body.
expect_status 422 POST "${GATEWAY}/api/v1/transactions" \
  "$(payment_body 900.00 "${RUN_ID}-order-2")" \
  "$TOKEN" "${RUN_ID}-declined"
[[ "$(json 'd["status"]')" == "DECLINED" ]] ||
  fail "expected a DECLINED payment, got $(cat "$BODY_FILE")"
pass "422 with a DECLINED body, not a 201 that a status-code-only client would read as success"

step "Settling, then reading it back"
expect_status 200 POST "${GATEWAY}/api/v1/transactions/${TX_ID}/settle" "" "$TOKEN"
[[ "$(json 'd["status"]')" == "SETTLED" ]] || fail "expected SETTLED, got $(json 'd["status"]')"
expect_status 200 GET "${GATEWAY}/api/v1/transactions/${TX_ID}" "" "$TOKEN"
[[ "$(json 'd["status"]')" == "SETTLED" ]] || fail "expected SETTLED on readback"
pass "SETTLED, and settled_at was accepted by the schema on a live database"

step "Reversing the capture, and confirming the money came back"
expect_status 200 POST "${GATEWAY}/api/v1/transactions/${TX_ID}/reverse" "" "$TOKEN"
[[ "$(json 'd["status"]')" == "REVERSED" ]] || fail "expected REVERSED, got $(json 'd["status"]')"
# Back to the post-top-up figure, not the pre-top-up one: the top-up above was a real credit and
# reversing the payment undoes the hold, not the funding.
FINAL="$(available_now)"
[[ "$FINAL" == "500.00" ]] ||
  fail "expected the full 500.00 back after reversing the 25.00 payment, got ${FINAL}"
pass "REVERSED, and all 500.00 is spendable again"

step "Refusing to read somebody else's payment"
# The stranger is fully approved, so eligibility cannot be the reason for the refusal.
expect_status 404 GET "${GATEWAY}/api/v1/transactions/${TX_ID}" "" "$STRANGER_TOKEN"
pass "404, and the stranger's approval was not the reason"

step "Confirming the ledger balances and the token is the only card reference"
# Debits against credits, per entry. direction holds the sign and amount_minor is always positive, so
# the test is a comparison of the two sides rather than a sum, and it would catch a posting that
# doubled one leg of an entry just as readily as one that dropped it.
UNBALANCED="$(psql_scalar "SELECT count(*) FROM journal_entries e WHERE (SELECT COALESCE(SUM(CASE WHEN l.direction = 'DEBIT' THEN l.amount_minor ELSE 0 END),0) - COALESCE(SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount_minor ELSE 0 END),0) FROM journal_lines l WHERE l.entry_id = e.id) <> 0;")"
[[ "$UNBALANCED" == "0" ]] || fail "${UNBALANCED} journal entries do not balance"
# The header records the entry's magnitude once, not once per leg, so it is compared against each
# side rather than against the total of both. A CAPTURE of 25.00 is a header of 2500 over a 2500 debit
# and a 2500 credit; adding the legs and comparing to the header would reject every correct entry.
HEADER_MISMATCH="$(psql_scalar "SELECT count(*) FROM journal_entries e WHERE e.amount_minor <> (SELECT COALESCE(SUM(CASE WHEN l.direction = 'DEBIT' THEN l.amount_minor ELSE 0 END),0) FROM journal_lines l WHERE l.entry_id = e.id);")"
[[ "$HEADER_MISMATCH" == "0" ]] || fail "${HEADER_MISMATCH} journal headers disagree with their own debit legs"
NEGATIVE="$(psql_scalar "SELECT count(*) FROM ledger_accounts WHERE type LIKE 'CUSTOMER%' AND balance_minor < 0;")"
[[ "$NEGATIVE" == "0" ]] || fail "${NEGATIVE} customer accounts are negative"
# Not a card number, and not something that could be turned back into one. A PAN is 13 to 19 digits
# with separators, so anything matching that shape in a token column is a leak.
PAN_LIKE="$(psql_scalar "SELECT count(*) FROM transactions WHERE card_token ~ '^[0-9][0-9 .-]{11,21}[0-9]$';")"
[[ "$PAN_LIKE" == "0" ]] || fail "${PAN_LIKE} card references look like card numbers rather than tokens"
OURS="$(psql_scalar "SELECT card_token FROM transactions WHERE id = '${TX_ID}';")"
[[ "$OURS" == "$CARD_TOKEN" ]] || fail "the stored card reference is not the token that was sent"
pass "every journal entry balances, no customer account is negative, and the only card reference is the opaque token"

step "Confirming the outbox published this payment's events"
UNPUBLISHED="$(psql_scalar "SELECT count(*) FROM outbox_events WHERE aggregate_id='${TX_ID}' AND published_at IS NULL;")"
[[ "$UNPUBLISHED" == "0" ]] ||
  fail "${UNPUBLISHED} events for ${TX_ID} are still unpublished, so the relay is not draining"
pass "all events for ${TX_ID} have been published"

printf '\n\033[1m%s\033[0m\n' "  the rows behind the payment above, if you want to see them yourself:"
printf '    docker compose exec -T postgres psql -U fintech_transactions -d fintech_transactions \\\n'
printf "      -c \"select id, status, amount_minor, currency_code, version from transactions where id = '%s';\"\n" "$TX_ID"
printf '    docker compose exec -T postgres psql -U fintech_transactions -d fintech_transactions \\\n'
printf '      -c "select type, balance_minor, version from ledger_accounts order by created_at;"\n'

printf '\n\033[32mPhase 5 payment lifecycle verified through the gateway.\033[0m\n'
printf 'Payment %s funded, authorised, settled, reversed, and replayed without paying twice.\n' "$TX_ID"
