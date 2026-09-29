import { AlertTriangle, Clock3, ShieldAlert, ShieldCheck, UserCheck } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAlerts, useFraudSummary } from '../../api/fraudApi';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { Card, CardHeader, StatCard } from '../../components/ui/Card';
import { PageHeader } from '../../components/ui/PageHeader';
import { EmptyState, ErrorState, TableSkeleton } from '../../components/ui/states';
import { formatMoney } from '../../utils/format';

function RiskMeter({ score }: { score: number }) {
  const level = score >= 75 ? 4 : score >= 50 ? 3 : score >= 25 ? 2 : 1;
  const cls = (i: number) => {
    if (i > level) return 'risk-seg';
    if (score >= 75) return 'risk-seg on-high';
    if (score >= 50) return 'risk-seg on-med';
    return 'risk-seg on-low';
  };
  return (
    <span className="risk-meter" role="img" aria-label={`Risk score ${score} of 100`}>
      {[1, 2, 3, 4].map((i) => (
        <span key={i} className={cls(i)} />
      ))}
      <strong style={{ marginLeft: 6, fontVariantNumeric: 'tabular-nums' }}>{score}</strong>
    </span>
  );
}

export function FraudPage() {
  const [band, setBand] = useState('ALL');
  const [page, setPage] = useState(0);
  const summary = useFraudSummary();
  const alerts = useAlerts(band === 'ALL' ? undefined : band, page, 50);

  return (
    <div>
      <PageHeader
        eyebrow="Risk & compliance"
        title="Fraud monitoring"
        sub="Scored after the payment, never before it — the engine is off the payment critical path. A payment can be authorised first and flagged later; working that queue is this page's job."
      />

      {summary.data ? (
        <div className="grid-stats">
          <StatCard
            title="Open alerts"
            value={String(summary.data.openAlerts)}
            hint="Awaiting analyst triage"
            icon={<ShieldAlert size={17} aria-hidden="true" />}
            tone={summary.data.openAlerts === 0 ? 'success' : 'danger'}
          />
          <StatCard
            title="Claimed"
            value={String(summary.data.claimedAlerts)}
            hint="Under active investigation"
            icon={<UserCheck size={17} aria-hidden="true" />}
            tone="accent"
          />
          <StatCard
            title="Past SLA"
            value={String(summary.data.breachingAlerts)}
            hint="Open or claimed beyond the window"
            icon={<Clock3 size={17} aria-hidden="true" />}
            tone={summary.data.breachingAlerts > 0 ? 'warning' : 'default'}
          />
          <StatCard
            title="Decline rate"
            value={`${Math.round(summary.data.declinedRate * 100)}%`}
            hint={`${summary.data.totalDecisions} decisions in window`}
            icon={<ShieldCheck size={17} aria-hidden="true" />}
          />
        </div>
      ) : null}

      {summary.data && summary.data.breachingAlerts > 0 ? (
        <div className="alert alert-danger" role="alert">
          <AlertTriangle size={18} aria-hidden="true" />
          <div>
            <strong>{summary.data.breachingAlerts} alerts past SLA</strong>
            <p>Triage breaching alerts before opening new ones — oldest breach first.</p>
          </div>
        </div>
      ) : null}

      <Card labelledBy="queue-heading">
        <CardHeader
          titleId="queue-heading"
          title="Alert queue"
          sub="Highest risk first · claim before acting"
          actions={
            <div className="field" style={{ marginBottom: 0, minWidth: 170 }}>
              <label className="field-label" htmlFor="alert-band" style={{ fontSize: '0.76rem' }}>
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
          }
        />

        {alerts.isPending ? (
          <TableSkeleton label="Loading alert queue" />
        ) : alerts.isError ? (
          <ErrorState error={alerts.error} onRetry={() => void alerts.refetch()} />
        ) : alerts.data.content.length === 0 ? (
          <EmptyState
            title="Queue is clear"
            body="No alerts match this filter. New flags land here, highest risk first."
          />
        ) : (
          <>
            <div className="table-wrap" style={{ boxShadow: 'none' }}>
              <table className="data">
                <thead>
                  <tr>
                    <th scope="col">Risk</th>
                    <th scope="col">Band</th>
                    <th scope="col">Decision</th>
                    <th scope="col">State</th>
                    <th scope="col" className="num">
                      Payment
                    </th>
                    <th scope="col">Summary</th>
                    <th scope="col">Raised</th>
                  </tr>
                </thead>
                <tbody>
                  {alerts.data.content.map((alert) => (
                    <tr key={alert.id}>
                      <td>
                        <Link to={`/fraud/alerts/${alert.id}`}>
                          <RiskMeter score={alert.score} />
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
                      <td className="num amount">
                        {formatMoney(alert.amount, alert.currency)}
                        <span className="sub-cell">{alert.payeeName}</span>
                      </td>
                      <td style={{ maxWidth: 280 }}>{alert.summary}</td>
                      <td style={{ whiteSpace: 'nowrap' }}>
                        {new Date(alert.createdAt).toLocaleString()}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <nav className="pagination" aria-label="Alert pages">
              <Button size="sm" disabled={alerts.data.first} onClick={() => setPage((p) => p - 1)}>
                Previous
              </Button>
              <span className="pagination-status" role="status">
                Page {alerts.data.page + 1} of {Math.max(alerts.data.totalPages, 1)} ·{' '}
                {alerts.data.totalElements} alerts
              </span>
              <Button size="sm" disabled={alerts.data.last} onClick={() => setPage((p) => p + 1)}>
                Next
              </Button>
            </nav>
          </>
        )}
      </Card>
    </div>
  );
}
