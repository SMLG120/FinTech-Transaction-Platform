import { z } from 'zod';

/**
 * Response contracts for the backend wire fixtures in
 * `contracts/src/main/resources/contracts/*.json` (single source of truth —
 * the contract test reads those files directly, no copies).
 *
 * Zod strips unknown keys by default, which is exactly the consumer
 * discipline: assert the fields this dashboard relies on, tolerate the rest.
 * A backend rename of a relied-on field fails the contract test before it
 * ships a page rendering `undefined`.
 */

export const eligibilityResponseSchema = z.object({
  id: z.string().uuid(),
  fullName: z.string(),
  email: z.string(),
  kycStatus: z.string(),
  masked: z.boolean(),
});
export type EligibilityResponse = z.infer<typeof eligibilityResponseSchema>;

export const transactionViewSchema = z.object({
  id: z.string().uuid(),
  amount: z.string(),
  currency: z.string().length(3),
  status: z.string(),
  payeeName: z.string(),
});
export type TransactionView = z.infer<typeof transactionViewSchema>;
