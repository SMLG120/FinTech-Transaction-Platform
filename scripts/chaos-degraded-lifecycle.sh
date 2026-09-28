#!/usr/bin/env bash
#
# Phase 13 live check: the platform degrades when pieces die, it does not hang.
#
# Two experiments against a running stack (`make up` first), each asserting one
# property the unit tests cannot: that a real outage over a real network fails
# fast, in the service's own vocabulary, and that the stack recovers afterwards.
#
#   1. customer-service stopped: card issuing answers 503 ELIGIBILITY_UNAVAILABLE
#      in seconds (fail closed, never a hang, never a card), and issues again
#      once the service is back.
#   2. kafka stopped: a payment still authorises (fraud scores after the
#      payment, on no payment's critical path), no decision exists while the
#      broker is down, and the engine scores the blind payment after recovery.
#
# Restore discipline: stopping containers is reverted on EXIT — including on
# failure — so a failed run never leaves the stack degraded. The breaker
# trip dynamics themselves (open after N failures, half-open recovery) are
# proven deterministically by the stub-server unit tests, not here: timing
# assertions against real containers get bounds, not exact values.
#
# Manual variant, deliberately not automated: `docker compose pause
# customer-service` instead of `stop` stalls connections rather than refusing
# them, which exercises the 2s read timeouts and the breaker rather than the
# fast-refusal path. It takes minutes (ten stalled calls to trip, thirty
# seconds open before the probe) and belongs in an incident rehearsal, not in
# a script that must finish.
#
# Usage: ./scripts/chaos-degraded-lifecycle.sh

set -euo pipefail

readonly GATEWAY="http://localhost:8080"
readonly KEYCLOAK="http://localhost:8180"
readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ENV_FILE="${ROOT_DIR}/.env"
readonly DEV_PASSWORD="fintech-dev-only"
readonly BODY_FILE=/tmp/chaos-body.json

readonly CHAOS_USER="chaos-a@fintech.test"
readonly FULL_NAME="Chaos Harper"
readonly DOB="1990-06-15"
readonly ADDRESS='{"line1":"7 Blast Radius Row","city":"London","postalCode":"E2 8DP","country":"GB"}'
readonly DOCREF="SYNTH-PPT-9100"
readonly RUN_ID="chaoscheck-${RANDOM}-${RANDOM}"

# Containers this run stopped, so the EXIT trap restores exactly what it broke.
STOPPED=""

