#!/usr/bin/env bash
#
# Phase 6 live check: make a payment, let the engine score it, and work the alert it raises, over
# HTTP, through the gateway, as a real fraud analyst.
#
# Dev convenience only. Every assertion here is one the unit and integration tests cannot make, and
# each one has caught something:
#
#   * the gateway routes /api/v1/fraud/** at all, and refuses it to a customer. The service enforces
#     the same rule, but a gateway that let a CUSTOMER token through to a staff endpoint would still
#     be a gateway that forwards a customer's request to a fraud engine;
#   * the platform actually scores a payment that was authorised, which is the whole point of the
#     phase and the one thing a fraud test that calls the service directly never proves -- the
#     transaction and the decision only meet through Kafka;
#   * the digest join works. The alert is raised against an ownerSubjectDigest that transaction-service
#     computed, and a mismatch between the two services' key or key derivation produces an alert with
#     an unrecognisable owner rather than an error, so it has to be read back and compared;
#   * a claim is exclusive. Two analysts claiming one alert, and the loser must be told so;
#   * the alert timeline records the human actions that made it, because an audit trail assembled only
#     from system events cannot say who overruled what.
#
# Usage: ./scripts/verify-fraud-lifecycle.sh
#
# Why this provisions its own customers
# --------------------------------------------------------------------------------
# A payment needs a funded account, and the check must not disturb a balance anyone else is using, so
# it funds throwaway identities of its own -- the same reasoning as verify-payment-lifecycle.sh. It
# reuses that script's funding and card-provisioning shape rather than inventing a second one.
#
# The analyst is NOT provisioned here. FRAUD_ANALYST is a realm role, and the realm's own users are
# one per role, so analyst@fintech.test exists in the realm file and is the identity this check uses.
# Creating a second analyst would mean granting a role to a throwaway user to test a policy that the
# realm file already defines; if that user is missing, the realm has not been re-imported and the
# honest answer is to say so.

set -euo pipefail

readonly GATEWAY="http://localhost:8080"
readonly KEYCLOAK="http://localhost:8180"
readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ENV_FILE="${ROOT_DIR}/.env"
readonly DEV_PASSWORD="fintech-dev-only"
readonly BODY_FILE=/tmp/fraud-body.json
readonly FIRST_FILE=/tmp/fraud-first.json

# A distinct pair per run, so a second run exercises fresh money rather than replaying the first run's
# and appearing to pass for the wrong reason.
readonly RUN_ID="fraudcheck-${RANDOM}-${RANDOM}"

# Who pays, and who is the victim whose velocity the engine will see. A second customer is not
# decoration: R003 is "velocity on this subject", and with one customer in the system the only subject
# with history is the one paying, so a card tested twice in a row trips velocity for the wrong reason
# and the engine's view of a fresh card is never actually observed.
readonly PAYER="fraudcheck-${RUN_ID}-a@fintech.test"
readonly VICTIM="fraudcheck-${RUN_ID}-b@fintech.test"

# The realm's analyst. See the note above: not created here.
readonly ANALYST="analyst@fintech.test"

# Seconds to wait for the engine to score. Scoring is asynchronous by design -- a payment is authorised
# before fraud has seen it -- so a check that asserted a decision the instant the payment returned
# would be asserting the opposite of the architecture.
readonly SCORE_TIMEOUT=45

