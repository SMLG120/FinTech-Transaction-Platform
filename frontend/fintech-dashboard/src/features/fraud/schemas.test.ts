import { describe, expect, it } from 'vitest';
import { adjustSchema, canClaim, closeSchema, rescoreSchema } from './schemas';

describe('fraud action schemas', () => {
  it('overrules only with a 0–100 score and a reason', () => {
    expect(adjustSchema.safeParse({ score: 90, reason: 'Verified with customer.' }).success).toBe(
      true,
    );
    expect(adjustSchema.safeParse({ score: 101, reason: 'Too high.' }).success).toBe(false);
    expect(adjustSchema.safeParse({ score: 50, reason: '' }).success).toBe(false);
  });

  it('requires a re-score reason and caps close labels', () => {
    expect(rescoreSchema.safeParse({ reason: '' }).success).toBe(false);
    expect(closeSchema.safeParse({ outcome: '', resolution: '' }).success).toBe(false);
    expect(
      closeSchema.safeParse({ resolution: 'x'.repeat(65), note: 'ok' }).success,
    ).toBe(false);
  });

  it('claims only open alerts — the lifecycle never returns to OPEN', () => {
    expect(canClaim('OPEN')).toBe(true);
    for (const state of ['CLAIMED', 'RESOLVED', 'DISMISSED']) {
      expect(canClaim(state)).toBe(false);
    }
  });
});
