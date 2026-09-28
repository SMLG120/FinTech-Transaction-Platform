import { describe, expect, it } from 'vitest';
import { declareActualSchema, isOpen, resolveBreakSchema } from './schemas';

describe('settlement schemas', () => {
  it('declares only plain non-negative figures with an ISO code', () => {
    expect(
      declareActualSchema.safeParse({ reference: 'SETTLE-2026-09-27-USD', actualAmount: '1199.00', currency: 'USD' })
        .success,
    ).toBe(true);
    expect(
      declareActualSchema.safeParse({ reference: 'r', actualAmount: '-5', currency: 'USD' }).success,
    ).toBe(false);
    expect(
      declareActualSchema.safeParse({ reference: 'r', actualAmount: '10.00', currency: 'usd' }).success,
    ).toBe(false);
  });

  it('demands a real explanation for a resolved break', () => {
    expect(resolveBreakSchema.safeParse({ resolution: 'Fixed' }).success).toBe(false);
    expect(
      resolveBreakSchema.safeParse({ resolution: 'Late capture posted to the next period.' }).success,
    ).toBe(true);
  });

  it('acts only on open periods', () => {
    expect(isOpen('OPEN')).toBe(true);
    for (const status of ['CLOSED', 'RECONCILED', 'BROKEN']) {
      expect(isOpen(status)).toBe(false);
    }
  });
});
