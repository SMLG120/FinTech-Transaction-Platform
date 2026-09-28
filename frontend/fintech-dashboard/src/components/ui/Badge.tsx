type Tone = 'success' | 'warning' | 'danger' | 'info' | 'neutral';

const TONES: Record<string, Tone> = {
  ACTIVE: 'success',
  SETTLED: 'success',
  APPROVE: 'success',
  APPROVED: 'success',
  RECONCILED: 'success',
  RESOLVED: 'success',
  SENT: 'success',
  OPEN: 'info',
  CLAIMED: 'info',
  ACKNOWLEDGED: 'info',
  CLOSED: 'info',
  PENDING: 'info',
  AUTHORISED: 'info',
  FROZEN: 'warning',
  HELD: 'warning',
  REVIEW: 'warning',
  HIGH: 'warning',
  DECLINED: 'danger',
  FAILED: 'danger',
  REVERSED: 'danger',
  LOST: 'danger',
  CRITICAL: 'danger',
  BROKEN: 'danger',
  CANCELLED: 'neutral',
  DISMISSED: 'neutral',
  LOW: 'success',
  MEDIUM: 'info',
};

export function toneFor(status: string): Tone {
  return TONES[status.toUpperCase()] ?? 'neutral';
}

/** Status badge — the text label carries the meaning, color only reinforces it. */
export function Badge({ status, tone }: { status: string; tone?: Tone }) {
  const resolved = tone ?? toneFor(status);
  return <span className={`badge badge-${resolved}`}>{status}</span>;
}
