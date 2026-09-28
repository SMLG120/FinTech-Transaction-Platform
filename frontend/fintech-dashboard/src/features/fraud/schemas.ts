import { z } from 'zod';

/** Client mirrors of FraudRequests caps. Backend validation is authoritative. */

export const adjustSchema = z.object({
  score: z.coerce.number().int('Enter a whole number.').min(0).max(100),
  reason: z
    .string()
    .min(1, 'Say why the engine is being overruled — it is the audit trail.')
    .max(500, 'Reason is at most 500 characters.'),
});
export type AdjustValues = z.infer<typeof adjustSchema>;

export const rescoreSchema = z.object({
  reason: z.string().min(1, 'Say why a re-score is needed.').max(500),
});
export type RescoreValues = z.infer<typeof rescoreSchema>;

export const closeSchema = z.object({
  resolution: z
    .string()
    .min(1, 'Give a short label — the false-positive rate is computed by category.')
    .max(64, 'Resolution is at most 64 characters.'),
  note: z.string().max(2000, 'Notes are at most 2000 characters.').optional(),
});
export type CloseValues = z.infer<typeof closeSchema>;

/** OPEN, then CLAIMED by one analyst, then RESOLVED or DISMISSED — never back. */
export function canClaim(state: string): boolean {
  return state === 'OPEN';
}
