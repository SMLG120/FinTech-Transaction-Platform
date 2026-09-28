import { describe, expect, it } from 'vitest';
import type { Transaction } from '../../types';
import { queryTransactions } from './TransactionsPage';

function txn(overrides: Partial<Transaction> & { id: string }): Transaction {
  return {
    amount: '10.00',
    currency: 'GBP',
    status: 'SETTLED',
    cardLastFour: '1234',
    payeeName: 'Acme Books',
    payeeReference: 'ref-1',
    declineReason: null,
    createdAt: '2026-09-20T10:00:00Z',
    authorizedAt: '2026-09-20T10:00:00Z',
    settledAt: '2026-09-20T10:01:00Z',
    reversedAt: null,
    ...overrides,
  };
}

const ITEMS = [
  txn({ id: 'aaa', payeeName: 'Acme Books', amount: '25.00', status: 'SETTLED' }),
  txn({ id: 'bbb', payeeName: 'Corner Shop', amount: '5.00', status: 'DECLINED' }),
  txn({ id: 'ccc', payeeName: 'Acme Books', amount: '100.00', status: 'AUTHORIZED' }),
];

const BASE = { search: '', status: 'ALL', sortKey: 'date' as const, sortDir: 'desc' as const, page: 1 };

describe('queryTransactions', () => {
  it('filters by free text across payee, reference and id', () => {
    expect(queryTransactions(ITEMS, { ...BASE, search: 'acme' }).total).toBe(2);
    expect(queryTransactions(ITEMS, { ...BASE, search: 'bbb' }).rows[0]?.id).toBe('bbb');
  });

  it('filters by status', () => {
    const result = queryTransactions(ITEMS, { ...BASE, status: 'DECLINED' });
    expect(result.rows.map((row) => row.id)).toEqual(['bbb']);
  });

  it('sorts by amount both directions', () => {
    const desc = queryTransactions(ITEMS, { ...BASE, sortKey: 'amount', sortDir: 'desc' });
    expect(desc.rows.map((row) => row.id)).toEqual(['ccc', 'aaa', 'bbb']);
    const asc = queryTransactions(ITEMS, { ...BASE, sortKey: 'amount', sortDir: 'asc' });
    expect(asc.rows.map((row) => row.id)).toEqual(['bbb', 'aaa', 'ccc']);
  });

  it('reports an empty result instead of an empty page', () => {
    expect(queryTransactions(ITEMS, { ...BASE, search: 'no-such-payee' })).toMatchObject({
      rows: [],
      total: 0,
      totalPages: 1,
    });
  });
});
