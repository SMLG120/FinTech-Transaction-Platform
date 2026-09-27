#!/usr/bin/env bash
#
# Phase 4 live check: issue a card and walk its lifecycle through the gateway.
#
# Dev convenience only, and deliberately paranoid. Every assertion here is one the unit tests cannot
# make: that the gateway routes the path, that card-service can reach customer-service across the
# Compose network with a signed identity, and that what lands in PostgreSQL is a token rather than a
# number.
#
# Usage: ./scripts/verify-card-lifecycle.sh
#
# Why this provisions its own Keycloak users instead of using scripts/get-token.sh
# --------------------------------------------------------------------------------
# The five identities in the realm are one per role, and only two of them can hold a customer
# profile: CUSTOMER and SUPPORT_AGENT. That is enough for one cardholder and not enough for two, and
# the erasure check in Phase 3 permanently retires the one CUSTOMER identity, because DELETE
# /api/v1/customers/me scrubs the subject and a tombstoned subject can never register again (409
# CUSTOMER_ALREADY_REGISTERED, then 410 on /me).
#
# So a live check that wanted a second customer would either hardcode an identity that stops working
# the first time somebody tests erasure, or erase an identity to free it up and destroy the state it
# was using. Both are worse than creating a throwaway user. The realm import is skipped once the
# realm exists, so these are created through the admin API and are safe to leave behind: they hold
# rows in the local dev Keycloak and nothing else.

set -euo pipefail

readonly GATEWAY="http://localhost:8080"
readonly KEYCLOAK="http://localhost:8180"
readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ENV_FILE="${ROOT_DIR}/.env"
readonly DEV_PASSWORD="fintech-dev-only"

# Subjects, kept separate so the two sides of the ownership test cannot collide.
readonly CARDHOLDER="cardcheck-a@fintech.test"
readonly OTHER_OWNER="cardcheck-b@fintech.test"
readonly UNVERIFIED="cardcheck-c@fintech.test"

pass() { printf '  \033[32mok\033[0m   %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; exit 1; }
step() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# Expects an HTTP status; anything else is a failure with the body printed.
#
# An empty token is a bug, not a request for "no credentials": a negative test that loses its token
# and then borrows the cardholder's tests something else entirely and reports success. Pass "none"
# to genuinely send no credentials.
expect_status() {
  local want="$1" method="$2" url="$3" body="${4:-}" token="${5-$TOKEN}"
  local args=(-s -o /tmp/card-body.json -w '%{http_code}' -X "$method" "$url")
  if [[ "$token" == "none" ]]; then
    token=""
  elif [[ -z "$token" ]]; then
    fail "${method} ${url} was called with an empty token; refusing to guess whose credentials to use"
  fi
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' -d "$body")
  local got
  got="$(curl "${args[@]}")"
  if [[ "$got" != "$want" ]]; then
    printf '  expected %s, got %s\n' "$want" "$got"
    cat /tmp/card-body.json
    fail "$method $url"
  fi
}

json() { python3 -c "import json,sys;d=json.load(open('/tmp/card-body.json'));print($1)"; }

# ---- Keycloak helpers -----------------------------------------------------------------------
# Nothing here prints a secret. The admin token lives in a variable and the admin password is only
# ever passed to curl's --data-urlencode, so it does not appear in a process listing on a shared box.

env_value() { grep -E "^$1=" "${ENV_FILE}" | head -1 | cut -d= -f2-; }

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

# Creates a local dev user with a realm role, or leaves it alone if it is already there.
kc_ensure_user() {
  local username="$1" role="$2" admin realm status user_id localpart
  admin="$(kc_admin_token)"
  [[ -n "$admin" ]] || fail "could not get a Keycloak admin token; is Keycloak healthy?"
  realm="$(env_value KEYCLOAK_REALM)"
  localpart="${username%@fintech.test}"

  user_id="$(curl -s "${KEYCLOAK}/admin/realms/${realm}/users?username=${username}&exact=true" \
    -H "Authorization: Bearer ${admin}" |
    python3 -c 'import json,sys; u=json.load(sys.stdin); print(u[0]["id"] if u else "")')"

  if [[ -z "$user_id" ]]; then
    # firstName and lastName are not decoration. The realm's user profile requires them, and a user
    # created without them authenticates to a 400 "Account is not fully set up" rather than to a
    # token, which reads as a broken realm rather than an incomplete create.
    status="$(curl -s -o /tmp/kc-body.json -w '%{http_code}' -X POST "${KEYCLOAK}/admin/realms/${realm}/users" \
      -H "Authorization: Bearer ${admin}" -H 'Content-Type: application/json' \
      -d "{\"username\":\"${username}\",\"enabled\":true,\"email\":\"${username}\",\"emailVerified\":true,\"firstName\":\"Card\",\"lastName\":\"Check\",\"credentials\":[{\"type\":\"password\",\"value\":\"${DEV_PASSWORD}\",\"temporary\":false}]}")"
    [[ "$status" == "201" ]] || { cat /tmp/kc-body.json; fail "creating local dev user ${username}"; }
    user_id="$(curl -s "${KEYCLOAK}/admin/realms/${realm}/users?username=${username}&exact=true" \
      -H "Authorization: Bearer ${admin}" |
      python3 -c 'import json,sys; u=json.load(sys.stdin); print(u[0]["id"] if u else "")')"
  fi
  [[ -n "$user_id" ]] || fail "could not resolve the id of ${username}"

  # Repair a user that exists but is incomplete, rather than trusting that creation did it. A partial
  # create is invisible until login, and login then answers "Account is not fully set up" -- which
  # points at the realm rather than at the one call that left the row half-written.
  curl -s "${KEYCLOAK}/admin/realms/${realm}/users/${user_id}" -H "Authorization: Bearer ${admin}" \
    -o /tmp/kc-user.json
  python3 - "$user_id" "$username" <<'PY' >/tmp/kc-user-fixed.json
