import { useState } from 'react';
import { Link } from 'react-router-dom';
import {
  useAuditByCorrelation,
  useAuditByResource,
  useAuditByTransaction,
  useAuditRecords,
} from '../../api/auditApi';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { EmptyState, ErrorState, Skeleton } from '../../components/ui/states';
import type { AuditRecord } from '../../types';

function RecordTable({ rows }: { rows: AuditRecord[] }) {
  if (rows.length === 0) {
    return <EmptyState title="No records" body="Nothing on the trail matches this lookup." />;
  }
  return (
    <div className="table-wrap">
      <table className="data">
        <thead>
          <tr>
            <th scope="col">Occurred</th>
            <th scope="col">Action</th>
            <th scope="col">Resource</th>
            <th scope="col">Result</th>
            <th scope="col">Correlation</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((record) => (
            <tr key={record.id}>
              <td>{new Date(record.occurredAt).toLocaleString()}</td>
              <td className="mono">{record.action}</td>
              <td>
                <span className="mono">
                  {record.resourceType}/{record.resourceId.slice(0, 8)}…
                </span>
                {record.transactionId ? (
                  <span className="field-hint" style={{ display: 'block' }}>
                    payment{' '}
                    <Link to={`/transactions/${record.transactionId}`} className="mono">
                      {record.transactionId.slice(0, 8)}…
                    </Link>
                  </span>
                ) : null}
              </td>
              <td>
                <Badge status={record.result} />
              </td>
              <td className="mono" title={record.correlationId}>
                {record.correlationId.slice(0, 8)}…
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function AuditPage() {
  const [action, setAction] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);

  /** datetime-local carries no offset; the API takes Instants, so interpret as local time. */
  const toInstant = (value: string): string | undefined => {
    if (!value) return undefined;
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
  };

  const trail = useAuditRecords({
    action: action || undefined,
    from: toInstant(from),
    to: toInstant(to),
    page,
    size: 20,
  });

  const [txInput, setTxInput] = useState('');
  const [txLookup, setTxLookup] = useState<string | null>(null);
  const byTx = useAuditByTransaction(txLookup);

  const [corrInput, setCorrInput] = useState('');
  const [corrLookup, setCorrLookup] = useState<string | null>(null);
  const byCorr = useAuditByCorrelation(corrLookup);

  const [resType, setResType] = useState('');
  const [resId, setResId] = useState('');
  const [resLookup, setResLookup] = useState<{ type: string; id: string } | null>(null);
  const byRes = useAuditByResource(resLookup?.type ?? null, resLookup?.id ?? null);

  return (
    <div>
      <h1>Audit logs</h1>
      <p className="field-hint">
        Append-only by database trigger — an UPDATE or DELETE against the trail is refused, and the
        API has no write endpoint. Actors appear as digests, never names.
      </p>

      <section className="card" aria-labelledby="trail-heading" style={{ marginBottom: 16 }}>
        <h2 id="trail-heading" style={{ fontSize: '0.9rem' }}>
          Trail
        </h2>
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 12 }}>
          <div className="field" style={{ marginBottom: 0, minWidth: 180 }}>
            <label className="field-label" htmlFor="audit-action">
              Action
            </label>
            <input
              id="audit-action"
              className="input mono"
              placeholder="e.g. PAYMENT_SETTLED"
              value={action}
              onChange={(event) => {
                setAction(event.target.value);
                setPage(0);
              }}
            />
          </div>
          <div className="field" style={{ marginBottom: 0 }}>
            <label className="field-label" htmlFor="audit-from">
              From
            </label>
            <input
              id="audit-from"
              className="input"
              type="datetime-local"
              value={from}
              onChange={(event) => {
                setFrom(event.target.value);
                setPage(0);
              }}
            />
          </div>
          <div className="field" style={{ marginBottom: 0 }}>
            <label className="field-label" htmlFor="audit-to">
              To
            </label>
            <input
              id="audit-to"
              className="input"
              type="datetime-local"
              value={to}
              onChange={(event) => {
                setTo(event.target.value);
                setPage(0);
              }}
            />
          </div>
        </div>

        {trail.isPending ? (
          <Skeleton label="Loading audit trail" />
        ) : trail.isError ? (
          <ErrorState error={trail.error} onRetry={() => void trail.refetch()} />
        ) : (
          <>
            <RecordTable rows={trail.data.content} />
            <nav aria-label="Audit pages" style={{ display: 'flex', gap: 12, alignItems: 'center', marginTop: 12 }}>
              <Button size="sm" disabled={trail.data.first} onClick={() => setPage((p) => p - 1)}>
                Previous
              </Button>
              <span role="status">
                Page {trail.data.page + 1} of {Math.max(trail.data.totalPages, 1)} ·{' '}
                {trail.data.totalElements} records
              </span>
              <Button size="sm" disabled={trail.data.last} onClick={() => setPage((p) => p + 1)}>
                Next
              </Button>
            </nav>
          </>
        )}
      </section>

      <div
        style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', gap: 16 }}
      >
        <section className="card" aria-labelledby="trace-heading">
          <h2 id="trace-heading" style={{ fontSize: '0.9rem' }}>
            Trace a request
          </h2>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              setCorrLookup(corrInput.trim() || null);
            }}
          >
            <div className="field">
              <label className="field-label" htmlFor="audit-corr">
                Correlation ID
              </label>
              <input
                id="audit-corr"
                className="input mono"
                autoComplete="off"
                placeholder="From any error message"
                value={corrInput}
                onChange={(event) => setCorrInput(event.target.value)}
              />
            </div>
            <Button type="submit" disabled={!corrInput.trim()}>
              Trace
            </Button>
          </form>
          <div style={{ marginTop: 12 }}>
            {!corrLookup ? null : byCorr.isPending ? (
              <Skeleton label="Tracing request" />
            ) : byCorr.isError ? (
              <ErrorState error={byCorr.error} onRetry={() => void byCorr.refetch()} />
            ) : (
              <RecordTable rows={byCorr.data?.content ?? []} />
            )}
          </div>
        </section>

        <section className="card" aria-labelledby="paytrail-heading">
          <h2 id="paytrail-heading" style={{ fontSize: '0.9rem' }}>
            Payment trail
          </h2>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              setTxLookup(txInput.trim() || null);
            }}
          >
            <div className="field">
              <label className="field-label" htmlFor="audit-tx">
                Transaction ID
              </label>
              <input
                id="audit-tx"
                className="input mono"
                autoComplete="off"
                value={txInput}
                onChange={(event) => setTxInput(event.target.value)}
              />
            </div>
            <Button type="submit" disabled={!txInput.trim()}>
              Show trail
            </Button>
          </form>
          <div style={{ marginTop: 12 }}>
            {!txLookup ? null : byTx.isPending ? (
              <Skeleton label="Loading payment trail" />
            ) : byTx.isError ? (
              <ErrorState error={byTx.error} onRetry={() => void byTx.refetch()} />
            ) : (
              <RecordTable rows={byTx.data?.content ?? []} />
            )}
          </div>
        </section>

        <section className="card" aria-labelledby="resource-heading">
          <h2 id="resource-heading" style={{ fontSize: '0.9rem' }}>
            Resource history
          </h2>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              setResLookup(resType.trim() && resId.trim() ? { type: resType.trim(), id: resId.trim() } : null);
            }}
          >
            <div className="field">
              <label className="field-label" htmlFor="audit-restype">
                Resource type
              </label>
              <input
                id="audit-restype"
                className="input mono"
                placeholder="e.g. FRAUD_ALERT"
                autoComplete="off"
                value={resType}
                onChange={(event) => setResType(event.target.value)}
              />
            </div>
            <div className="field">
              <label className="field-label" htmlFor="audit-resid">
                Resource ID
              </label>
              <input
                id="audit-resid"
                className="input mono"
                autoComplete="off"
                value={resId}
                onChange={(event) => setResId(event.target.value)}
              />
            </div>
            <Button type="submit" disabled={!resType.trim() || !resId.trim()}>
              Show history
            </Button>
          </form>
          <div style={{ marginTop: 12 }}>
            {!resLookup ? null : byRes.isPending ? (
              <Skeleton label="Loading resource history" />
            ) : byRes.isError ? (
              <ErrorState error={byRes.error} onRetry={() => void byRes.refetch()} />
            ) : (
              <RecordTable rows={byRes.data?.content ?? []} />
            )}
          </div>
        </section>
      </div>
    </div>
  );
}
