import { AlertTriangle } from 'lucide-react';
import { useParams } from 'react-router-dom';
import { useTransaction } from '../../api/transactionApi';
import { Badge } from '../../components/ui/Badge';
import { Card } from '../../components/ui/Card';
import { Breadcrumbs, PageHeader } from '../../components/ui/PageHeader';
import { EmptyState, ErrorState, Skeleton } from '../../components/ui/states';
import { formatMoney } from '../../utils/format';
import { DecisionPanel } from '../fraud/DecisionPanel';
import { TransactionAuditTrail } from '../audit/TransactionAuditTrail';
import { TransactionMessages } from '../notifications/TransactionMessages';

function formatDateTime(value: string | null | undefined): string {
  if (!value) return '— not reached';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function Timeline({ transaction }: { transaction: ReturnType<typeof useTransaction>['data'] }) {
  if (!transaction) return null;
  const events: { label: string; at: string | null }[] = [
    { label: 'Created', at: transaction.createdAt },
    { label: 'Authorised', at: transaction.authorizedAt },
    { label: 'Settled', at: transaction.settledAt },
    { label: 'Reversed', at: transaction.reversedAt },
  ];
  return (
    <ol className="timeline">
      {events.map((event) => (
        <li key={event.label} className={event.at ? 'done' : ''}>
          <div className="timeline-title">{event.label}</div>
          <div className="timeline-time">
            {event.at ? (
              <time dateTime={event.at}>{formatDateTime(event.at)}</time>
            ) : (
              'Not reached'
            )}
          </div>
        </li>
      ))}
    </ol>
  );
}

export function TransactionDetailPage() {
  const { id } = useParams<{ id: string }>();
  const transaction = useTransaction(id);

  if (transaction.isPending) {
    return (
      <div>
        <PageHeader eyebrow="Money" title="Transaction" sub="Loading payment record…" />
        <Skeleton label="Loading transaction" />
      </div>
    );
  }

  if (transaction.isError) {
    return (
      <div>
        <PageHeader eyebrow="Money" title="Transaction" sub="Payment investigation view." />
        <ErrorState error={transaction.error} onRetry={() => void transaction.refetch()} />
      </div>
    );
  }

  const txn = transaction.data;
  if (!txn) {
    return (
      <div>
        <PageHeader eyebrow="Money" title="Transaction" sub="Payment investigation view." />
        <EmptyState title="Not found" body="That transaction does not exist or is not yours." />
      </div>
    );
  }

  return (
    <div>
      <Breadcrumbs
        trail={[
          { label: 'Transactions', to: '/transactions' },
          { label: `${txn.id.slice(0, 8)}…` },
        ]}
      />
      <PageHeader
        eyebrow="Money · Investigation"
        title={`${txn.payeeName} · ${formatMoney(txn.amount, txn.currency)}`}
        sub={`Payment ${txn.id}`}
        actions={<Badge status={txn.status} />}
      />

      {txn.declineReason ? (
        <div className="alert alert-danger" role="note">
          <AlertTriangle size={18} aria-hidden="true" />
          <div>
            <strong>Declined — {txn.declineReason}</strong>
            <p>The authorisation was refused. Funds were never moved; no settlement followed.</p>
          </div>
        </div>
      ) : null}

      <div className="grid-2">
        <Card labelledBy="txn-summary">
          <h2 id="txn-summary" style={{ fontSize: '0.92rem' }}>
            Payment summary
          </h2>
          <dl className="spec" style={{ marginTop: 12 }}>
            <div>
              <dt>Transaction ID</dt>
              <dd className="mono">{txn.id}</dd>
            </div>
            <div>
              <dt>Payee</dt>
              <dd>{txn.payeeName}</dd>
            </div>
            <div>
              <dt>Amount</dt>
              <dd className="mono" style={{ fontWeight: 700 }}>
                {formatMoney(txn.amount, txn.currency)}
              </dd>
            </div>
            <div>
              <dt>Status</dt>
              <dd>
                <Badge status={txn.status} />
              </dd>
            </div>
            <div>
              <dt>Card</dt>
              <dd className="mono">{txn.cardLastFour ? `•••• …${txn.cardLastFour}` : '—'}</dd>
            </div>
            <div>
              <dt>Reference</dt>
              <dd className="mono">{txn.payeeReference || '—'}</dd>
            </div>
            <div>
              <dt>Created</dt>
              <dd>{formatDateTime(txn.createdAt)}</dd>
            </div>
          </dl>
        </Card>

        <Card labelledBy="txn-timeline">
          <h2 id="txn-timeline" style={{ fontSize: '0.92rem' }}>
            Lifecycle timeline
          </h2>
          <p className="field-hint" style={{ marginBottom: 12 }}>
            Authorisation holds funds; settlement moves them; reversal returns them.
          </p>
          <Timeline transaction={txn} />
        </Card>
      </div>

      <div className="stack" style={{ marginTop: 16 }}>
        <DecisionPanel transactionId={txn.id} />
        <TransactionAuditTrail transactionId={txn.id} />
        <TransactionMessages transactionId={txn.id} />
      </div>
    </div>
  );
}
