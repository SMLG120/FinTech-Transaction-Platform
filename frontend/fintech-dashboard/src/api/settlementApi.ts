import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from './client';
import type {
  CycleDetail,
  PageResponse,
  SettlementBreak,
  SettlementCycle,
} from '../types';

/**
 * Reads are supervision (operator, compliance, auditor, admin); the four
 * actions — close, declare actual, reconcile, work a break — are operators
 * and admins only. The service enforces both; the UI mirrors them.
 */

export function useCycles(status: string | undefined, page: number, size = 20) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (status && status !== 'ALL') params.set('status', status);
  return useQuery({
    queryKey: ['settlement-cycles', status ?? 'ALL', page, size],
    queryFn: () =>
      apiClient.get<PageResponse<SettlementCycle>>(`/api/v1/settlement/cycles?${params}`),
  });
}

/** One cycle with its full statement — the reference, not the id, is the URL. */
export function useCycle(reference: string | undefined) {
  return useQuery({
    queryKey: ['settlement-cycle', reference],
    queryFn: () =>
      apiClient.get<CycleDetail>(
        `/api/v1/settlement/cycles/${encodeURIComponent(reference ?? '')}`,
      ),
    enabled: Boolean(reference),
    retry: false,
  });
}

/** Freezes a cycle's lines and total. Irreversible. */
export async function closeCycle(reference: string): Promise<SettlementCycle> {
  return apiClient.post<SettlementCycle>('/api/v1/settlement/cycles/close', { reference });
}

/** Declares the independently sourced actual. 200 either way — a mismatch is
 * a finding in the body, not a failed request. */
export async function declareActual(
  reference: string,
  actualAmount: string,
  currency: string,
): Promise<SettlementCycle> {
  return apiClient.post<SettlementCycle>('/api/v1/settlement/cycles/actual', {
    reference,
    actualAmount,
    currency,
  });
}

/** Confirms a cycle whose declared actual matched. */
export async function reconcileCycle(reference: string): Promise<SettlementCycle> {
  return apiClient.post<SettlementCycle>(
    `/api/v1/settlement/cycles/${encodeURIComponent(reference)}/reconcile`,
  );
}

export function useBreaks(status: string | undefined, page: number, size = 20) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (status && status !== 'ALL') params.set('status', status);
  return useQuery({
    queryKey: ['settlement-breaks', status ?? 'ALL', page, size],
    queryFn: () =>
      apiClient.get<PageResponse<SettlementBreak>>(`/api/v1/settlement/breaks?${params}`),
  });
}

export function useBreak(breakId: string | undefined) {
  return useQuery({
    queryKey: ['settlement-break', breakId],
    queryFn: () =>
      apiClient.get<SettlementBreak>(`/api/v1/settlement/breaks/${breakId}`),
    enabled: Boolean(breakId),
    retry: false,
  });
}

/** Records that somebody has seen a break. The actor comes from the identity. */
export async function acknowledgeBreak(breakId: string): Promise<SettlementBreak> {
  return apiClient.post<SettlementBreak>(`/api/v1/settlement/breaks/${breakId}/acknowledge`);
}

export async function resolveBreak(breakId: string, resolution: string): Promise<SettlementBreak> {
  return apiClient.post<SettlementBreak>(`/api/v1/settlement/breaks/${breakId}/resolve`, {
    resolution,
  });
}

function invalidateSettlement(queryClient: ReturnType<typeof useQueryClient>) {
  void queryClient.invalidateQueries({ queryKey: ['settlement-cycles'] });
  void queryClient.invalidateQueries({ queryKey: ['settlement-cycle'] });
  void queryClient.invalidateQueries({ queryKey: ['settlement-breaks'] });
  void queryClient.invalidateQueries({ queryKey: ['settlement-break'] });
}

export function useCloseCycle() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (reference: string) => closeCycle(reference),
    onSettled: () => invalidateSettlement(queryClient),
  });
}

export function useDeclareActual() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { reference: string; actualAmount: string; currency: string }) =>
      declareActual(input.reference, input.actualAmount, input.currency),
    onSettled: () => invalidateSettlement(queryClient),
  });
}
