import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from './client';
import type { CustomerProfile, KycCheck, UpdateProfilePayload } from '../types';

/** The caller's own profile, unmasked. 410 when the caller erased it, 404/409 when none. */
export function useMyProfile() {
  return useQuery({
    queryKey: ['customers', 'me'],
    queryFn: () => apiClient.get<CustomerProfile>('/api/v1/customers/me'),
    retry: false,
  });
}

/** A named profile. Masked for anyone but the owner — the service decides. */
export function useCustomerProfile(customerId: string | null) {
  return useQuery({
    queryKey: ['customers', customerId],
    queryFn: () => apiClient.get<CustomerProfile>(`/api/v1/customers/${customerId}`),
    enabled: Boolean(customerId),
    retry: false,
  });
}

/** The caller's own identity-check history. */
export function useMyKycHistory(enabled: boolean) {
  return useQuery({
    queryKey: ['customers', 'me', 'kyc'],
    queryFn: () => apiClient.get<KycCheck[]>('/api/v1/customers/me/kyc'),
    enabled,
  });
}

/** Full profile replacement (PUT /me). */
export async function updateMyProfile(payload: UpdateProfilePayload): Promise<CustomerProfile> {
  return apiClient.request<CustomerProfile>('PUT', '/api/v1/customers/me', { body: payload });
}

/** Irreversible erasure (204). Afterwards /me answers 410. */
export async function eraseMyProfile(): Promise<void> {
  await apiClient.del<void>('/api/v1/customers/me');
}

export function useUpdateProfile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: updateMyProfile,
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: ['customers', 'me'] });
    },
  });
}
