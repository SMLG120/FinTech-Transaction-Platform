import { z } from 'zod';

/** Client mirrors of SettlementRequests. Backend validation is authoritative. */

export const declareActualSchema = z.object({
  reference: z.string().min(1, 'Enter the cycle reference.').max(64),
  actualAmount: z
    .string()
    .min(1, 'Enter the independently sourced figure.')
    .regex(/^\d+(\.\d+)?$/, 'Plain digits with at most one decimal point.')
    .refine((value) => Number(value) >= 0, 'A declared actual cannot be negative.'),
  currency: z
    .string()
    .length(3, 'Use a 3-letter ISO 4217 code.')
    .regex(/^[A-Z]{3}$/, 'Use upper-case ISO 4217, e.g. USD.'),
});
export type DeclareActualValues = z.infer<typeof declareActualSchema>;

export const resolveBreakSchema = z.object({
  resolution: z
    .string()
    .min(12, 'Explain what the money is doing — "fixed" is not an explanation.')
    .max(500, 'Resolution is at most 500 characters.'),
});
export type ResolveBreakValues = z.infer<typeof resolveBreakSchema>;

/** A closed period is final: late money is a finding, never a new line. */
export function isOpen(status: string): boolean {
  return status === 'OPEN';
}
