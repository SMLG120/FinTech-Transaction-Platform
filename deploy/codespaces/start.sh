#!/usr/bin/env bash
#
# Starts the full FinTech stack inside a GitHub Codespace and publishes it on the codespace's
# public HTTPS URLs. Run from the repository root inside the codespace:
#
#   ./deploy/codespaces/start.sh
#
# Idempotent. Only the first run is slow (image builds); every container restarts by itself when
# the codespace resumes, because they all use `restart: unless-stopped`.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"
COMPOSE=(docker compose -f docker-compose.yml -f deploy/docker-compose.public.yml -f deploy/codespaces/docker-compose.caddy.yml)
# shellcheck source=../lib.sh
source deploy/lib.sh

[[ -n "${CODESPACE_NAME:-}" && -n "${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-}" ]] \
  || fail "run this inside a GitHub Codespace (CODESPACE_NAME is not set)"
require_docker

url() { printf 'https://%s-%s.%s' "$CODESPACE_NAME" "$1" "$GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN"; }
APP="$(url 4001)"; DASH="$(url 4002)"; API="$(url 4080)"; AUTH="$(url 4180)"

ensure_secrets
set_public_urls "$APP" "$DASH" "$API" "$AUTH"
start_stack

step "Waiting for the stack (inside the codespace)"
wait_for "http://127.0.0.1:4180/realms/fintech/.well-known/openid-configuration" 200
wait_for "http://127.0.0.1:4080/actuator/health" 404   # blocked at the edge, by design
wait_for "http://127.0.0.1:4001/" 200
wait_for "http://127.0.0.1:4002/" 200

step "Making the four ports public"
# Private forwarded ports demand a GitHub login, which breaks the browser's cross-origin calls
# between the UIs, the gateway and Keycloak. Only these four are opened; nothing else is forwarded.
if gh codespace ports visibility 4001:public 4002:public 4080:public 4180:public -c "$CODESPACE_NAME" >/dev/null 2>&1; then
  echo "ports 4001 4002 4080 4180 are public"
else
  cat <<'MANUAL'
Could not change port visibility from inside the codespace. Do it once, either:
  - from your own machine:  gh codespace ports visibility 4001:public 4002:public 4080:public 4180:public -c <codespace-name>
  - or in VS Code / the browser editor: Ports panel -> right-click each of 4001 4002 4080 4180 -> Port Visibility -> Public
MANUAL
fi

cat <<DONE

Running. Public URLs (live while this codespace is running):
  Customer web UI     ${APP}
  React dashboard     ${DASH}
  API gateway         ${API}
  Identity (Keycloak) ${AUTH}
DONE
print_demo_logins
