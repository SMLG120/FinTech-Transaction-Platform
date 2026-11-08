#!/usr/bin/env bash
#
# Deploys (or redeploys) the full FinTech stack publicly on this machine behind Caddy + HTTPS.
#
# Usage, from the repository root on the VM:
#   ./deploy/oracle/deploy.sh                       # domain = <public-ip>.sslip.io (auto-detected)
#   ./deploy/oracle/deploy.sh myfintech.duckdns.org # or any domain whose *.domain points here
#
# Idempotent: re-run after `git pull` to rebuild and roll the stack. Secrets are generated once by
# scripts/bootstrap.sh into .env (mode 0600) and never printed.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"
COMPOSE=(docker compose -f docker-compose.yml -f deploy/oracle/docker-compose.public.yml)

step() { printf '\n\033[1m%s\033[0m\n' "$1"; }
fail() { printf '\033[31mERROR\033[0m %s\n' "$1" >&2; exit 1; }

command -v docker >/dev/null || fail "docker is not installed (see deploy/oracle/README.md)"
docker compose version >/dev/null 2>&1 || fail "the docker compose plugin is not installed"

DOMAIN="${1:-}"
if [[ -z "$DOMAIN" ]]; then
  ip="$(curl -fsS --max-time 10 https://api.ipify.org || true)"
  [[ "$ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "could not detect the public IP; pass a domain"
  DOMAIN="${ip//./-}.sslip.io"
fi
DOMAIN="$(printf '%s' "$DOMAIN" | tr 'A-Z' 'a-z')"

step "Secrets"
if [[ ! -f .env ]]; then
  ./scripts/bootstrap.sh
else
  ./scripts/bootstrap.sh >/dev/null   # backfills any key added since .env was created
fi
chmod 600 .env
# Record the domain in .env so plain `docker compose` commands later resolve the same hostnames.
if grep -q '^PUBLIC_DOMAIN=' .env; then
  previous="$(grep '^PUBLIC_DOMAIN=' .env | cut -d= -f2-)"
  sed -i.bak "s|^PUBLIC_DOMAIN=.*|PUBLIC_DOMAIN=${DOMAIN}|" .env && rm -f .env.bak
  if [[ "$previous" != "$DOMAIN" ]]; then
    printf 'Domain changed (%s -> %s). Keycloak keeps the old realm until its volume is removed:\n' "$previous" "$DOMAIN"
    printf '  %s rm -sf keycloak && docker volume rm secure-fintech-platform_keycloak-data\n' "${COMPOSE[*]}"
  fi
else
  printf '\n# Public hostnames are app./dash./api./auth. under this domain (deploy/oracle).\nPUBLIC_DOMAIN=%s\n' "$DOMAIN" >> .env
fi
echo "secrets present in .env (not shown)"

step "Rendering public configuration for ${DOMAIN}"
python3 deploy/oracle/render.py "$DOMAIN"

step "Building and starting the stack (first run takes 10-20 minutes on 4 ARM cores)"
"${COMPOSE[@]}" up -d --build --remove-orphans

step "Waiting for the public endpoints"
deadline=$(( $(date +%s) + 900 ))
for url in "https://auth.${DOMAIN}/realms/fintech/.well-known/openid-configuration" \
           "https://api.${DOMAIN}/actuator/health" \
           "https://app.${DOMAIN}/" "https://dash.${DOMAIN}/"; do
  expected=200; [[ "$url" == *actuator* ]] && expected=404   # blocked at the edge, by design
  until [[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$url")" == "$expected" ]]; do
    (( $(date +%s) < deadline )) || fail "${url} did not answer ${expected}; check: ${COMPOSE[*]} logs --tail=100 caddy keycloak api-gateway"
    sleep 10
  done
  printf '  ok   %s\n' "$url"
done

cat <<DONE

Deployed.
  Customer web UI     https://app.${DOMAIN}
  React dashboard     https://dash.${DOMAIN}
  API gateway         https://api.${DOMAIN}
  Identity (Keycloak) https://auth.${DOMAIN}

Demo logins: customer@fintech.test (and agent@, auditor@, analyst@, settlement@, compliance@,
admin@fintech.test). The password is SEED_USER_PASSWORD in .env:  grep SEED_USER_PASSWORD .env
Share only the customer login publicly; staff logins can see every demo customer's data.
DONE
