import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAlerts, useFraudSummary } from '../../api/fraudApi';
import { StatCard } from '../../components/ui/Card';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { EmptyState, ErrorState, Skeleton } from '../../components/ui/states';
import { formatMoney } from '../../utils/format';

export function FraudPage() {
  const [band, setBand] = useState('ALL');
  const [page, setPage] = useState(0);
  const summary = useFraudSummary();
  const alerts = useAlerts(band === 'ALL' ? undefined : band, page, 50);

  return (
    <div>
      <h1>Fraud monitoring</h1>
      <p className="field-hint">
        Scored after the payment, never before it — the engine is on no payment's critical path.
        A payment can be authorised first and flagged later; working that queue is this page's job.
      </p>

      {summary.data ? (
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
            gap: 16,
            marginBottom: 24,
          }}
        >
          <StatCard title="Open alerts" value={String(summary.data.openAlerts)} />
          <StatCard title="Claimed" value={String(summary.data.claimedAlerts)} />
          <StatCard
            title="Past SLA"
            value={String(summary.data.breachingAlerts)}
            hint="Open or claimed beyond the configured window"
          />
          <StatCard
            title="Decline rate"
            value={`${Math.round(summary.data.declinedRate * 100)}%`}
            hint={`${summary.data.totalDecisions} decisions in window`}
          />
        </div>
      ) : null}

      <div className="field" style={{ maxWidth: 240 }}>
        <label className="field-label" htmlFor="alert-band">
          Risk band
        </label>
        <select
          id="alert-band"
          className="select"
          value={band}
          onChange={(event) => {
            setBand(event.target.value);
            setPage(0);
          }}
        >
          <option value="ALL">All bands</option>
          <option value="LOW">Low</option>
          <option value="MEDIUM">Medium</option>
          <option value="HIGH">High</option>
          <option value="CRITICAL">Critical</option>
        </select>
      </div>

      {alerts.isPending ? (
        <Skeleton label="Loading alert queue" />
      ) : alerts.isError ? (
        <ErrorState error={alerts.error} onRetry={() => void alerts.refetch()} />
      ) : alerts.data.content.length === 0 ? (
        <EmptyState title="Queue is clear" body="No alerts match this filter. New flags land here, highest risk first." />
      ) : (
        <>
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th scope="col">Score</th>
                  <th scope="col">Band</th>
                  <th scope="col">Decision</th>
                  <th scope="col">State</th>
                  <th scope="col">Payment</th>
                  <th scope="col">Summary</th>
                  <th scope="col">Raised</th>
                </tr>
              </thead>
              <tbody>
                {alerts.data.content.map((alert) => (
                  <tr key={alert.id}>
                    <td>
                      <Link to={`/fraud/alerts/${alert.id}`}>
                        <strong>{alert.score}</strong>
                      </Link>
                    </td>
                    <td>
                      <Badge status={alert.band} />
                    </td>
                    <td>
                      <Badge status={alert.decision} />
                    </td>
                    <td>
                      <Badge status={alert.state} />
                    </td>
                    <td className="mono">
                      {formatMoney(alert.amount, alert.currency)}
                      <span className="field-hint" style={{ display: 'block' }}>
                        {alert.payeeName}
                      </span>
                    </td>
                    <td>{alert.summary}</td>
                    <td>{new Date(alert.createdAt).toLocaleString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <nav
            aria-label="Alert pages"
            style={{ display: 'flex', gap: 12, alignItems: 'center', marginTop: 12 }}
          >
            <Button size="sm" disabled={alerts.data.first} onClick={() => setPage((p) => p - 1)}>
              Previous
            </Button>
            <span role="status">
              Page {alerts.data.page + 1} of {Math.max(alerts.data.totalPages, 1)} ·{' '}
              {alerts.data.totalElements} alerts
            </span>
            <Button size="sm" disabled={alerts.data.last} onClick={() => setPage((p) => p + 1)}>
              Next
            </Button>
          </nav>
        </>
      )}
    </div>
  );
}
