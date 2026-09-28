import { describe, expect, it } from 'vitest';
import { auditParams } from './auditApi';

describe('auditParams', () => {
  it('builds a paginated query with optional filters', () => {
    expect(auditParams({ page: 2, size: 20 })).toBe('page=2&size=20');
    const full = auditParams({
      action: ' PAYMENT_SETTLED ',
      from: '2026-09-27T00:00:00.000Z',
      to: '2026-09-28T00:00:00.000Z',
      page: 0,
      size: 20,
    });
    expect(full).toContain('action=PAYMENT_SETTLED');
    expect(full).toContain('from=2026-09-27T00%3A00%3A00.000Z');
  });

  it('clamps paging into the backend bounds', () => {
    expect(auditParams({ page: -1, size: 0 })).toBe('page=0&size=1');
    expect(auditParams({ page: 0, size: 1000 })).toBe('page=0&size=200');
  });
});
