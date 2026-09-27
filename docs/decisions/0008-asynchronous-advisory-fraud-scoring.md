# ADR-0008: Asynchronous advisory fraud scoring over digests, with the analyst queue as the enforcement point

- Status: accepted, implemented in Phase 6
- Date: 2026-09-27
- Phase: 6

## Context

Phase 6 adds fraud scoring to a platform that, as of Phase 5, authorises payments inline. That
raises four questions, and each one has an obvious answer that is wrong.

**Does the payment wait for the fraud engine?** The obvious answer is yes: score, then authorise.
That is how card authorisation actually works, and it is the right answer for an issuer. It is the
wrong answer here for three reasons that are structural rather than matters of taste. A synchronous
call makes every payment's latency a function of a rules engine, a Redis round trip and a database
write, so a fraud-service incident becomes a payments outage. It makes the engine's availability the
platform's availability, which is a very large blast radius to hand to the newest and least
battle-tested service in the system. And it puts a 100ms budget on the one component whose correct
behaviour is to do more work when it is worried, which is precisely when it will be slowest.

The alternative — score after the fact — is uncomfortable, because it means a payment can be
authorised and then declined later. The question is what "later" means for the platform, and that
question has to be answered now rather than discovered in production.

**What does the engine get to see?** The engine's whole value is recognising a customer, a card and a
device it has seen before. Recognising means comparing, and comparing needs an identifier. The
obvious identifier is the one transaction-service already holds: a card token, a device identifier, a
customer id, an IP address. Passing any of those makes the engine's database a second copy of the
platform's most sensitive data, held by a service with a staff-facing API.

**Who decides?** A score is advice and a human is accountable, but a platform with no analyst queue
is a platform that either auto-declines or auto-approves. Both are worse than the status quo:
auto-approval is not fraud control, and auto-decline is a denial-of-service surface that anyone with
a stolen card can aim at a real customer.

**How much can one person change?** Manual adjustment is the analyst's real lever, and an unbounded
one is a single keystroke away from permanently declining an arbitrary payment with no second pair
of eyes.

## Decision

**Fraud scoring is asynchronous and advisory. A payment is authorised on Phase 5's rules, and a
later decision is advisory until step-down enforcement exists.** transaction-service publishes
`transaction-created`; fraud-service consumes it, scores, persists one decision, raises an alert if
required, and publishes `fraud-analysis-completed`. Nothing on the payment's critical path waits for
any of that.

This is only acceptable because the advisory output has somewhere to go. Three mechanisms carry it:

- **An analyst queue.** Alerts are the product of the advisory path, not a side effect. They are
  claimable, and a claim is exclusive.
- **A step-down recommendation, recorded but not enforced.** `DecisionResponse` carries
  `stepDownRecommended` and every decision stores it, so the day enforcement is switched on the
  history already says which payments would have been stepped down. Deciding the semantics now and
  implementing the switch in Phase 12 means the data to justify enforcement has been accumulating for
  six months instead of starting on the day it is needed.
- **An analytics window.** A dashboard of decline rate, band mix and top-risk merchants, so a shift
  sees the pattern rather than one payment at a time.

**The engine receives digests and never raw identifiers.** transaction-service computes
purpose-separated HMACs — one per purpose, so a card digest cannot be correlated with a device
digest by anyone holding one of them — and passes them in the event. fraud-service stores the digests
and has no column for what they stand for. A raw card token, PAN, device identifier or IP address
never crosses into the service, so its database is not a place a breach becomes a card-fingerprint
breach.

**The privacy cost of that choice is accepted explicitly.** A shared, purpose-prefixed key is what
makes a digest joinable across two databases that share no rows, and joinability is the engine's
entire function. The cost is that these two services are the only two that can reverse a digest, and
that either one's database plus the key is enough to confirm a guessed subject. The mitigations are
that the key is environment-injected and never stored (`FraudDigestKey` refuses to start without
it), that the two services are the only holders, and that Phase 15's per-service keys will break the
join deliberately when mTLS makes service-to-service trust unnecessary. Recorded in
`docs/security.md` rather than left implicit, because it is the kind of trade that gets forgotten
and then re-litigated by whoever next needs a join.

**The engine is a deterministic scoring model, not a model that learned something.** Seven named
rules contribute fixed points, the total is capped at 100, and the score maps to a band through
published boundaries. Every decision stores the rules that fired, the facts they read, and the score
before and after any human override. A ruleset is auditable in a way a trained model is not: when a
customer asks why their payment was declined, the answer is a sentence naming a rule, and when a
regulator asks, the same sentence is the answer.

