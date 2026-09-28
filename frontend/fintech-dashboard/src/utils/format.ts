import { ApiError } from '../api/client';

/** Renders a decimal amount string next to its code, the way the services do. */
export function formatMoney(amount: string | null | undefined, currency: string): string {
  if (amount === null || amount === undefined || amount === '') return '—';
  return `${currency} ${amount}`;
}

/**
 * User-friendly message for any fetch failure. Never exposes stack traces;
 * surfaces the backend correlationId as a support reference.
 */
export function userMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const ref = error.correlationId ? ` (ref ${error.correlationId})` : '';
    switch (error.status) {
      case 400:
        return `That request was not valid: ${error.message}${ref}`;
      case 401:
        return `Your session has expired. Please sign in again.${ref}`;
      case 403:
        return `You do not have permission for that action.${ref}`;
      case 404:
        return `That record was not found. It may belong to another customer.${ref}`;
      case 409:
        return `That conflicts with the current state: ${error.message}${ref}`;
      case 422:
        return `That was declined: ${error.message}${ref}`;
      case 429:
        return `Too many requests — please wait a moment and retry.${ref}`;
      default:
        return `Something went wrong. Please retry.${ref}`;
    }
  }
  if (error instanceof Error) return error.message;
  return 'Something went wrong. Please retry.';
}
