import { useState } from 'react';
import { CreditCard, Lock, Snowflake } from 'lucide-react';
import { issueCard, useCancelCard, useCardAction, useCards } from '../../api/cardApi';
import { useMyProfile } from '../../api/customerApi';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { Card, CardHeader } from '../../components/ui/Card';
import { PageHeader } from '../../components/ui/PageHeader';
import { EmptyState, ErrorState, TableSkeleton } from '../../components/ui/states';
import { useToast } from '../../components/ui/Toast';
import { userMessage } from '../../utils/format';
import type { Card as CardType, CardBrand } from '../../types';

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

function CardVisual({ card }: { card: CardType }) {
  const frozen = card.status === 'FROZEN';
  return (
    <div
      aria-hidden="true"
      style={{
        borderRadius: 10,
        padding: '16px 18px',
        color: '#fff',
        background: frozen
          ? 'linear-gradient(135deg, #3b4a61 0%, #232f45 100%)'
          : 'linear-gradient(135deg, #16294a 0%, #0e1b2e 100%)',
        border: '1px solid #0a1424',
        minWidth: 250,
        maxWidth: 320,
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <CreditCard size={20} />
        <span style={{ fontSize: '0.72rem', fontWeight: 700, letterSpacing: '0.08em' }}>
          {card.brand}
        </span>
      </div>
      <div
        className="mono"
        style={{ fontSize: '1.05rem', letterSpacing: '0.12em', margin: '18px 0 12px' }}
      >
        •••• •••• •••• {card.last4}
      </div>
      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.75rem', color: '#b8c6da' }}>
        <span>Expires {card.expiresOn}</span>
        <span style={{ display: 'inline-flex', gap: 4, alignItems: 'center' }}>
          {frozen ? <Snowflake size={13} /> : <Lock size={13} />} {card.status}
        </span>
      </div>
    </div>
  );
}

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
      {
        onError: (error) => notify(userMessage(error), 'error'),
        onSuccess: () => notify(name === 'freeze' ? 'Card frozen.' : 'Card unfrozen.', 'success'),
      },
    );
  };

  const confirmDestructive = () => {
    if (!confirming) return;
    const { id, action: name } = confirming;
    setConfirming(null);
    if (name === 'cancel') {
      cancel.mutate(id, {
        onError: (error) => notify(userMessage(error), 'error'),
        onSuccess: () => notify('Card closed. The record is retained for future disputes.', 'success'),
      });
    } else {
      action.mutate(
        { cardId: id, action: name },
        {
          onError: (error) => notify(userMessage(error), 'error'),
          onSuccess: () => notify('Card reported lost and blocked.', 'success'),
        },
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
      notify('Card issued. Copy the number now — it will not be shown again.', 'success');
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
      notify('Card number copied. Store it securely — it will not be shown again.', 'success');
    } catch {
      notify('Copy failed — write the number down manually.', 'error');
    }
  };

  return (
    <div>
      <PageHeader
        eyebrow="Money"
        title="Cards"
        sub="Issue, freeze, and manage payment cards. Destructive actions always confirm first."
      />

      <Card labelledBy="issue-heading">
        <CardHeader
          titleId="issue-heading"
          title="Issue a card"
          sub="Debit draws on your balance · credit draws on a limit"
        />
        {profile.isPending ? (
          <p className="field-hint" role="status">Loading profile…</p>
        ) : profile.isError || !profile.data ? (
          <div className="alert alert-warning" role="note">
            <Lock size={16} aria-hidden="true" />
            <div>
              <strong>Customer profile required</strong>
              <p>
                Card issuing needs your customer profile, which could not be loaded
                {profile.error ? `: ${userMessage(profile.error)}` : '.'} Register a profile first.
              </p>
            </div>
          </div>
        ) : (
          <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap' }}>
            <div className="field" style={{ marginBottom: 0, minWidth: 280 }}>
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
          <div className="alert alert-warning" role="alert" style={{ marginTop: 12 }}>
            <Lock size={16} aria-hidden="true" />
            <div style={{ flex: 1 }}>
              <strong>New card number — copy it now, it will never be shown again.</strong>
              <p className="mono" style={{ fontSize: '1.15rem', color: 'var(--ink)', margin: '6px 0' }}>
                {issuedNumber}
              </p>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                <Button size="sm" variant="primary" onClick={() => void copyNumber()}>
                  Copy number
                </Button>
                <Button size="sm" onClick={() => setIssuedNumber(null)}>
                  I saved it — dismiss
                </Button>
              </div>
            </div>
          </div>
        ) : null}
      </Card>

      <div style={{ marginTop: 16 }} />

      {cards.isPending ? (
        <TableSkeleton label="Loading cards" />
      ) : cards.isError ? (
        <ErrorState error={cards.error} onRetry={() => void cards.refetch()} />
      ) : cards.data.length === 0 ? (
        <EmptyState title="No cards yet" body="Issue a debit card above to get started." />
      ) : (
        <Card labelledBy="cards-heading">
          <CardHeader
            titleId="cards-heading"
            title="Your cards"
            sub={`${cards.data.length} card${cards.data.length === 1 ? '' : 's'} on file`}
          />
          <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', marginBottom: 16 }}>
            {cards.data
              .filter((c: CardType) => c.status === 'ACTIVE' || c.status === 'FROZEN')
              .slice(0, 3)
              .map((card: CardType) => (
                <CardVisual key={card.id} card={card} />
              ))}
          </div>
          <div className="table-wrap" style={{ boxShadow: 'none' }}>
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
                {cards.data.map((card: CardType) => (
                  <tr key={card.id}>
                    <td className="mono row-main">…{card.last4}</td>
                    <td>{card.brand}</td>
                    <td>
                      <Badge status={card.status} />
                      {!card.usable && (card.status === 'ACTIVE' || card.status === 'FROZEN') ? (
                        <span className="sub-cell">Not currently usable</span>
                      ) : null}
                    </td>
                    <td className="mono">{card.expiresOn}</td>
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
        </Card>
      )}

      {confirming ? (
        <div className="modal-backdrop" onClick={() => setConfirming(null)}>
          <div
            className="modal"
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="confirm-title"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 id="confirm-title">
              {confirming.action === 'cancel' ? 'Close this card?' : 'Report this card lost?'}
            </h2>
            <p className="field-hint">
              {confirming.action === 'cancel'
                ? 'The card is retained as CANCELLED for future disputes — this cannot be undone.'
                : 'A lost card is terminal and will be blocked immediately. You will need a replacement.'}
            </p>
            <div className="modal-actions">
              <Button variant="danger" onClick={confirmDestructive}>
                Confirm
              </Button>
              <Button onClick={() => setConfirming(null)}>Back</Button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}
