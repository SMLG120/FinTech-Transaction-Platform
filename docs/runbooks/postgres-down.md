# Runbook: postgres down or full

## Symptoms

- Everything 5xx at once (every service shares one Postgres instance
  locally), or `ConnectionPoolExhaustion` (pending connections > 0) on
  several services simultaneously.
- `FilesystemFillingUp` is the warning that precedes the disk-full variant
  (Prometheus retention is 15 days; unexpected cardinality growth shows up
  there first).
- Logs: `FATAL: terminating connection`, `An I/O error occurred while
  sending to the backend`, or `remaining connection slots are reserved`.

## Triage

**1. Process or disk?**

```bash
docker compose ps postgres
docker exec fintech-postgres pg_isready -d postgres
docker system df
```

Not running → start it (`docker compose start postgres`) and skip to
recovery. Running but refusing → disk or slots: `pg_isready` distinguishes
a down postmaster (no response) from a full one (response, then errors).

**2. If full: what grew?** Usual suspects in order: Prometheus retention
(check the alert first — it names `/prometheus`), Postgres WAL on a busy
day, container json logs without rotation (this repo caps them at
10m×3 per container in Compose, so look elsewhere first).

**3. If slots exhausted:** one shared instance means one service's leak
starves nine others. `log_min_duration_statement` (500ms) and
`log_lock_waits` name the slow and the blocked; `statement_timeout` (30s)
and `idle_in_transaction_session_timeout` (30s) bound them. Find the waiter
in the Postgres log before raising any pool.

## Recovery

```bash
docker compose start postgres   # if down
./scripts/wait-for-http.sh http://localhost:8080/actuator/health 120
./scripts/verify-payment-lifecycle.sh
```

Data persists in the `postgres-data` volume across restarts (`make down`
keeps it). Only `make clean-all` deletes it — never run that in an
incident: it destroys the evidence and the data together.

Then confirm each service reconnected: Hikari recovers pools on its own,
but a service that started *while* Postgres was down may have failed
Flyway validation at boot and exited — `docker compose ps` shows who needs
a `docker compose up -d` rather than patience.

## Stop conditions

- If the volume is gone or corrupted, stop: restore from backup (see the
  backup procedure), do not invent rows. Per-database dumps exist for
  exactly this reason.
- If `postgres-data` was deleted with `clean-all`, every service boots
  empty and healthy-looking: re-run the full `make verify-live`, because
  "up" and "correct" diverged the moment the data did.
