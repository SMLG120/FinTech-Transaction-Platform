import { Link } from 'react-router-dom';
import { useAuditByTransaction } from '../../api/auditApi';
import { Badge } from '../../components/ui/Badge';
import { ErrorState, Skeleton } from '../../components/ui/states';
import { useAuth } from '../auth/AuthContext';

/** One payment's trail inside the investigation view. Read-only roles only. */
export function TransactionAuditTrail({ transactionId }: { transactionId: string }) {
  const { hasRole } = useAuth();
  const canRead = hasRole('AUDITOR', 'COMPLIANCE_OFFICER', 'PLATFORM_ADMIN');
  const trail = useAuditByTransaction(canRead ? transactionId : null);

  if (!canRead) return null;

  return (
    <section className="card" aria-labelledby="audit-heading" style={{ marginTop: 16 }}>
      <h2 id="audit-heading" style={{ fontSize: '0.9rem' }}>
        Audit trail
      </h2>
      {trail.isPending ? (
        <Skeleton label="Loading audit trail" />
      ) : trail.isError ? (
        <ErrorState error={trail.error} onRetry={() => void trail.refetch()} />
      ) : (trail.data?.content ?? []).length === 0 ? (
        <p className="field-hint">No trail rows recorded for this payment yet.</p>
      ) : (
        <ol style={{ margin: 0, paddingLeft: 20 }}>
          {(trail.data?.content ?? []).map((record) => (
            <li key={record.id} style={{ marginBottom: 8 }}>
              <span className="mono">{record.action}</span> <Badge status={record.result} />
              <span className="field-hint" style={{ display: 'block' }}>
                {new Date(record.occurredAt).toLocaleString()} ·{' '}
                <Link to="/audit" className="mono" title={record.correlationId}>
                  ref {record.correlationId.slice(0, 8)}…
                </Link>
              </span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
