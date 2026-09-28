import { describe, expect, it } from 'vitest';
import { canRetry } from './notificationApi';

describe('canRetry', () => {
  it('retries only failed deliveries', () => {
    expect(canRetry('FAILED')).toBe(true);
    // SENT must never resend; PENDING belongs to the scheduler.
    expect(canRetry('SENT')).toBe(false);
    expect(canRetry('PENDING')).toBe(false);
  });
});
