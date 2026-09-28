import { useState } from 'react';
import { issueCard, useCancelCard, useCardAction, useCards } from '../../api/cardApi';
import { useMyProfile } from '../../api/customerApi';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { EmptyState, ErrorState, Skeleton } from '../../components/ui/states';
import { useToast } from '../../components/ui/Toast';
import { userMessage } from '../../utils/format';
import type { Card, CardBrand } from '../../types';

/** Which lifecycle actions the UI offers for a status. Pure — tested. */
export function availableActions(status: string): ('freeze' | 'unfreeze' | 'lost' | 'cancel')[] {
  if (status === 'ACTIVE') return ['freeze', 'lost', 'cancel'];
  if (status === 'FROZEN') return ['unfreeze', 'lost', 'cancel'];
  return [];
}

const ACTION_LABEL: Record<string, string> = {
  freeze: 'Freeze',
  unfreeze: 'Unfreeze',
  lost: 'Report lost',
  cancel: 'Close card',
};

export function CardsPage() {
  const cards = useCards();
  const profile = useMyProfile();
  const action = useCardAction();
  const cancel = useCancelCard();
  const { notify } = useToast();
  const [brand, setBrand] = useState<CardBrand>('DEBIT');
  const [issuing, setIssuing] = useState(false);
  /** The number, held only until dismissed — it exists in exactly one response. */
  const [issuedNumber, setIssuedNumber] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<{ id: string; action: 'lost' | 'cancel' } | null>(
    null,
  );

  const busy = action.isPending || cancel.isPending || issuing;

  const runAction = (cardId: string, name: 'freeze' | 'unfreeze' | 'lost' | 'cancel') => {
    if (name === 'lost' || name === 'cancel') {
      setConfirming({ id: cardId, action: name });
      return;
    }
    action.mutate(
      { cardId, action: name },
      { onError: (error) => notify(userMessage(error), 'error') },
    );
  };

  const confirmDestructive = () => {
    if (!confirming) return;
    const { id, action: name } = confirming;
    setConfirming(null);
    if (name === 'cancel') {
      cancel.mutate(id, {
        onError: (error) => notify(userMessage(error), 'error'),
        onSuccess: () => notify('Card closed. The record is retained for future disputes.'),
      });
    } else {
      action.mutate(
        { cardId: id, action: name },
        { onError: (error) => notify(userMessage(error), 'error') },
      );
    }
  };

  const issue = async () => {
    if (!profile.data) return;
    setIssuing(true);
    try {
      const issued = await issueCard(profile.data.id, brand);
      // Shown once: the platform never stores the number and cannot show it again.
      setIssuedNumber(issued.cardNumber);
      await cards.refetch();
    } catch (error) {
      notify(userMessage(error), 'error');
    } finally {
      setIssuing(false);
    }
  };

  const copyNumber = async () => {
    if (!issuedNumber) return;
    try {
      await navigator.clipboard.writeText(issuedNumber.replace(/\s/g, ''));
      notify('Card number copied. Store it securely — it will not be shown again.');
    } catch {
      notify('Copy failed — write the number down manually.', 'error');
    }
  };

  return (
    <div>
      <h1>Cards</h1>

      <section className="card" aria-labelledby="issue-heading" style={{ marginBottom: 16 }}>
        <h2 id="issue-heading" style={{ fontSize: '0.9rem' }}>
          Issue a card
        </h2>
        {profile.isPending ? (
          <Skeleton label="Loading profile" />
        ) : profile.isError || !profile.data ? (
          <p className="field-hint">
            Card issuing needs your customer profile, which could not be loaded
            {profile.error ? `: ${userMessage(profile.error)}` : '.'} Register a profile first.
          </p>
        ) : (
          <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap' }}>
            <div className="field" style={{ marginBottom: 0 }}>
              <label className="field-label" htmlFor="card-brand">
                Brand
              </label>
              <select
                id="card-brand"
                className="select"
                value={brand}
                onChange={(event) => setBrand(event.target.value as CardBrand)}
              >
                <option value="DEBIT">DEBIT — draws on your balance</option>
                <option value="CREDIT">CREDIT — draws on a limit</option>
              </select>
            </div>
            <Button variant="primary" disabled={busy || issuing} onClick={() => void issue()}>
              {issuing ? 'Issuing…' : 'Issue card'}
            </Button>
          </div>
        )}
        {issuedNumber ? (
          <div role="alert" style={{ marginTop: 12 }}>
            <p>
              <strong>New card number — copy it now, it will never be shown again.</strong>
            </p>
            <p className="mono" style={{ fontSize: '1.2rem' }}>
              {issuedNumber}
            </p>
            <div style={{ display: 'flex', gap: 8 }}>
              <Button size="sm" variant="primary" onClick={() => void copyNumber()}>
                Copy number
              </Button>
              <Button size="sm" onClick={() => setIssuedNumber(null)}>
                I saved it — dismiss
              </Button>
            </div>
          </div>
        ) : null}
      </section>

      {cards.isPending ? (
        <Skeleton label="Loading cards" />
      ) : cards.isError ? (
        <ErrorState error={cards.error} onRetry={() => void cards.refetch()} />
      ) : cards.data.length === 0 ? (
        <EmptyState title="No cards yet" body="Issue a debit card above to get started." />
      ) : (
        <div className="table-wrap">
          <table className="data">
            <thead>
              <tr>
                <th scope="col">Card</th>
                <th scope="col">Brand</th>
                <th scope="col">Status</th>
                <th scope="col">Expires</th>
                <th scope="col">Actions</th>
              </tr>
            </thead>
            <tbody>
              {cards.data.map((card: Card) => (
                <tr key={card.id}>
                  <td className="mono">…{card.last4}</td>
                  <td>{card.brand}</td>
                  <td>
                    <Badge status={card.status} />
                    {!card.usable && (card.status === 'ACTIVE' || card.status === 'FROZEN') ? (
                      <span className="field-hint" style={{ display: 'block' }}>
                        Not currently usable
                      </span>
                    ) : null}
                  </td>
                  <td>{card.expiresOn}</td>
                  <td>
                    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                      {availableActions(card.status).map((name) => (
                        <Button
                          key={name}
                          size="sm"
                          variant={name === 'cancel' || name === 'lost' ? 'danger' : 'secondary'}
                          disabled={busy}
                          onClick={() => runAction(card.id, name)}
                        >
                          {ACTION_LABEL[name]}
                        </Button>
                      ))}
                      {availableActions(card.status).length === 0 ? (
                        <span className="field-hint">Terminal — no actions</span>
                      ) : null}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {confirming ? (
        <div role="alertdialog" aria-modal="true" aria-labelledby="confirm-title">
          <section className="card" style={{ marginTop: 16 }}>
            <h2 id="confirm-title" style={{ fontSize: '0.9rem' }}>
              {confirming.action === 'cancel' ? 'Close this card?' : 'Report this card lost?'}
            </h2>
            <p>
              {confirming.action === 'cancel'
                ? 'The card is retained as CANCELLED for future disputes — this cannot be undone.'
                : 'A lost card is terminal. You will need a replacement.'}
            </p>
            <div style={{ display: 'flex', gap: 8 }}>
              <Button variant="danger" onClick={confirmDestructive}>
                Confirm
              </Button>
              <Button onClick={() => setConfirming(null)}>Back</Button>
            </div>
          </section>
        </div>
      ) : null}
    </div>
  );
}
