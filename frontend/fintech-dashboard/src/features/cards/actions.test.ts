import { describe, expect, it } from 'vitest';
import { availableActions } from './CardsPage';

describe('availableActions', () => {
  it('offers freeze, lost and cancel on active cards', () => {
    expect(availableActions('ACTIVE')).toEqual(['freeze', 'lost', 'cancel']);
  });

  it('offers unfreeze instead of freeze on frozen cards', () => {
    expect(availableActions('FROZEN')).toEqual(['unfreeze', 'lost', 'cancel']);
  });

  it('offers nothing on terminal cards', () => {
    for (const status of ['LOST', 'CANCELLED', 'EXPIRED']) {
      expect(availableActions(status)).toEqual([]);
    }
  });
});
