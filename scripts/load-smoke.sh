#!/usr/bin/env bash
#
# Phase 14 smoke load: a bounded burst of real payments through the gateway.
#
# What it proves is deliberately narrow — that a few hundred fund/pay/settle
# cycles complete with no 5xx, no rate-limit hits, no idempotency conflicts,
# a bounded p95, and a ledger that still balances with the exact expected
# figure. It is a smoke test, not a capacity plan: one laptop, one broker,
# one Postgres, loopback networking. Local numbers are bounds on regressions,
# not predictions about production. See docs/performance.md.
#
# Profile (all overridable, all modest on purpose):
#   LOAD_WORKERS=8        concurrent payment loops
#   LOAD_PAYMENTS=200     total payments attempted
#   LOAD_AMOUNT=5.00      per payment, far under the 5000.00 ceiling
#   LOAD_P95_BOUND_MS=500   the run fails if pay p95 exceeds this (first measured
#                             p95 was ~125ms on a warm laptop stack; the bound is
#                             ~4x headroom, not a prediction)
#
# The loader is stdlib python3 (urllib + threads): no k6, no Locust, no new
# toolchain — the same reason the verify scripts are curl. Every payment
# carries a fresh Idempotency-Key, so a retry can only replay, never double.
# Money is synthetic; the load customer is a throwaway Keycloak identity.
#
# Usage: ./scripts/load-smoke.sh   (needs `make up` first)

set -euo pipefail

readonly GATEWAY="http://localhost:8080"
readonly KEYCLOAK="http://localhost:8180"
readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ENV_FILE="${ROOT_DIR}/.env"
readonly DEV_PASSWORD="fintech-dev-only"
readonly BODY_FILE=/tmp/load-body.json

readonly LOAD_USER="load-a@fintech.test"
readonly FULL_NAME="Load Amber"
readonly DOB="1992-03-08"
readonly ADDRESS='{"line1":"9 Throughput Terrace","city":"Leeds","postalCode":"LS1 4DP","country":"GB"}'
readonly DOCREF="SYNTH-PPT-9200"
readonly RUN_ID="loadcheck-${RANDOM}-${RANDOM}"

readonly WORKERS="${LOAD_WORKERS:-8}"
readonly PAYMENTS="${LOAD_PAYMENTS:-200}"
readonly AMOUNT="${LOAD_AMOUNT:-5.00}"
readonly P95_BOUND_MS="${LOAD_P95_BOUND_MS:-500}"

