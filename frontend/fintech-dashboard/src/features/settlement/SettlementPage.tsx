import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useBreaks, useCycles } from '../../api/settlementApi';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { EmptyState, ErrorState, Skeleton } from '../../components/ui/states';
import { formatMoney } from '../../utils/format';

export function SettlementPage() {
  const [cycleStatus, setCycleStatus] = useState('ALL');
  const [cyclePage, setCyclePage] = useState(0);
  const cycles = useCycles(cycleStatus === 'ALL' ? undefined : cycleStatus, cyclePage, 20);

  const [breakStatus, setBreakStatus] = useState('ALL');
  const [breakPage, setBreakPage] = useState(0);
  const breaks = useBreaks(breakStatus === 'ALL' ? undefined : breakStatus, breakPage, 20);

  return (
    <div>
      <h1>Settlement</h1>
      <p className="field-hint">
        Counted from the events, closed, given out, then compared with a figure from outside — a
        system that reconciles its own arithmetic against itself has checked nothing. A statement
        that has been given out cannot be edited.
      </p>

      <section className="card" aria-labelledby="cycles-heading" style={{ marginBottom: 16 }}>
        <h2 id="cycles-heading" style={{ fontSize: '0.9rem' }}>
          Periods
        </h2>
        <div className="field" style={{ maxWidth: 240 }}>
          <label className="field-label" htmlFor="cycle-status">
            Status
          </label>
          <select
            id="cycle-status"
            className="select"
            value={cycleStatus}
            onChange={(event) => {
              setCycleStatus(event.target.value);
              setCyclePage(0);
            }}
          >
            <option value="ALL">All</option>
            <option value="OPEN">Open</option>
            <option value="CLOSED">Closed</option>
            <option value="RECONCILED">Reconciled</option>
            <option value="BROKEN">Broken</option>
          </select>
        </div>

        {cycles.isPending ? (
          <Skeleton label="Loading settlement periods" />
        ) : cycles.isError ? (
          <ErrorState error={cycles.error} onRetry={() => void cycles.refetch()} />
        ) : cycles.data.content.length === 0 ? (
          <EmptyState title="No periods" body="Settlement periods appear here once money moves." />
        ) : (
          <>
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th scope="col">Reference</th>
                    <th scope="col">Date</th>
                    <th scope="col">Status</th>
                    <th scope="col">Expected</th>
                    <th scope="col">Actual</th>
                    <th scope="col">Difference</th>
                    <th scope="col">Lines</th>
                    <th scope="col">Open breaks</th>
                  </tr>
                </thead>
                <tbody>
                  {cycles.data.content.map((cycle) => (
                    <tr key={cycle.id}>
                      <td className="mono">
                        <Link to={`/settlement/cycles/${encodeURIComponent(cycle.reference)}`}>
                          {cycle.reference}
                        </Link>
                      </td>
                      <td>{cycle.businessDate}</td>
                      <td>
                        <Badge status={cycle.status} />
                      </td>
                      <td className="mono">{formatMoney(cycle.expected, cycle.currency)}</td>
                      <td className="mono">
                        {cycle.actual === null ? '— not declared' : formatMoney(cycle.actual, cycle.currency)}
                      </td>
                      <td className="mono">
                        {cycle.difference === null ? '—' : formatMoney(cycle.difference, cycle.currency)}
                      </td>
                      <td>{cycle.lineCount}</td>
                      <td>{cycle.openBreaks}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <nav aria-label="Cycle pages" style={{ display: 'flex', gap: 12, alignItems: 'center', marginTop: 12 }}>
              <Button size="sm" disabled={cycles.data.first} onClick={() => setCyclePage((p) => p - 1)}>
                Previous
              </Button>
              <span role="status">
                Page {cycles.data.page + 1} of {Math.max(cycles.data.totalPages, 1)}
              </span>
              <Button size="sm" disabled={cycles.data.last} onClick={() => setCyclePage((p) => p + 1)}>
                Next
              </Button>
            </nav>
          </>
        )}
      </section>

      <section className="card" aria-labelledby="breaks-heading">
        <h2 id="breaks-heading" style={{ fontSize: '0.9rem' }}>
          Reconciliation findings
        </h2>
        <div className="field" style={{ maxWidth: 240 }}>
          <label className="field-label" htmlFor="break-status">
            Status
          </label>
          <select
            id="break-status"
            className="select"
            value={breakStatus}
            onChange={(event) => {
              setBreakStatus(event.target.value);
              setBreakPage(0);
            }}
          >
            <option value="ALL">All</option>
            <option value="OPEN">Open</option>
            <option value="ACKNOWLEDGED">Acknowledged</option>
            <option value="RESOLVED">Resolved</option>
          </select>
        </div>

        {breaks.isPending ? (
          <Skeleton label="Loading findings" />
        ) : breaks.isError ? (
          <ErrorState error={breaks.error} onRetry={() => void breaks.refetch()} />
        ) : breaks.data.content.length === 0 ? (
          <EmptyState title="No findings" body="A mismatch between the statement and the declared actual records a break here." />
        ) : (
          <>
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th scope="col">Period</th>
                    <th scope="col">Kind</th>
                    <th scope="col">Status</th>
                    <th scope="col">Detail</th>
                    <th scope="col">Raised</th>
                  </tr>
                </thead>
                <tbody>
                  {breaks.data.content.map((found) => (
                    <tr key={found.id}>
                      <td className="mono">
                        <Link to={`/settlement/cycles/${encodeURIComponent(found.reference)}`}>
                          {found.reference}
                        </Link>
                      </td>
                      <td className="mono">{found.kind}</td>
                      <td>
                        <Badge status={found.status} />
                      </td>
                      <td>{found.detail}</td>
                      <td>{new Date(found.createdAt).toLocaleString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <nav aria-label="Finding pages" style={{ display: 'flex', gap: 12, alignItems: 'center', marginTop: 12 }}>
              <Button size="sm" disabled={breaks.data.first} onClick={() => setBreakPage((p) => p - 1)}>
                Previous
              </Button>
              <span role="status">
                Page {breaks.data.page + 1} of {Math.max(breaks.data.totalPages, 1)}
              </span>
              <Button size="sm" disabled={breaks.data.last} onClick={() => setBreakPage((p) => p + 1)}>
                Next
              </Button>
            </nav>
          </>
        )}
      </section>
    </div>
  );
}
