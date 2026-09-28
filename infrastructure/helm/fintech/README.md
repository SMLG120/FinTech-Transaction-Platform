# FinTech Helm chart

Deploys the ten platform services to Kubernetes. External dependencies are
**not** in this chart — PostgreSQL, Redis, Kafka, Keycloak (or an external
IdP), and Prometheus must already exist in the cluster. The chart deploys
the platform, not the world.

## Usage

```bash
# Validate only (no cluster needed):
make helm-template

# Install (needs a cluster plus a secrets file that is never committed):
helm install fintech infrastructure/helm/fintech \
  -f /run/secrets/fintech-secrets.yaml
```

The secrets file gives every value under `services.*.secrets` plus
`global.identitySigningKey` and `global.redisPassword`. Rendering without
them fails loudly naming the missing value — the Helm equivalent of the
Compose `${VAR:?...}` rule. A service that starts with an empty password is
worse than a release that refuses to render.

## Layout

- `templates/deployment.yaml` — one Deployment per service from a single
  loop. The services differ only in configuration, never in shape; a
  difference that cannot be expressed in values belongs in a service's own
  chart, not in a tenth copy of this file.
- `templates/service.yaml` — ClusterIP for everything. No ingress here: an
  ingress with a placeholder hostname and no certificate looks finished and
  is not.
- `templates/secret.yaml` — one Secret per service with database and
  service keys, plus one shared Secret for the interim shared values (redis
  password, identity HMAC key). Database passwords are per service — the
  shared local Compose password has no counterpart here.
- `templates/NOTES.txt` — post-install checklist (migrations job, access,
  key rotation).

## Production posture baked in

- `SPRING_FLYWAY_ENABLED=false` on every service with a database:
  migrations are a pre-deploy job, never the serving pods (see
  docs/deployment.md).
- Probes on the same readiness/liveness endpoints the Docker healthcheck
  curls, with JVM-aware initial delays.
- Non-root user, `RuntimeDefault` seccomp, 30s termination grace for
  `server.shutdown=graceful`.
- Resource requests/limits are starting points — tune from the Grafana
  heap/GC panels, not from guesses.
- `templates/networkpolicy.yaml` — default-deny ingress plus explicit
  allows: the gateway from anywhere, each service from the gateway, and the
  two synchronous hops (card→customer, dispute→transaction). Metrics
  scraping from the labelled monitoring namespace. Egress deliberately open
  (see the template header for why).
- `templates/serviceaccount.yaml` — one account per service, carrying no
  RBAC today: identity anchors for the mTLS migration, not authorisation.
- `templates/pki.yaml` — behind `pki.enabled` (off by default): a
  self-signed development issuer plus one Certificate per service (DNS SANs
  for every dialled name, `spiffe://fintech/<service>` URI SAN). Render for
  review with `--set pki.enabled=true`; the cluster-free gate excludes it
  because cert-manager CRDs fail client-side validation. Full design and the
  Java migration sequence: ADR-0016.

## Verification

`make helm-template` runs `helm lint`, a full render with throwaway
secrets, and schema validation. `kubectl apply --dry-run` additionally
needs a cluster, so it is documented for cluster use rather than gated.
