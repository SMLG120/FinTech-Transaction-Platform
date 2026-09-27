#!/usr/bin/env bash
#
# Phase 7 live check: pay money, let settlement build a period from the events, close it, hand it an
# actual that does not match, and work the finding it raises.
#
# Dev convenience only. Every assertion here is one the unit and integration tests cannot make, and
# each one is about a seam between services rather than about settlement's own logic:
#
#   * the gateway routes /api/v1/settlement/** at all, and refuses it to a customer. The service
#     enforces the same rule, but a gateway that let a CUSTOMER token through would still be a
#     gateway forwarding a customer's request to a statement of money that moved to a merchant;
#   * the platform actually builds a period from the event. This is the whole point of the phase, and
#     it is the one thing a test that calls the service directly never proves -- the payment and the
#     statement line only ever meet through Kafka;
#   * the amounts agree across the seam. The statement's total is compared against the transaction's
#     own amount, because two services that disagree about a figure produce a statement that is
#     internally consistent and wrong;
#   * a closed period really is closed to late money, end to end. A payment made *after* the close
#     arrives for a period that has already been given out, and the platform's answer must be a
#     recorded finding rather than a mutated statement or a lost payment. This is the immutability
#     rule, observed across Kafka rather than asserted in a unit test;
#   * the finding is worked by a human, and the audit trail says who.
#
# Usage: ./scripts/verify-settlement-lifecycle.sh
#
# Why it uses USD and not GBP
# --------------------------------------------------------------------------------
# A cycle is keyed by (UTC business date, currency), so a period is *shared state*: the
# SETTLE-<today>-GBP that this check closes is the same row the payment and fraud checks write to, and
# the same one a developer poking at the stack by hand writes to. The first version of this script used
# GBP, which made it run exactly once. Every later run found the period already CLOSED, or BROKEN with
# yesterday's 35.00 capture still on it, and failed on arithmetic that had nothing to do with settlement
# being correct -- a check that only passes in the first five minutes of a day is a check nobody runs.
#
# USD is a second currency the platform already supports, and nothing else in the local stack funds or
# pays in it, so the payment and fraud checks cannot collide with this one. It also buys a real
# assertion: a cycle is one currency by definition, and the check can now prove a USD payment landed in
# the USD period and not in the GBP one.
#
# It does not make the check repeatable on the same day, and the difference is worth stating plainly,
# because a check that cannot be re-run is a check nobody runs. A cycle is one row per (business date,
# currency) -- a unique index rather than a convention -- and this check closes the period and then
# breaks it, because a break is the interesting outcome. One run therefore consumes
# SETTLE-<today>-<currency> for good, and a second run on the same day would be closing a period that
# already holds this check's own money. Working around that by opening a second cycle for the same day is
# what the unique index forbids, and deleting the first one would disprove the phase's central claim: a
# platform whose promise is that a given-out statement is never edited cannot be checked by editing one.
#
# The answer is a second currency rather than a second day, and SETTLEMENT_CHECK_CURRENCY makes that an
# argument instead of advice:
#
#     SETTLEMENT_CHECK_CURRENCY=EUR ./scripts/verify-settlement-lifecycle.sh
#
# That run owns SETTLE-<today>-EUR, which nothing else in the local stack funds or pays in.
#
# Why it provisions its own customer
# --------------------------------------------------------------------------------
# A payment needs a funded account, and the check must not disturb a balance anyone else is using, so
# it funds a throwaway identity of its own -- the same reasoning as verify-payment-lifecycle.sh and
# verify-fraud-lifecycle.sh, and it reuses their provisioning shape rather than inventing a third.
#
# The operator and the auditor are NOT provisioned here. SETTLEMENT_OPERATOR and AUDITOR are realm
# roles, and the realm's own identities are one per role, so settlement@fintech.test and
# auditor@fintech.test exist in the realm file and are the identities this check uses. Creating a
# second operator would mean granting a role to a throwaway user in order to test a policy the realm
# file already defines.
#
# This script will not grant the role to make that work, and says why in the failure message: a check
# that repairs the thing it is checking has stopped being a check. The recovery is the realm import
# documented in infrastructure/keycloak/realm/README.md, and the most common cause of needing it is
# exactly this phase -- a stack that was already up when the role was added to the realm file, which
# Keycloak will not re-import over an existing realm.
set -euo pipefail

readonly GATEWAY="http://localhost:8080"
readonly KEYCLOAK="http://localhost:8180"
readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ENV_FILE="${ROOT_DIR}/.env"
readonly DEV_PASSWORD="fintech-dev-only"
readonly BODY_FILE=/tmp/settlement-body.json

