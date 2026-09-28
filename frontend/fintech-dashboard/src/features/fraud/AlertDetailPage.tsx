import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { Link, useParams } from 'react-router-dom';
import { useAlert, useClaimAlert, useCloseAlert } from '../../api/fraudApi';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { EmptyState, ErrorState, Skeleton } from '../../components/ui/states';
import { useToast } from '../../components/ui/Toast';
import { formatMoney, userMessage } from '../../utils/format';
import { useAuth } from '../auth/AuthContext';
import { DecisionPanel } from './DecisionPanel';
import { canClaim, closeSchema } from './schemas';
import type { CloseValues } from './schemas';

export function AlertDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { hasRole } = useAuth();
  const { notify } = useToast();
  const alert = useAlert(id);
  const claim = useClaimAlert();
  const close = useCloseAlert();
  // Readers supervise; only analysts change. Auditors and compliance see
  // everything here and can touch nothing — enforced by the service.
  const canAct = hasRole('FRAUD_ANALYST', 'PLATFORM_ADMIN');

  const closeForm = useForm<CloseValues>({ resolver: zodResolver(closeSchema) });

  const take = () => {
    if (!id) return;
    claim.mutate(id, {
      onSuccess: () => notify('Alert claimed. It is yours to work.'),
      // 409 names the current holder — the loser's useful answer.
      onError: (error) => notify(userMessage(error), 'error'),
    });
  };

  const shut = (kind: 'resolve' | 'dismiss', values: CloseValues) => {
    if (!id) return;
    close.mutate(
      { alertId: id, close: kind, resolution: values.resolution.trim(), note: values.note?.trim() },
      {
        onSuccess: (updated) =>
          notify(
            updated.state === 'RESOLVED'
              ? 'Alert resolved as a genuine finding.'
              : 'Alert dismissed as a false positive.',
          ),
        onError: (error) => notify(userMessage(error), 'error'),
      },
    );
  };

  if (alert.isPending) {
    return (
      <div>
        <h1>Alert</h1>
        <Skeleton label="Loading alert" />
      </div>
    );
  }

  if (alert.isError) {
    return (
      <div>
        <h1>Alert</h1>
        <ErrorState error={alert.error} onRetry={() => void alert.refetch()} />
      </div>
    );
  }

  if (!alert.data) {
    return (
      <div>
        <h1>Alert</h1>
        <EmptyState title="Not found" body="That alert does not exist." />
      </div>
    );
  }

  const { alert: detail, timeline } = alert.data;

  return (
    <div>
      <h1>
        Alert — score {detail.score} / 100
      </h1>

      <section className="card" aria-labelledby="alert-heading" style={{ marginBottom: 16 }}>
        <h2 id="alert-heading" style={{ fontSize: '0.9rem' }}>
          <Badge status={detail.band} /> <Badge status={detail.decision} />{' '}
          <Badge status={detail.state} />
        </h2>
        <p>{detail.summary}</p>
        <dl
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
            gap: 12,
            margin: 0,
          }}
        >
          <div>
            <dt className="field-hint">Payment</dt>
            <dd style={{ margin: 0 }}>
              <Link to={`/transactions/${detail.transactionId}`} className="mono">
                {detail.transactionId.slice(0, 8)}…
              </Link>{' '}
              · {formatMoney(detail.amount, detail.currency)}
            </dd>
          </div>
          <div>
            <dt className="field-hint">Payee</dt>
            <dd style={{ margin: 0 }}>{detail.payeeName}</dd>
          </div>
          <div>
            <dt className="field-hint">Raised</dt>
            <dd style={{ margin: 0 }}>{new Date(detail.createdAt).toLocaleString()}</dd>
          </div>
        </dl>
        {canAct && canClaim(detail.state) ? (
          <div style={{ marginTop: 12 }}>
            <Button variant="primary" disabled={claim.isPending} onClick={take}>
              {claim.isPending ? 'Claiming…' : 'Claim this alert'}
            </Button>
          </div>
        ) : null}
      </section>

      <section className="card" aria-labelledby="timeline-heading" style={{ marginBottom: 16 }}>
        <h2 id="timeline-heading" style={{ fontSize: '0.9rem' }}>
          Timeline ({timeline.length})
        </h2>
        {timeline.length === 0 ? (
          <p className="field-hint">No events recorded yet.</p>
        ) : (
          <ol style={{ margin: 0, paddingLeft: 20 }}>
            {timeline.map((event) => (
              <li key={event.id} style={{ marginBottom: 8 }}>
                <strong>{event.action}</strong>
                {event.note ? <span> — {event.note}</span> : null}
                <span className="field-hint" style={{ display: 'block' }}>
                  {new Date(event.occurredAt).toLocaleString()}
                </span>
              </li>
            ))}
          </ol>
        )}
      </section>

      {canAct && detail.state === 'CLAIMED' ? (
        <section className="card" aria-labelledby="close-heading">
          <h2 id="close-heading" style={{ fontSize: '0.9rem' }}>
            Close (you claimed it)
          </h2>
          <p className="field-hint">
            Two endpoints, not a checkbox: the false-positive rate is measured on this choice, so
            it must be impossible to record one by accident. Only the claimer can close.
          </p>
          <form noValidate aria-labelledby="close-heading">
            <div className="field">
              <label className="field-label" htmlFor="close-resolution">
                Resolution label
              </label>
              <input
                id="close-resolution"
                className="input"
                placeholder="e.g. confirmed-fraud, benign-velocity"
                {...closeForm.register('resolution')}
              />
              {closeForm.formState.errors.resolution ? (
                <p className="field-error" role="alert">
                  {closeForm.formState.errors.resolution.message}
                </p>
              ) : null}
            </div>
            <div className="field">
              <label className="field-label" htmlFor="close-note">
                Note <span className="field-hint">(optional)</span>
              </label>
              <input id="close-note" className="input" {...closeForm.register('note')} />
              {closeForm.formState.errors.note ? (
                <p className="field-error" role="alert">
                  {closeForm.formState.errors.note.message}
                </p>
              ) : null}
            </div>
            <div style={{ display: 'flex', gap: 8 }}>
              <Button
                variant="primary"
                disabled={close.isPending}
                onClick={(e) => {
                  e.preventDefault();
                  void closeForm.handleSubmit((v) => shut('resolve', v))(e);
                }}
              >
                Resolve as genuine
              </Button>
              <Button
                disabled={close.isPending}
                onClick={(e) => {
                  e.preventDefault();
                  void closeForm.handleSubmit((v) => shut('dismiss', v))(e);
                }}
              >
                Dismiss as false positive
              </Button>
            </div>
          </form>
        </section>
      ) : null}

      <DecisionPanel transactionId={detail.transactionId} />
    </div>
  );
}
