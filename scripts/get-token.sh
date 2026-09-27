#!/usr/bin/env bash
#
# Prints an access token for one of the synthetic local identities.
#
#   ./scripts/get-token.sh                 # customer@fintech.test
#   ./scripts/get-token.sh admin
#   ./scripts/get-token.sh admin --decode  # and print the claims
#
# Development convenience only. It uses the direct access grant, which exists in the local realm
# precisely so that a terminal can act as a client; a deployed realm does not enable it.

set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ENV_FILE="${ROOT_DIR}/.env"

IDENTITY="${1:-customer}"
DECODE=0
[[ "${2:-}" == "--decode" ]] && DECODE=1

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "no .env found. Run ./scripts/bootstrap.sh first." >&2
  exit 1
fi

# Sourced for KEYCLOAK_REALM only; nothing here echoes the admin password.
# shellcheck disable=SC1090
KEYCLOAK_REALM="$(grep -E '^KEYCLOAK_REALM=' "${ENV_FILE}" | cut -d= -f2-)"
KEYCLOAK_PORT="$(grep -E '^KEYCLOAK_PORT=' "${ENV_FILE}" | cut -d= -f2- || true)"
KEYCLOAK_PORT="${KEYCLOAK_PORT:-8180}"
ISSUER="http://localhost:${KEYCLOAK_PORT}/realms/${KEYCLOAK_REALM}"

case "${IDENTITY}" in
  admin | customer | agent | auditor | compliance | analyst) ;;
  -h | --help)
    sed -n '3,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 0
    ;;
  *)
    echo "unknown identity: ${IDENTITY}" >&2
    echo "expected one of: admin customer agent auditor compliance analyst" >&2
    exit 2
    ;;
esac

USERNAME="${IDENTITY}@fintech.test"
PASSWORD="fintech-dev-only"

response="$(curl -fsS -X POST "${ISSUER}/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password" \
  -d "client_id=fintech-web" \
  -d "username=${USERNAME}" \
  -d "password=${PASSWORD}")" || {
  echo "could not obtain a token for ${USERNAME}." >&2
  echo "is Keycloak running and healthy? Check: docker compose ps keycloak" >&2
  exit 1
}

token="$(printf '%s' "${response}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("access_token",""))')"

if [[ -z "${token}" ]]; then
  echo "no access token in the response:" >&2
  printf '%s\n' "${response}" >&2
  exit 1
fi

# Decoding is done in python because JWT payloads are base64url *without* padding, and BSD base64
# refuses to decode unpadded input. The tr/base64 pipeline looks like it works right up until it
# silently produces nothing.
decode_claims() {
  python3 -c '
import base64, json, sys

payload = sys.stdin.read().strip().split(".")[1]
payload += "=" * (-len(payload) % 4)
print(json.dumps(json.loads(base64.urlsafe_b64decode(payload)), indent=2))
'
}

# A token with no roles is valid and useless: it authenticates nobody in particular, and it is the
# failure mode this platform'"'"'s realm configuration actually produced during development. Saying so
# here is cheaper than debugging it from a 403 later.
roles="$(printf '%s' "${token}" | decode_claims |
  python3 -c 'import json,sys; print(",".join(json.load(sys.stdin).get("realm_access",{}).get("roles",[])) or "none")')"

if [[ "${roles}" == "none" ]]; then
  echo "warning: the token for ${USERNAME} carries no roles, so every request will be refused." >&2
  echo "         see infrastructure/keycloak/realm/README.md" >&2
fi

if [[ ${DECODE} -eq 1 ]]; then
  printf '%s' "${token}" | decode_claims
else
  printf '%s\n' "${token}"
fi