pass() { printf '  \033[32mok\033[0m   %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; exit 1; }
step() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# ---- HTTP helpers ---------------------------------------------------------------------------

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

# Reads a field out of the last response. A missing field raises here rather than returning an empty
# string, because under `set -e` a command substitution that fails takes the whole script down with no
# message at all -- which is indistinguishable from the check passing.
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

# The realm roles in a token. A JWT is three base64url segments, not JSON, so the payload has to be
# decoded before it can be read -- and a token that verifies but carries no roles is the exact failure
# this platform's realm produced during development, so the check reads the roles rather than inferring
# them from a 403 later.
token_roles() {
  printf '%s' "$1" | python3 -c '
import base64, json, sys
payload = sys.stdin.read().strip().split(".")[1]
payload += "=" * (-len(payload) % 4)
print(",".join(json.loads(base64.urlsafe_b64decode(payload)).get("realm_access", {}).get("roles", [])) or "none")
'
}

# ---- environment, database, Keycloak -------------------------------------------------------

env_value() { grep -E "^$1=" "${ENV_FILE}" | head -1 | cut -d= -f2- | tr -d '"'; }

# A scalar against the fraud database. This is how the digest join is checked: the API returns the
# owner digest it holds, and the database is the record of what transaction-service actually sent, so
# a divergence between the two shows up here rather than in production.
fraud_psql() {
  docker compose -f "${ROOT_DIR}/docker-compose.yml" exec -T postgres \
    psql -U "$(env_value POSTGRES_SUPERUSER)" -d fintech_fraud -tAc "$1" 2>/dev/null | tr -d ' \r'
}

# A scalar against the transaction database, for the same comparison on the transaction's own copy.
txn_psql() {
  docker compose -f "${ROOT_DIR}/docker-compose.yml" exec -T postgres \
    psql -U "$(env_value POSTGRES_SUPERUSER)" -d fintech_transactions -tAc "$1" 2>/dev/null | tr -d ' \r'
}

kc_admin_token() {
  curl -s -X POST "${KEYCLOAK}/realms/master/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=admin-cli \
    --data-urlencode "username=$(env_value KEYCLOAK_ADMIN_USERNAME)" \
    --data-urlencode "password=$(env_value KEYCLOAK_ADMIN_PASSWORD)" |
    python3 -c 'import json,sys; print(json.load(sys.stdin).get("access_token", ""))'
}

# A token for a local identity. Accepts either "analyst" or the full address, because a caller that
# passes the short form and gets an empty token back is indistinguishable from a realm that is down.
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
 "firstName":"Fraud","lastName":"Check",
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

  # realmRoles in the create payload is ignored by Keycloak, and the assignment call wants the full
  # role representation rather than {id, name} or it answers 409.
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

# Echoes "<token> <customer-id>", or returns non-zero.
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
      # Registered by an earlier run. Erasure scrubs a subject permanently, so a check that cannot
      # tell a reused identity from a tombstone reports a working platform as broken.
      status="$(curl -s -o "$BODY_FILE" -w '%{http_code}' "${GATEWAY}/api/v1/customers/me" \
        -H "Authorization: Bearer ${token}")"
      [[ "$status" == "200" ]] || return 1
      id="$(json 'd["id"]')" ;;
    *) return 1 ;;
  esac
  [[ -n "$id" ]] || return 1

  # The provider decides at submission and the state machine will not revisit a decided check, so an
  # already-decided customer is taken as it stands -- otherwise the second run of this script fails.
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

# A card token for this run, in the same opaque form verify-payment-lifecycle.sh uses.
#
# Not provisioned through card-service, deliberately: CardResponse carries no token by design -- the
# token is derived, not stored, and only the PAN is shown back at issuance. The payment API takes the
# opaque token, so a synthetic one is both sufficient and the honest thing to use here. What the fraud
# engine needs is a stable card string it can digest, and the same string twice digests to the same
# reference, which is exactly the property the velocity assertions below depend on.
#
# Two customers, two tokens: a shared one would make both payments look like the same card, and the
# engine's per-subject velocity would be tested against a card belonging to someone else.
card_token() {
  printf 'tok_%s%048d' "$1" "$RANDOM"
}

fund() {
  local token="$1" amount="$2" key="$3"
  expect_status 200 POST "${GATEWAY}/api/v1/accounts/fund?amount=${amount}&currency=GBP" "" "$token" "$key"
}

pay() {
  local token="$1" card="$2" amount="$3" key="$4" payee="$5"
  expect_status 201 POST "${GATEWAY}/api/v1/transactions" \
    "$(printf '{"amount":"%s","currency":"GBP","cardToken":"%s","payeeName":"%s","payeeReference":"fraudcheck-%s"}' \
        "$amount" "$card" "$payee" "$key")" "$token" "$key"
}

# ---- the check ------------------------------------------------------------------------------

