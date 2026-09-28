import { describe, expect, it } from 'vitest';
import { fundSchema, paySchema } from './schemas';

describe('fundSchema', () => {
  it('accepts a decimal amount and normalises the currency', () => {
    expect(fundSchema.parse({ amount: '100.00', currency: 'gbp' })).toEqual({
      amount: '100.00',
      currency: 'GBP',
    });
  });

  it('rejects non-decimal, zero and negative amounts', () => {
    for (const amount of ['0', '0.00', '-5', 'abc', '10.999', '']) {
      expect(fundSchema.safeParse({ amount, currency: 'GBP' }).success).toBe(false);
    }
  });
});

describe('paySchema', () => {
  const valid = {
    amount: '25.00',
    currency: 'GBP',
    cardToken: 'tok_abc',
    payeeName: 'Acme Books',
    channel: 'WEB',
  } as const;

  it('accepts a minimal valid payment', () => {
    expect(paySchema.safeParse(valid).success).toBe(true);
  });

  it('rejects a missing payee and an over-long token', () => {
    expect(paySchema.safeParse({ ...valid, payeeName: '' }).success).toBe(false);
    expect(paySchema.safeParse({ ...valid, cardToken: 'x'.repeat(65) }).success).toBe(false);
  });

  it('rejects an unknown channel', () => {
    expect(paySchema.safeParse({ ...valid, channel: 'CRYPTO' }).success).toBe(false);
  });
});