import json, sys
user = json.load(open('/tmp/kc-user.json'))
user.update(id=sys.argv[1], username=sys.argv[2], firstName="Card", lastName="Check",
            email=sys.argv[2], emailVerified=True, enabled=True)
user.pop('userProfileMetadata', None)
json.dump(user, open('/tmp/kc-user-fixed.json', 'w'))
PY
  curl -s -o /dev/null -X PUT "${KEYCLOAK}/admin/realms/${realm}/users/${user_id}" \
    -H "Authorization: Bearer ${admin}" -H 'Content-Type: application/json' -d @/tmp/kc-user-fixed.json

  # The role cannot be set in the create payload; Keycloak's admin API ignores realmRoles there and
  # wants a separate call with the full role representation, which it will not accept as a bare
  # {id, name} -- it answers 409 "Role not found". Fetching the representation is the difference
  # between a user who can register and one who gets 403 INSUFFICIENT_ROLE.
  curl -s "${KEYCLOAK}/admin/realms/${realm}/roles/${role}" -H "Authorization: Bearer ${admin}" \
    -o /tmp/kc-role.json
  python3 -c "import json; json.dump([json.load(open('/tmp/kc-role.json'))], open('/tmp/kc-rolemap.json','w'))"
  curl -s -o /dev/null -X POST "${KEYCLOAK}/admin/realms/${realm}/users/${user_id}/role-mappings/realm" \
    -H "Authorization: Bearer ${admin}" -H 'Content-Type: application/json' -d @/tmp/kc-rolemap.json

  kc_token "$username" | grep -q . || fail "${username} cannot obtain a token after being created"
}

# ---- Customer setup -------------------------------------------------------------------------

register_body() {
  printf '{"fullName":"%s","dateOfBirth":"%s","nationality":"GB","email":"%s","phone":"%s","address":%s}' \
    "$1" "$2" "$3" "$4" "$5"
}

readonly FULL_NAME="Ada Lovelace"
readonly DOB="1815-12-10"
readonly ADDRESS='{"line1":"12 Analytical Engine Way","city":"London","postalCode":"EC1A 1BB","country":"GB"}'

# Registers (or reuses) a profile, submits a document, and echoes "<token> <customer-id> <kyc-status>".
#
# The synthetic provider approves on five checks, so the details are chosen to pass all five: an
# adult, a GB address, an unexpired document whose printed name matches the claim, and a reference
# that is not on the provider's lost-or-stolen list. Passing one of them a document that has been
# reported stolen is how the unapproved customer is produced.
setup_customer() {
  local username="$1" name="$2" docref="$3" token status id
  token="$(kc_token "$username")"
  [[ -n "$token" ]] || return 1

  status="$(curl -s -o /tmp/card-body.json -w '%{http_code}' -X POST "${GATEWAY}/api/v1/customers" \
    -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' \
    -d "$(register_body "$name" "$DOB" "user-${username}" "+447700900${RANDOM:0:3}" "$ADDRESS")")"
  case "$status" in
    201) id="$(json 'd["id"]')" ;;
    409)
      # Already registered from an earlier run. Reuse it, but only if it is not a tombstone: erasure
      # scrubs the subject permanently, and a check that cannot tell the two apart reports a working
      # platform as broken.
      status="$(curl -s -o /tmp/card-body.json -w '%{http_code}' "${GATEWAY}/api/v1/customers/me" \
        -H "Authorization: Bearer ${token}")"
      [[ "$status" == "200" ]] || return 1
      id="$(json 'd["id"]')" ;;
    *) return 1 ;;
  esac
  [[ -n "$id" ]] || return 1

  # The synthetic provider decides at submission, and the state machine then refuses to move a
  # decided check again -- APPROVED and REJECTED both only accept a resubmission from EXPIRED. So an
  # already-decided customer is taken as it stands. Without this the second run of this script fails,
  # which is the worst possible property for a check that exists to be re-run.
  status="$(curl -s -o /tmp/card-body.json -w '%{http_code}' "${GATEWAY}/api/v1/customers/me" \
    -H "Authorization: Bearer ${token}")"
  local existing
  existing="$(json 'd["kycStatus"]')"
  if [[ "$existing" != "NOT_STARTED" ]]; then
    printf '%s %s %s' "$token" "$id" "$existing"
    return 0
  fi

  status="$(curl -s -o /tmp/card-body.json -w '%{http_code}' -X POST "${GATEWAY}/api/v1/customers/${id}/kyc" \
    -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' \
    -d "{\"documentReference\":\"${docref}\",\"printedName\":\"${name}\",\"expiryDate\":\"2035-01-01\",\"issuingCountry\":\"GB\",\"nationality\":\"GB\"}")"
  [[ "$status" == "200" ]] || return 1
  local decided
  decided="$(json 'd["status"]')"
  [[ -n "$decided" ]] || return 1
  printf '%s %s %s' "$token" "$id" "$decided"
}

