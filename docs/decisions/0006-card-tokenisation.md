# ADR-0006: Card numbers tokenised at issue, never stored

- Status: accepted, implemented in Phase 4
- Date: 2026-09-26
- Phase: 4

## Context

card-service issues cards. A card needs an identifier that is unique, stable, indexable and safe to
hold in a database, and the only value that comes to mind is the card number. Storing it is what a
card vault does, and it is the decision that determines what this service is when it is breached.

The platform does not have a card scheme, an issuer or an acquiring bank, so it does not need a real
PAN. That is a genuine simplification and it is worth separating from the storage question, because
"we simulated the number anyway" is not an argument about storage. A simulated number and a stored
real one are the same shape of data with different consequences, and the architecture should not
depend on the simulation staying simulated.

Three options were available, and the middle one is the one most projects reach for by accident.

- **Store the PAN encrypted at rest**, the way [ADR-0005](0005-pii-encrypted-at-rest.md) treats
  personal data. Defensible under PCI DSS, which permits it, and it buys back the ability to show a
  cardholder their number again later.
- **Store the PAN in the clear**, behind access controls. What a prototype does, and what it keeps
  doing.
- **Tokenise at issue and store the token.** The number is minted, tokenised and dropped; the row
  holds a value that cannot be inverted to it.

The argument for the third is not that the second is forbidden. It is that the first two create a
permanent obligation to protect the number forever, and the requirement driving this design is the
opposite: a disclosure of this database should not be a disclosure of anyone's card.

There is a second question that looks like a detail and is not. The cardholder has to see their
number, at least once. Where does it live between "the cardholder reads it" and "somebody rings in
eighteen months asking for it"?

## Decision

Card numbers are **tokenised in card-service at the moment of issue and never persisted**. The only
representation of a card number in the `cards` table is a keyed one-way digest.

```
token       = HMAC-SHA-256(ikm = CARD_TOKENISATION_KEY, msg = PAN)
owner digest = HMAC-SHA-256(ikm = CARD_TOKENISATION_KEY, msg = subject || 0x1F)
```

Both are base64url-encoded without padding, and both use a purpose string as the message prefix
(`fintech.card.token.v1` and `fintech.card.owner.v1`) so that a digest of one thing can never be
presented as a digest of the other.

A separate 32-byte key, `CARD_TOKENISATION_KEY`, not a second use of `CUSTOMER_PII_MASTER_KEY`. The
two keys do opposite jobs and have opposite rotation stories. The PII master key protects data the
platform must read back; it can be rotated by re-encrypting, and losing it is catastrophic but
recoverable. The tokenisation key derives a value the platform must never invert; it is never used to
read anything, and rotating it invalidates every existing token rather than requiring a rewrite.

The card number is minted by card-service, shown to the cardholder exactly once, and never again.
The schema has no `pan`, no `cvv`, no track data, no PIN and no cardholder name column. That is the
control, rather than a policy about which columns may be populated: a schema that cannot hold a
number has no migration to write when somebody later wants to store one.

`last4` is kept, and is explicitly not a control. It is not sensitive authentication data, it cannot
authorise a payment, and it is what appears on every receipt a cardholder has ever seen.

The CVV is absent by a different route and the distinction is worth stating. PCI DSS permits storing
a PAN encrypted and forbids storing a CVV after authorisation, always. This platform picks the
stronger of the two options available to it and stores neither, so there is no window in which a used
card — whose CVV is therefore known to have existed — leaves a CVV behind. Since the platform never
authorises, the simpler rule holds everywhere.

## Consequences

**Good, and the point of the exercise.**

- A disclosure of the `cards` table is not a disclosure of anyone's card. It is a list of tokens,
  brands, last-four digits and lifecycle timestamps. The tokens cannot be inverted without
  `CARD_TOKENISATION_KEY`, which is not in the database, a backup, or this repository.
- A stolen `CARD_TOKENISATION_KEY` does not yield card numbers, because HMAC is one-way. It yields
  the ability to *test* a guessed number against a stored token, which is a different and much
  narrower capability: it becomes the key to protect, and it is the key to protect in the same way
  the PII master key is.
- Rotating the tokenisation key is a deploy. Old tokens stop resolving and cards have to be reissued;
  there is no re-encryption, because there is nothing to decrypt.
- The one-time number cannot be re-shown, because it was never stored. "I've lost my card number" is
  answered with a replacement, which is also the only answer a real scheme gives.
- `CardPersistenceTest` asserts the exact column list and asserts the digits appear nowhere in the
  row, so a future migration that adds an encrypted PAN column fails the build.

**Bad, and accepted.**

- **A cardholder who loses the number has to be reissued.** Real schemes solve this with a card
  number that can be regenerated or re-read. This platform cannot, by construction, and the
  product-visible consequence is a support path that has to be honest about it rather than a lookup.
- **Deterministic tokenisation means token equality is card-number equality.** Two rows with the
  same token are the same card number. This is what makes the unique constraint on `token` a real
  check rather than a formality, and it also means the token is a stable identifier for a card
  number to anyone holding the key. That is a considered trade for making the constraint possible at
  all: a random per-issue token would not detect a number being issued twice.
- **The owner digest is a keyed handle on a Keycloak subject**, so card-service holds a joinable
  mapping between cards and cardholders. ADR-0002 gives each service its own database and this is
  why the two services' subject digests are derived under different keys: they cannot be joined
  without both keys, so reading either database does not deanonymise the other.
- **Cards are not currently authorisation-capable.** With no stored PAN there is nothing to present
  to a scheme, so a real payment would need a vault that this design does not include. The honest
  statement is that Phase 4 issues and manages card *records*; authorising payments against them is
  a later phase with a different trust boundary, and this ADR is the constraint it has to work
  within rather than a claim that it is solved.
- **Tokens are not portable across environments.** A token minted in one environment cannot be
  recognised in another, because the key differs. There is no cross-environment token lookup, by
  design.

## Alternatives rejected

- **Store the PAN encrypted under a per-customer derived key**, as ADR-0005 does for PII. Permitted by
  PCI DSS and genuinely defensible. Rejected because it commits the platform to protecting a card
  number indefinitely, and because a per-customer key makes a card number unreadable to support the
  moment the customer's own key rotates — a property that is correct for personal data and wrong for
  a payment credential.
- **Delegate to a real tokenisation vault** (Basis Theory, Primecash or similar). The standard
  answer for a platform that has a scheme to talk to. Rejected as premature: there is no scheme, and
  adopting a vault's PAN storage model in order to not hold numbers would import the very liability
  being avoided. If a real integration appears, the token column is the seam to replace.
- **A per-issue random token** rather than a keyed digest. Removes the equality property above, and
  with it the ability to detect a number being issued twice. Not worth it while the platform mints
  its own numbers.
