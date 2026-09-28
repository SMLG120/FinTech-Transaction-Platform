import { useParams } from 'react-router-dom';
import { useTransaction } from '../../api/transactionApi';
import { Badge } from '../../components/ui/Badge';
import { EmptyState, ErrorState, Skeleton } from '../../components/ui/states';
import { formatMoney } from '../../utils/format';
import { DecisionPanel } from '../fraud/DecisionPanel';
import { TransactionAuditTrail } from '../audit/TransactionAuditTrail';
import { TransactionMessages } from '../notifications/TransactionMessages';

function Timeline({ transaction }: { transaction: ReturnType<typeof useTransaction>['data'] }) {
  if (!transaction) return null;
  const events: { label: string; at: string | null }[] = [
    { label: 'Created', at: transaction.createdAt },
    { label: 'Authorised', at: transaction.authorizedAt },
    { label: 'Settled', at: transaction.settledAt },
    { label: 'Reversed', at: transaction.reversedAt },
  ];
  return (
    <ol style={{ margin: 0, paddingLeft: 20 }}>
      {events.map((event) => (
        <li key={event.label} style={{ marginBottom: 8 }}>
          <strong>{event.label}</strong>{' '}
          {event.at ? (
            <time dateTime={event.at}>{new Date(event.at).toLocaleString()}</time>
          ) : (
            <span className="field-hint">— not reached</span>
          )}
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
        <h1>Transaction</h1>
        <Skeleton label="Loading transaction" />
      </div>
    );
  }

  if (transaction.isError) {
    return (
      <div>
        <h1>Transaction</h1>
        <ErrorState error={transaction.error} onRetry={() => void transaction.refetch()} />
      </div>
    );
  }

  const txn = transaction.data;
  if (!txn) {
    return (
      <div>
        <h1>Transaction</h1>
        <EmptyState title="Not found" body="That transaction does not exist or is not yours." />
      </div>
    );
  }

  return (
    <div>
      <h1>Transaction investigation</h1>
      <section className="card" aria-labelledby="txn-summary">
        <h2 id="txn-summary" style={{ fontSize: '0.9rem' }}>
          Summary
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
            <dt className="field-hint">Transaction ID</dt>
            <dd className="mono" style={{ margin: 0, overflowWrap: 'anywhere' }}>
              {txn.id}
            </dd>
          </div>
          <div>
            <dt className="field-hint">Payee</dt>
            <dd style={{ margin: 0 }}>{txn.payeeName}</dd>
          </div>
          <div>
            <dt className="field-hint">Amount</dt>
            <dd className="mono" style={{ margin: 0 }}>
              {formatMoney(txn.amount, txn.currency)}
            </dd>
          </div>
          <div>
            <dt className="field-hint">Status</dt>
            <dd style={{ margin: 0 }}>
              <Badge status={txn.status} />
            </dd>
          </div>
          <div>
            <dt className="field-hint">Card</dt>
            <dd className="mono" style={{ margin: 0 }}>
              {txn.cardLastFour ? `…${txn.cardLastFour}` : '—'}
            </dd>
          </div>
          <div>
            <dt className="field-hint">Reference</dt>
            <dd className="mono" style={{ margin: 0 }}>
              {txn.payeeReference || '—'}
            </dd>
          </div>
        </dl>
        {txn.declineReason ? (
          <p role="note">
            <strong>Decline reason:</strong> {txn.declineReason}
          </p>
        ) : null}
      </section>

      <section className="card" aria-labelledby="txn-timeline" style={{ marginTop: 16 }}>
        <h2 id="txn-timeline" style={{ fontSize: '0.9rem' }}>
          Timeline
        </h2>
        <Timeline transaction={txn} />
      </section>

      <DecisionPanel transactionId={txn.id} />
      <TransactionAuditTrail transactionId={txn.id} />
      <TransactionMessages transactionId={txn.id} />
    </div>
  );
}