# A distinct identity per run, so a second run exercises fresh money rather than replaying the first
# run's and appearing to pass for the wrong reason.
readonly RUN_ID="settlementcheck-${RANDOM}-${RANDOM}"
readonly PAYER="settlementcheck-${RUN_ID}@fintech.test"

# The realm's staff identities. See the note above: not created here.
readonly OPERATOR="settlement@fintech.test"
readonly AUDITOR="auditor@fintech.test"

# Seconds to wait for a movement to appear on a statement. Consumption is asynchronous by design -- a
# payment settles before settlement has heard of it -- so a check that asserted a line the instant the
# payment returned would be asserting the opposite of the architecture.
readonly SETTLE_TIMEOUT=45

# The currency this check pays in. See the note above: this is what makes the check repeatable.
# The currency whose period this run owns. Overridable so that a second run on the same day is possible
# without touching the first run's data; the note at the top explains why that is the only way round it.
readonly CURRENCY="${SETTLEMENT_CHECK_CURRENCY:-USD}"

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
# The subject claim, which is what the service records as the actor. Compared against rather than
# assumed: Keycloak's default `sub` is a generated UUID, not the username, so an assertion of the form
# "acknowledgedBy contains operator@fintech.test" is a guess about the realm's mappers rather than a
# check of anything. The claim is read from the token that was actually sent.
token_subject() {
  printf '%s' "$1" | python3 -c '
import base64, json, sys
payload = sys.stdin.read().strip().split(".")[1]
payload += "=" * (-len(payload) % 4)
print(json.loads(base64.urlsafe_b64decode(payload)).get("sub", ""))
'
}

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

# A scalar against the settlement database. The statement is the platform's own record, so the
# assertions read it from the database as well as from the API: an API that renders a figure the
# database does not hold would pass every response-shape check while the ledger underneath disagreed.
settle_psql() {
  docker compose -f "${ROOT_DIR}/docker-compose.yml" exec -T postgres \
    psql -U "$(env_value POSTGRES_SUPERUSER)" -d fintech_settlement -tAc "$1" 2>/dev/null | tr -d ' \r'
}

# A scalar against the transaction database, for comparing a payment's own amount with what the
# statement recorded for it.
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
 "firstName":"Settlement","lastName":"Check",
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

  # realmRoles in the create payload is ignored by Keycloak, and the assignment call wants the full role
  # representation rather than {id, name} or it answers 409.
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
      # Registered by an earlier run. Erasure scrubs a subject permanently, so a check that cannot tell a
      # reused identity from a tombstone reports a working platform as broken.
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

# A card token for this run, in the same opaque form verify-payment-lifecycle.sh uses. Synthetic on
# purpose: CardResponse carries no token by design, and a settlement check needs a stable card string
# the payment API will accept, not a real one.
#
# Fits the payment API's 64-character limit, which is worth noting because the first version embedded the
# whole run id here and produced a 78-character token: a 400 that reads as a broken payment API rather
# than as an over-long field, and a check that fails before it reaches settlement at all.
card_token() { printf 'tok_set%04x%09d' "$$" "$RANDOM"; }

fund() {
  local token="$1" amount="$2" key="$3"
  expect_status 200 POST "${GATEWAY}/api/v1/accounts/fund?amount=${amount}&currency=${CURRENCY}" "" "$token" "$key"
}

pay() {
  local token="$1" card="$2" amount="$3" key="$4" payee="$5"
  expect_status 201 POST "${GATEWAY}/api/v1/transactions" \
    "$(printf '{"amount":"%s","currency":"%s","cardToken":"%s","payeeName":"%s","payeeReference":"settlementcheck-%s"}' \
        "$amount" "$CURRENCY" "$card" "$payee" "$key")" "$token" "$key"
}

# Minor units to a decimal amount, for a figure the script derives rather than reads.
minor_to_amount() { python3 -c "print(f'{$1/100:.2f}')"; }

# Capture. Deliberately explicit, and this is the single most important thing the first version of this
# script got wrong: it created a payment and then waited for a statement line, which times out every
# time. A new payment is AUTHORIZED -- the customer's money is held and nothing has been paid out -- and
# only `POST /transactions/{id}/settle` produces transaction-settled, which is the event a statement is
# built from. Nothing in the service suite catches that, because the tests hand the service a capture
# directly and never go through the state machine that decides when money actually moves.
settle() {
  local token="$1" id="$2"
  expect_status 200 POST "${GATEWAY}/api/v1/transactions/${id}/settle" "" "$token"
  [[ "$(json 'd["status"]')" == "SETTLED" ]] ||
    fail "settling ${id} reported status '$(json 'd["status"]')'"
}

