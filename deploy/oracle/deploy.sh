#!/usr/bin/env bash
#
# Deploys (or redeploys) the full FinTech stack publicly on this machine behind Caddy + HTTPS.
#
# Usage, from the repository root on the VM:
#   ./deploy/oracle/deploy.sh                       # domain = <public-ip>.sslip.io (auto-detected)
#   ./deploy/oracle/deploy.sh myfintech.duckdns.org # or any domain whose *.domain points here
#
# Idempotent: re-run after `git pull` to rebuild and roll the stack.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"
COMPOSE=(docker compose -f docker-compose.yml -f deploy/docker-compose.public.yml -f deploy/oracle/docker-compose.caddy.yml)
# shellcheck source=../lib.sh
source deploy/lib.sh

require_docker

DOMAIN="${1:-}"
if [[ -z "$DOMAIN" ]]; then
  ip="$(curl -fsS --max-time 10 https://api.ipify.org || true)"
  [[ "$ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "could not detect the public IP; pass a domain"
  DOMAIN="${ip//./-}.sslip.io"
fi
DOMAIN="$(printf '%s' "$DOMAIN" | tr 'A-Z' 'a-z')"
[[ "$DOMAIN" =~ ^([a-z0-9-]+\.)+[a-z]{2,63}$ ]] || fail "not a domain name: ${DOMAIN}"

ensure_secrets
set_env PUBLIC_DOMAIN "$DOMAIN" >/dev/null   # Caddy's site addresses
set_public_urls "https://app.${DOMAIN}" "https://dash.${DOMAIN}" "https://api.${DOMAIN}" "https://auth.${DOMAIN}"
start_stack

step "Waiting for the public endpoints"
wait_for "https://auth.${DOMAIN}/realms/fintech/.well-known/openid-configuration" 200
wait_for "https://api.${DOMAIN}/actuator/health" 404   # blocked at the edge, by design
wait_for "https://app.${DOMAIN}/" 200
wait_for "https://dash.${DOMAIN}/" 200

cat <<DONE

Deployed.
  Customer web UI     https://app.${DOMAIN}
  React dashboard     https://dash.${DOMAIN}
  API gateway         https://api.${DOMAIN}
  Identity (Keycloak) https://auth.${DOMAIN}
DONE
print_demo_logins
