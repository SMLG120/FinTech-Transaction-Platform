import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '../features/auth/AuthContext';
import { apiClient } from './client';
import type {
  AlertDetail,
  DashboardSummary,
  FraudAlert,
  FraudDecisionDetail,
  PageResponse,
} from '../types';

export const FRAUD_READERS = [
  'FRAUD_ANALYST',
  'COMPLIANCE_OFFICER',
  'AUDITOR',
  'PLATFORM_ADMIN',
] as const;

/** May change alerts/scores. Readers who are not actors get a read-only UI. */
export const FRAUD_ACTORS = ['FRAUD_ANALYST', 'PLATFORM_ADMIN'] as const;

/** Staff-only. UI must gate on the same roles; the service enforces them. */
export function useFraudSummary(window = 'PT24H') {
  const { hasRole } = useAuth();
  const allowed = hasRole(...FRAUD_READERS);
  return useQuery({
    queryKey: ['fraud-summary', window],
    queryFn: () =>
      apiClient.get<DashboardSummary>(
        `/api/v1/fraud/summary?window=${encodeURIComponent(window)}`,
      ),
    enabled: allowed,
  });
}

/** The unworked queue, highest risk first. */
export function useAlerts(band: string | undefined, page: number, size = 50) {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (band && band !== 'ALL') params.set('band', band);
  return useQuery({
    queryKey: ['fraud-alerts', band ?? 'ALL', page, size],
    queryFn: () => apiClient.get<PageResponse<FraudAlert>>(`/api/v1/fraud/alerts?${params}`),
  });
}

/** One alert with its timeline. */
export function useAlert(alertId: string | undefined) {
  return useQuery({
    queryKey: ['fraud-alert', alertId],
    queryFn: () => apiClient.get<AlertDetail>(`/api/v1/fraud/alerts/${alertId}`),
    enabled: Boolean(alertId),
    retry: false,
  });
}

/** One payment's fraud decision. Absent (404) until the async engine scores it. */
export function useFraudDecision(transactionId: string | undefined) {
  const { hasRole } = useAuth();
  const allowed = hasRole(...FRAUD_READERS);
  return useQuery({
    queryKey: ['fraud-decision', transactionId],
    queryFn: () =>
      apiClient.get<FraudDecisionDetail>(`/api/v1/fraud/decisions/${transactionId}`),
    enabled: allowed && Boolean(transactionId),
    retry: false,
  });
}

/** Takes an alert. 409 when somebody else already holds it. */
export async function claimAlert(alertId: string): Promise<FraudAlert> {
  return apiClient.post<FraudAlert>(`/api/v1/fraud/alerts/${alertId}/claim`);
}

/** Closes a claimed alert. Two endpoints — resolve vs dismiss must never be accidental. */
export async function closeAlert(
  alertId: string,
  close: 'resolve' | 'dismiss',
  resolution: string,
  note?: string,
): Promise<FraudAlert> {
  return apiClient.post<FraudAlert>(`/api/v1/fraud/alerts/${alertId}/${close}`, {
    resolution,
    note: note || undefined,
  });
}

/** Overrules the engine's score. Refused above the configured cap (422). */
export async function adjustDecision(
  transactionId: string,
  score: number,
  reason: string,
): Promise<FraudDecisionDetail> {
  return apiClient.post<FraudDecisionDetail>(`/api/v1/fraud/decisions/${transactionId}/adjust`, {
    score,
    reason,
  });
}

/** Asks for a re-score. 202 — the work happens on the topic. */
export async function rescoreDecision(transactionId: string, reason: string): Promise<void> {
  await apiClient.post<void>(`/api/v1/fraud/decisions/${transactionId}/rescore`, { reason });
}

export function useClaimAlert() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (alertId: string) => claimAlert(alertId),
    onSettled: (_data, _error, alertId) => {
      void queryClient.invalidateQueries({ queryKey: ['fraud-alerts'] });
      void queryClient.invalidateQueries({ queryKey: ['fraud-alert', alertId] });
    },
  });
}

export function useCloseAlert() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: {
      alertId: string;
      close: 'resolve' | 'dismiss';
      resolution: string;
      note?: string;
    }) => closeAlert(input.alertId, input.close, input.resolution, input.note),
    onSettled: (_data, _error, input) => {
      void queryClient.invalidateQueries({ queryKey: ['fraud-alerts'] });
      void queryClient.invalidateQueries({ queryKey: ['fraud-alert', input.alertId] });
    },
  });
}