# The currencies this check may take, in the order it prefers them. Both are ones the payment API accepts,
# because suggesting a currency it refuses spends a developer a 422 CURRENCY_NOT_SUPPORTED to learn
# something this script already knows.
#
# GBP is absent, not merely last, and the distinction matters. GBP is supported, and a developer with a
# clean day could run in it -- but the payment and fraud checks fund and pay in GBP, so offering it here
# would send someone straight back into the shared-period collision that the currency change exists to
# avoid, in the one message they are guaranteed to read. Advice that undoes its own reasoning is worse
# than no advice, so a day with no free currency gets "another day" instead.
readonly CHECK_CURRENCIES=(EUR USD)

# --reset-period deletes this check's own leftovers, and nothing else. See reset_period() for why an OPEN
# period is treated as provisional and a closed one as untouchable.
RESET_PERIOD=0
for arg in "$@"; do
  case "$arg" in
    --reset-period) RESET_PERIOD=1 ;;
    -h|--help)
      printf 'Usage: %s [--reset-period]\n\n' "$(basename "$0")"
      printf '  SETTLEMENT_CHECK_CURRENCY=CODE  the currency whose period this run owns (default USD)\n'
      printf '  --reset-period                  delete an OPEN period left by an interrupted run first\n'
      exit 0
      ;;
    *) printf 'unknown argument: %s\n' "$arg" >&2; exit 2 ;;
  esac
done

# A supported currency with no period for today, or nothing at all if they are all spoken for. Only picks
# the wording of the advice below -- the run still fails until a developer acts on it.
free_currency() {
  local code existing
  for code in "${CHECK_CURRENCIES[@]}"; do
    [[ "$code" == "$CURRENCY" ]] && continue
    existing="$(settle_psql "select count(*) from settlement_cycles where business_date='${TODAY}' and currency_code='${code}'")"
    [[ "$existing" == "0" ]] && { printf '%s' "$code"; return 0; }
  done
  printf ''
}

# Delete an OPEN period and its lines, for the current (date, currency) only.
#
# The narrowest recovery that makes this check usable, and narrow on purpose. A cycle that has been closed
# has been given out: the service refuses to reopen it, and so does this, because the value of the phase is
# that a statement nobody can edit is the evidence. An OPEN cycle has not been given out, nothing depends
# on it, and its lines are provisional entries that a later capture would have changed anyway -- which is
# the only reason settlement allows a period to be open at all. An interrupted run is exactly this case,
# and without a way out the check is single-use on any machine where it has ever failed, which is a worse
# outcome than the deletion this permits, because it makes people not run it.
#
# Refuses outright rather than asking anything of the database beyond its own tables. No cascade tricks, no
# TRUNCATE: the deletes are the same rows the check would have created, in FK order, so what is removed is
# visible in this script rather than inferred from a schema.
reset_period() {
  local lines
  lines="$(settle_psql "select count(*) from settlement_lines l join settlement_cycles c on c.id=l.cycle_id where c.reference='${CYCLE_REF}'")"
  [[ "$lines" == "0" ]] && return 0
  step "Resetting the open period ${CYCLE_REF} (${lines} line(s)), because --reset-period was given"
  settle_psql "delete from settlement_breaks where cycle_id in (select id from settlement_cycles where reference='${CYCLE_REF}')" >/dev/null
  settle_psql "delete from settlement_lines where cycle_id in (select id from settlement_cycles where reference='${CYCLE_REF}')" >/dev/null
  settle_psql "delete from settlement_cycles where reference='${CYCLE_REF}'" >/dev/null
  pass "${CYCLE_REF} removed; it was open, so it had never been given out"
}

# What to do about a period this check cannot use. A currency when one is free, another day when none is --
# because on a day where every supported currency has a period, naming one of them is advice that fails
# identically to not naming any.
remedy() {
  local code
  code="$(free_currency)"
  if [[ -n "$code" ]]; then
    printf 'Run it in a currency with no period of its own:\n\n    SETTLEMENT_CHECK_CURRENCY=%s ./scripts/verify-settlement-lifecycle.sh' "$code"
  else
    printf 'Every currency this check can take (%s) already has a period for today, and GBP\nis the one the payment and fraud checks share, so this check has to wait for another day.' "${CHECK_CURRENCIES[*]}"
  fi
}

# ---- the check ------------------------------------------------------------------------------

