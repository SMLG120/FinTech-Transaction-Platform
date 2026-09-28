import { Link } from 'react-router-dom';
import { useNotificationsByTransaction } from '../../api/notificationApi';
import { Badge } from '../../components/ui/Badge';
import { ErrorState, Skeleton } from '../../components/ui/states';
import { useAuth } from '../auth/AuthContext';

/** "Was the customer told" — inside the investigation view for support staff. */
export function TransactionMessages({ transactionId }: { transactionId: string }) {
  const { hasRole } = useAuth();
  const canRead = hasRole('SUPPORT_AGENT', 'PLATFORM_ADMIN');
  const messages = useNotificationsByTransaction(canRead ? transactionId : null);

  if (!canRead) return null;

  return (
    <section className="card" aria-labelledby="messages-heading" style={{ marginTop: 16 }}>
      <h2 id="messages-heading" style={{ fontSize: '0.9rem' }}>
        Messages
      </h2>
      {messages.isPending ? (
        <Skeleton label="Loading messages" />
      ) : messages.isError ? (
        <ErrorState error={messages.error} onRetry={() => void messages.refetch()} />
      ) : (messages.data?.content ?? []).length === 0 ? (
        <p className="field-hint">
          No messages recorded for this payment — an approved payment notifies nobody.
        </p>
      ) : (
        <ol style={{ margin: 0, paddingLeft: 20 }}>
          {(messages.data?.content ?? []).map((message) => (
            <li key={message.id} style={{ marginBottom: 8 }}>
              <Link to={`/notifications/${message.id}`}>{message.subject}</Link>{' '}
              <Badge status={message.status} />
              <span className="field-hint" style={{ display: 'block' }}>
                {message.channel} · {new Date(message.createdAt).toLocaleString()}
              </span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
