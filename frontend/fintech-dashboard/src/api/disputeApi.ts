import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient, newId } from './client';
import type {
  DisputeDetail,
  DisputeEvidence,
  DisputeReason,
  PageResponse,
  ResolveOutcome,
} from '../types';
import type { Dispute } from '../types';

export function useDisputes(status: string | undefined, page: number, size = 20) {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (status && status !== 'ALL') params.set('status', status);
  return useQuery({
    queryKey: ['disputes', status ?? 'ALL', page, size],
    queryFn: () => apiClient.get<PageResponse<Dispute>>(`/api/v1/disputes?${params.toString()}`),
  });
}

export function useDispute(id: string | undefined) {
  return useQuery({
    queryKey: ['dispute', id],
    queryFn: () => apiClient.get<DisputeDetail>(`/api/v1/disputes/${id}`),
    enabled: Boolean(id),
  });
}

/** Opens a case on a settled payment. Only the opener's own (CUSTOMER, ADMIN). */
export async function openDispute(
  transactionId: string,
  reason: DisputeReason,
  description: string,
): Promise<DisputeDetail> {
  return apiClient.post<DisputeDetail>(
    '/api/v1/disputes',
    { transactionId, reason, description },
    newId(),
  );
}

/** Appends a statement to an open case. Either party; resolved cases refuse. */
export async function addEvidence(id: string, body: string): Promise<DisputeEvidence> {
  return apiClient.post<DisputeEvidence>(`/api/v1/disputes/${id}/evidence`, { body });
}

/** Decides a case. Staff only (SUPPORT_AGENT, ADMIN) — backend enforces. */
export async function resolveDispute(
  id: string,
  outcome: ResolveOutcome,
  resolution: string,
): Promise<DisputeDetail> {
  return apiClient.post<DisputeDetail>(`/api/v1/disputes/${id}/resolve`, { outcome, resolution });
}

export function useResolveDispute() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { id: string; outcome: ResolveOutcome; resolution: string }) =>
      resolveDispute(input.id, input.outcome, input.resolution),
    onSettled: (_data, _error, input) => {
      void queryClient.invalidateQueries({ queryKey: ['disputes'] });
      void queryClient.invalidateQueries({ queryKey: ['dispute', input.id] });
    },
  });
}