step "Checking the local realm has a settlement operator and an auditor"
[[ -f "${ENV_FILE}" ]] || fail "no .env found. Run ./scripts/bootstrap.sh first."

# Both failure modes below are one cause: a stack that was already running when SETTLEMENT_OPERATOR was
# added to the realm file. Keycloak imports a realm only when it does not exist, so the file's contents
# apply to a fresh stack and to nothing else. The distinction between "no such user" and "a user with no
# roles" is worth keeping apart in the message, because they look identical from the outside -- a 403 --
# and the second one is the case that reads as a gateway misconfiguration.
if ! OPERATOR_TOKEN="$(kc_token "$OPERATOR")" || [[ -z "$OPERATOR_TOKEN" ]]; then
  fail "${OPERATOR} cannot obtain a token, so the running realm predates it. A realm is imported only when \
it does not exist, so editing fintech-realm.dev.json does nothing to a stack that is already up. Delete \
the realm and restart Keycloak, as documented in infrastructure/keycloak/realm/README.md."
fi
OPERATOR_ROLES="$(token_roles "$OPERATOR_TOKEN")"
[[ "$OPERATOR_ROLES" == *SETTLEMENT_OPERATOR* ]] ||
  fail "${OPERATOR} exists but holds '${OPERATOR_ROLES}', not SETTLEMENT_OPERATOR. A token with no roles is \
valid and useless -- it authenticates nobody in particular, and the 403 that follows reads like a gateway \
misconfiguration when it is a realm that was never re-imported."
AUDITOR_TOKEN="$(kc_token "$AUDITOR")"
[[ -n "$AUDITOR_TOKEN" ]] || fail "${AUDITOR} has no token"
AUDITOR_ROLES="$(token_roles "$AUDITOR_TOKEN")"
[[ "$AUDITOR_ROLES" == *AUDITOR* ]] || fail "${AUDITOR} holds '${AUDITOR_ROLES}', not AUDITOR"
pass "operator and auditor tokens carry their roles"

step "The gateway routes settlement, and refuses a customer"
kc_ensure_user "$PAYER"
absorb "$PAYER" "$FULL_NAME" "doc-${RUN_ID}"
TOKEN="$ABSORBED_TOKEN"
CUSTOMER_TOKEN="$ABSORBED_TOKEN"
CUSTOMER_CARD="$(card_token)"

# Refused before anything else. A 200 with an empty page would be worse: it is indistinguishable from
# "there are no periods", so a misconfigured route looks exactly like a working system with no data.
expect_status 403 GET "${GATEWAY}/api/v1/settlement/cycles" "" "$CUSTOMER_TOKEN"
pass "a customer is refused the statement API"

# This check's own period, and the first thing it establishes: that the period is untouched.
#
# Ordered before any payment on purpose. The first version of this block sat after the first capture, so
# a second run in a dirty period paid and settled first and then failed the guard against the line it had
# just created itself -- which is the worst possible failure to debug, because the evidence that
# "somebody else has been paying in this currency" was this script, thirty seconds earlier. A precondition
# that runs after the thing it is a precondition for is not a precondition.
TODAY="$(date -u +%Y-%m-%d)"
CYCLE_REF="SETTLE-${TODAY}-${CURRENCY}"

EXISTING="$(settle_psql "select status from settlement_cycles where reference='${CYCLE_REF}'")"
if [[ -n "$EXISTING" && "$EXISTING" != "OPEN" ]]; then
  fail "${CYCLE_REF} is ${EXISTING} and this check needs an untouched period. It is left behind on purpose -- a \
closed statement cannot be reopened, and deleting the row would delete the one thing this service promises \
to keep.
$(remedy)"
fi
if [[ "$RESET_PERIOD" == "1" ]]; then
  reset_period
fi
EXISTING_LINES="$(settle_psql "select count(*) from settlement_lines l join settlement_cycles c on c.id=l.cycle_id where c.reference='${CYCLE_REF}'")"
[[ "$EXISTING_LINES" == "0" ]] ||
  fail "${CYCLE_REF} is OPEN but already carries ${EXISTING_LINES} line(s) before this check has paid \
anything. Either an earlier run was interrupted after paying, or a developer has been paying in ${CURRENCY} \
by hand. Continuing is not an option: the declared actual further down would be reconciled against money \
this check did not pay, and the difference would be reported as a break that has nothing to do with \
settlement being correct.
$(remedy)

Or, if those lines are this check's own from an interrupted run, --reset-period removes them. It only \
works on an open period."
pass "${CYCLE_REF} is open and empty"