pass() { printf '  \033[32mok\033[0m   %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; exit 1; }
step() { printf '\n\033[1m%s\033[0m\n' "$1"; }

json() { python3 -c "import json,sys;d=json.load(open('${BODY_FILE}'));print($1)"; }
env_value() { grep -E "^$1=" "${ENV_FILE}" | head -1 | cut -d= -f2-; }

# ---- Restore ----------------------------------------------------------------------------
# Starts anything this run stopped and waits for the gateway to answer. Idempotent:
# starting a running container is a no-op, so calling it twice is safe.

restore() {
  if [[ -n "$STOPPED" ]]; then
    printf '\nrestoring: %s\n' "$STOPPED"
    # shellcheck disable=SC2086
    docker compose -f "${ROOT_DIR}/docker-compose.yml" start $STOPPED >/dev/null 2>&1 || true
    STOPPED=""
    wait_for_gateway
  fi
}
trap restore EXIT

wait_for_gateway() {
  for _ in $(seq 1 60); do
    if curl -s -o /dev/null -w '%{http_code}' --max-time 5 "${GATEWAY}/actuator/health" 2>/dev/null |
      grep -qE '^[23]'; then
      return 0
    fi
    sleep 2
  done
  fail "gateway did not come back after restore"
}

stop_service() {
  docker compose -f "${ROOT_DIR}/docker-compose.yml" stop "$1" >/dev/null
  STOPPED="${STOPPED} $1"
}

wait_for_port() {
  local port="$1" deadline=$((SECONDS + $2))
  while [[ "$SECONDS" -lt "$deadline" ]]; do
    if (echo >/dev/tcp/127.0.0.1/"$port") >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

# ---- Keycloak helpers ---------------------------------------------------------------------
# Throwaway local dev user, same reasoning as verify-card-lifecycle.sh: the realm's fixed
# identities cannot survive the erasure checks, so chaos gets its own customer it can keep.

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

kc_ensure_user() {
  local username="$1" role="$2" admin realm user_id status localpart
  admin="$(kc_admin_token)"
  [[ -n "$admin" ]] || fail "could not get a Keycloak admin token; is Keycloak healthy?"
  realm="$(env_value KEYCLOAK_REALM)"
  localpart="${username%@fintech.test}"

  user_id="$(curl -s "${KEYCLOAK}/admin/realms/${realm}/users?username=${username}&exact=true" \
    -H "Authorization: Bearer ${admin}" |
    python3 -c 'import json,sys; u=json.load(sys.stdin); print(u[0]["id"] if u else "")')"

  if [[ -z "$user_id" ]]; then
    status="$(curl -s -o /tmp/kc-body.json -w '%{http_code}' -X POST "${KEYCLOAK}/admin/realms/${realm}/users" \
      -H "Authorization: Bearer ${admin}" -H 'Content-Type: application/json' \
      -d "{\"username\":\"${username}\",\"enabled\":true,\"email\":\"${username}\",\"emailVerified\":true,\"firstName\":\"Chaos\",\"lastName\":\"Harper\",\"credentials\":[{\"type\":\"password\",\"value\":\"${DEV_PASSWORD}\",\"temporary\":false}]}")"
    [[ "$status" == "201" ]] || { cat /tmp/kc-body.json; fail "creating local dev user ${username}"; }
    user_id="$(curl -s "${KEYCLOAK}/admin/realms/${realm}/users?username=${username}&exact=true" \
      -H "Authorization: Bearer ${admin}" |
      python3 -c 'import json,sys; u=json.load(sys.stdin); print(u[0]["id"] if u else "")')"
  fi
  [[ -n "$user_id" ]] || fail "could not resolve the id of ${username}"

  curl -s "${KEYCLOAK}/admin/realms/${realm}/roles/${role}" -H "Authorization: Bearer ${admin}" \
    -o /tmp/kc-role.json
  python3 -c "import json; json.dump([json.load(open('/tmp/kc-role.json'))], open('/tmp/kc-rolemap.json','w'))"
  curl -s -o /dev/null -X POST "${KEYCLOAK}/admin/realms/${realm}/users/${user_id}/role-mappings/realm" \
    -H "Authorization: Bearer ${admin}" -H 'Content-Type: application/json' -d @/tmp/kc-rolemap.json

  kc_token "$username" | grep -q . || fail "${username} cannot obtain a token after being created"
}

# ---- Platform helpers -----------------------------------------------------------------------

# Registers (or reuses) a profile, runs the synthetic identity check to APPROVED unless already
# decided, and echoes "<token> <customer-id>".
setup_customer() {
  local username="$1" token status id decided
  token="$(kc_token "$username")"
  [[ -n "$token" ]] || return 1

  status="$(curl -s -o "${BODY_FILE}" -w '%{http_code}' -X POST "${GATEWAY}/api/v1/customers" \
    -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' \
    -d "{\"fullName\":\"${FULL_NAME}\",\"dateOfBirth\":\"${DOB}\",\"nationality\":\"GB\",\"email\":\"user-$1\",\"phone\":\"+447700900111\",\"address\":${ADDRESS}}")"
  case "$status" in
    201) id="$(json 'd["id"]')" ;;
    409)
      status="$(curl -s -o "${BODY_FILE}" -w '%{http_code}' "${GATEWAY}/api/v1/customers/me" \
        -H "Authorization: Bearer ${token}")"
      [[ "$status" == "200" ]] || return 1
      id="$(json 'd["id"]')" ;;
    *) return 1 ;;
  esac

  status="$(curl -s -o "${BODY_FILE}" -w '%{http_code}' "${GATEWAY}/api/v1/customers/me" \
    -H "Authorization: Bearer ${token}")"
  [[ "$status" == "200" ]] || return 1
  if [[ "$(json 'd["kycStatus"]')" == "NOT_STARTED" ]]; then
    status="$(curl -s -o "${BODY_FILE}" -w '%{http_code}' -X POST "${GATEWAY}/api/v1/customers/${id}/kyc" \
      -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' \
      -d "{\"documentReference\":\"${DOCREF}\",\"printedName\":\"${FULL_NAME}\",\"expiryDate\":\"2035-01-01\",\"issuingCountry\":\"GB\",\"nationality\":\"GB\"}")"
    [[ "$status" == "200" ]] || return 1
  fi
  decided="$(curl -s -o "${BODY_FILE}" -w '%{http_code}' "${GATEWAY}/api/v1/customers/me" \
    -H "Authorization: Bearer ${token}" >/dev/null; json 'd["kycStatus"]')"
  [[ "$decided" == "APPROVED" ]] || return 1
  printf '%s %s' "$token" "$id"
}
txn_psql() {
  docker compose -f "${ROOT_DIR}/docker-compose.yml" exec -T postgres \
    psql -U "$(env_value POSTGRES_SUPERUSER)" -d fintech_transactions -tAc "$1" 2>/dev/null | tr -d ' \r'
}