pass() { printf '  \033[32mok\033[0m   %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; exit 1; }
step() { printf '\n\033[1m%s\033[0m\n' "$1"; }

json() { python3 -c "import json,sys;d=json.load(open('${BODY_FILE}'));print($1)"; }
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

kc_ensure_user() {
  local username="$1" role="$2" admin realm user_id status
  admin="$(kc_admin_token)"
  [[ -n "$admin" ]] || fail "could not get a Keycloak admin token; is Keycloak healthy?"
  realm="$(env_value KEYCLOAK_REALM)"

  user_id="$(curl -s "${KEYCLOAK}/admin/realms/${realm}/users?username=${username}&exact=true" \
    -H "Authorization: Bearer ${admin}" |
    python3 -c 'import json,sys; u=json.load(sys.stdin); print(u[0]["id"] if u else "")')"

  if [[ -z "$user_id" ]]; then
    status="$(curl -s -o /tmp/kc-body.json -w '%{http_code}' -X POST "${KEYCLOAK}/admin/realms/${realm}/users" \
      -H "Authorization: Bearer ${admin}" -H 'Content-Type: application/json' \
      -d "{\"username\":\"${username}\",\"enabled\":true,\"email\":\"${username}\",\"emailVerified\":true,\"firstName\":\"Load\",\"lastName\":\"Amber\",\"credentials\":[{\"type\":\"password\",\"value\":\"${DEV_PASSWORD}\",\"temporary\":false}]}")"
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

setup_customer() {
  local username="$1" token status id decided
  token="$(kc_token "$username")"
  [[ -n "$token" ]] || return 1

  status="$(curl -s -o "${BODY_FILE}" -w '%{http_code}' -X POST "${GATEWAY}/api/v1/customers" \
    -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' \
    -d "{\"fullName\":\"${FULL_NAME}\",\"dateOfBirth\":\"${DOB}\",\"nationality\":\"GB\",\"email\":\"user-$1\",\"phone\":\"+447700900222\",\"address\":${ADDRESS}}")"
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
  decided="$(curl -s -o "${BODY_FILE}" "${GATEWAY}/api/v1/customers/me" \
    -H "Authorization: Bearer ${token}" >/dev/null; json 'd["kycStatus"]')"
  [[ "$decided" == "APPROVED" ]] || return 1
  printf '%s %s' "$token" "$id"
}

txn_psql() {
  docker compose -f "${ROOT_DIR}/docker-compose.yml" exec -T postgres \
    psql -U "$(env_value POSTGRES_SUPERUSER)" -d fintech_transactions -tAc "$1" 2>/dev/null | tr -d ' \r'
}

step "Provisioning the load customer"
[[ -f "${ENV_FILE}" ]] || fail "no .env found. Run ./scripts/bootstrap.sh first."
kc_ensure_user "$LOAD_USER" CUSTOMER
read -r TOKEN CUSTOMER_ID <<<"$(setup_customer "$LOAD_USER")" || fail "could not set up ${LOAD_USER}"
[[ -n "$TOKEN" && -n "$CUSTOMER_ID" ]] || fail "load customer setup returned empty fields"
pass "load customer holds an approved profile"

step "Funding and reading the opening balance"
# Funded far above the run's total so every payment is about the platform,
# never about an empty account: 200 x 5.00 needs 1000.00.
FUND="5000.00"
curl -s -o "${BODY_FILE}" -w '%{http_code}' -X POST \
  "${GATEWAY}/api/v1/accounts/fund?amount=${FUND}&currency=GBP" \
  -H "Authorization: Bearer ${TOKEN}" -H "Idempotency-Key: ${RUN_ID}-fund" | grep -q '^200$' \
  || { cat "${BODY_FILE}"; fail "funding failed"; }
OPENING="$(curl -s "${GATEWAY}/api/v1/accounts/balance?currency=GBP" \
  -H "Authorization: Bearer ${TOKEN}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["available"])')"
pass "funded ${FUND}; opening available ${OPENING}"

step "Driving ${PAYMENTS} payments across ${WORKERS} workers"
export GATEWAY TOKEN RUN_ID PAYMENTS WORKERS AMOUNT
OUTCOMES="$(python3 - <<'PY'
import json, os, statistics, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor
gateway = os.environ["GATEWAY"]; token = os.environ["TOKEN"]; run_id = os.environ["RUN_ID"]
count = int(os.environ["PAYMENTS"]); workers = int(os.environ["WORKERS"]); amount = os.environ["AMOUNT"]
def call(method, path, body=None, key=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(gateway + path, data=data, method=method,
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json",
                 "Idempotency-Key": key or f"{run_id}-{time.time_ns()}"})
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            s = r.status
            try: p = json.loads(r.read() or b"null")
            except ValueError: p = None
    except urllib.error.HTTPError as e:
        s = e.code
        try: p = json.loads(e.read() or b"null")
        except ValueError: p = None
    return s, (time.perf_counter() - t0) * 1000, p
def one(i):
    k = f"{run_id}-pay-{i}"
    o = {"pay": 0, "pay_ms": 0.0, "settle": 0, "settle_ms": 0.0}
    s, ms, p = call("POST", "/api/v1/transactions",
        {"amount": amount, "currency": "GBP", "cardToken": f"tok_load_{(i * 2654435761) % 16**16:016x}",
         "payeeName": "Load Grocer", "payeeReference": k}, k)
    o["pay"] = s; o["pay_ms"] = ms
    if s == 201 and isinstance(p, dict) and p.get("status") == "AUTHORIZED":
        s2, ms2, _ = call("POST", f"/api/v1/transactions/{p['id']}/settle")
        o["settle"] = s2; o["settle_ms"] = ms2
    return o
with ThreadPoolExecutor(max_workers=workers) as pool:
    print(json.dumps(list(pool.map(one, range(count)))))
PY
)"