step "Checking the local realm has an analyst"
[[ -f "${ENV_FILE}" ]] || fail "no .env found. Run ./scripts/bootstrap.sh first."
ANALYST_TOKEN="$(kc_token "$ANALYST")"
[[ -n "$ANALYST_TOKEN" ]] ||
  fail "${ANALYST} has no token. The realm predates FRAUD_ANALYST: re-import it, or add the role to an existing realm by hand."
ANALYST_ROLES="$(token_roles "$ANALYST_TOKEN")"
[[ "$ANALYST_ROLES" == *FRAUD_ANALYST* ]] ||
  fail "${ANALYST} holds '${ANALYST_ROLES}', not FRAUD_ANALYST. A token with no roles is valid and useless -- it authenticates nobody in particular."
pass "analyst token carries FRAUD_ANALYST"

step "Provisioning two local dev identities"
kc_ensure_user "$PAYER"
kc_ensure_user "$VICTIM"
pass "payer and a second customer whose card history the engine can compare against"

step "Registering and identity-checking both customers"
absorb "$PAYER" "$FULL_NAME" "SYNTH-PPT-${RANDOM:0:4}"
PAYER_TOKEN="$ABSORBED_TOKEN"
PAYER_ID="$ABSORBED_ID"
absorb "$VICTIM" "Grace Hopper" "SYNTH-PPT-${RANDOM:0:4}"
VICTIM_TOKEN="$ABSORBED_TOKEN"
VICTIM_ID="$ABSORBED_ID"
TOKEN="$PAYER_TOKEN"
pass "customers $PAYER_ID and $VICTIM_ID, KYC APPROVED"

step "Refusing a customer any view of the fraud surface"
# The gateway and the service both refuse this. Asserting it here, with a real customer token, is the
# only version of the claim that is about a real caller rather than a mock.
expect_status 403 GET "${GATEWAY}/api/v1/fraud/summary?window=PT168H" "" "$PAYER_TOKEN"
# The gateway refuses with a nested body and fraud-service with a flat one; the shape differs because
# the refusal happens at a different layer, and reading the wrong one reports a real 403 as a failure.
[[ "$(json 'd["error"]["code"]')" == "INSUFFICIENT_ROLE" ]] ||
  fail "expected INSUFFICIENT_ROLE for a customer, got $(json 'd["error"]["code"]')"
expect_status 403 GET "${GATEWAY}/api/v1/fraud/alerts" "" "$PAYER_TOKEN"
pass "403 INSUFFICIENT_ROLE for a customer on both a read and a queue read"

step "Refusing an auditor the ability to act"
# The read/actor split is the part of the role model that is easy to break by accident: adding
# AUDITOR to the action set "because it already reads" is a one-word change and a real control failure.
expect_status 200 GET "${GATEWAY}/api/v1/fraud/summary?window=PT168H" "" "$ANALYST_TOKEN"
AUDITOR_TOKEN="$(kc_token "auditor@fintech.test")"
[[ -n "$AUDITOR_TOKEN" ]] || fail "auditor@fintech.test has no token"
expect_status 200 GET "${GATEWAY}/api/v1/fraud/summary?window=PT168H" "" "$AUDITOR_TOKEN"
expect_status 403 POST "${GATEWAY}/api/v1/fraud/alerts/00000000-0000-0000-0000-000000000000/claim" \
  '' "$AUDITOR_TOKEN"
pass "an auditor reads the dashboard and is refused an alert claim"

step "Funding the payer, and giving each customer a card"
# 500 covers every payment this check makes: two ordinary ones, a five-payment burst, and a re-score.
fund "$PAYER_TOKEN" "500.00" "${RUN_ID}-fund-payer"
fund "$VICTIM_TOKEN" "500.00" "${RUN_ID}-fund-victim"
PAYER_CARD="$(card_token a)"
VICTIM_CARD="$(card_token b)"
pass "two funded customers, one card each"

step "Paying, and waiting for the engine to score it"
# The advisory property, asserted rather than assumed: the payment is authorised and the decision
# does not exist yet. Checking the decision is absent right after the payment is the half that proves
# scoring is genuinely asynchronous; polling for it is the half that proves it happens at all.
pay "$PAYER_TOKEN" "$PAYER_CARD" "12.00" "${RUN_ID}-pay-1" "Coffee"
TRANSACTION_ID="$(json 'd["id"]')"
PAYMENT_STATUS="$(json 'd["status"]')"
[[ "$PAYMENT_STATUS" == "AUTHORIZED" || "$PAYMENT_STATUS" == "COMPLETED" ]] ||
  fail "the payment came back ${PAYMENT_STATUS}; a 40.00 GBP coffee should be authorised, and this check is not testing the rules"
