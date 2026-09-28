import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from './client';
import type { Notification, PageResponse } from '../types';

/**
 * Support-only delivery log. There is deliberately no customer route: this
 * service cannot scope a notification to its caller, so a per-customer rule
 * would be enforced by nothing. The recipient digest never leaves the service.
 */

export function useNotifications(status: string | undefined, page: number, size = 20) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (status && status !== 'ALL') params.set('status', status);
  return useQuery({
    queryKey: ['notifications', status ?? 'ALL', page, size],
    queryFn: () =>
      apiClient.get<PageResponse<Notification>>(`/api/v1/notifications?${params}`),
  });
}

export function useNotification(id: string | undefined) {
  return useQuery({
    queryKey: ['notification', id],
    queryFn: () => apiClient.get<Notification>(`/api/v1/notifications/${id}`),
    enabled: Boolean(id),
    retry: false,
  });
}

/** Every message about one payment, in recorded order. */
export function useNotificationsByTransaction(transactionId: string | null) {
  return useQuery({
    queryKey: ['notifications', 'by-transaction', transactionId],
    queryFn: () =>
      apiClient.get<PageResponse<Notification>>(
        `/api/v1/notifications/by-transaction/${transactionId}`,
      ),
    enabled: Boolean(transactionId),
    retry: false,
  });
}

/**
 * Retries one failed notification, now. 200 either way; refusing a SENT one
 * is a 409 — resending what already went out is the duplicate delivery the
 * event-id uniqueness exists to prevent.
 */
export async function retryNotification(id: string): Promise<Notification> {
  return apiClient.post<Notification>(`/api/v1/notifications/${id}/retry`);
}

/** Only failed deliveries are retryable — the scheduler owns the rest. Pure. */
export function canRetry(status: string): boolean {
  return status === 'FAILED';
}

export function useRetryNotification() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => retryNotification(id),
    onSettled: (_data, _error, id) => {
      void queryClient.invalidateQueries({ queryKey: ['notifications'] });
      void queryClient.invalidateQueries({ queryKey: ['notification', id] });
    },
  });
}
