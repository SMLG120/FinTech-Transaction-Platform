import { Link, useParams } from 'react-router-dom';
import { canRetry, useNotification, useRetryNotification } from '../../api/notificationApi';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { EmptyState, ErrorState, Skeleton } from '../../components/ui/states';
import { useToast } from '../../components/ui/Toast';
import { formatMoney, userMessage } from '../../utils/format';

export function NotificationDetailPage() {
  const { id } = useParams<{ id: string }>();
  const message = useNotification(id);
  const retry = useRetryNotification();
  const { notify } = useToast();

  if (message.isPending) {
    return (
      <div>
        <h1>Message</h1>
        <Skeleton label="Loading message" />
      </div>
    );
  }

  if (message.isError) {
    return (
      <div>
        <h1>Message</h1>
        <ErrorState error={message.error} onRetry={() => void message.refetch()} />
      </div>
    );
  }

  if (!message.data) {
    return (
      <div>
        <h1>Message</h1>
        <EmptyState title="Not found" body="That message does not exist." />
      </div>
    );
  }

  const detail = message.data;

  return (
    <div>
      <h1>{detail.subject}</h1>

      <section className="card" aria-labelledby="msg-heading" style={{ marginBottom: 16 }}>
        <h2 id="msg-heading" style={{ fontSize: '0.9rem' }}>
          Delivery · <Badge status={detail.status} />
        </h2>
        <dl
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
            gap: 12,
            margin: 0,
          }}
        >
          <div>
            <dt className="field-hint">Channel / kind</dt>
            <dd className="mono" style={{ margin: 0 }}>
              {detail.channel} / {detail.kind}
            </dd>
          </div>
          <div>
            <dt className="field-hint">Attempts</dt>
            <dd style={{ margin: 0 }}>{detail.attempts}</dd>
          </div>
          <div>
            <dt className="field-hint">Created</dt>
            <dd style={{ margin: 0 }}>{new Date(detail.createdAt).toLocaleString()}</dd>
          </div>
          <div>
            <dt className="field-hint">Sent</dt>
            <dd style={{ margin: 0 }}>
              {detail.sentAt ? new Date(detail.sentAt).toLocaleString() : '—'}
            </dd>
          </div>
          {detail.transactionId ? (
            <div>
              <dt className="field-hint">Payment</dt>
              <dd style={{ margin: 0 }}>
                <Link to={`/transactions/${detail.transactionId}`} className="mono">
                  {detail.transactionId.slice(0, 8)}…
                </Link>
                {detail.amount && detail.currency ? (
                  <span> · {formatMoney(detail.amount, detail.currency)}</span>
                ) : null}
              </dd>
            </div>
          ) : null}
          {detail.cycleReference ? (
            <div>
              <dt className="field-hint">Period</dt>
              <dd style={{ margin: 0 }}>
                <Link to={`/settlement/cycles/${encodeURIComponent(detail.cycleReference)}`} className="mono">
                  {detail.cycleReference}
                </Link>
              </dd>
            </div>
          ) : null}
        </dl>
        {detail.lastError ? (
          <p role="note">
            <strong>Last error:</strong> {detail.lastError}
          </p>
        ) : null}
        {detail.nextAttemptAt ? (
          <p className="field-hint">Next attempt at {new Date(detail.nextAttemptAt).toLocaleString()}.</p>
        ) : null}
        {canRetry(detail.status) ? (
          <Button
            variant="primary"
            disabled={retry.isPending}
            onClick={() =>
              id &&
              retry.mutate(id, {
                onSuccess: (updated) =>
                  notify(
                    updated.status === 'SENT'
                      ? 'Message sent.'
                      : `Retry ran — now ${updated.status}.`,
                  ),
                onError: (error) => notify(userMessage(error), 'error'),
              })
            }
          >
            {retry.isPending ? 'Retrying…' : 'Retry now'}
          </Button>
        ) : null}
      </section>

      <section className="card" aria-labelledby="body-heading">
        <h2 id="body-heading" style={{ fontSize: '0.9rem' }}>
          Content
        </h2>
        <p style={{ whiteSpace: 'pre-wrap' }}>{detail.body}</p>
        <p className="field-hint">
          The response names the recorded figure with no recipient digest in it — the join key
          must never travel.
        </p>
      </section>
    </div>
  );
}