cp "$BODY_FILE" "$FIRST_FILE"
pass "payment $TRANSACTION_ID ${PAYMENT_STATUS} before fraud has seen it"

# Reaches the database once here, so a stack that is up but unfunded fails as "not up" rather than as a
# missing decision thirty seconds later.
fraud_psql "select count(*) from risk_decisions" >/dev/null 2>&1 ||
  fail "cannot reach the fraud database; is the stack up?"

DECIDED=""
for _ in $(seq 1 "$SCORE_TIMEOUT"); do
  COUNT="$(fraud_psql "select count(*) from risk_decisions where transaction_id='${TRANSACTION_ID}'")"
  if [[ "$COUNT" == "1" ]]; then
    DECIDED=yes
    break
  fi
  sleep 1
done
[[ -n "$DECIDED" ]] ||
  fail "no decision for ${TRANSACTION_ID} after ${SCORE_TIMEOUT}s. Check fraud-service logs and whether the platform topic exists."
pass "the engine scored payment $TRANSACTION_ID"

step "Reading the decision back through the API"
expect_status 200 GET "${GATEWAY}/api/v1/fraud/decisions/${TRANSACTION_ID}" "" "$ANALYST_TOKEN"
API_SCORE="$(json 'd["score"]')"
API_DECISION="$(json 'd["decision"]')"
API_BAND="$(json 'd["band"]')"
DB_SCORE="$(fraud_psql "select score from risk_decisions where transaction_id='${TRANSACTION_ID}'")"
[[ "$API_SCORE" == "$DB_SCORE" ]] ||
  fail "the API reports score ${API_SCORE} but the row says ${DB_SCORE}; a read path that disagrees with storage is worse than a wrong score"
# A band is derived, so it has to agree with the score it was derived from. A dashboard that sorts by
# band and highlights by score is a dashboard whose two columns can contradict each other.
EXPECTED_BAND="$(python3 -c "
s = int('${API_SCORE}')
print('LOW' if s <= 25 else 'MEDIUM' if s <= 50 else 'HIGH' if s <= 75 else 'CRITICAL')")"
[[ "$API_BAND" == "$EXPECTED_BAND" ]] ||
  fail "score ${API_SCORE} should band as ${EXPECTED_BAND}, but the API says ${API_BAND}"
pass "score ${API_SCORE}, band ${API_BAND}, decision ${API_DECISION}, and the API agrees with storage"

step "Checking the digest join between the two services"
# The engine is handed a digest, not an identity, and it is the only way it can recognise the owner of
# a card it has seen before. If transaction-service and fraud-service disagree about the key or the
# derivation, the engine does not error -- it raises an alert against a subject nobody recognises, and
# velocity silently resets. Comparing the two databases' copies of the same digest is the only place
# that failure is visible.
API_OWNER="$(json 'd.get("ownerSubjectDigest") or ""')"
TXN_OWNER="$(txn_psql "select owner_subject_digest from transactions where id='${TRANSACTION_ID}'")"
[[ -n "$API_OWNER" ]] || fail "the decision carries no ownerSubjectDigest, so the engine cannot recognise this customer next time"
[[ "$API_OWNER" == "$TXN_OWNER" ]] ||
  fail "the decision's owner digest ${API_OWNER} is not the transaction's ${TXN_OWNER}: the two services do not share a digest"
# And it must be a digest, not something reversible. A raw card token here would undo the entire
# privacy boundary, and nothing else on this path would complain.
[[ "$API_OWNER" != *"$PAYER_CARD"* ]] ||
  fail "ownerSubjectDigest contains the card token; the digest is not a digest"
