# Public deployment on Oracle Cloud Always Free

Runs the **entire** platform (10 Spring Boot services, Keycloak, Kafka, Postgres, Redis and both UIs)
on one free ARM VM, behind Caddy with automatic HTTPS. Cost: **$0** on the Always Free tier.

```
Internet ──443──▶ Caddy ──▶ app.<domain>   static customer UI     (web-frontend)
                        ├─▶ dash.<domain>  React dashboard        (dashboard)
                        ├─▶ api.<domain>   API gateway            (/actuator blocked)
                        └─▶ auth.<domain>  Keycloak               (/admin + master realm blocked)
Everything else (Postgres, Kafka, Redis, Grafana, Prometheus, Kafka UI, service ports) is bound
to the VM's loopback only.
```

**Why not Vercel/Render?** Their free tiers give 512 MB per service and no Kafka; this stack needs
about 5 GB. Oracle's Always Free tier gives 4 ARM cores and 24 GB RAM on one VM.

## 1. Create the VM (about 10 minutes, one time)

1. Sign up at <https://signup.oraclecloud.com/>. A card is required for identity verification; Always
   Free resources are not charged. Pick a **home region** close to you; it cannot be changed later.
2. **Compute → Instances → Create instance**
   - **Image:** Canonical Ubuntu 24.04 (the *aarch64* build is selected automatically for Ampere).
   - **Shape:** Ampere → `VM.Standard.A1.Flex`, **4 OCPU, 24 GB**. It must show *Always Free-eligible*.
     If you see "Out of host capacity", try another availability domain, or retry later.
   - **Networking:** create a new VCN with a public subnet and **assign a public IPv4 address**.
   - **SSH keys:** upload your public key (`~/.ssh/id_ed25519.pub`) or download the generated one.
   - **Advanced options → Management → cloud-init:** paste the contents of
     [`cloud-init.yaml`](cloud-init.yaml). It installs Docker, opens ports 80/443 in the host
     firewall and clones this repository to `/opt/fintech`.
3. **Open 80/443 in the cloud firewall:** Networking → Virtual cloud networks → your VCN → Security
   → Default security list → **Add ingress rules**: source `0.0.0.0/0`, TCP, destination port `80`;
   repeat for `443`. Without this, nothing reaches the VM even though the host firewall allows it.
4. Note the instance's **public IP address**.

## 2. Deploy (one command)

```bash
ssh ubuntu@<public-ip>
cd /opt/fintech
./deploy/oracle/deploy.sh            # uses <ip-with-dashes>.sslip.io, no domain purchase needed
```

The first run builds every image on the VM (10–20 minutes). It then prints the four URLs once they
answer over HTTPS. `deploy.sh`:

1. generates every secret into `.env` (mode 0600) with `scripts/bootstrap.sh`, including the random
   demo-user password;
2. renders `deploy/generated/` with `deploy/render.py`: the realm with public redirect URIs and web
   origins, **without** the dev-only `fintech-cli` password-grant client, plus the UI configs and CSPs;
3. starts `docker-compose.yml` with the shared `deploy/docker-compose.public.yml` and this target's
   `deploy/oracle/docker-compose.caddy.yml`.

The routes and their filtering (`deploy/edge.caddy`) are shared with the
[Codespaces target](../codespaces/README.md); only the ingress differs.

**Domain:** `sslip.io` names resolve to the IP embedded in them, so `app.203-0-113-7.sslip.io` →
`203.0.113.7` with no DNS setup. If Let's Encrypt refuses a certificate for sslip.io (shared rate
limits), use a free [DuckDNS](https://www.duckdns.org) name pointed at the VM and run
`./deploy/oracle/deploy.sh yourname.duckdns.org`. DuckDNS also resolves `app.yourname.duckdns.org`.

## 3. Demo logins

| Login | Role |
|---|---|
| `customer@fintech.test` | CUSTOMER: pay, cards, disputes |
| `agent@`, `analyst@`, `settlement@`, `auditor@`, `compliance@`, `admin@fintech.test` | staff roles |

All use the random `SEED_USER_PASSWORD`: `grep SEED_USER_PASSWORD /opt/fintech/.env`.
**Share only the customer login publicly.** Staff logins can see every demo customer's data. To
change the password, edit it in Keycloak through the SSH tunnel below.

## Operating it

| Task | Command (in `/opt/fintech`) |
|---|---|
| Update to the latest `main` | `git pull && ./deploy/oracle/deploy.sh` |
| Status | `docker compose -f docker-compose.yml -f deploy/docker-compose.public.yml -f deploy/oracle/docker-compose.caddy.yml ps` |
| Logs | same, with `logs -f --tail=100 api-gateway` instead of `ps` |
| Stop / start | `... stop` / `./deploy/oracle/deploy.sh` |
| Grafana, Keycloak admin, Kafka UI | `ssh -L 3000:localhost:3000 -L 8180:localhost:8180 -L 8081:localhost:8081 ubuntu@<ip>` then open `http://localhost:3000` etc. |
| Change domain | re-run `deploy.sh <new-domain>`, then follow its printed instructions to re-import the realm |

## Security posture of this deployment

- HTTPS everywhere (Let's Encrypt via Caddy), HSTS, strict CSP pointing only at the API and IdP.
- Browser sign-in is Authorization Code + PKCE with exact redirect URIs; the password grant does not
  exist in this realm.
- Only Caddy is internet-facing. Keycloak's admin console, admin API and master realm, plus the
  gateway's metrics, return 404 at the edge.
- `X-Forwarded-For` is trusted by the gateway only because Caddy overwrites it with the real
  client address, so per-IP rate limits and the registration throttle work.
- Money movement is synthetic. `PAYMENTS_SANDBOX_MODE=true` (the compose default) keeps the demo
  "Add funds" button working; set it to `false` in `.env` to disable self-funding.

**Known demo limitations:** Keycloak runs `start-dev` with its embedded database, Postgres has no
off-box backups, and everything runs on one VM. Oracle may reclaim Always Free instances that stay
idle (under 20% CPU) for 7 days; this stack's background load normally keeps it above that.