step "Paying, and waiting for the payment to appear on a statement"
fund "$TOKEN" "500.00" "fund-${RUN_ID}-1"
fund "$TOKEN" "500.00" "fund-${RUN_ID}-2"
pay "$TOKEN" "$CUSTOMER_CARD" "20.00" "${RUN_ID}-pay-1" "Coffee"
TRANSACTION_ID="$(json 'd["id"]')"
[[ -n "$TRANSACTION_ID" ]] || fail "the payment returned no id"

# Not yet on a statement, and that is correct rather than a gap: authorised money is held, not paid out.
# Asserted explicitly because "a statement counts what moved, not what was approved" is the property
# being checked, and it is invisible if the check only ever looks for a line after settling.
[[ "$(json 'd["status"]')" == "AUTHORIZED" ]] ||
  fail "a new payment reported status '$(json 'd["status"]')'"
PRE_SETTLE_LINES="$(settle_psql "select count(*) from settlement_lines where transaction_id='${TRANSACTION_ID}'")"
[[ "$PRE_SETTLE_LINES" == "0" ]] ||
  fail "an authorised payment already has a statement line; a statement is counting money that has not moved"
settle "$TOKEN" "$TRANSACTION_ID"

LINES=""
for _ in $(seq 1 "$SETTLE_TIMEOUT"); do
  LINES="$(settle_psql "select count(*) from settlement_lines where transaction_id='${TRANSACTION_ID}'")"
  [[ "$LINES" == "1" ]] && break
  sleep 1
done
[[ "$LINES" == "1" ]] ||
  fail "the payment settled and ${SETTLE_TIMEOUT}s later no statement line exists for it; the event was never consumed"

# One line, for 20.00, and the two services agree on the figure. Two services that disagree about an
# amount produce a statement that is internally consistent and wrong, which reconciles against nothing
# and is discovered days later.
LINE_MINOR="$(settle_psql "select amount_minor from settlement_lines where transaction_id='${TRANSACTION_ID}'")"
[[ "$LINE_MINOR" == "2000" ]] ||
  fail "the statement recorded ${LINE_MINOR} minor units for a 20.00 payment; it should be 2000"
TXN_MINOR="$(txn_psql "select amount_minor from transactions where id='${TRANSACTION_ID}'")"
[[ "$TXN_MINOR" == "$LINE_MINOR" ]] ||
  fail "the transaction says ${TXN_MINOR} and the statement says ${LINE_MINOR}; the two services disagree about the amount"

# A period is one currency by definition, so a line cannot be counted against another currency's total.
# The reference is not decoration: it is how the service finds the period to add a line to, and a
# currency that got into that key by accident would merge two merchants' money into one figure.
WRONG_CURRENCY="$(settle_psql "select count(*) from settlement_lines where transaction_id='${TRANSACTION_ID}' and cycle_id in (select id from settlement_cycles where currency_code<>'${CURRENCY}')")"
[[ "$WRONG_CURRENCY" == "0" ]] ||
  fail "the ${CURRENCY} payment has ${WRONG_CURRENCY} line(s) in a period of another currency"
expect_status 200 GET "${GATEWAY}/api/v1/settlement/cycles/${CYCLE_REF}" "" "$OPERATOR_TOKEN"
expect_status 200 GET "${GATEWAY}/api/v1/settlement/cycles/${CYCLE_REF}" "" "$AUDITOR_TOKEN"
pass "one line of 20.00 on ${CYCLE_REF}, in no other currency, and both roles can read it"

step "Refunding a captured payment, and the refund carrying as a negative line"
# The distinction is real rather than pedantic: reversing an AUTHORIZED payment releases a hold and
# publishes nothing, so refunding the capture is what puts a REVERSAL line on a statement.
expect_status 200 POST "${GATEWAY}/api/v1/transactions/${TRANSACTION_ID}/reverse" "" "$TOKEN"
[[ "$(json 'd["status"]')" == "REVERSED" ]] ||
  fail "reversing a settled payment reported status '$(json 'd["status"]')'"
REFUND_LINES=""
for _ in $(seq 1 "$SETTLE_TIMEOUT"); do
  REFUND_LINES="$(settle_psql "select count(*) from settlement_lines where transaction_id='${TRANSACTION_ID}' and kind='REVERSAL'")"
  [[ "$REFUND_LINES" == "1" ]] && break
  sleep 1
done
[[ "$REFUND_LINES" == "1" ]] ||
  fail "the refund was accepted and ${SETTLE_TIMEOUT}s later no REVERSAL line exists for it"
