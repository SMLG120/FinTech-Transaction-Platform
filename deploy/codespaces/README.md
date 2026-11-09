# Public demo on GitHub Codespaces (free, no card)

Runs the **entire** platform (10 Spring Boot services, Keycloak, Kafka, Postgres, Redis and both UIs)
in a GitHub Codespace. GitHub's port forwarding gives each public port its own HTTPS URL.

```
Browser ──HTTPS──▶ GitHub port forwarding ──▶ Caddy :4001  static customer UI   (web-frontend)
  https://<codespace>-<port>.app.github.dev        ├─ :4002  React dashboard     (dashboard)
                                                   ├─ :4080  API gateway         (/actuator blocked)
                                                   └─ :4180  Keycloak            (/admin + master realm blocked)
Everything else (Postgres, Kafka, Redis, Grafana, Keycloak admin, service ports) stays inside the
codespace and is not forwarded.
```

**Cost:** $0. A free GitHub account includes 120 core-hours and 15 GB of storage a month. This
uses the 4-core machine, so that is **about 30 running hours a month**. A stopped codespace uses
no core-hours (only storage).

**The catch:** it is not always on. The URLs answer only while the codespace runs, and it stops
after the idle timeout (default 30 minutes without editor activity, up to 4 hours). For an
interview, start it about 10 minutes before and share the links. The URLs stay the same every
time you restart the same codespace.

## 1. Create the codespace (once)

```bash
gh auth refresh -h github.com -s codespace     # once: lets gh manage codespaces
gh codespace create -R SMLG120/FinTech-Transaction-Platform -b main \
  -m standardLinux32gb --idle-timeout 4h --display-name fintech-demo
```

`standardLinux32gb` is the 4-core, 16 GB machine; the 2-core one is too small for the full stack. Or
use the website: repository page → **Code → Codespaces → ⋯ → New with options**, pick
**4-core**.

## 2. Start the stack (first time: 15-25 minutes)

```bash
gh codespace ssh -c <codespace-name> -- 'cd /workspaces/FinTech-Transaction-Platform && ./deploy/codespaces/start.sh'
```

Or open the codespace in the browser and run `./deploy/codespaces/start.sh` in its terminal.
`start.sh`:

1. generates every secret into `.env` (mode 0600) with `scripts/bootstrap.sh`;
2. renders `deploy/generated/` with `deploy/render.py` for this codespace's URLs: the realm with
   exact redirect URIs and **without** the dev-only `fintech-cli` password-grant client, plus the
   UI configs and CSPs;
3. starts `docker-compose.yml` + `deploy/docker-compose.public.yml` +
   `deploy/codespaces/docker-compose.caddy.yml`, waits until all four routes answer;
4. makes ports 4001, 4002, 4080 and 4180 **public**. If it cannot do that from inside the codespace,
   it prints the one command to run on your own machine:
   `gh codespace ports visibility 4001:public 4002:public 4080:public 4180:public -c <codespace-name>`.

It prints the four URLs at the end. They look like
`https://<codespace-name>-4001.app.github.dev`.

## 3. Before each demo

```bash
gh codespace list                                  # find the name
gh codespace ssh -c <codespace-name> -- true      # starts it; the containers restart by themselves
```

Wait about 3 minutes for Keycloak and the services, then open the customer URL.
`gh codespace stop -c <codespace-name>` afterwards saves core-hours.

## Demo logins

| Login | Role |
|---|---|
| `customer@fintech.test` | CUSTOMER: pay, cards, disputes |
| `agent@`, `analyst@`, `settlement@`, `auditor@`, `compliance@`, `admin@fintech.test` | staff roles |

All use the random `SEED_USER_PASSWORD` in the codespace's `.env`:
`grep SEED_USER_PASSWORD .env`. **Share only the customer login.** Staff logins can see every
demo customer's data.

## Security posture

Same edge as the [Oracle target](../oracle/README.md) (`deploy/edge.caddy`): HTTPS (terminated by
GitHub), strict CSP, Authorization Code + PKCE only, Keycloak admin/master realm and gateway metrics
return 404 publicly. Differences:

- **Per-IP rate limits see one address.** Caddy only sees GitHub's forwarding proxy, so every
  visitor shares one rate-limit bucket. Fine for a demo; a burst from one visitor slows everyone.
- **Public ports are public.** Anyone with a URL can reach the four routes while the codespace runs.
  Nothing else is forwarded.
- Keycloak runs `start-dev` with its embedded database, as in the Oracle target. Deleting the
  codespace deletes all data; stopping it keeps everything.
