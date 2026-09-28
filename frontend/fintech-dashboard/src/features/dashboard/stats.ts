import type { Transaction } from '../../types';

export interface DashboardStats {
  total: number;
  approved: number;
  declined: number;
  pending: number;
  reversed: number;
  approvalRate: number;
  /** Totals grouped by currency — never summed across currencies. */
  totalsByCurrency: { currency: string; total: number; count: number }[];
}

function parseAmount(value: string): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

/** Aggregate transaction stats. Pure — unit tested. */
export function computeStats(items: Transaction[]): DashboardStats {
  let approved = 0;
  let declined = 0;
  let pending = 0;
  let reversed = 0;
  const byCurrency = new Map<string, { total: number; count: number }>();

  for (const item of items) {
    const status = item.status.toUpperCase();
    if (status === 'AUTHORIZED' || status === 'SETTLED') approved += 1;
    else if (status === 'DECLINED') declined += 1;
    else if (status === 'PENDING') pending += 1;
    else if (status === 'REVERSED') reversed += 1;

    // Reversed payments moved money back — exclude from volume totals.
    if (status !== 'REVERSED') {
      const entry = byCurrency.get(item.currency) ?? { total: 0, count: 0 };
      entry.total += parseAmount(item.amount);
      entry.count += 1;
      byCurrency.set(item.currency, entry);
    }
  }

  const decided = approved + declined;
  return {
    total: items.length,
    approved,
    declined,
    pending,
    reversed,
    approvalRate: decided === 0 ? 0 : approved / decided,
    totalsByCurrency: [...byCurrency.entries()]
      .map(([currency, entry]) => ({ currency, ...entry }))
      .sort((a, b) => b.count - a.count),
  };
}

export interface DayBucket {
  day: string;
  approved: number;
  declined: number;
  other: number;
}

/** Bucket transactions by day for the trailing `days` days (UTC). Pure — unit tested. */
export function bucketByDay(items: Transaction[], days = 14, now = Date.now()): DayBucket[] {
  const buckets = new Map<string, DayBucket>();
  for (let i = days - 1; i >= 0; i -= 1) {
    const date = new Date(now - i * 86_400_000);
    const day = date.toISOString().slice(0, 10);
    buckets.set(day, { day: day.slice(5), approved: 0, declined: 0, other: 0 });
  }
  for (const item of items) {
    const day = new Date(item.createdAt).toISOString().slice(0, 10);
    const bucket = buckets.get(day);
    if (!bucket) continue;
    const status = item.status.toUpperCase();
    if (status === 'AUTHORIZED' || status === 'SETTLED') bucket.approved += 1;
    else if (status === 'DECLINED') bucket.declined += 1;
    else bucket.other += 1;
  }
  return [...buckets.values()];
}
