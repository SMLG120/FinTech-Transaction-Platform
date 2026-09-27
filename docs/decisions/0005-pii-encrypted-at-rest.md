# ADR-0005: Personal data encrypted at rest with a per-customer key

- Status: accepted, implemented in Phase 3
- Date: 2026-09-26
- Phase: 3

## Context

customer-service holds names, dates of birth, email addresses, phone numbers, postal addresses and
identity-document references. That is the personal data the platform exists to protect, and it is
the data a breach of this one service would be about.

Encrypting it raises the question the rest of this ADR is about: with what key?

The obvious answers are the three that get chosen by accident:

- **One platform-wide data key.** Simple, and a single compromised key decrypts every customer in the
  platform. It also makes rotation a flag day: changing the key means re-encrypting the whole table
  before anything can be read, so in practice it never gets rotated at all.
- **The identity-signing key from [ADR-0004](0004-jwt-verified-at-the-gateway.md).** It is already in
  every service's configuration. Reusing it means one leaked key does two jobs, and rotates on the
  gateway's schedule rather than the data's.
- **The master key in the clear, in the configuration.** No derivation, no per-customer isolation,
  and a configuration backup is a plaintext copy of the personal data.

Two requirements push against each other. Compromise of stored data must not yield plaintext, which
argues for per-record keys. Rotation must be possible without a full-table rewrite, which argues for
a key version stamped on each record.

## Decision

PII is encrypted with **AES-256-GCM**, under a key derived **per customer** with
**HKDF-SHA-256** from a single 32-byte platform master key and the customer's own opaque subject.

```
PII key for a customer = HKDF-SHA-256(
    ikm  = platform master key,
    salt = the customer's subject,          <- this is what makes the key per-customer
    info = "fintech.customer.pii.v1",
    len  = 32
)
```

The two lookups that must *not* vary per customer use a different salt, because they are queried
without knowing who the caller is:

```
blind index key     = HKDF-SHA-256(ikm = master key, salt = master key,
                                   info = "fintech.customer.pii.bidx.v1")
subject digest key  = HKDF-SHA-256(ikm = master key, salt = master key,
                                   info = "fintech.customer.pii.subject.v1")
```

A blind index is salted by the master key rather than by the subject because the whole point of it
is to find a row from a field value alone, with no subject in hand. Salting it by the subject would
make the index uncomputable.

The payload is `keyVersion(1) || iv(12) || ciphertext+tag`, base64url-encoded without padding. The
IV is random per encryption and never reused, which is the one mistake that would make GCM worthless.

A separate HMAC-SHA-256 blind index, over its own derived key (see below),
supports "find the customer with this email" without decrypting anything. Domain separation between
the two derivations is not decorative: one key doing two jobs invites using the output of one where
the other is expected.

**Rotation is a deploy, not a migration.** Because the version byte travels with the ciphertext, a
service can be rolled out with a new key and a bumped version. Old records fail closed with an error
that names the version mismatch, and a background re-encryption (not yet built) moves rows forward.
`CUSTOMER_PII_MASTER_KEY` has no default, and `PiiProperties` validates it in its constructor, so a
service without a usable key fails at startup rather than storing something it cannot read.

**Erasure is enforced by the data, not by the application.** `Customer.erase()` nulls the
ciphertext columns, drops the email and phone blind indexes, and retains the `subject_digest` — an
HMAC over a separately derived key, so it is not even a plain hash of the subject. The digest is not
reversible to the subject, is not a blind index for any field, and exists so that a customer who has
deleted their profile can still be told
"yes, that is gone" instead of being told they never had one. `PiiCipher` refuses to encrypt or
decrypt a record with no subject, so a tombstone cannot be resurrected by an ordinary write.

## Consequences

**Good.**

- A stolen database dump, a backup, or a `SELECT *` from a replica is not personal data. It is
  ciphertext keyed by a value that is not in the same row.
- Compromise of the master key is still total, and is bounded by the master key living in one
  environment variable rather than in the data. It is the key to protect, and it is not in the dump.
- Rotation is bounded by a deploy, not by a rewrite of the table.
- Decryption failures are uniform. A caller learns that a record could not be read, never why: a
  tampered payload, a rotated key and a wrong customer all produce the same message, because a
  message that distinguished them would tell an attacker which of the three they managed.

**Bad, and accepted.**

- **The master key is a single point of total compromise.** Per-customer derivation limits blast
  radius only in the sense that it limits a *record-level* breach. Documented in `security.md` as
  the key whose loss ends confidentiality platform-wide.
- **Blind indexes leak equality.** `findByEmailBlindIndex` answers "does this email exist" to anyone
  who can run it. That is the deliberate trade for making lookup possible at all; a hash of an
  email is not reversible, but it is a stable identifier for that email.
- **Blind indexes must be dropped on erasure**, and are. That is what makes erasure irreversible:
  a customer whose profile is deleted can no longer be found by the email they gave, even by us.
- **GCM makes random access impossible.** Every field is its own ciphertext, so a query cannot
  filter, sort or aggregate over personal data. Everything reportable has to be computed in the
  application after decryption, which is why masking is applied on the way out.
- **Erasure is a tombstone, not a `DELETE`.** The row and its `kyc_checks` history are kept so an
  erasure request can be evidenced. The `subject_digest` outlives the personal data by design.
