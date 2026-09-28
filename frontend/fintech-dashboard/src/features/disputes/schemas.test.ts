import { describe, expect, it } from 'vitest';
import { evidenceSchema, isResolved, openDisputeSchema, resolveSchema } from './schemas';

describe('dispute schemas', () => {
  it('opens only with a transaction id, reason and description', () => {
    expect(
      openDisputeSchema.safeParse({
        transactionId: 'not-a-uuid',
        reason: 'FRAUD',
        description: 'x',
      }).success,
    ).toBe(false);
    expect(
      openDisputeSchema.safeParse({
        transactionId: '123e4567-e89b-12d3-a456-426614174000',
        reason: 'FRAUD',
        description: 'Charged twice.',
      }).success,
    ).toBe(true);
  });

  it('caps descriptions and statements at the backend column limits', () => {
    expect(
      openDisputeSchema.safeParse({
        transactionId: '123e4567-e89b-12d3-a456-426614174000',
        reason: 'OTHER',
        description: 'x'.repeat(2001),
      }).success,
    ).toBe(false);
    expect(evidenceSchema.safeParse({ body: 'x'.repeat(4001) }).success).toBe(false);
    expect(resolveSchema.safeParse({ outcome: 'REFUND', resolution: '' }).success).toBe(false);
  });

  it('classifies resolved cases so the file and decision lock correctly', () => {
    expect(isResolved('OPEN')).toBe(false);
    expect(isResolved('RESOLVED_REFUNDED')).toBe(true);
    expect(isResolved('RESOLVED_REJECTED')).toBe(true);
  });
});
