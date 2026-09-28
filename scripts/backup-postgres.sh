#!/usr/bin/env bash
#
# Dumps every service database to a timestamped directory under backups/.
#
# What this is: a local-dev safety net before risky operations (and the
# procedure the postgres runbook points at). What it is not: a production
# backup strategy — production needs managed PITR backups, tested restores,
# and encryption at rest, none of which a pg_dump loop provides.
#
# Keycloak is deliberately excluded: the dev realm reimports from
# infrastructure/keycloak/realm/fintech-realm.dev.json, so its database is
# reproducible state, not backup-worthy state. Throwaway verify-script users
# live there too.
#
# Usage:
#   ./scripts/backup-postgres.sh                 # dump all service databases
#   ./scripts/backup-postgres.sh --list          # show existing backups
#   ./scripts/backup-postgres.sh --restore DIR   # restore each dump into its
#                                                # database (databases must be
#                                                # empty; see below)
#
# Restore overwrites: it drops and recreates each target database first, so
# never point it at a database holding anything. The round trip this script
# was verified with: backup, drop fintech_audit, restore, compare row counts.

set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ENV_FILE="${ROOT_DIR}/.env"
readonly BACKUP_ROOT="${ROOT_DIR}/backups"

readonly DATABASES="fintech_auth fintech_customers fintech_cards fintech_transactions fintech_fraud fintech_notifications fintech_audit fintech_disputes fintech_settlement"

fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; exit 1; }
pass() { printf '  \033[32mok\033[0m   %s\n' "$1"; }

env_value() { grep -E "^$1=" "${ENV_FILE}" | head -1 | cut -d= -f2-; }
superuser() { env_value POSTGRES_SUPERUSER; }

psql_exec() {
  docker compose -f "${ROOT_DIR}/docker-compose.yml" exec -T postgres \
    psql -U "$(superuser)" -d postgres -tAc "$1" 2>/dev/null | tr -d ' \r'
}

list_backups() {
  if [[ ! -d "$BACKUP_ROOT" ]]; then
    echo "no backups yet"
    return 0
  fi
  ls -1 "$BACKUP_ROOT"
}

do_backup() {
  [[ -f "${ENV_FILE}" ]] || fail "no .env found. Run ./scripts/bootstrap.sh first."
  local dir="${BACKUP_ROOT}/postgres-$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$dir"
  for db in $DATABASES; do
    docker compose -f "${ROOT_DIR}/docker-compose.yml" exec -T postgres \
      pg_dump -U "$(superuser)" -d "$db" --clean --if-exists 2>/dev/null | gzip >"${dir}/${db}.sql.gz" \
      || fail "dumping ${db}"
    pass "${db} -> ${dir}/${db}.sql.gz"
  done
  printf '\nbackup complete: %s\n' "$dir"
}

do_restore() {
  local dir="$1"
  [[ -d "$dir" ]] || fail "no such backup directory: ${dir}"
  printf 'restoring into EMPTY databases from %s\n' "$dir"
  printf 'this drops and recreates each target database first.\n'
  printf 'type the word OVERWRITE to continue: '
  read -r answer
  [[ "$answer" == "OVERWRITE" ]] || fail "aborted; nothing was touched"
  for db in $DATABASES; do
    [[ -f "${dir}/${db}.sql.gz" ]] || fail "missing dump for ${db} in ${dir}"
    # Terminate backends first: a restore against live connections fails
    # halfway, which is worse than refusing to start.
    psql_exec "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='${db}' AND pid <> pg_backend_pid();" >/dev/null
    psql_exec "DROP DATABASE IF EXISTS \"${db}\";" >/dev/null
    psql_exec "CREATE DATABASE \"${db}\" OWNER \"$(superuser)\";" >/dev/null
    gunzip -c "${dir}/${db}.sql.gz" | docker compose -f "${ROOT_DIR}/docker-compose.yml" exec -T postgres \
      psql -U "$(superuser)" -d "$db" -q -v ON_ERROR_STOP=1 >/dev/null \
      || fail "restoring ${db}"
    pass "${db} restored"
  done
  printf '\nrestore complete. Re-run the affected lifecycle scripts before trusting the stack.\n'
}

case "${1:-}" in
  --list) list_backups ;;
  --restore)
    [[ -n "${2:-}" ]] || fail "usage: $0 --restore DIR"
    do_restore "$2"
    ;;
  "") do_backup ;;
  *) fail "usage: $0 [--list | --restore DIR]" ;;
esac
