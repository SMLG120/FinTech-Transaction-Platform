import { Suspense, lazy, useMemo } from 'react';
import {
  ArrowLeftRight,
  BadgeCheck,
  Clock3,
  Landmark,
  Plus,
  ShieldAlert,
  TrendingUp,
} from 'lucide-react';
import { Link } from 'react-router-dom';
import { useFraudSummary } from '../../api/fraudApi';
import { useBalance, useTransactions } from '../../api/transactionApi';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { CardHeader, StatCard } from '../../components/ui/Card';
import { Card } from '../../components/ui/Card';
import { PageHeader } from '../../components/ui/PageHeader';
import { EmptyState, ErrorState, Skeleton, TableSkeleton } from '../../components/ui/states';
import { formatMoney } from '../../utils/format';
import { bucketByDay, computeStats } from './stats';

// Code-split: Recharts is the heaviest dependency and only the dashboard
// needs it, so the charts load after the stats and tables paint.
const Charts = lazy(() => import('./Charts'));

function formatDateTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

export function DashboardPage() {
  const transactions = useTransactions(200);
  const fraud = useFraudSummary();
  const balance = useBalance();

  const stats = useMemo(
    () => computeStats(transactions.data?.items ?? []),
    [transactions.data],
  );
  const daily = useMemo(
    () => bucketByDay(transactions.data?.items ?? []),
    [transactions.data],
  );

  const approvalSlices = useMemo(
    () => [
      { name: 'Approved', value: stats.approved },
      { name: 'Declined', value: stats.declined },
      { name: 'Pending', value: stats.pending },
      { name: 'Reversed', value: stats.reversed },
    ],
    [stats],
  );

  const topCurrency = stats.totalsByCurrency[0];
  const openAlerts = fraud.data?.openAlerts ?? 0;
  const securityTone = openAlerts === 0 ? 'success' : openAlerts <= 5 ? 'warning' : 'danger';

  if (transactions.isPending) {
    return (
      <div>
        <PageHeader
          eyebrow="Overview"
          title="Dashboard"
          sub="Balances, payment activity, and security posture at a glance."
        />
        <Skeleton label="Loading dashboard" />
        <div style={{ marginTop: 16 }}>
          <TableSkeleton label="Loading dashboard tables" />
        </div>
      </div>
    );
  }

  if (transactions.isError) {
    return (
      <div>
        <PageHeader
          eyebrow="Overview"
          title="Dashboard"
          sub="Balances, payment activity, and security posture at a glance."
        />
        <ErrorState error={transactions.error} onRetry={() => void transactions.refetch()} />
      </div>
    );
  }

  const items = transactions.data?.items ?? [];

  return (
    <div>
      <PageHeader
        eyebrow="Overview"
        title="Dashboard"
        sub="Balances, payment activity, and security posture at a glance."
        actions={
          <>
            <Link to="/pay" className="btn btn-primary">
              <Plus size={16} aria-hidden="true" /> New payment
            </Link>
            <Link to="/transactions" className="btn">
              View transactions
            </Link>
          </>
        }
      />

      {items.length === 0 ? (
        <EmptyState
          title="No transactions yet"
          body="Fund your account and make a payment — figures appear here once money moves."
          action={
            <Link to="/pay" className="btn btn-primary">
              <Plus size={16} aria-hidden="true" /> Fund account & pay
            </Link>
          }
        />
      ) : (
        <>
          {/* Balance hero — the trust anchor of the page */}
          <section className="hero" aria-label="Account balances">
            <div>
              <p className="hero-label">Total balance</p>
              <p className="hero-balance">
                {balance.data
                  ? formatMoney(
                      String(
                        Number(balance.data.available ?? 0) + Number(balance.data.held ?? 0),
                      ),
                      balance.data.currency,
                    )
                  : topCurrency
                    ? `${topCurrency.currency} ${topCurrency.total.toFixed(2)}`
                    : '—'}
              </p>
              <p className="hero-sub">
                {balance.data
                  ? `Available ${formatMoney(balance.data.available, balance.data.currency)} · Held ${formatMoney(balance.data.held, balance.data.currency)}`
                  : 'Ledger volume across settled and authorised payments.'}
              </p>
              <div className="hero-actions">
                <Link to="/pay" className="btn btn-primary btn-sm">
                  <Plus size={15} aria-hidden="true" /> Pay / fund
                </Link>
                <Link to="/cards" className="btn btn-sm">
                  Manage cards
                </Link>
              </div>
            </div>
            <div className="hero-meta">
              <p className="hero-label">Available to spend</p>
              <p className="hero-amount">
                {balance.data ? formatMoney(balance.data.available, balance.data.currency) : '—'}
              </p>
              <p className="hero-sub">Cleared funds, ready for payments.</p>
            </div>
            <div className="hero-meta">
              <p className="hero-label">Security posture</p>
              <p className="hero-amount">
                {fraud.data ? `${openAlerts} open alert${openAlerts === 1 ? '' : 's'}` : '—'}
              </p>
              <p className="hero-sub">
                {fraud.data
                  ? `${fraud.data.claimedAlerts} claimed · ${Math.round(fraud.data.declinedRate * 100)}% decline rate`
                  : 'Fraud queue status unavailable.'}
              </p>
            </div>
          </section>

          <div className="grid-stats">
            <StatCard
              title="Total transactions"
              value={String(stats.total)}
              hint={`${stats.approved} approved · ${stats.declined} declined`}
              icon={<ArrowLeftRight size={17} aria-hidden="true" />}
              tone="accent"
            />
            <StatCard
              title="Approval rate"
              value={`${Math.round(stats.approvalRate * 100)}%`}
              hint={`${stats.approved} approved of ${stats.approved + stats.declined} decided`}
              icon={<BadgeCheck size={17} aria-hidden="true" />}
              tone="success"
            />
            <StatCard
              title="Pending review"
              value={String(stats.pending)}
              hint={stats.reversed > 0 ? `${stats.reversed} reversed` : 'Awaiting capture or decision'}
              icon={<Clock3 size={17} aria-hidden="true" />}
              tone="warning"
            />
            <StatCard
              title="Volume"
              value={
                topCurrency ? `${topCurrency.currency} ${topCurrency.total.toFixed(2)}` : '—'
              }
              hint={
                topCurrency
                  ? `${topCurrency.count} payments${stats.totalsByCurrency.length > 1 ? ` · +${stats.totalsByCurrency.length - 1} more currencies` : ''}`
                  : undefined
              }
              icon={<TrendingUp size={17} aria-hidden="true" />}
            />
            <StatCard
              title="Held funds"
              value={
                balance.data ? formatMoney(balance.data.held, balance.data.currency) : '—'
              }
              hint={balance.data ? `Currency ${balance.data.currency}` : 'Balance service unavailable'}
              icon={<Landmark size={17} aria-hidden="true" />}
            />
            <StatCard
              title="Open fraud alerts"
              value={fraud.data ? String(openAlerts) : '—'}
              hint={
                fraud.data
                  ? `${fraud.data.claimedAlerts} claimed · ${fraud.data.breachingAlerts} past SLA`
                  : 'Fraud service unavailable'
              }
              icon={<ShieldAlert size={17} aria-hidden="true" />}
              tone={securityTone}
            />
          </div>

          <Suspense fallback={<Skeleton label="Loading charts" />}>
            <Charts daily={daily} slices={approvalSlices} />
          </Suspense>

          <div className="grid-2">
            <Card labelledBy="recent-activity">
              <CardHeader
                titleId="recent-activity"
                title="Recent activity"
                sub="Latest payments across all statuses"
                actions={
                  <Link to="/transactions">
                    <Button size="sm" variant="ghost">
                      View all
                    </Button>
                  </Link>
                }
              />
              <div className="table-wrap" style={{ boxShadow: 'none' }}>
                <table className="data">
                  <thead>
                    <tr>
                      <th scope="col">Payee</th>
                      <th scope="col" className="num">
                        Amount
                      </th>
                      <th scope="col">Status</th>
                      <th scope="col">Date</th>
                    </tr>
                  </thead>
                  <tbody>
                    {items.slice(0, 8).map((txn) => (
                      <tr key={txn.id}>
                        <td className="row-main">
                          <Link to={`/transactions/${txn.id}`}>{txn.payeeName}</Link>
                          <span className="sub-cell mono">{txn.id.slice(0, 8)}…</span>
                        </td>
                        <td className="num amount">{formatMoney(txn.amount, txn.currency)}</td>
                        <td>
                          <Badge status={txn.status} />
                        </td>
                        <td style={{ whiteSpace: 'nowrap' }}>{formatDateTime(txn.createdAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>

            <Card labelledBy="security-watch">
              <CardHeader
                titleId="security-watch"
                title="Security watch"
                sub="What needs attention before money moves further"
                actions={
                  <Link to="/fraud">
                    <Button size="sm" variant="ghost">
                      Open queue
                    </Button>
                  </Link>
                }
              />
              {!fraud.data ? (
                <p className="field-hint">
                  Fraud summary unavailable — the queue can still be worked from Fraud &amp;
                  Security.
                </p>
              ) : openAlerts === 0 ? (
                <div className="alert alert-success" role="status">
                  <ShieldAlert size={18} aria-hidden="true" />
                  <div>
                    <strong>No open fraud alerts</strong>
                    <p>The scoring engine is current and the queue is clear.</p>
                  </div>
                </div>
              ) : (
                <div className="alert alert-warning" role="status">
                  <ShieldAlert size={18} aria-hidden="true" />
                  <div>
                    <strong>
                      {openAlerts} open alert{openAlerts === 1 ? '' : 's'} ·{' '}
                      {fraud.data.breachingAlerts} past SLA
                    </strong>
                    <p>
                      {fraud.data.claimedAlerts} claimed by analysts ·{' '}
                      {Math.round(fraud.data.declinedRate * 100)}% decline rate in window. Work
                      highest risk first.
                    </p>
                  </div>
                </div>
              )}
              <dl className="spec" style={{ marginTop: 12 }}>
                <div>
                  <dt>Pending payments</dt>
                  <dd>{stats.pending} awaiting capture</dd>
                </div>
                <div>
                  <dt>Reversed</dt>
                  <dd>{stats.reversed} returned to sender</dd>
                </div>
                <div>
                  <dt>Held funds</dt>
                  <dd className="mono">
                    {balance.data ? formatMoney(balance.data.held, balance.data.currency) : '—'}
                  </dd>
                </div>
              </dl>
            </Card>
          </div>
        </>
      )}
    </div>
  );
}
