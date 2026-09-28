# ADR-0016: mTLS migration with a provisioned PKI and a dual-trust period

- Status: accepted; PKI provisioned, Java migration sequenced (not yet implemented)
- Date: 2026-09-28
- Phase: 15

## Context

The interim from ADR-0004 has held for eleven phases: the gateway signs the
forwarded identity with one HMAC key that every service holds, so any
compromised service can mint an identity for any user. NetworkPolicies (this
phase) close the reachability half — only the gateway and the two declared
sync hops can arrive at a pod — but a policy is a firewall, not an identity:
it says where a connection came from, never who holds the other end. The
remaining step is mutual TLS with per-service certificates, at which point a
compromised service holds credentials for itself and nothing else.

The PKI half is provisioned here: a self-signed development issuer plus one
Certificate per service, each carrying the DNS names the platform dials and
a `spiffe://fintech/<service>` URI SAN bound to that service's account. The
Java half — terminating TLS, presenting and verifying client certificates,
and retiring the shared key — is designed below and sequenced after it, for
one reason: TLS without a live peer to shake hands with is untestable
configuration, and this repository does not ship untestable security
changes. The manifests render behind `pki.enabled` precisely so the
cluster-free gate stays green.

## Decision

**Dual-trust migration, in this order:**

1. **Serve TLS everywhere, verify nothing new yet.** Each service mounts its
   certificate via Spring Boot SSL bundles (`spring.ssl.bundle.pem`) and
   terminates TLS; the gateway and the two sync-hop clients trust the dev CA
   bundle. The identity filter is untouched: HMAC verification continues, so
   a misconfigured certificate breaks nothing — connections still establish
   and identities still verify the old way. This step is observable in
   metrics (handshake failures) before it is load-bearing.
2. **Require and verify client certificates, alongside the HMAC.** Services
   set client-auth to need and the identity filter additionally asserts that
   the presented certificate's URI SAN matches the expected caller identity
   for the path (gateway everywhere; customer-service also accepts
   card-service; transaction-service also accepts dispute-service — the same
   two hops the NetworkPolicies name). A mismatch is a 401 with the reason
   in the log, never in the response, like every other identity refusal.
3. **Retire the shared key.** Once every service verifies client
   certificates, `INTERNAL_IDENTITY_SIGNING_KEY` stops being distributed:
   new deployments omit it, the filter's key-absent branch (which already
   refuses everything) becomes the only branch, and the codec stays for
   tests. Rotation becomes per-service certificate renewal (90-day,
   renewed from 15 days out) instead of a coordinated ten-service restart.

**Production CA is a swap of one object.** The Certificates reference the
issuer by name, so replacing the self-signed development issuer with a real
CA (private PKI or ACME) touches the `ClusterIssuer`, not the ten
certificates. Short-lived certificates follow the same path.

**Explicitly not here:** Kafka, PostgreSQL and Redis TLS (separate
listeners with their own rotation story — an outage-shaped change, not a
deploy-shaped one), ingress certificates (no ingress object exists to
attach them to), and egress policy (recorded as open in the NetworkPolicy
template header).

## Consequences

- The shared key's two weaknesses (any-holder forgery, coordinated
  rotation) get a dated retirement plan instead of another restatement.
- Per-service ServiceAccounts exist before anything depends on them; the
  URI SANs name them, so identity and account cannot drift apart later.
- The Java migration is net-new code in eleven modules when sequenced —
  estimated after this phase, with a live cluster to shake hands against.

## Rejected

- **Flag-day cutover.** Turning off HMAC verification the same day client
  certificates turn on couples two failure modes with no fallback between
  them. Dual-trust costs a transition period and buys a rollback that is a
  configuration change, not a redeploy.
- **One wildcard certificate.** Cheaper to provision and worthless for the
  purpose: any holder could present it as any service, reproducing the
  shared-key weakness in ASN.1.
- **Implementing the Java half now, unverified.** A TLS configuration that
  has never completed a handshake is a promise, and promises about
  transport security are how outages and bypasses both happen. It waits for
  a peer.
