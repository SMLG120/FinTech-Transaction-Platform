import { useQuery } from '@tanstack/react-query';
import { apiClient } from './client';
import type { PageResponse } from '../types';
import type { AuditRecord } from '../types';

/**
 * The append-only trail. Reads only — there is no write endpoint by design,
 * so this module has no mutations. Actors are digests, never names: resolving
 * one would turn the trail into the customer list its schema refuses to be.
 */

export interface AuditFilters {
  action?: string;
  from?: string;
  to?: string;
  page: number;
  size: number;
}

/** Builds the list query string. Pure — unit tested. */
export function auditParams(filters: AuditFilters): string {
  const params = new URLSearchParams({
    page: String(Math.max(0, filters.page)),
    size: String(Math.min(Math.max(1, filters.size), 200)),
  });
  if (filters.action && filters.action.trim()) params.set('action', filters.action.trim());
  if (filters.from) params.set('from', filters.from);
  if (filters.to) params.set('to', filters.to);
  return params.toString();
}

export function useAuditRecords(filters: AuditFilters) {
  return useQuery({
    queryKey: ['audit', filters.action ?? '', filters.from ?? '', filters.to ?? '', filters.page],
    queryFn: () => apiClient.get<PageResponse<AuditRecord>>(`/api/audit/records?${auditParams(filters)}`),
  });
}

export function useAuditRecord(id: string | null) {
  return useQuery({
    queryKey: ['audit', 'record', id],
    queryFn: () => apiClient.get<AuditRecord>(`/api/audit/records/${id}`),
    enabled: Boolean(id),
    retry: false,
  });
}

/** One payment's trail, in the order it happened. */
export function useAuditByTransaction(transactionId: string | null) {
  return useQuery({
    queryKey: ['audit', 'by-transaction', transactionId],
    queryFn: () =>
      apiClient.get<PageResponse<AuditRecord>>(`/api/audit/records/by-transaction/${transactionId}`),
    enabled: Boolean(transactionId),
    retry: false,
  });
}

/** One request traced across every service it touched. */
export function useAuditByCorrelation(correlationId: string | null) {
  return useQuery({
    queryKey: ['audit', 'by-correlation', correlationId],
    queryFn: () =>
      apiClient.get<PageResponse<AuditRecord>>(
        `/api/audit/records/by-correlation/${encodeURIComponent(correlationId ?? '')}`,
      ),
    enabled: Boolean(correlationId),
    retry: false,
  });
}

export function useAuditByResource(resourceType: string | null, resourceId: string | null) {
  const enabled = Boolean(resourceType && resourceId);
  return useQuery({
    queryKey: ['audit', 'by-resource', resourceType, resourceId],
    queryFn: () =>
      apiClient.get<PageResponse<AuditRecord>>(
        `/api/audit/records/by-resource/${encodeURIComponent(resourceType ?? '')}/${encodeURIComponent(resourceId ?? '')}`,
      ),
    enabled,
    retry: false,
  });
}
