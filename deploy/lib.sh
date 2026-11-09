# Shared steps for the deploy targets. Sourced by deploy/oracle/deploy.sh and
# deploy/codespaces/start.sh after they cd to the repository root and set COMPOSE (an array).
# Secrets are generated once by scripts/bootstrap.sh into .env (mode 0600) and never printed.

step() { printf '\n\033[1m%s\033[0m\n' "$1"; }
fail() { printf '\033[31mERROR\033[0m %s\n' "$1" >&2; exit 1; }

require_docker() {
  command -v docker >/dev/null || fail "docker is not installed"
  docker compose version >/dev/null 2>&1 || fail "the docker compose plugin is not installed"
}

ensure_secrets() {
  step "Secrets"
  # Creates .env on the first run; later runs only backfill keys added since. Idempotent.
  ./scripts/bootstrap.sh >/dev/null
  chmod 600 .env
  echo "secrets present in .env (not shown)"
}

# set_env KEY VALUE: insert or replace one non-secret line in .env. Prints the previous value.
set_env() {
  local key="$1" value="$2" previous=""
  [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] || fail "refusing a multi-line value for ${key}"
  if grep -q "^${key}=" .env; then
    previous="$(grep "^${key}=" .env | cut -d= -f2-)"
    # .env is rewritten through a temp file so its 0600 mode is kept.
    awk -v k="$key" -v v="$value" 'BEGIN{FS=OFS="="} $1==k{print k"="v; next} {print}' .env > .env.tmp
    cat .env.tmp > .env && rm -f .env.tmp
  else
    printf '%s=%s\n' "$key" "$value" >> .env
  fi
  printf '%s' "$previous"
}

# set_public_urls APP DASH API AUTH: records the public origins in .env (so plain `docker compose`
# commands resolve them later) and renders deploy/generated/ from them.
set_public_urls() {
  local app="$1" dash="$2" api="$3" auth="$4" previous_auth
  step "Public configuration"
  grep -q '^# Public origins' .env || printf '\n# Public origins (written by deploy/lib.sh)\n' >> .env
  set_env PUBLIC_APP_URL "$app" >/dev/null
  set_env PUBLIC_DASH_URL "$dash" >/dev/null
  set_env PUBLIC_API_URL "$api" >/dev/null
  previous_auth="$(set_env PUBLIC_AUTH_URL "$auth")"
  if [[ -n "$previous_auth" && "$previous_auth" != "$auth" ]]; then
    # Keycloak imports the realm only when it does not exist yet, so new redirect URIs need a
    # fresh Keycloak volume. That deletes self-registered users, so it is not done automatically.
    printf 'Public URLs changed (%s -> %s). Keycloak keeps the old realm until its volume is removed:\n' "$previous_auth" "$auth"
    printf '  %s rm -sf keycloak && docker volume rm secure-fintech-platform_keycloak-data\n' "${COMPOSE[*]}"
  fi
  python3 deploy/render.py --app "$app" --dash "$dash" --api "$api" --auth "$auth"
}

start_stack() {
  step "Building and starting the stack (the first run takes 10-20 minutes)"
  "${COMPOSE[@]}" up -d --build --remove-orphans
}

# wait_for URL EXPECTED_STATUS [EXTRA_CURL_ARGS...]
wait_for() {
  local url="$1" expected="$2" deadline=$(( $(date +%s) + 900 ))
  shift 2
  until [[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$@" "$url")" == "$expected" ]]; do
    (( $(date +%s) < deadline )) || fail "${url} did not answer ${expected}; check: ${COMPOSE[*]} logs --tail=100 caddy keycloak api-gateway"
    sleep 10
  done
  printf '  ok   %s -> %s\n' "$url" "$expected"
}

print_demo_logins() {
  cat <<'DONE'

Demo logins: customer@fintech.test (and agent@, auditor@, analyst@, settlement@, compliance@,
admin@fintech.test). The password is SEED_USER_PASSWORD in .env:  grep SEED_USER_PASSWORD .env
Share only the customer login publicly; staff logins can see every demo customer's data.
DONE
}