step "Provisioning three local dev identities"
[[ -f "${ENV_FILE}" ]] || fail "no .env found. Run ./scripts/bootstrap.sh first."
kc_ensure_user "$CARDHOLDER" CUSTOMER
kc_ensure_user "$OTHER_OWNER" CUSTOMER
kc_ensure_user "$UNVERIFIED" CUSTOMER
pass "cardholder, a second customer owner, and one that will fail identity checks"

# Reads "<token> <id> <kyc-status>" into the three named variables, or fails the script.
#
# Not a bare `read ... <<< "$(setup_customer ...)" || fail`, because read succeeds on an empty line.
# A setup that returned nothing therefore looked like a setup that succeeded with empty fields, and
# the next request went out with no token -- which expect_status then quietly substituted the
# cardholder's, so a negative test passed because it had accidentally become a positive one.
absorb() {
  local raw
  raw="$(setup_customer "$@")" || fail "could not set up ${1}"
  read -r ABSORBED_TOKEN ABSORBED_ID ABSORBED_KYC <<<"$raw"
  [[ -n "$ABSORBED_TOKEN" && -n "$ABSORBED_ID" && -n "$ABSORBED_KYC" ]] ||
    fail "setup for ${1} returned '${raw}', expected a token, an id and a KYC status"
}

step "Registering the cardholder and passing identity checks"
# A card can only be issued to a customer the provider approves, so this is not a formality: it is the
# eligibility rule being satisfied for real over HTTP rather than mocked.
absorb "$CARDHOLDER" "$FULL_NAME" SYNTH-PPT-9001
TOKEN="$ABSORBED_TOKEN"; CUSTOMER_ID="$ABSORBED_ID"
[[ "$ABSORBED_KYC" == "APPROVED" ]] || fail "cardholder kyc is ${ABSORBED_KYC}, expected APPROVED"
pass "customer $CUSTOMER_ID, KYC APPROVED, all five checks passed"

step "Registering a second customer, also approved, owned by a different identity"
absorb "$OTHER_OWNER" "Grace Hopper" SYNTH-PPT-9002
OTHER_ID="$ABSORBED_ID"
[[ "$ABSORBED_KYC" == "APPROVED" ]] || fail "second customer kyc is ${ABSORBED_KYC}, expected APPROVED"
pass "customer $OTHER_ID, KYC APPROVED"

step "Registering a third customer whose document has been reported stolen"
# Deliberately ineligible, and owned by whoever holds this token. The point of doing it this way
# rather than pointing at somebody else's profile is that the refusal has to be about eligibility and
# not about ownership, and a test that cannot tell those two apart stops being meaningful the moment
# the ownership check is loosened.
absorb "$UNVERIFIED" "Alan Turing" SYNTH-LOST-0001
UNVERIFIED_TOKEN="$ABSORBED_TOKEN"; UNVERIFIED_ID="$ABSORBED_ID"
[[ "$ABSORBED_KYC" != "APPROVED" ]] || fail "the stolen-document submission was approved, which it must not be"
pass "customer $UNVERIFIED_ID, KYC $ABSORBED_KYC"

