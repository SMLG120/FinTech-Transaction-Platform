import { z } from 'zod';

/** Client mirrors of DisputeRequests caps. Backend validation is authoritative. */

export const openDisputeSchema = z.object({
  transactionId: z.string().uuid('Enter the transaction ID to dispute.'),
  reason: z.enum(['FRAUD', 'NOT_RECEIVED', 'DUPLICATE', 'DEFECTIVE', 'OTHER']),
  description: z
    .string()
    .min(1, 'Describe what happened.')
    .max(2000, 'Description is at most 2000 characters.'),
});
export type OpenDisputeValues = z.infer<typeof openDisputeSchema>;

export const evidenceSchema = z.object({
  body: z.string().min(1, 'Write your statement.').max(4000, 'Statements are at most 4000 characters.'),
});
export type EvidenceValues = z.infer<typeof evidenceSchema>;

export const resolveSchema = z.object({
  outcome: z.enum(['REFUND', 'REJECT']),
  resolution: z
    .string()
    .min(1, 'Give a reason — it is recorded either way.')
    .max(2000, 'Resolution is at most 2000 characters.'),
});
export type ResolveValues = z.infer<typeof resolveSchema>;

/** A resolved case never reopens; evidence lands only on open ones. */
export function isResolved(status: string): boolean {
  return status === 'RESOLVED_REFUNDED' || status === 'RESOLVED_REJECTED';
}
