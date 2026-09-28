/**
 * Typed request/response contracts mirroring the gateway DTOs.
 * Amounts are decimal strings in both directions (a JSON number would arrive
 * as a double); scores are genuine integers. Field names match the backend.
 */

export type TransactionStatus = 'PENDING' | 'AUTHORIZED' | 'DECLINED' | 'SETTLED' | 'REVERSED';

export interface Transaction {
  id: string;
  amount: string;
  currency: string;
  status: TransactionStatus | string;
  cardLastFour: string | null;
  payeeName: string;
  payeeReference: string | null;
  declineReason: string | null;
  createdAt: string;
  authorizedAt: string | null;
  settledAt: string | null;
  reversedAt: string | null;
}

export interface TransactionList {
  items: Transaction[];
  count: number;
}

export interface Balance {
  currency: string;
  available: string;
  held: string;
}

export interface CreatePayment {
  amount: string;
  currency: string;
  cardToken: string;
  payeeName: string;
  payeeReference?: string;
  channel?: 'WEB' | 'MOBILE' | 'POS' | 'ATM' | 'MERCHANT_API';
  deviceFingerprint?: string;
}

export type RiskBand = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type FraudDecision = 'APPROVE' | 'REVIEW' | 'DECLINE';

export interface RuleOutcome {
  ruleId: string;
  ruleName: string;
  points: number;
  explanation: string;
  evidence: Record<string, string>;
}

export interface FraudDecisionDetail {
  transactionId: string;
  ownerSubjectDigest: string;
  amount: string;
  currency: string;
  payeeName: string;
  merchantReference: string;
  channel: string;
  score: number;
  band: RiskBand;
  decision: FraudDecision;
  alertRequired: boolean;
  stepDownRecommended: boolean;
  reasons: RuleOutcome[];
  facts: Record<string, string>;
  occurredAt: string;
  evaluatedAt: string;
  createdAt: string;
  updatedAt: string;
  attempt: number;
  manuallyAdjusted: boolean;
  manualScore: number | null;
  manualAdjustedBy: string | null;
  manualAdjustedAt: string | null;
  manualReason: string | null;
}