REVERSAL_MINOR="$(settle_psql "select amount_minor from settlement_lines where transaction_id='${TRANSACTION_ID}' and kind='REVERSAL'")"
# Negative, because a statement sums one signed column and a refund is a movement in the opposite
# direction. A refund stored as a positive amount with a REVERSAL label is a statement that adds money
# nobody received.
[[ "$REVERSAL_MINOR" == "-2000" ]] ||
  fail "the refund is ${REVERSAL_MINOR} minor units; a refund must be negative"
TOTAL_MINOR="$(settle_psql "select coalesce(sum(amount_minor),0) from settlement_lines l join settlement_cycles c on c.id=l.cycle_id where c.reference='${CYCLE_REF}'")"
[[ "$TOTAL_MINOR" == "0" ]] ||
  fail "a capture and its own refund leave ${TOTAL_MINOR} minor units on the period; they should net to zero"
pass "the refund is -2000 and the period nets to 0.00"

step "An auditor can read but not act"
expect_status 403 POST "${GATEWAY}/api/v1/settlement/cycles/close" \
  "$(printf '{"reference":"%s"}' "$CYCLE_REF")" "$AUDITOR_TOKEN"
pass "the auditor is refused a close, and the close did not happen"

step "Closing the period, and closing it twice"
pay "$TOKEN" "$CUSTOMER_CARD" "35.00" "${RUN_ID}-pay-2" "Bakery"
SECOND_TXN="$(json 'd["id"]')"
settle "$TOKEN" "$SECOND_TXN"
for _ in $(seq 1 "$SETTLE_TIMEOUT"); do
  LINES="$(settle_psql "select count(*) from settlement_lines where transaction_id='${SECOND_TXN}'")"
  [[ "$LINES" == "1" ]] && break
  sleep 1
done
[[ "$LINES" == "1" ]] || fail "the second payment never reached the statement"

expect_status 200 POST "${GATEWAY}/api/v1/settlement/cycles/close" \
  "$(printf '{"reference":"%s"}' "$CYCLE_REF")" "$OPERATOR_TOKEN"
[[ "$(json 'd["status"]')" == "CLOSED" ]] || fail "the period did not report CLOSED after closing"
FROZEN_TOTAL="$(json 'd["expected"]')"
# The figure the platform handed out, kept for the immutability check below. Comparing against this
# rather than against a literal is the difference between asserting "the statement did not change" and
# asserting "the statement is 35.00", which is a different and much weaker claim to be making about a
# figure this script did not choose.
FROZEN_MINOR="$(settle_psql "select expected_minor from settlement_cycles where reference='${CYCLE_REF}'")"
[[ "$(minor_to_amount "$FROZEN_MINOR")" == "$FROZEN_TOTAL" ]] ||
  fail "the close response said ${FROZEN_TOTAL} and the stored total is $(minor_to_amount "$FROZEN_MINOR")"
# Not 409 from a 500. What happened is that the caller asked for something the period's state forbids,
# and the colleague who clicks the same button next gets the same answer -- so it has to be a conflict
# with a code, not a server fault that says the platform is broken.
expect_status 409 POST "${GATEWAY}/api/v1/settlement/cycles/close" \
  "$(printf '{"reference":"%s"}' "$CYCLE_REF")" "$OPERATOR_TOKEN"
[[ "$(json 'd["error"]')" == "SETTLEMENT_CYCLE_NOT_OPEN" ]] ||
  fail "closing a closed period answered '$(json 'd["error"]')' rather than a state conflict"
pass "closed at ${FROZEN_TOTAL}, and a second close is a 409 with SETTLEMENT_CYCLE_NOT_OPEN"

step "A payment made after the close does not change the statement"
# The immutability rule, observed across Kafka rather than asserted in a unit test. This payment settles
# into today's period, which has already been given out, so the period cannot take another line. What it
# must do instead is record the finding: the money moved and no statement will show it, and a payment
# that is quietly dropped is a customer who was charged and cannot see why.
LATE_TXN=""
LATE_BREAK=""
# Matched to the transaction, not counted. A finding carries the payment that caused it, so the
# assertion can name the payment rather than a total: "there is one PERIOD_ALREADY_CLOSED finding" would
# pass on a platform that recorded some earlier run's finding and then stopped recording them, and
# would pass on a platform that recorded this one twice.
pay "$TOKEN" "$CUSTOMER_CARD" "7.00" "${RUN_ID}-pay-3" "Late"
LATE_TXN="$(json 'd["id"]')"
# The late capture. This is the payment that has to miss the closed period, so it must be captured
# rather than merely authorised -- an authorised hold that is never captured never settles, produces no
# event, and would make this check pass for the wrong reason on a platform that drops nothing.
settle "$TOKEN" "$LATE_TXN"
for _ in $(seq 1 "$SETTLE_TIMEOUT"); do
  LATE_BREAK="$(settle_psql "select count(*) from settlement_breaks b join settlement_cycles c on c.id=b.cycle_id where c.reference='${CYCLE_REF}' and b.kind='PERIOD_ALREADY_CLOSED' and b.transaction_id='${LATE_TXN}'")"
  [[ "$LATE_BREAK" == "1" ]] && break
  sleep 1