fraud_psql() {
  docker compose -f "${ROOT_DIR}/docker-compose.yml" exec -T postgres \
    psql -U "$(env_value POSTGRES_SUPERUSER)" -d fintech_fraud -tAc "$1" 2>/dev/null | tr -d ' \r'
}

# ---- Provisioning -----------------------------------------------------------------------------

step "Provisioning the chaos customer"
[[ -f "${ENV_FILE}" ]] || fail "no .env found. Run ./scripts/bootstrap.sh first."
kc_ensure_user "$CHAOS_USER" CUSTOMER
read -r TOKEN CUSTOMER_ID <<<"$(setup_customer "$CHAOS_USER")" || fail "could not set up ${CHAOS_USER}"
[[ -n "$TOKEN" && -n "$CUSTOMER_ID" ]] || fail "chaos customer setup returned empty fields"
pass "chaos customer holds an approved profile"

step "Baseline: issuing while healthy"
curl -s -o "${BODY_FILE}" -w '%{http_code}' -X POST "${GATEWAY}/api/v1/cards" \
  -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: ${RUN_ID}-baseline" \
  -d "{\"customerId\":\"${CUSTOMER_ID}\",\"brand\":\"DEBIT\"}" | grep -q '^201$' \
  || { cat "${BODY_FILE}"; fail "baseline card issue failed while the stack is healthy"; }
pass "card issues while healthy"

# ---- Experiment 1: the eligibility dependency is down --------------------------------------------

step "Experiment 1: stopping customer-service"
stop_service customer-service
sleep 3
pass "customer-service stopped"

step "Card issuing fails closed and fast, never hangs and never issues"
START="$SECONDS"
CODE="$(curl -s -o "${BODY_FILE}" -w '%{http_code}' --max-time 60 -X POST "${GATEWAY}/api/v1/cards" \
  -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: ${RUN_ID}-outage" \
  -d "{\"customerId\":\"${CUSTOMER_ID}\",\"brand\":\"DEBIT\"}")"
ELAPSED=$((SECONDS - START))
[[ "$CODE" == "503" ]] || { cat "${BODY_FILE}"; fail "expected 503, got ${CODE}"; }
[[ "$(json 'd["error"]')" == "ELIGIBILITY_UNAVAILABLE" ]] ||
  fail "expected ELIGIBILITY_UNAVAILABLE, got $(json 'd["error"]')"
[[ -n "$(json 'd["correlationId"]')" ]] || fail "refusal carries no correlation id for the support ticket"
# Two attempts at ~instant refusal plus the gateway hop: generous bound, but an
# unbounded hang (or a 30s gateway timeout) fails it loudly.
[[ "$ELAPSED" -lt 15 ]] || fail "refusal took ${ELAPSED}s; the outage hung instead of failing fast"
pass "503 ELIGIBILITY_UNAVAILABLE with a correlation id in ${ELAPSED}s"