/** One alert in the queue — the list view, without reasons/facts by design. */
export interface FraudAlert {
  id: string;
  transactionId: string;
  ownerSubjectDigest: string;
  amount: string;
  currency: string;
  payeeName: string;
  merchantReference: string;
  score: number;
  band: RiskBand | string;
  decision: FraudDecision | string;
  state: 'OPEN' | 'CLAIMED' | 'RESOLVED' | 'DISMISSED' | string;
  summary: string;
  claimedBy: string | null;
  claimedAt: string | null;
  closedBy: string | null;
  closedAt: string | null;
  resolution: string | null;
  resolutionNote: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AlertTimelineEvent {
  id: string;
  action: string;
  actorDigest: string;
  note: string | null;
  occurredAt: string;
}

export interface AlertDetail {
  alert: FraudAlert;
  timeline: AlertTimelineEvent[];
}

/** One append-only trail row. Actors are digests, never resolved to names. */
export interface AuditRecord {
  id: string;
  eventId: string;
  action: string;
  resourceType: string;
  resourceId: string;
  transactionId: string | null;
  actorDigest: string;
  result: string;
  correlationId: string;
  metadata: string | null;
  occurredAt: string;
  receivedAt: string;
}

/** A period's headline. Null figures mean "not declared yet", never zero. */
export interface SettlementCycle {
  id: string;
  reference: string;
  businessDate: string;
  currency: string;
  status: 'OPEN' | 'CLOSED' | 'RECONCILED' | 'BROKEN' | string;
  expected: string;
  actual: string | null;
  difference: string | null;
  lineCount: number;
  openBreaks: number;
  closedAt: string | null;
  settledAt: string | null;
}

export interface SettlementLine {
  id: string;
  transactionId: string;
  kind: string;
  amount: string;
  businessDate: string;
  createdAt: string;
}

/** One reconciliation finding. Figures are in the cycle's currency. */
export interface SettlementBreak {
  id: string;
  cycleId: string;
  reference: string;
  kind: string;
  status: 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED' | string;
  expected: string | null;
  actual: string | null;
  difference: number;
  detail: string;
  transactionId: string | null;
  acknowledgedBy: string | null;
  acknowledgedAt: string | null;
  resolution: string | null;
  resolvedAt: string | null;
  createdAt: string;
}

export interface CycleDetail {
  cycle: SettlementCycle;
  lines: SettlementLine[];
  breaks: SettlementBreak[];
}

/** One message and whether it went out. No recipient — the digest stays inside. */
export interface Notification {
  id: string;
  eventId: string;
  topic: string;
  kind: string;
  channel: string;
  status: 'PENDING' | 'SENT' | 'FAILED' | string;
  transactionId: string | null;
  cycleReference: string | null;
  amount: string | null;
  currency: string | null;
  payeeName: string | null;
  subject: string;
  body: string;
  attempts: number;
  lastError: string | null;
  nextAttemptAt: string | null;
  sentAt: string | null;
  createdAt: string;
}

export interface DashboardSummary {  window: string;
  totalDecisions: number;
  byBand: Partial<Record<RiskBand, number>>;
  byDecision: Partial<Record<FraudDecision, number>>;
  declinedRate: number;
  openAlerts: number;
  claimedAlerts: number;
  resolvedAlerts: number;
  dismissedAlerts: number;
  breachingAlerts: number;
  topMerchants: { merchantReference: string; decisions: number; averageScore: number }[];
  riskiestCustomers: { ownerSubjectDigest: string; decisions: number; averageScore: number }[];
  recentDeclines: unknown[];
}

export interface CustomerProfile {
  id: string;
  fullName: string;
  dateOfBirth: string | null;
  birthYear: string | null;
  email: string;
  phone: string;
  address: {
    line1: string;
    line2: string | null;
    city: string;
    postalCode: string;
    country: string;
  } | null;
  kycStatus: string;
  createdAt: string;
  updatedAt: string;
  erased: boolean;
  masked: boolean;
}

export type CardBrand = 'DEBIT' | 'CREDIT';
export type CardStatus = 'ACTIVE' | 'FROZEN' | 'LOST' | 'CANCELLED' | 'EXPIRED';

export interface Card {
  id: string;
  customerId: string;
  last4: string;
  brand: CardBrand | string;
  expiresOn: string;
  status: CardStatus | string;
  usable: boolean;
  frozenAt: string | null;
  lostAt: string | null;
  cancelledAt: string | null;
  createdAt: string;
}

export interface IssuedCard {
  card: Card;
  cardNumber: string;
}

/** Stable platform pagination contract (platform-common PageResponse). */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export type DisputeStatus = 'OPEN' | 'RESOLVED_REFUNDED' | 'RESOLVED_REJECTED';
export type DisputeReason = 'FRAUD' | 'NOT_RECEIVED' | 'DUPLICATE' | 'DEFECTIVE' | 'OTHER';

/** One case. No amounts: dispute-service never learns them (see ADR-0012). */
export interface Dispute {
  id: string;
  transactionId: string;
  reason: string;
  description: string;
  status: DisputeStatus | string;
  evidenceCount: number;
  resolvedBy: string | null;
  resolution: string | null;
  createdAt: string;
  resolvedAt: string | null;
}

export interface DisputeEvidence {
  id: string;
  body: string;
  submittedByMe: boolean;
  submittedAt: string;
}

export interface DisputeDetail {
  dispute: Dispute;
  evidence: DisputeEvidence[];
}

export type ResolveOutcome = 'REFUND' | 'REJECT';

export interface KycCheckResult {
  checkName: string;
  passed: boolean;
  reason: string;
}

export interface KycCheck {
  id: string;
  outcome: string;
  providerReference: string;
  failureReasons: string[];
  checks: KycCheckResult[];
  submittedAt: string;
  decidedAt: string | null;
}

export interface UpdateProfilePayload {
  fullName: string;
  dateOfBirth: string;
  nationality: string;
  email: string;
  phone: string;
  address: {
    line1: string;
    line2?: string;
    city: string;
    postalCode: string;
    country: string;
  };
}
