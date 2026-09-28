import { describe, expect, it } from 'vitest';
import type { Transaction } from '../../types';
import { bucketByDay, computeStats } from './stats';

function txn(overrides: Partial<Transaction> & { id: string }): Transaction {
  return {
    amount: '10.00',
    currency: 'GBP',
    status: 'SETTLED',
    cardLastFour: '1234',
    payeeName: 'Acme',
    payeeReference: null,
    declineReason: null,
    createdAt: '2026-09-20T10:00:00Z',
    authorizedAt: '2026-09-20T10:00:00Z',
    settledAt: '2026-09-20T10:01:00Z',
    reversedAt: null,
    ...overrides,
  };
}

describe('computeStats', () => {
  it('counts outcomes and computes the approval rate over decided payments', () => {
    const stats = computeStats([
      txn({ id: '1', status: 'SETTLED' }),
      txn({ id: '2', status: 'AUTHORIZED' }),
      txn({ id: '3', status: 'DECLINED' }),
      txn({ id: '4', status: 'PENDING' }),
      txn({ id: '5', status: 'REVERSED' }),
    ]);
    expect(stats).toMatchObject({
      total: 5,
      approved: 2,
      declined: 1,
      pending: 1,
      reversed: 1,
      approvalRate: 2 / 3,
    });
  });

  it('never sums across currencies and excludes reversed payments from volume', () => {
    const stats = computeStats([
      txn({ id: '1', amount: '10.00', currency: 'GBP' }),
      txn({ id: '2', amount: '5.00', currency: 'GBP', status: 'REVERSED' }),
      txn({ id: '3', amount: '20.00', currency: 'USD' }),
    ]);
    expect(stats.totalsByCurrency).toEqual([
      { currency: 'GBP', total: 10, count: 1 },
      { currency: 'USD', total: 20, count: 1 },
    ]);
  });

  it('handles an empty list without dividing by zero', () => {
    expect(computeStats([]).approvalRate).toBe(0);
  });
});

describe('bucketByDay', () => {
  it('buckets the trailing window by outcome', () => {
    const now = new Date('2026-09-27T12:00:00Z').getTime();
    const buckets = bucketByDay(
      [
        txn({ id: '1', status: 'SETTLED', createdAt: '2026-09-27T08:00:00Z' }),
        txn({ id: '2', status: 'DECLINED', createdAt: '2026-09-27T09:00:00Z' }),
        txn({ id: '3', status: 'PENDING', createdAt: '2026-09-26T09:00:00Z' }),
        txn({ id: '4', status: 'SETTLED', createdAt: '2026-08-01T09:00:00Z' }),
      ],
      2,
      now,
    );
    expect(buckets).toEqual([
      { day: '09-26', approved: 0, declined: 0, other: 1 },
      { day: '09-27', approved: 1, declined: 1, other: 0 },
    ]);
  });
});
