# ADR-0010: Notifications as an exactly-once delivery log with a support retry

- Status: accepted, implemented in Phase 8
- Date: 2026-09-27
- Phase: 8

## Context

Phases 5 through 7 gave the platform money that moves, a score that follows it, and a period
statement that cannot be edited once given out. None of those tells anybody. A payment settles and
the customer learns nothing; a fraud engine declines and the customer learns it from a failed
checkout; a period breaks and no operator is woken. Phase 8 is the service that turns facts the
platform already recorded into messages somebody reads.

Three questions have to be answered, and each has a convenient wrong answer.

**Does notification read the producing services?** The obvious design is a notification API that,
on demand, queries transaction-service for a payment and renders a message. It is synchronous,
consistent, and wrong for the same structural reason settlement refused the ledger scan in ADR-0009:
it puts the delivery path on the availability of every service it reads from, and a support agent
asking "was the customer told" during a transaction-service outage gets no answer at exactly the
moment the question matters. So notification-service consumes `transaction-authorized`,
`transaction-declined`, `transaction-settled`, `transaction-reversed`, `fraud-analysis-completed`,
`settlement-cycle-finalised` and `settlement-break-detected`, and builds its own delivery log. It
holds no copy of any ledger and reads no table it does not own.

**What happens when the send fails?** The wrong answer is to fail the event: throw from the
consumer, let the broker redeliver, and eventually park a well-formed payment event on the
dead-letter topic because an SMS gateway blipped. The event was fine; the channel was not. So a
send failure is caught, the notification stays `FAILED` with the attempt counted and the next
attempt scheduled, and the method still returns true — the event is consumed. Kafka redelivery is
for events that could not be *read*; the scheduler is for messages that could not be *sent*. The
dead-letter policy still exists, for the poison event that cannot be parsed at all, and it is the
same bounded-retry-then-park policy fraud-service and settlement-service carry, because Spring
Kafka's default — retry, log, commit, drop — reads as healthy while losing the message.

**Who may see the log?** The convenient answer is per-customer scoping: a customer reads their own
notifications. It is unenforceable here and therefore worse than no rule. This service correlates
on an owner digest and holds no mapping from a token to that digest, so "their own" is a lookup it
cannot perform — a rule promising it would be enforced by nothing, and a customer who can list
messages by transaction id learns the platform's fraud and settlement wording. So there is no
customer route by design, which is a stronger property than a rule that could be misconfigured. The
readers are `SUPPORT_AGENT` and `PLATFORM_ADMIN`, and the retry is the same set: reading a failed
delivery and re-sending it are one support job, and a role that may see a failure but may not fix
it is a queue that fills and nobody empties.

## Decision

**A consumed fact becomes exactly one message, claimed with an insert.** The claim is an
`INSERT ... ON CONFLICT DO NOTHING` in the same transaction that creates the notification, so there
is no window in which the event is marked processed and the message is lost, and no window in
which two consumers both send. A redelivery finds the claim taken and returns, and the customer is
told once however many times the broker hands the event over.

**An approved payment notifies nobody.** `fraud-analysis-completed` with `APPROVE` returns before
any write: the payment needs no human action, and a message per approval would be noise at best and
a per-payment SMS bill at worst. There is deliberately no `FRAUD_APPROVED` kind, for the same
reason there is no fourth delivery state: a `RETRYING` status would let a row claim to be in flight
while no thread holds it, which is how a notification gets stuck "retrying" forever after a
restart. The attempts counter and the next-attempt timestamp say everything a fourth state would,
and they survive a restart.

**The sender is simulated, and that is the design for this phase, not a stub.** No email relay, no
SMS gateway, no push service is ever called; the log line is the delivery. What this phase has to
prove is that the platform turns facts into messages exactly once, retries a failed send without
resending a success, and keeps a delivery log a support agent can read. Whether bytes reach a phone
is a provider integration, and a provider integration tested against a fake would prove nothing
about the provider either. The `NotificationSender` interface is the seam a real provider plugs
into, and the retry scheduler re-sends only the send — never the consume path — which is what
makes a provider swap safe.

**The response names no recipient.** The row holds the owner digest; the view renders everything
except it. A digest in a support response is a customer-list entry wearing a thin disguise: it
joins across the fraud and notification databases for anyone who can read both. A support agent
answering "was the customer told" needs the message and its state, not the join key — and the
message templates never interpolate a card token, a device reference or a subject, so the message
is safe to read back over a support call.

**A sent message is not retried, by a 409 rather than by silence.** Resending a `SENT` message is
the duplicate the event-id uniqueness was supposed to prevent, reached through the API instead of
through the consumer. Answering 200 with no action would leave the agent pressing retry again, and
the one retry that finally lands looks like the cause of a message the customer received twice.

**Channels are fixed per kind: payments over push, fraud over SMS, settlement over email.**
A channel per kind rather than a preference per customer, because there is no customer profile in
this service to hold a preference — and because the urgency differs by fact: a fraud text must
interrupt, a settlement email must be fileable.

## Consequences

The delivery log is staff-readable, which makes it the one service whose rows a support agent opens
during every "where is my money" call. The price of the digest discipline is that the agent cannot
search by customer — only by transaction or by recency — and that is accepted: searchability by
person is exactly the index a breach turns into a customer list.

Retry is bounded per pass (fifty rows) and exponential with a cap, so a provider outage cannot turn
the scheduler into an unbounded backlog that holds the thread past the next tick. The cost is that
a notification can sit `FAILED` indefinitely past its configured attempts, and nothing in the
platform treats that as urgent — which is a gap Phase 16's alerting and runbooks have to close,
next to the `BROKEN` cycle that already waits there.

Settlement emails close the loop ADR-0009 left open: a period that breaks rather than reconciles is
final *badly*, and this service is what tells an operator so. The settlement payload quotes its
figures as text rather than parsing them, because a notification that fails to parse a figure the
settlement service already recorded would be a message about money that refuses to name it.

## Rejected

**A synchronous notification read from the producing services.** Consistent, and unavailable
whenever any producer is. A log built from events answers during the outage that prompted the
question.

**Failing the event when the send fails.** It parks well-formed payment events on the dead-letter
topic for channel faults, where they read as parsing problems. The event and the send are
different failures with different remedies, and they retry on different paths.

**Per-customer notification reads.** Unenforceable without a token-to-digest mapping this service
deliberately does not hold. The absence of the route is the control.

**A real provider integration in this phase.** It would prove the shape of one vendor's sandbox and
nothing about exactly-once delivery, retry discipline, or the log. The seam exists; the vendor
does not, yet.
