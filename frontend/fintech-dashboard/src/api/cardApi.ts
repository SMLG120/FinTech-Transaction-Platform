import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient, newId } from './client';
import type { Card, CardBrand, IssuedCard } from '../types';

export function useCards() {
  return useQuery({
    queryKey: ['cards'],
    queryFn: () => apiClient.get<Card[]>('/api/v1/cards'),
  });
}

/** Issues a card. The response carries the number exactly once — the caller
 * must show it immediately and never store it. */
export async function issueCard(customerId: string, brand: CardBrand): Promise<IssuedCard> {
  return apiClient.post<IssuedCard>('/api/v1/cards', { customerId, brand }, newId());
}

export type CardAction = 'freeze' | 'unfreeze' | 'lost';

export async function cardAction(cardId: string, action: CardAction): Promise<Card> {
  return apiClient.post<Card>(`/api/v1/cards/${cardId}/${action}`);
}

/** Closes a card. Terminal — the record is retained as CANCELLED, never deleted. */
export async function cancelCard(cardId: string): Promise<Card> {
  return apiClient.del<Card>(`/api/v1/cards/${cardId}`);
}

export function useCardAction() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ cardId, action }: { cardId: string; action: CardAction }) =>
      cardAction(cardId, action),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: ['cards'] });
    },
  });
}

export function useCancelCard() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (cardId: string) => cancelCard(cardId),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: ['cards'] });
    },
  });
}