step "Recovering customer-service"
restore
# The gateway answers before the service behind it is ready: a JVM with
# Flyway migrations boots slower than a router, so poll the real endpoint
# rather than asserting on the first answer after the container starts.
RECOVERED=""
for _ in $(seq 1 60); do
  STATUS="$(curl -s -o "${BODY_FILE}" -w '%{http_code}' --max-time 10 "${GATEWAY}/api/v1/customers/me" \
    -H "Authorization: Bearer ${TOKEN}")"
  if [[ "$STATUS" == "200" ]]; then
    RECOVERED=yes
    break
  fi
  sleep 2
done
[[ -n "$RECOVERED" ]] || fail "customer-service did not recover (me answered ${STATUS})"
curl -s -o "${BODY_FILE}" -w '%{http_code}' -X POST "${GATEWAY}/api/v1/cards" \
  -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: ${RUN_ID}-recovered" \
  -d "{\"customerId\":\"${CUSTOMER_ID}\",\"brand\":\"DEBIT\"}" | grep -q '^201$' \
  || { cat "${BODY_FILE}"; fail "card issue did not recover with customer-service"; }
pass "card issues again after recovery"

# ---- Experiment 2: the broker is down ----------------------------------------------------------------

step "Experiment 2: funding, then stopping kafka"
FUND_KEY="${RUN_ID}-fund"
curl -s -o "${BODY_FILE}" -w '%{http_code}' -X POST \
  "${GATEWAY}/api/v1/accounts/fund?amount=500.00&currency=GBP" \
  -H "Authorization: Bearer ${TOKEN}" -H "Idempotency-Key: ${FUND_KEY}" | grep -q '^200$' \
  || { cat "${BODY_FILE}"; fail "funding failed while healthy"; }
pass "account funded"
stop_service kafka
sleep 3
pass "kafka stopped"

step "A payment still authorises while the broker is down"
PAY_KEY="${RUN_ID}-pay"
CARD_TOKEN="tok_chaos_${RANDOM}${RANDOM}"
START="$SECONDS"
CODE="$(curl -s -o "${BODY_FILE}" -w '%{http_code}' --max-time 60 -X POST "${GATEWAY}/api/v1/transactions" \
  -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: ${PAY_KEY}" \
  -d "{\"amount\":\"5.00\",\"currency\":\"GBP\",\"cardToken\":\"${CARD_TOKEN}\",\"payeeName\":\"Chaos Grocer\",\"payeeReference\":\"${PAY_KEY}\"}")"
ELAPSED=$((SECONDS - START))
[[ "$CODE" == "201" ]] || { cat "${BODY_FILE}"; fail "expected 201, got ${CODE}"; }
TXN_STATUS="$(json 'd["status"]')"
TXN_ID="$(json 'd["id"]')"
[[ "$TXN_STATUS" == "AUTHORIZED" ]] || fail "payment came back ${TXN_STATUS} instead of AUTHORIZED"
[[ "$ELAPSED" -lt 15 ]] || fail "authorisation took ${ELAPSED}s; the broker outage slowed the payment path"
pass "payment ${TXN_ID} authorised in ${ELAPSED}s with kafka down"

step "No fraud decision exists while the broker is down"
COUNT="$(fraud_psql "select count(*) from risk_decisions where transaction_id='${TXN_ID}'")"
[[ "$COUNT" == "0" ]] || fail "a decision exists with no broker to carry the event — scoring is not async"
pass "no decision recorded without a broker"

step "Recovering kafka: the blind payment is still scored"
restore
KAFKA_PORT="$(env_value KAFKA_PORT)"
[[ -n "$KAFKA_PORT" ]] || KAFKA_PORT=9092
wait_for_port "$KAFKA_PORT" 120 || fail "kafka port ${KAFKA_PORT} never reopened after restore"
DECIDED=""
for _ in $(seq 1 120); do
  COUNT="$(fraud_psql "select count(*) from risk_decisions where transaction_id='${TXN_ID}'")"
  if [[ "$COUNT" == "1" ]]; then
    DECIDED=yes
    break
  fi
  sleep 1
done
[[ -n "$DECIDED" ]] || fail "no decision for ${TXN_ID} 120s after kafka recovered"
pass "the engine scored the payment authorised while blind"

printf '\nchaos complete: both outages degraded, neither hung, both recovered\n'