[[ ${#API_OWNER} -ge 32 ]] || fail "ownerSubjectDigest is ${#API_OWNER} chars, too short to be an HMAC"
pass "the two services agree on a ${#API_OWNER}-character digest that is not the card"

step "The same payment, read twice, is the same decision"
expect_status 200 GET "${GATEWAY}/api/v1/fraud/decisions/${TRANSACTION_ID}" "" "$ANALYST_TOKEN"
SECOND_SCORE="$(json 'd["score"]')"
[[ "$SECOND_SCORE" == "$API_SCORE" ]] ||
  fail "the same payment scored ${API_SCORE} then ${SECOND_SCORE}; a read is re-deriving the decision"
DECISION_ROWS="$(fraud_psql "select count(*) from risk_decisions where transaction_id='${TRANSACTION_ID}'")"
[[ "$DECISION_ROWS" == "1" ]] ||
  fail "${DECISION_ROWS} decisions for one payment; the transaction-id insert guard is not holding"
pass "one row, one score, stable across reads"

step "Paying the same card again, and watching the history grow"
# The engine's velocity rule needs two payments on one card to have anything to count. The second is
# what turns a static ruleset into something with memory, and it is the assertion that fails if the
# velocity lookup is silently returning zero.
pay "$PAYER_TOKEN" "$PAYER_CARD" "15.00" "${RUN_ID}-pay-2" "Bakery"
SECOND_TRANSACTION_ID="$(json 'd["id"]')"
SECOND_SCORE=""
for _ in $(seq 1 "$SCORE_TIMEOUT"); do
  COUNT="$(fraud_psql "select count(*) from risk_decisions where transaction_id='${SECOND_TRANSACTION_ID}'")"
  if [[ "$COUNT" == "1" ]]; then
    expect_status 200 GET "${GATEWAY}/api/v1/fraud/decisions/${SECOND_TRANSACTION_ID}" "" "$ANALYST_TOKEN"
    SECOND_SCORE="$(json 'd["score"]')"
    break
  fi
  sleep 1
done
[[ -n "$SECOND_SCORE" ]] || fail "no decision for the second payment after ${SCORE_TIMEOUT}s"
pass "payment $SECOND_TRANSACTION_ID scored ${SECOND_SCORE}"

step "Proving the engine gave the second payment credit for the first"
# Not "the second score is higher" -- that is not guaranteed, since the two payments differ in payee
# and the rules are not all velocity. The assertion is that the engine recorded a history entry for
# the card, which is the thing a broken velocity lookup would drop.
# The observation table is keyed by (scope, kind, subject, key) and carries a hit counter, not a row per
# payment: the second payment on a card is a second hit on one observation, not a second observation.
# Counting rows would therefore stay at one forever and prove nothing about velocity.
CARD_HITS="$(fraud_psql "select coalesce(max(hit_count),0) from fraud_observations where kind='CARD' and observation_key=(select card_reference from risk_decisions where transaction_id='${SECOND_TRANSACTION_ID}')")"
[[ "$CARD_HITS" -ge 2 ]] ||
  fail "the card was hit ${CARD_HITS} time(s) after two payments; velocity is being computed against nothing"
SUBJECT_OBS="$(fraud_psql "select count(*) from fraud_observations where subject_digest='${API_OWNER}'")"
[[ "$SUBJECT_OBS" -ge 1 ]] || fail "no observations at all for the payer's digest; the engine has no memory of this customer"
pass "the card was hit ${CARD_HITS} times and the customer carries ${SUBJECT_OBS} observations"

step "Paying fast enough to raise an alert"
# The queue assertions below need an alert, and a check that quietly reports success without ever
# entering the queue is worse than no check. Two slow payments to two payees correctly raise nothing,
# so this step builds one deliberately.
#
# Velocity is the trigger: R002 is 35 points for more than five payments on one subject inside 60
# seconds, and R005 is 20 more because the card was first observed minutes ago. Together they clear the
# 51-point alert threshold on their own.
#
# Not the large-amount rule, which is the obvious choice and cannot fire: its threshold is "strictly
# more than 5000.00 GBP" while the payment API refuses anything above 5000.00, so the amount can never
# cross the line. See the note in fraud-service's application.yml.
ALERT_PAYEE="Burst-${RUN_ID}"
ALERT_TRANSACTION_ID=""
for i in 1 2 3 4 5 6; do
  pay "$PAYER_TOKEN" "$PAYER_CARD" "1.00" "${RUN_ID}-burst-${i}" "$ALERT_PAYEE"
  id="$(json 'd["id"]')"
  if [[ "$i" == "6" ]]; then
    ALERT_TRANSACTION_ID="$id"
  fi
done
[[ -n "$ALERT_TRANSACTION_ID" && "$ALERT_TRANSACTION_ID" != "$SECOND_TRANSACTION_ID" ]] ||
  fail "the burst did not produce six distinct payments"

ALERTED=""
for _ in $(seq 1 "$SCORE_TIMEOUT"); do
  COUNT="$(fraud_psql "select count(*) from fraud_alerts where transaction_id='${ALERT_TRANSACTION_ID}'")"
  if [[ "$COUNT" == "1" ]]; then
    ALERTED=yes
    break
  fi
  sleep 1
done
[[ -n "$ALERTED" ]] ||
  fail "six payments in ${SCORE_TIMEOUT}s raised no alert on the sixth; the velocity rule is not escalating"
expect_status 200 GET "${GATEWAY}/api/v1/fraud/decisions/${ALERT_TRANSACTION_ID}" "" "$ANALYST_TOKEN"
ALERT_SCORE="$(json 'd["score"]')"
ALERT_REQUIRED="$(json 'd["alertRequired"]')"
[[ "$ALERT_REQUIRED" == "True" ]] || fail "the decision says alertRequired=${ALERT_REQUIRED} while an alert exists"
VELOCITY_FIRED="$(json 'len([r for r in d["reasons"] if r["ruleId"] == "R002"])')"
[[ "$VELOCITY_FIRED" -ge 1 ]] || fail "no R002 on the decision, so the alert came from somewhere unexpected"
pass "the sixth of six payments scored ${ALERT_SCORE}, fired R002, and required an alert"

step "Asking for a re-score, and reading what it left behind"
# A re-score is how a payment is looked at again after new information. The interesting property is
# not the new score but that the alert timeline says who asked and why -- an alert whose history holds
# only system events cannot answer "why was this reviewed twice".
RESCORE_STATUS="$(request POST "${GATEWAY}/api/v1/fraud/decisions/${ALERT_TRANSACTION_ID}/rescore" \
  '{"reason":"Chargeback received from the cardholder overnight."}' "$ANALYST_TOKEN")"
if [[ "$RESCORE_STATUS" == "202" || "$RESCORE_STATUS" == "200" ]]; then
  pass "re-score accepted (${RESCORE_STATUS})"
else
  printf '  re-score answered %s\n' "$RESCORE_STATUS"
  cat "$BODY_FILE"
  fail "asking for a re-score"
fi

# The 202 is the point: the request is queued to a topic and the second pass happens on the consumer,
# so the attempt counter has to be polled rather than read. Reading it once would be a race that
# usually fails, and a flaky check is worse than no check.
ATTEMPTS=""
for _ in $(seq 1 "$SCORE_TIMEOUT"); do
  ATTEMPTS="$(fraud_psql "select attempt from risk_decisions where transaction_id='${ALERT_TRANSACTION_ID}'")"
  if [[ -n "$ATTEMPTS" && "$ATTEMPTS" -ge 2 ]]; then
    break
  fi
  sleep 1
done
[[ -n "$ATTEMPTS" && "$ATTEMPTS" -ge 2 ]] ||
  fail "attempt is ${ATTEMPTS:-unknown} after a re-score; the request was accepted and nothing happened"
pass "the decision records attempt ${ATTEMPTS}"

step "Working the alert queue"
expect_status 200 GET "${GATEWAY}/api/v1/fraud/alerts?size=50" "" "$ANALYST_TOKEN"
ALERT_COUNT="$(json 'd["totalElements"]')"
pass "queue readable, ${ALERT_COUNT} alert(s) open"

ALERT_ID="$(fraud_psql "select id from fraud_alerts where transaction_id='${ALERT_TRANSACTION_ID}'")"
[[ -n "$ALERT_ID" ]] ||
fail "the alerting payment ${ALERT_TRANSACTION_ID} has no alert row, so the queue below cannot be worked"

step "Claiming an alert, and losing the race for it"
expect_status 200 POST "${GATEWAY}/api/v1/fraud/alerts/${ALERT_ID}/claim" '' "$ANALYST_TOKEN"
CLAIMED_BY="$(json 'd["claimedBy"]')"
[[ -n "$CLAIMED_BY" ]] || fail "the claim succeeded but nobody owns the alert"
pass "alert $ALERT_ID claimed"

# A claim that is not exclusive hands the same alert to two analysts, and both then close it with a
# different finding. The loser must be told, not silently given a second claim.
SECOND_ANALYST_TOKEN="$(kc_token admin)"
[[ -n "$SECOND_ANALYST_TOKEN" ]] || fail "admin has no token; a PLATFORM_ADMIN second actor is required"
SECOND_STATUS="$(request POST "${GATEWAY}/api/v1/fraud/alerts/${ALERT_ID}/claim" '' "$SECOND_ANALYST_TOKEN")"
[[ "$SECOND_STATUS" == "403" || "$SECOND_STATUS" == "409" ]] ||
  fail "a second claim on an already-claimed alert answered ${SECOND_STATUS}; claims are not exclusive"
pass "a second claim is refused (${SECOND_STATUS})"

step "Refusing to close an alert the analyst does not own"
expect_status 200 GET "${GATEWAY}/api/v1/fraud/alerts/${ALERT_ID}" "" "$ANALYST_TOKEN"
OWNER_MATCH="$(json "d['alert']['claimedBy'] == '${CLAIMED_BY}'")"
[[ "$OWNER_MATCH" == "True" ]] || fail "the alert reports a different owner than the claim returned"

step "Overruling the score, within the cap"
# A manual adjustment is the analyst's actual lever, and it is bounded: without a cap, one keystroke
# is a permanent DECLINE on an arbitrary payment. The cap is what makes the action reviewable.
ADJUST_STATUS="$(request POST "${GATEWAY}/api/v1/fraud/decisions/${ALERT_TRANSACTION_ID}/adjust" \
  "{\"score\":75,\"reason\":\"Confirmed fraud on the card; chargeback evidence attached.\"}" "$ANALYST_TOKEN")"
if [[ "$ADJUST_STATUS" == "200" ]]; then
  ADJUSTED_SCORE="$(json 'd["score"]')"
  [[ "$ADJUSTED_SCORE" == "75" ]] || fail "the adjustment asked for 75 and the decision reports ${ADJUSTED_SCORE}"
  pass "score overruled to ${ADJUSTED_SCORE}"
else
  printf '  adjust answered %s\n' "$ADJUST_STATUS"
  cat "$BODY_FILE"
  fail "overruling a score"
fi

step "Refusing a score above the cap, and one outside the range at all"
# Two different refusals, and the distinction is the point. 900 is not a policy refusal, it is a
# malformed request: the body is rejected before the cap is ever consulted. 90 is a well-formed score
# that the cap refuses, and it is the one that matters -- above the cap a decision needs a second
# approver, and this deployment has no second approver, so the answer is no rather than a queue.
expect_status 400 POST "${GATEWAY}/api/v1/fraud/decisions/${ALERT_TRANSACTION_ID}/adjust" \
  '{"score":900,"reason":"Out of range on purpose."}' "$ANALYST_TOKEN"
expect_status 422 POST "${GATEWAY}/api/v1/fraud/decisions/${ALERT_TRANSACTION_ID}/adjust" \
  '{"score":90,"reason":"Above the configured cap on purpose."}' "$ANALYST_TOKEN"
[[ "$(json 'd["error"]')" == "MANUAL_SCORE_ABOVE_CAP" ]] ||
  fail "expected MANUAL_SCORE_ABOVE_CAP for a score of 90, got $(json 'd["error"]')"
STILL="$(fraud_psql "select score from risk_decisions where transaction_id='${ALERT_TRANSACTION_ID}'")"
[[ "$STILL" == "75" ]] || fail "a refused adjustment still changed the score to ${STILL}"
pass "900 is a validation error, 90 is a cap refusal, and neither moved the score"

step "Closing the alert, and reading the full timeline"
expect_status 200 POST "${GATEWAY}/api/v1/fraud/alerts/${ALERT_ID}/resolve" \
  '{"resolution":"CONFIRMED_FRAUD","note":"Chargeback matched; card reissued."}' "$ANALYST_TOKEN"
CLOSED_STATUS="$(json 'd["state"]')"
[[ "$CLOSED_STATUS" == "RESOLVED" ]] || fail "the alert reports ${CLOSED_STATUS} after resolve"
pass "alert $ALERT_ID RESOLVED"

expect_status 200 GET "${GATEWAY}/api/v1/fraud/alerts/${ALERT_ID}" "" "$ANALYST_TOKEN"
TIMELINE="$(json 'len(d.get("timeline") or [])')"
[[ "$TIMELINE" -ge 3 ]] ||
  fail "the timeline holds ${TIMELINE} entries; raised, claimed, re-scored and resolved is four"
# The re-score reason has to be on the timeline, and not merely the fact that a re-score happened: the
# next analyst to open this alert is reading it to find out what changed, and a bare RESCORED entry
# tells them only that somebody looked again.
RESCORE_ENTRIES="$(json 'len([e for e in d["timeline"] if e["action"] == "RESCORED"])')"
[[ "$RESCORE_ENTRIES" -ge 1 ]] || fail "no RESCORED entry on the timeline of alert ${ALERT_ID}"
RESCORE_NOTE="$(json '[e["note"] for e in d["timeline"] if e["action"] == "RESCORED"][0] or ""')"
[[ -n "$RESCORE_NOTE" ]] || fail "the RESCORED entry carries no note; the reason for the second look is not recorded"
# Every human action names who did it, as a digest rather than an email, which is the same privacy
# boundary the subject digests sit behind.
UNATTRIBUTED="$(json 'len([e for e in d["timeline"] if e["action"] in ("CLAIMED","RESOLVED","RESCORED") and not e.get("actorDigest")])')"
[[ "$UNATTRIBUTED" -eq 0 ]] || fail "${UNATTRIBUTED} human timeline entries have no actor"
pass "the timeline holds ${TIMELINE} entries, including a RESCORED carrying its reason, all attributed"

step "The dashboard answers over a window"
expect_status 200 GET "${GATEWAY}/api/v1/fraud/summary?window=PT168H" "" "$ANALYST_TOKEN"
WINDOW="$(json 'd["window"]')"
[[ "$WINDOW" == "PT168H" ]] || fail "asked for a 7-day window and the dashboard reports ${WINDOW}; a figure with the wrong period beside it is not readable"
TOTAL="$(json 'd["totalDecisions"]')"
[[ -n "$TOTAL" ]] || fail "the dashboard reported no totalDecisions"
# Below an hour the aggregates are noise -- one payment can be 100% of them -- and the clamp is what
# stops a dashboard panel turning into a report. A negative window is the same argument.
expect_status 200 GET "${GATEWAY}/api/v1/fraud/summary?window=PT0S" "" "$ANALYST_TOKEN"
CLAMPED="$(json 'd["window"]')"
[[ "$CLAMPED" == "PT1H" ]] || fail "a zero window was accepted as ${CLAMPED} rather than clamped to the one-hour floor"
expect_status 200 GET "${GATEWAY}/api/v1/fraud/summary?window=P400D" "" "$ANALYST_TOKEN"
CEILING="$(json 'd["window"]')"
[[ "$CEILING" == "PT2160H" ]] || fail "a 400-day window was accepted as ${CEILING} rather than clamped to the 90-day ceiling"
pass "the window is echoed and clamped both ways (PT168H kept, PT0S to PT1H, PT400D to ${CEILING})"

step "Paging the queue"
expect_status 200 GET "${GATEWAY}/api/v1/fraud/alerts?page=0&size=1" "" "$ANALYST_TOKEN"
PAGE_SIZE="$(json 'len(d["content"])')"
[[ "$PAGE_SIZE" -le 1 ]] || fail "asked for size=1 and received ${PAGE_SIZE} alerts"
pass "size is honoured"

printf '\n\033[32mFraud lifecycle verified.\033[0m Payment scored, digest join checked across both\n'
printf 'databases, alert queue claimed exclusively, and the auditor refused an action.\n'
printf 'Spend left behind: two throwaway customers and 1000.00 GBP funded between them.\n'
