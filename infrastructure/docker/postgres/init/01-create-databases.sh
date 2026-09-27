#!/usr/bin/env bash
#
# Creates one database and one least-privilege application role per service.
#
# WHY A DATABASE PER SERVICE
#   A service that can read another service's tables has no boundary. Sharing one schema means a
#   single bad SQL statement, a single compromised credential or a single mistaken join in any
#   service can reach cardholder or card data belonging to another. Separate databases make the
#   isolation a property the database enforces rather than a property the code remembers.
#
# WHY A ROLE PER SERVICE (AND NOT ONE SHARED ROLE)
#   Postgres privileges are granted per role. One shared `fintech_*` role would let the transaction
#   service's compromise reach cardholder records. One role per service also makes pg_stat_activity,
#   connection pools and audit logs attributable to exactly one service, and revoking one service's
#   access is a single REVOKE.
#
# HEREDOC TERMINATORS SIT IN COLUMN 0 ON PURPOSE
#   `<<-` strips leading *tabs*, not spaces. An indented terminator is not recognised, so the
#   terminator line itself is handed to psql and it reports `syntax error at or near "SQL"` -- an
#   error that points at the wrong line entirely. Every `SQL` terminator below is unindented.
#
# WHY THIS IS A .sh AND NOT A .sql
#   The password must come from the environment. A .sql file cannot read one, which would force
#   either a hard-coded password in the repository or passing it via `psql -c`, where it is visible
#   in the process list to every local user. The Postgres entrypoint runs this script with the
#   container environment already populated, and the value is handed to psql with `\getenv`, which
#   reads the variable inside psql rather than through argv.
#
# Idempotent: safe to re-run. Existing roles get their password rotated; existing databases are
# left alone so that local data survives a restart.

set -euo pipefail

: "${SERVICE_DB_PASSWORD:?SERVICE_DB_PASSWORD must be set in the environment (run scripts/bootstrap.sh)}"

# One name per service. The database and the role are both named after it, deliberately derived
# from a single value rather than listed as two columns: when they were listed separately they
# drifted (databases `auth`/`customers`, roles `fintech_auth`/`fintech_customers`) and every
# service failed to connect with "database does not exist" while the bootstrap script cheerfully
# reported success. There is now no second place to update.
#
# These names must match the SERVICE_DB_URL in each service's application.yml, which
# scripts/check-secret-isolation.sh asserts by requiring each service's username to appear in its
# own JDBC path.
SERVICES=(
  fintech_auth
  fintech_customers
  fintech_cards
  fintech_transactions
  fintech_fraud
  fintech_notifications
  fintech_audit
  fintech_disputes
)

log() { printf '[db-init] %s\n' "$*"; }

# ON_ERROR_STOP is mandatory: without it psql reports a failed statement as success and the script
# would carry on believing the database was created.
psql_super() {
  psql --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" --set ON_ERROR_STOP=1 --quiet "$@"
}

for name in "${SERVICES[@]}"; do
  database="${name}"
  role="${name}"

  log "ensuring role ${role}"
  # Generated with \gexec rather than written inside a DO $$ ... $$ block, and that is not a style
  # preference. psql does not substitute :'variables' inside a dollar-quoted string, so a DO block
  # passes the text `:'role'` straight through to the server and it fails with
  # `syntax error at or near ":"`. Outside dollar quoting the substitution happens, and \gexec runs
  # the resulting statement. The role name and the password are both passed through format's %I and
  # %L, so neither can be used to inject SQL.
  psql_super --set=role="${role}" <<-'SQL'
    \getenv app_password SERVICE_DB_PASSWORD
    SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'role', :'app_password')
    WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'role')\gexec
    SELECT format('ALTER ROLE %I WITH LOGIN PASSWORD %L', :'role', :'app_password')\gexec
SQL

  log "ensuring database ${database} owned by ${role}"
  # CREATE DATABASE cannot run inside a transaction, so it is generated conditionally and executed
  # with \gexec rather than being wrapped in a DO block.
  psql_super --set=database="${database}" --set=role="${role}" <<-'SQL'
    SELECT format('CREATE DATABASE %I OWNER %I ENCODING ''UTF8''', :'database', :'role')
    WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'database')\gexec
SQL

  log "locking down ${database}"
  # PUBLIC is revoked at the database and schema level so a role that has not been explicitly
  # granted access cannot even read another service's catalog. The service role is the owner, so it
  # retains full rights over its own objects without needing further grants.
  psql_super --set=database="${database}" --set=role="${role}" <<-'SQL'
    \connect :"database"
    REVOKE ALL ON DATABASE :"database" FROM PUBLIC;
    REVOKE ALL ON SCHEMA public FROM PUBLIC;
    GRANT CONNECT, TEMPORARY ON DATABASE :"database" TO :"role";
    ALTER SCHEMA public OWNER TO :"role";
    GRANT ALL ON SCHEMA public TO :"role";
SQL
done

log "database bootstrap complete: ${#SERVICES[@]} databases and roles provisioned"