done
[[ "$LATE_BREAK" == "1" ]] ||
  fail "a payment settled into a closed period and no PERIOD_ALREADY_CLOSED finding naming ${LATE_TXN} was \
recorded; the money moved with no statement line"

# And the finding says what the money was, which is the part an operator acts on. "This period is
# closed" is a rule; "7.00 for this payment arrived after you were sent the statement" is a task.
LATE_DETAIL="$(settle_psql "select detail from settlement_breaks where transaction_id='${LATE_TXN}' and kind='PERIOD_ALREADY_CLOSED'")"
[[ "$LATE_DETAIL" == *"${LATE_TXN}"* && "$LATE_DETAIL" == *"7.00"* ]] ||
  fail "the finding for ${LATE_TXN} does not name the payment or its amount: '${LATE_DETAIL}'"

# And the statement itself is untouched, which is the half that matters: a finding is not a repair.
AFTER_TOTAL="$(settle_psql "select expected_minor from settlement_cycles where reference='${CYCLE_REF}'")"
[[ "$AFTER_TOTAL" == "$FROZEN_MINOR" ]] ||
  fail "the period was handed out at ${FROZEN_MINOR} minor units and now holds ${AFTER_TOTAL}; a closed statement changed after it was given out"
LATE_LINES="$(settle_psql "select count(*) from settlement_lines where transaction_id='${LATE_TXN}'")"
[[ "$LATE_LINES" == "0" ]] ||
  fail "the late payment has a statement line, so the closed period was not actually closed"
pass "no line added, the frozen total is unchanged, and a PERIOD_ALREADY_CLOSED finding was recorded"

step "Declaring an actual that does not match, and working the finding"
# A mismatch is an answer, not a failed request: the declaration succeeded and what it revealed is that
# the period does not balance. A 409 here would tell the operator their request was malformed, and they
# would go looking in their own input instead of in the clearing file.
# One currency unit short of what was handed out, derived rather than typed. The point of the check is
# that a shortfall is reported as -1.00 and breaks the period, not that this particular run happened to
# have 35.00 in it.
SHORT_ACTUAL="$(minor_to_amount "$((FROZEN_MINOR - 100))")"
expect_status 200 POST "${GATEWAY}/api/v1/settlement/cycles/actual" \
  "$(printf '{"reference":"%s","actualAmount":"%s","currency":"%s"}' "$CYCLE_REF" "$SHORT_ACTUAL" "$CURRENCY")" "$OPERATOR_TOKEN"
[[ "$(json 'd["status"]')" == "BROKEN" ]] || fail "a 1.00 shortfall did not break the period"
DIFFERENCE="$(json 'd["difference"]')"
[[ "$DIFFERENCE" == "-1.00" ]] ||
  fail "the platform is 1.00 short and reports a difference of '${DIFFERENCE}'; the sign tells the operator which way round to read it"
pass "${FROZEN_TOTAL} expected against ${SHORT_ACTUAL} declared is reported as BROKEN with a difference of -1.00"

expect_status 409 POST "${GATEWAY}/api/v1/settlement/cycles/${CYCLE_REF}/reconcile" "" "$OPERATOR_TOKEN"
pass "the period cannot be confirmed while a finding is open"

expect_status 200 GET "${GATEWAY}/api/v1/settlement/breaks?status=OPEN" "" "$OPERATOR_TOKEN"
OPEN_BREAKS="$(json 'len([b for b in d["content"] if b["kind"]=="AMOUNT_MISMATCH" and b["reference"]=="'"$CYCLE_REF"'"])')"
[[ "$OPEN_BREAKS" == "1" ]] || fail "expected one open AMOUNT_MISMATCH on ${CYCLE_REF}, found ${OPEN_BREAKS}"
BREAK_ID="$(json '[b for b in d["content"] if b["kind"]=="AMOUNT_MISMATCH" and b["reference"]=="'"$CYCLE_REF"'"][0]["id"]')"