**A rule's band is stored, not derived on read.** The boundaries are this service's published
contract, so a decision read after they move must still say what it said at the time.

**Velocity fails open, and says so.** If Redis is unavailable, velocity rules are skipped rather
than treated as "no history", because the alternative silently disables the only rules with memory
during the incident when a burst of fraud would arrive. The decision records
`velocityAvailable=false` and the degraded path is visible in analytics, so a window of missing
velocity is an operator-visible fact rather than an invisible one.

**Fraud never receives an amount it has to convert.** Currency amounts are passed in minor units with
their currency and are compared within a currency; there is no FX table in this service.

**Reading and acting are different permissions, and the roles are separate ones.**
`FraudAuthorization` holds both sets and is called explicitly — `@PreAuthorize` is not trusted, and
neither is a gateway path predicate. `FRAUD_ANALYST`, `COMPLIANCE_OFFICER`, `PLATFORM_ADMIN` and
`AUDITOR` may read; only `FRAUD_ANALYST` and `PLATFORM_ADMIN` may act.

`AUDITOR` reading but not acting is deliberate and is the decision most likely to look like a bug:
an auditor who could claim an alert would be editing the evidence they audit. `COMPLIANCE_OFFICER`
has the same split. `FRAUD_ANALYST` is a separate role from `COMPLIANCE_OFFICER` rather than an
extension of it, so "work the queue" and "supervise the queue" are separately grantable.

**One alert per decision, keyed by payment.** A separate alert identity would allow two open alerts
for one payment, and two analysts each resolving "the same" suspicious payment is how one of them
resolves it without reading it. The database enforces the alert's state machine, so an illegal
transition is not writable.

**The human override is capped at 75, and a second approver is not faked.** Above the cap a decision
needs a second approver, and this deployment has none, so the answer is 422 rather than a queue that
nobody works.

## Consequences

- A payment can be authorised and later declined. This is visible to operators, is recorded per
  decision, and is the reason the analyst queue and the analytics window are part of this phase
  rather than a follow-up. Enforcement is a switch, not a redesign.
- Fraud-service is on no payment's critical path. It can be down, slow, or deploying without
  payments noticing; what degrades is fraud coverage, and `velocityAvailable` plus the degraded
  metrics are how that is seen.
- Analytics are eventually consistent by construction. A dashboard read immediately after a payment
  will not include it, and the window is echoed in the response so a figure is never read out of the
  wrong period.
- Digests are not reversible, so a human cannot read a customer's identity off an alert. Analysts
  work from digests. Phase 9's regulatory reporting will need a deliberate, audited join to customer
  data, and this ADR is where that cost was accepted rather than discovered.
- The scoring model is only as good as its seven rules. It is a floor, not a ceiling, and the reasons
  stored per decision are what a future model would be trained and evaluated against.
- A shared digest key is a real, accepted risk with a known expiry: Phase 15.

## Rejected

**Synchronous scoring before authorisation.** Rejected on latency, availability coupling, and the
incentive it creates to make a worried engine fast. Revisit if and when step-down enforcement needs
a pre-authorisation signal, at which point the answer is a separate, deliberately small synchronous
check — not this engine in the critical path.

**Passing the card token, device identifier or IP to the engine.** Rejected: it would make the
fraud database a copy of the platform's most sensitive data, behind a staff-facing API, for an
identifier the engine only ever compares.

**Per-service digest keys from the start.** Rejected because the two services could not join a
digest and the engine could not recognise anything. The break is scheduled for Phase 15, where mTLS
makes it safe.

**A trained model scoring the payments.** Rejected for this phase on explainability: a customer's
right to an answer, and a regulator's right to the same, both need a rule and a number rather than a
weight. The stored reasons and facts are the training set when a model does arrive.

**Auto-declining above the decline threshold.** Rejected: it is a denial-of-service surface aimed at
real customers by anyone holding a stolen card, and it has no queue, so nothing is ever looked at.

**Auto-approving and recording only.** Rejected: that is not fraud control, and it would have been
the cheaper option.

**One broad fraud-operations role covering read and act.** Rejected because supervision and work are
different jobs with different failure modes, and collapsing them means the only way to supervise an
analyst is to give them the analyst's powers.

**An unbounded manual score.** Rejected: it is the largest single lever in the system and it needs a
bound. The cap is low enough that overriding it requires a capability this deployment does not have.
