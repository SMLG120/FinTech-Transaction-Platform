import { Suspense, lazy, useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useFraudSummary } from '../../api/fraudApi';
import { useBalance, useTransactions } from '../../api/transactionApi';
import { Badge } from '../../components/ui/Badge';
import { StatCard } from '../../components/ui/Card';
import { EmptyState, ErrorState, Skeleton } from '../../components/ui/states';
import { formatMoney } from '../../utils/format';
import { bucketByDay, computeStats } from './stats';

// Code-split: Recharts is the heaviest dependency and only the dashboard
// needs it, so the charts load after the stats and tables paint.
const Charts = lazy(() => import('./Charts'));

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

  if (transactions.isPending) {
    return (
      <div>
        <h1>Dashboard</h1>
        <Skeleton label="Loading dashboard" />
      </div>
    );
  }

  if (transactions.isError) {
    return (
      <div>
        <h1>Dashboard</h1>
        <ErrorState error={transactions.error} onRetry={() => void transactions.refetch()} />
      </div>
    );
  }

  const items = transactions.data?.items ?? [];

  return (
    <div>
      <h1>Dashboard</h1>
      {items.length === 0 ? (
        <EmptyState
          title="No transactions yet"
          body="Fund your account and make a payment — figures appear here once money moves."
        />
      ) : (
        <>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))',
              gap: 16,
              marginBottom: 24,
            }}
          >
            <StatCard title="Total transactions" value={String(stats.total)} />
            <StatCard
              title="Approval rate"
              value={`${Math.round(stats.approvalRate * 100)}%`}
              hint={`${stats.approved} approved · ${stats.declined} declined`}
            />
            <StatCard
              title="Pending"
              value={String(stats.pending)}
              hint={stats.reversed > 0 ? `${stats.reversed} reversed` : undefined}
            />
            <StatCard
              title="Volume"
              value={
                topCurrency
                  ? `${topCurrency.currency} ${topCurrency.total.toFixed(2)}`
                  : '—'
              }
              hint={
                topCurrency
                  ? `${topCurrency.count} payments${stats.totalsByCurrency.length > 1 ? ` · +${stats.totalsByCurrency.length - 1} more currencies` : ''}`
                  : undefined
              }
            />
            {balance.data ? (
              <StatCard
                title={`Available · ${balance.data.currency}`}
                value={formatMoney(balance.data.available, balance.data.currency)}
                hint={`Held ${formatMoney(balance.data.held, balance.data.currency)}`}
              />
            ) : null}
            {fraud.data ? (
              <StatCard
                title="Open fraud alerts"
                value={String(fraud.data.openAlerts)}
                hint={`${fraud.data.claimedAlerts} claimed · ${Math.round(fraud.data.declinedRate * 100)}% decline rate`}
              />
            ) : null}
          </div>

          <Suspense fallback={<Skeleton label="Loading charts" />}>
            <Charts daily={daily} slices={approvalSlices} />
          </Suspense>

          <section className="card" aria-labelledby="recent-activity">
            <h2 id="recent-activity" style={{ fontSize: '0.9rem' }}>
              Recent activity
            </h2>
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th scope="col">Payee</th>
                    <th scope="col">Amount</th>
                    <th scope="col">Status</th>
                    <th scope="col">Date</th>
                  </tr>
                </thead>
                <tbody>
                  {items.slice(0, 8).map((txn) => (
                    <tr key={txn.id}>
                      <td>
                        <Link to={`/transactions/${txn.id}`}>{txn.payeeName}</Link>
                      </td>
                      <td className="mono">{formatMoney(txn.amount, txn.currency)}</td>
                      <td>
                        <Badge status={txn.status} />
                      </td>
                      <td>{new Date(txn.createdAt).toLocaleString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        </>
      )}
    </div>
  );
}
