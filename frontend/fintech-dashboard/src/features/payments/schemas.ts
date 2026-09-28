import { z } from 'zod';

/**
 * Client-side mirrors of the backend validation (CreateTransactionRequest,
 * FundAccountRequest, Counterparty limits). Backend validation remains
 * authoritative — these only fail fast with useful messages.
 */

const amount = z
  .string()
  .min(1, 'Enter an amount.')
  .max(32, 'Amount is too long.')
  .regex(/^\d+(\.\d{1,2})?$/, 'Enter a decimal amount like 25.00.')
  .refine((value) => Number(value) > 0, 'Amount must be greater than zero.');

const currency = z
  .string()
  .length(3, 'Use a 3-letter currency code.')
  .regex(/^[A-Za-z]{3}$/, 'Use a 3-letter currency code.')
  .transform((value) => value.toUpperCase());

export const fundSchema = z.object({ amount, currency });
export type FundValues = z.infer<typeof fundSchema>;

export const paySchema = z.object({
  amount,
  currency,
  cardToken: z
    .string()
    .min(1, 'Enter the card token from your wallet.')
    .max(64, 'Card token is at most 64 characters.'),
  payeeName: z
    .string()
    .min(1, 'Enter who you are paying.')
    .max(140, 'Payee name is too long.'),
  payeeReference: z.string().max(64, 'Reference is too long.').optional(),
  channel: z.enum(['WEB', 'MOBILE', 'POS', 'ATM', 'MERCHANT_API']),
});
export type PayValues = z.infer<typeof paySchema>;