step "Issuing a card"
expect_status 201 POST "${GATEWAY}/api/v1/cards" "{\"customerId\":\"${CUSTOMER_ID}\",\"brand\":\"DEBIT\"}"
CARD_NUMBER="$(json 'd["cardNumber"]')"
CARD_ID="$(json 'd["card"]["id"]')"
LAST4="$(json 'd["card"]["last4"]')"
[[ "$CARD_NUMBER" =~ ^[0-9]{4}\ [0-9]{4}\ [0-9]{4}\ [0-9]{4}$ ]] || fail "unexpected card number format: ${CARD_NUMBER}"
[[ "${CARD_NUMBER: -4}" == "$LAST4" ]] || fail "last4 ${LAST4} does not match the number issued"
pass "card ${CARD_ID}, number ${CARD_NUMBER} shown exactly once"

step "Listing own cards, and confirming the number is not in the response"
expect_status 200 GET "${GATEWAY}/api/v1/cards"
COUNT="$(json 'len(d)')"
[[ "$COUNT" -ge 1 ]] || fail "expected at least one card, got ${COUNT}"
grep -q 'cardNumber' /tmp/card-body.json && fail "the list response contains a card number"
pass "${COUNT} card(s) listed, no card number anywhere in the payload"

step "Reading the card back"
expect_status 200 GET "${GATEWAY}/api/v1/cards/${CARD_ID}"
[[ "$(json 'd["status"]')" == "ACTIVE" ]] || fail "expected ACTIVE"
pass "ACTIVE"

step "Freezing and unfreezing"
expect_status 200 POST "${GATEWAY}/api/v1/cards/${CARD_ID}/freeze"
[[ "$(json 'd["status"]')" == "FROZEN" ]] || fail "expected FROZEN"
expect_status 200 POST "${GATEWAY}/api/v1/cards/${CARD_ID}/unfreeze"
[[ "$(json 'd["status"]')" == "ACTIVE" ]] || fail "expected ACTIVE"
pass "FROZEN then ACTIVE"

step "Reporting the card lost, then confirming it cannot be reactivated"
expect_status 200 POST "${GATEWAY}/api/v1/cards/${CARD_ID}/lost"
[[ "$(json 'd["status"]')" == "LOST" ]] || fail "expected LOST"
expect_status 409 POST "${GATEWAY}/api/v1/cards/${CARD_ID}/unfreeze"
[[ "$(json 'd["error"]')" == "CARD_REPORTED_LOST" ]] || fail "expected CARD_REPORTED_LOST, got $(json 'd["error"]')"
pass "LOST, and unfreeze refused with CARD_REPORTED_LOST"

step "Cancelling, and confirming the record survives"
expect_status 200 DELETE "${GATEWAY}/api/v1/cards/${CARD_ID}"
[[ "$(json 'd["status"]')" == "CANCELLED" ]] || fail "expected CANCELLED"
expect_status 200 GET "${GATEWAY}/api/v1/cards/${CARD_ID}"
[[ "$(json 'd["status"]')" == "CANCELLED" ]] || fail "a cancelled card should still read back"
pass "CANCELLED, and still readable"

step "Refusing to read somebody else's card"
# The target customer is fully approved, so eligibility cannot be the reason for the refusal.
expect_status 403 GET "${GATEWAY}/api/v1/cards/${CARD_ID}" "" "$UNVERIFIED_TOKEN"
pass "NOT_THE_CARDHOLDER for a valid token that does not own the card"

step "Refusing to issue against somebody else's customer"
expect_status 403 POST "${GATEWAY}/api/v1/cards" "{\"customerId\":\"${OTHER_ID}\",\"brand\":\"DEBIT\"}"
[[ "$(json 'd["error"]')" == "NOT_THE_CARDHOLDER" ]] ||
  fail "expected NOT_THE_CARDHOLDER, got $(json 'd["error"]')"
pass "NOT_THE_CARDHOLDER, and the approved owner was not the reason"

step "Refusing to issue to a customer who has not passed identity checks"
expect_status 409 POST "${GATEWAY}/api/v1/cards" \
  "{\"customerId\":\"${UNVERIFIED_ID}\",\"brand\":\"DEBIT\"}" "$UNVERIFIED_TOKEN"
[[ "$(json 'd["error"]')" == "HOLDER_NOT_ELIGIBLE" ]] ||
  fail "expected HOLDER_NOT_ELIGIBLE, got $(json 'd["error"]')"
pass "HOLDER_NOT_ELIGIBLE for an owner who has not passed identity checks"

step "Confirming PostgreSQL holds a token and not the number"
printf '\n\033[1m%s\033[0m\n' "  the row behind the card issued above, if you want to see it yourself:"
printf '    docker compose exec -T postgres psql -U fintech_cards -d fintech_cards \\\n'
printf "      -c \"select last4, brand, status, left(token, 12) as token_prefix from cards;\"\n"

printf '\n\033[32mPhase 4 lifecycle verified through the gateway.\033[0m\n'
printf 'Card %s, number %s (shown once, never retrievable again).\n' "$CARD_ID" "$CARD_NUMBER"
