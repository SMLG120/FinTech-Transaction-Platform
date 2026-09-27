#!/usr/bin/env bash
#
# Creates a local .env with freshly generated random secrets.
#
# The point of generating secrets rather than shipping a fixed development
# password in .env.example is that a committed credential outlives its
# usefulness: it ends up in blog posts, screenshots, forks and CI logs, and it
# trains everyone to treat checked-in values as disposable. Generating a fresh
# 32-byte value per machine costs one command and removes the whole category of
# problem.
#
# Idempotent: an existing .env is never overwritten unless --force is passed.

set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ENV_FILE="${ROOT_DIR}/.env"
readonly EXAMPLE_FILE="${ROOT_DIR}/.env.example"

FORCE=0
for arg in "$@"; do
  case "${arg}" in
    --force) FORCE=1 ;;
    -h | --help)
      sed -n '3,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "unknown argument: ${arg}" >&2
      exit 2
      ;;
  esac
done

if [[ ! -f "${EXAMPLE_FILE}" ]]; then
  echo "cannot find ${EXAMPLE_FILE}" >&2
  exit 1
fi

# A 32-byte (64 hex char) value is used for the internal-identity signing key so that the HMAC-SHA256
# key is 256 bits, matching the digest it feeds.
random_hex() {
  local bytes="${1:-32}"
  if ! command -v openssl >/dev/null 2>&1; then
    echo "openssl is required to generate secrets but was not found on PATH" >&2
    exit 1
  fi
  openssl rand -hex "${bytes}"
}

# The PII master key is base64 rather than hex because it has to be fed to AES as raw bytes, and
# base64 is what survives a YAML/Compose round trip without an escaping mistake. 32 bytes exactly:
# customer-service rejects any other length, since a shorter key would silently weaken AES-256.
random_base64() {
  local bytes="${1:-32}"
  if ! command -v openssl >/dev/null 2>&1; then
    echo "openssl is required to generate secrets but was not found on PATH" >&2
    exit 1
  fi
  openssl rand -base64 "${bytes}"
}

if [[ -f "${ENV_FILE}" && ${FORCE} -eq 0 ]]; then
  # Backfill any generated secret that a later phase added but this .env predates.
  #
  # A key that is absent is the one case where doing nothing is worse than it looks. The documented
  # remedy is --force, which rotates every secret including SERVICE_DB_PASSWORD and the Keycloak
  # admin password, breaking existing Postgres volumes and every issued token. That is a large,
  # destructive answer to "one new variable is missing", so the missing case is handled here
  # instead: a key is appended only if the file does not already have it, and nothing existing is
  # ever rewritten. A key that is present is left exactly as it is.
  backfilled=0
  for name in CUSTOMER_PII_MASTER_KEY CARD_TOKENISATION_KEY SUBJECT_DIGEST_KEY; do
    if ! grep -q "^${name}=" "${ENV_FILE}"; then
      echo "adding ${name}, which this .env predates"
      case "${name}" in
        CUSTOMER_PII_MASTER_KEY) value="$(random_base64 32)" ;;
        # A distinct secret, not a second use of the PII key: that one protects data the platform
        # must read back, this one derives a value it must never invert. Sharing them would make a
        # compromise of the PII vault also compromise every card, and would collapse two rotation
        # cadences that are genuinely unrelated into one decision.
        CARD_TOKENISATION_KEY) value="$(random_base64 32)" ;;
        # A third, and for the same underlying reason as the tokenisation key. transaction-service
        # derives its own subject digests, and ADR-0002's whole claim is that a subject cannot be
        # correlated across service databases. That isolation lives entirely in these keys being
        # different: hand transaction-service any other service's key, or any other service this
        # one, and the join starts working with no error anywhere to notice it. Regenerating this
        # key does not invalidate stored digests, but it does orphan every account and payment row
        # already written under the old one, so rotate it before there are any.
        SUBJECT_DIGEST_KEY) value="$(random_base64 32)" ;;
        # Any future generated secret MUST get a case here. The default arm exits rather than
        # inventing a value, because a key generated with the wrong length fails at service startup
        # and a key generated with the wrong derivation silently works while protecting nothing.
        *) echo "no generator is defined for ${name}" >&2; exit 1 ;;
      esac
      printf '%s=%s\n' "${name}" "${value}" >>"${ENV_FILE}"
      backfilled=1
    fi
  done
  chmod 600 "${ENV_FILE}"
  if [[ ${backfilled} -eq 0 ]]; then
    echo ".env already exists. Re-run with --force to regenerate every secret."
    echo "Note: regenerating rotates SERVICE_DB_PASSWORD. Existing Postgres volumes keep the old"
    echo "role passwords, so run 'docker compose down -v' if services cannot connect afterwards."
  fi
  exit 0
fi


SERVICE_DB_PASSWORD_VALUE="$(random_hex 32)"
REDIS_PASSWORD_VALUE="$(random_hex 32)"
KEYCLOAK_ADMIN_PASSWORD_VALUE="$(random_hex 24)"
POSTGRES_SUPERUSER_PASSWORD_VALUE="$(random_hex 32)"
GRAFANA_ADMIN_PASSWORD_VALUE="$(random_hex 24)"
INTERNAL_IDENTITY_SIGNING_KEY_VALUE="$(random_hex 32)"
CUSTOMER_PII_MASTER_KEY_VALUE="$(random_base64 32)"

sed \
  -e "s|^KEYCLOAK_ADMIN_PASSWORD=.*|KEYCLOAK_ADMIN_PASSWORD=${KEYCLOAK_ADMIN_PASSWORD_VALUE}|" \
  -e "s|^POSTGRES_SUPERUSER_PASSWORD=.*|POSTGRES_SUPERUSER_PASSWORD=${POSTGRES_SUPERUSER_PASSWORD_VALUE}|" \
  -e "s|^SERVICE_DB_PASSWORD=.*|SERVICE_DB_PASSWORD=${SERVICE_DB_PASSWORD_VALUE}|" \
  -e "s|^REDIS_PASSWORD=.*|REDIS_PASSWORD=${REDIS_PASSWORD_VALUE}|" \
  -e "s|^GRAFANA_ADMIN_PASSWORD=.*|GRAFANA_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD_VALUE}|" \
  -e "s|^INTERNAL_IDENTITY_SIGNING_KEY=.*|INTERNAL_IDENTITY_SIGNING_KEY=${INTERNAL_IDENTITY_SIGNING_KEY_VALUE}|" \
  -e "s|^CUSTOMER_PII_MASTER_KEY=.*|CUSTOMER_PII_MASTER_KEY=${CUSTOMER_PII_MASTER_KEY_VALUE}|" \
  "${EXAMPLE_FILE}" >"${ENV_FILE}"

chmod 600 "${ENV_FILE}"

# Fail loudly rather than letting a placeholder reach a running container.
if grep -q 'CHANGE_ME' "${ENV_FILE}"; then
  echo "ERROR: .env still contains CHANGE_ME placeholders. Delete it and re-run this script." >&2
  exit 1
fi

cat <<EOF
Created ${ENV_FILE} with fresh random secrets (permissions 600).

Next:
  docker compose up -d postgres redis kafka      # infrastructure only
  docker compose up --build                        # everything

Verify:
  docker compose ps
  curl -fsS http://localhost:8080/actuator/health

customer-service refuses to start without CUSTOMER_PII_MASTER_KEY, so if it is crash-looping after a
regeneration, re-run this script rather than setting the variable to a placeholder.
EOF