# Resolution before acknowledgement has to say so. A generic "not in a state that allows this change"
# teaches the operator nothing and they will send the same request again.
expect_status 409 POST "${GATEWAY}/api/v1/settlement/breaks/${BREAK_ID}/resolve" \
  '{"resolution":"a 1.00 bank charge, found in the clearing file"}' "$OPERATOR_TOKEN"
[[ "$(json 'd["error"]')" == "SETTLEMENT_BREAK_NOT_ACKNOWLEDGED" ]] ||
  fail "resolving an unlooked-at finding answered '$(json 'd["error"]')' rather than telling the operator to acknowledge it"

expect_status 200 POST "${GATEWAY}/api/v1/settlement/breaks/${BREAK_ID}/acknowledge" "" "$OPERATOR_TOKEN"
[[ "$(json 'd["status"]')" == "ACKNOWLEDGED" ]] || fail "the acknowledgement did not take"
# The actor comes from the verified identity. There is no field to send, which is the point: an
# acknowledgement is the record of who looked at a discrepancy.
OPERATOR_SUBJECT="$(token_subject "$OPERATOR_TOKEN")"
[[ -n "$OPERATOR_SUBJECT" ]] || fail "the operator's token carries no sub claim, so it cannot be attributed"
[[ "$(json 'd["acknowledgedBy"]')" == "$OPERATOR_SUBJECT" ]] ||
  fail "the acknowledgement is attributed to '$(json 'd["acknowledgedBy"]')' rather than to the subject \
that sent it, ${OPERATOR_SUBJECT}"

expect_status 200 POST "${GATEWAY}/api/v1/settlement/breaks/${BREAK_ID}/resolve" \
  '{"resolution":"a 1.00 bank charge, found in the clearing file"}' "$OPERATOR_TOKEN"
[[ "$(json 'd["status"]')" == "RESOLVED" ]] || fail "the resolution did not take"
pass "the finding was acknowledged by the operator, and resolved with its explanation kept"

step "The closed period is still a statement that adds up"
# The invariant that makes a statement defensible: the total frozen at closing is the sum of the lines it
# was frozen from. Two implementations of the same figure exist on purpose -- one live, one frozen -- and
# this is where they are proved to agree. A stored total that has drifted from its own lines reconciles
# against nothing, and no amount of care at the API layer hides it.
FROZEN="$(settle_psql "select expected_minor from settlement_cycles where reference='${CYCLE_REF}'")"
SUMMED="$(settle_psql "select coalesce(sum(l.amount_minor),0) from settlement_lines l join settlement_cycles c on c.id=l.cycle_id where c.reference='${CYCLE_REF}'")"
[[ "$FROZEN" == "$SUMMED" ]] ||
  fail "the frozen total is ${FROZEN} minor units and the lines sum to ${SUMMED}; the statement does not add up to its own figure"
pass "the frozen total (${FROZEN}) equals the sum of the lines"

step "The period list is filterable by status"
expect_status 200 GET "${GATEWAY}/api/v1/settlement/cycles?status=BROKEN" "" "$OPERATOR_TOKEN"
BROKEN_LISTED="$(json 'len([c for c in d["content"] if c["reference"]=="'"$CYCLE_REF"'"])')"
[[ "$BROKEN_LISTED" == "1" ]] || fail "a BROKEN period is not in the BROKEN filter, or the reference is not what was closed"
expect_status 200 GET "${GATEWAY}/api/v1/settlement/cycles?status=RECONCILED" "" "$OPERATOR_TOKEN"
RECONCILED_LISTED="$(json 'len([c for c in d["content"] if c["reference"]=="'"$CYCLE_REF"'"])')"
[[ "$RECONCILED_LISTED" == "0" ]] || fail "a period that never balanced is listed as RECONCILED"
pass "the period appears under BROKEN and not under RECONCILED"

printf '\n\033[32mSettlement lifecycle verified.\033[0m A payment settled onto a period over Kafka, its\n'
printf 'refund carried as a negative line, the period closed and frozen, a late payment recorded as a\n'
printf 'finding instead of mutating the statement, and an unmatched actual worked to a resolution.\n'
printf 'Spend left behind: one throwaway customer and 1000.00 %s funded, plus whatever the payments\n' "$CURRENCY"
printf 'above debited. The period %s is left BROKEN with a resolved finding, which is the correct\n' "$CYCLE_REF"
printf 'end state for a period that did not balance -- and it stays, because a statement that has been\n'
printf 'given out is the one thing this service will not edit. This run consumed %s, and a period is\n' "$CYCLE_REF"
printf 'closed once, so a second run today needs a period of its own. %s\n' "$(remedy)"
