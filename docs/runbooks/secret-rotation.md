# Runbook: secret rotation

Which secrets can be rotated, in what order, and which cannot be rotated at
all. Read the whole entry for a secret before touching it: half-rotated
credentials (database changed, application not restarted) read as an outage.

## Rotatable with a restart

**Service database passwords.** One role per service; rotate singly:

```bash
# 1. Set the new password on the role (needs the superuser from .env):
docker compose exec -T postgres psql -U "$POSTGRES_SUPERUSER" -d postgres \
  -c "ALTER ROLE fintech_cards WITH PASSWORD 'new-value';"
# 2. Put the same value in .env as SERVICE_DB_PASSWORD (local) or the
#    service's Secret (Kubernetes: fintech-card-service).
# 3. Recreate exactly that service:
docker compose up -d card-service
```

One service at a time, never all nine: a shared mistake repeated nine
times is nine outages. Verify with that service's lifecycle script before
moving on. `scripts/check-secret-isolation.sh` still applies afterwards —
the new value must land in exactly one holder.

**Redis password.** Change it in Redis and in every holder (`REDIS_PASSWORD`
is shared), then recreate the stack. Lettuce reconnects on its own, but a
service that boots between the two changes fails its first commands, not
its healthcheck — watch for command errors, not restarts. settlement-service
holds no Redis configuration and is unaffected; that exclusion is load-
bearing here too.

**Keycloak admin and Grafana passwords.** Change at the product, update
`.env`, restart that container. Nothing else holds them
(`check-secret-isolation.sh` asserts exactly this).

## Rotatable with coordination

**`INTERNAL_IDENTITY_SIGNING_KEY`.** Every service plus the gateway must
change together: mixed keys mean the gateway signs what services reject —
a total, silent 401 outage. Procedure is stop-the-world locally
(`make down`, new key in `.env`, `make up`) and a coordinated rollout in
production. This is the rotation that mTLS retires (ADR-0016): per-service
certificates renew independently instead.

## Effectively unrotatable — read before acting

**`CUSTOMER_PII_MASTER_KEY`.** There is no rotation path that does not
require the old key: every stored value decrypts only under it, and losing
it is unrecoverable (ADR-0005). Rotation would need a re-encryption job
that reads with the old key version and writes with the new — the key
version already travels with each record, but the job does not exist.
Until it does: protect the value, back it up offline and separately from
database backups, and never put it through a channel that logs.

**`SUBJECT_DIGEST_KEY`.** Rotation breaks the join it exists to provide:
stored digests in two databases were computed under the old key and cannot
be recomputed (the services never hold the subjects). A rotation needs a
dual-key transition period accepting both digests, which does not exist
yet. Same posture as the PII key: protect, do not rotate.

**`CARD_TOKENISATION_KEY`.** Rotation invalidates every token in the
`cards` table at once — every card stops working with no recovery except
reissue. Rotate only in a maintenance window with a reissue plan, never as
a routine operation.

## After any rotation

1. `scripts/check-secret-isolation.sh` — the new value went where it
   should and nowhere else.
2. The affected lifecycle script (`verify-card-lifecycle.sh`,
   `verify-payment-lifecycle.sh`, …) — authentication and data access
   actually work, not just the healthchecks.
3. Delete the old value from shell history and any scratch files
   (`history -d`, shred `/tmp/kc-body.json`-style leftovers).
