import { useState } from 'react';
import { Link } from 'react-router-dom';
import { canRetry, useNotifications, useRetryNotification } from '../../api/notificationApi';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { EmptyState, ErrorState, Skeleton } from '../../components/ui/states';
import { useToast } from '../../components/ui/Toast';
import { userMessage } from '../../utils/format';

export function NotificationsPage() {
  const [status, setStatus] = useState('ALL');
  const [page, setPage] = useState(0);
  const log = useNotifications(status === 'ALL' ? undefined : status, page, 20);
  const retry = useRetryNotification();
  const { notify } = useToast();

  const runRetry = (id: string) => {
    retry.mutate(id, {
      onSuccess: (updated) =>
        notify(
          updated.status === 'SENT'
            ? 'Message sent.'
            : `Retry ran — now ${updated.status}. The body says what happened.`,
        ),
      onError: (error) => notify(userMessage(error), 'error'),
    });
  };

  return (
    <div>
      <h1>Notifications</h1>
      <p className="field-hint">
        Each consumed fact becomes exactly one message — push for a payment, SMS for a fraud
        outcome worth acting on, email for a settlement period. An approved payment notifies
        nobody: there is no human action in it.
      </p>

      <div className="field" style={{ maxWidth: 240 }}>
        <label className="field-label" htmlFor="notif-status">
          Status
        </label>
        <select
          id="notif-status"
          className="select"
          value={status}
          onChange={(event) => {
            setStatus(event.target.value);
            setPage(0);
          }}
        >
          <option value="ALL">All</option>
          <option value="PENDING">Pending</option>
          <option value="SENT">Sent</option>
          <option value="FAILED">Failed</option>
        </select>
      </div>

      {log.isPending ? (
        <Skeleton label="Loading delivery log" />
      ) : log.isError ? (
        <ErrorState error={log.error} onRetry={() => void log.refetch()} />
      ) : log.data.content.length === 0 ? (
        <EmptyState title="No messages" body="Deliveries appear here once events flow through the platform." />
      ) : (
        <>
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th scope="col">Subject</th>
                  <th scope="col">Channel</th>
                  <th scope="col">Status</th>
                  <th scope="col">Attempts</th>
                  <th scope="col">About</th>
                  <th scope="col">Created</th>
                  <th scope="col">Action</th>
                </tr>
              </thead>
              <tbody>
                {log.data.content.map((message) => (
                  <tr key={message.id}>
                    <td>
                      <Link to={`/notifications/${message.id}`}>{message.subject}</Link>
                      <span className="field-hint" style={{ display: 'block' }}>
                        {message.kind}
                      </span>
                    </td>
                    <td className="mono">{message.channel}</td>
                    <td>
                      <Badge status={message.status} />
                      {message.status === 'FAILED' && message.lastError ? (
                        <span className="field-hint" style={{ display: 'block' }}>
                          {message.lastError}
                        </span>
                      ) : null}
                    </td>
                    <td>{message.attempts}</td>
                    <td>
                      {message.transactionId ? (
                        <Link to={`/transactions/${message.transactionId}`} className="mono">
                          payment {message.transactionId.slice(0, 8)}…
                        </Link>
                      ) : message.cycleReference ? (
                        <Link
                          to={`/settlement/cycles/${encodeURIComponent(message.cycleReference)}`}
                          className="mono"
                        >
                          {message.cycleReference}
                        </Link>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td>{new Date(message.createdAt).toLocaleString()}</td>
                    <td>
                      {canRetry(message.status) ? (
                        <Button
                          size="sm"
                          disabled={retry.isPending}
                          onClick={() => runRetry(message.id)}
                        >
                          Retry
                        </Button>
                      ) : (
                        <span className="field-hint">
                          {message.status === 'SENT' ? 'Delivered — never resent' : 'On schedule'}
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <nav aria-label="Message pages" style={{ display: 'flex', gap: 12, alignItems: 'center', marginTop: 12 }}>
            <Button size="sm" disabled={log.data.first} onClick={() => setPage((p) => p - 1)}>
              Previous
            </Button>
            <span role="status">
              Page {log.data.page + 1} of {Math.max(log.data.totalPages, 1)} ·{' '}
              {log.data.totalElements} messages
            </span>
            <Button size="sm" disabled={log.data.last} onClick={() => setPage((p) => p + 1)}>
              Next
            </Button>
          </nav>
        </>
      )}
    </div>
  );
}