step "Summarising ${PAYMENTS} payments"
export OUTCOMES
SUMMARY="$(python3 - <<'PY'
import json, os
rows = json.loads(os.environ["OUTCOMES"])
pay_ms = sorted(r["pay_ms"] for r in rows)
settle_ms = sorted(r["settle_ms"] for r in rows if r["settle"])
def pct(xs, q):
    return xs[min(len(xs) - 1, int(q * len(xs)))] if xs else 0.0
codes = {}
for r in rows:
    codes[r["pay"]] = codes.get(r["pay"], 0) + 1
    if r["settle"]:
        key = "settle:%s" % r["settle"]
        codes[key] = codes.get(key, 0) + 1
settled = sum(1 for r in rows if r["settle"] == 200)
print(json.dumps({
    "attempted": len(rows),
    "codes": codes,
    "settled": settled,
    "server_errors": sum(1 for r in rows if r["pay"] >= 500 or (r["settle"] and r["settle"] >= 500)),
    "rate_limited": sum(1 for r in rows if r["pay"] == 429 or r["settle"] == 429),
    "declined": sum(1 for r in rows if r["pay"] == 422),
    "pay_p50_ms": round(pct(pay_ms, 0.50), 1),
    "pay_p95_ms": round(pct(pay_ms, 0.95), 1),
    "pay_max_ms": round(pay_ms[-1], 1) if pay_ms else 0.0,
    "settle_p95_ms": round(pct(settle_ms, 0.95), 1) if settle_ms else 0.0,
}))
PY
)"
printf '%s\n' "$SUMMARY" | python3 -m json.tool
value() { printf '%s' "$SUMMARY" | python3 -c "import json,sys; print(json.load(sys.stdin)[\"$1\"])"; }

[[ "$(value server_errors)" == "0" ]] || fail "$(value server_errors) requests answered 5xx"
[[ "$(value rate_limited)" == "0" ]] || fail "$(value rate_limited) requests hit the rate limiter at ${WORKERS} workers"
[[ "$(value declined)" == "0" ]] || fail "$(value declined) payments declined; the account was underfunded for this run"
[[ "$(value attempted)" == "$PAYMENTS" ]] || fail "only $(value attempted) of ${PAYMENTS} payments ran"
PAY_P95="$(value pay_p95_ms)"
python3 -c "import sys; sys.exit(0 if float('${PAY_P95}') <= float('${P95_BOUND_MS}') else 1)" \
  || fail "pay p95 ${PAY_P95}ms exceeds the ${P95_BOUND_MS}ms bound"
pass "no 5xx, no 429, no declines; pay p95 ${PAY_P95}ms within ${P95_BOUND_MS}ms"

step "Confirming the money is exact and the ledger balances"
# Integer pence throughout: floats never touch money, not even in a test.
CLOSING="$(curl -s "${GATEWAY}/api/v1/accounts/balance?currency=GBP" \
  -H "Authorization: Bearer ${TOKEN}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["available"])')"
python3 - "$OPENING" "$CLOSING" "$AMOUNT" "$(value settled)" <<'PY'
import sys
from decimal import Decimal
opening, closing, amount, settled = Decimal(sys.argv[1]), Decimal(sys.argv[2]), Decimal(sys.argv[3]), int(sys.argv[4])
expected = opening - amount * settled
assert closing == expected, f"available {closing} != {opening} - {settled} x {amount} = {expected}"
print(f"available {closing} == opening {opening} minus {settled} settled x {amount}")
PY
pass "balance is exact to the penny"
UNBALANCED="$(txn_psql "SELECT count(*) FROM journal_entries e WHERE (SELECT COALESCE(SUM(CASE WHEN l.direction = 'DEBIT' THEN l.amount_minor ELSE 0 END),0) - COALESCE(SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount_minor ELSE 0 END),0) FROM journal_lines l WHERE l.entry_id = e.id) <> 0;")"
[[ "$UNBALANCED" == "0" ]] || fail "${UNBALANCED} journal entries do not balance after the run"
pass "every journal entry still balances"

printf '\nload smoke complete: %s payments, pay p95 %sms, ledger exact\n' "$PAYMENTS" "$PAY_P95"
