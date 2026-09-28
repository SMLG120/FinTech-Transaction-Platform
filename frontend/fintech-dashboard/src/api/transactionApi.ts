import { useQuery } from '@tanstack/react-query';
import { apiClient, newId } from './client';
import type { Balance, CreatePayment, Transaction, TransactionList } from '../types';

export function useTransactions(limit = 200) {
  return useQuery({
    queryKey: ['transactions', limit],
    queryFn: () => apiClient.get<TransactionList>(`/api/v1/transactions?limit=${limit}`),
  });
}

export function useTransaction(id: string | undefined) {
  return useQuery({
    queryKey: ['transaction', id],
    queryFn: () => apiClient.get<Transaction>(`/api/v1/transactions/${id}`),
    enabled: Boolean(id),
  });
}

export function useBalance(currency = 'GBP') {
  return useQuery({
    queryKey: ['balance', currency],
    queryFn: () =>
      apiClient.get<Balance>(`/api/v1/accounts/balance?currency=${encodeURIComponent(currency)}`),
  });
}

/** Creates and authorises a payment. One idempotency key per user-initiated submit. */
export async function createPayment(payment: CreatePayment): Promise<Transaction> {
  return apiClient.post<Transaction>('/api/v1/transactions', payment, newId());
}

/** Captures an authorised payment. Not idempotency-keyed: the ledger state
 * machine refuses a second capture with a 409. */
export async function settleTransaction(id: string): Promise<Transaction> {
  return apiClient.post<Transaction>(`/api/v1/transactions/${id}/settle`);
}

export async function reverseTransaction(id: string): Promise<Transaction> {
  return apiClient.post<Transaction>(`/api/v1/transactions/${id}/reverse`);
}

/** Tops up the caller's account. Moves money, so it carries an idempotency key. */
export async function fundAccount(amount: string, currency: string): Promise<unknown> {
  return apiClient.post(
    `/api/v1/accounts/fund?amount=${encodeURIComponent(amount)}&currency=${encodeURIComponent(currency)}`,
    undefined,
    newId(),
  );
}
