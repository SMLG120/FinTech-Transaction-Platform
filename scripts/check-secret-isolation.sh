#!/usr/bin/env bash
#
# Fails if any application container is given a secret that belongs to another component.
#
# This is a regression guard for a mistake that is easy to make and invisible in review: adding
# `env_file: .env` to the service anchor would hand every service the Postgres superuser password,
# the Keycloak admin password and the Grafana password, and nothing in the compose file would look
# wrong. This turns that mistake into a non-zero exit.
#
# Usage: ./scripts/check-secret-isolation.sh
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if ! docker compose version >/dev/null 2>&1; then
  echo "docker compose is required" >&2
  exit 1
fi
if [ ! -f .env ]; then
  echo ".env is missing; run ./scripts/bootstrap.sh first" >&2
  exit 1
fi

rendered="$(mktemp)"
trap 'rm -f "$rendered"' EXIT
docker compose config --format json >"$rendered"

# The compose output is passed as a path rather than piped, because a heredoc supplies python's
# stdin (the script itself) and a pipe could not be read at the same time.
python3 - "$rendered" <<'PY'
import json
import sys

config = json.load(open(sys.argv[1]))
services = config["services"]

# Secrets owned by a single component. None of these may appear in an application container.
INFRASTRUCTURE_SECRETS = {
    "KEYCLOAK_ADMIN_PASSWORD",
    "POSTGRES_SUPERUSER_PASSWORD",
    "GRAFANA_ADMIN_PASSWORD",
}

# Secrets an application container may legitimately hold.
APPLICATION_SECRETS = {"SERVICE_DB_PASSWORD", "REDIS_PASSWORD"}

# Services that talk to no database at all, so must not be given database configuration.
NO_DATABASE = {"api-gateway"}

APPLICATION_SERVICES = {
    "api-gateway",
    "auth-service",
    "customer-service",
    "card-service",
    "transaction-service",
    "fraud-service",
    "notification-service",
    "audit-service",
    "dispute-service",
    "settlement-service",
}

missing = APPLICATION_SERVICES - set(services)
if missing:
    print(f"FAIL: compose is missing application services: {sorted(missing)}")
    sys.exit(1)

problems = []

for name in sorted(APPLICATION_SERVICES):
    env = services[name].get("environment", {})
    keys = set(env)

    leaked = keys & INFRASTRUCTURE_SECRETS
    if leaked:
        problems.append(f"{name} receives infrastructure secrets: {sorted(leaked)}")

    unexpected_secrets = {k for k in keys if ("PASSWORD" in k or "SECRET" in k) and k not in APPLICATION_SECRETS}
    if unexpected_secrets:
        problems.append(f"{name} receives unrecognised secrets: {sorted(unexpected_secrets)}")

    for key in ("REDIS_PASSWORD",):
        if key not in keys:
            problems.append(f"{name} is missing required secret {key}")

    if name in NO_DATABASE:
        db_keys = {k for k in keys if k.startswith("SERVICE_DB_")}
        if db_keys:
            problems.append(f"{name} uses no database but is given {sorted(db_keys)}")
    else:
        for key in ("SERVICE_DB_URL", "SERVICE_DB_USERNAME", "SERVICE_DB_PASSWORD"):
            if key not in keys:
                problems.append(f"{name} is missing required configuration {key}")
        username = env.get("SERVICE_DB_USERNAME")
        url = env.get("SERVICE_DB_URL", "")
        if username and f"/{username}" not in url:
            problems.append(
                f"{name} connects to {url!r} but authenticates as {username!r}; "
                "the Postgres init script creates one role per database, so these must match"
            )

    for other in sorted(APPLICATION_SERVICES - {name}):
        if name in NO_DATABASE or other in NO_DATABASE:
            continue
        if env.get("SERVICE_DB_URL") == services[other]["environment"].get("SERVICE_DB_URL"):
            problems.append(f"{name} and {other} share a database")

if problems:
    print("FAIL: secret isolation is broken\n")
    for problem in problems:
        print(f"  - {problem}")
    sys.exit(1)

print(f"ok: {len(APPLICATION_SERVICES)} application services, no infrastructure secret exposure")
PY
