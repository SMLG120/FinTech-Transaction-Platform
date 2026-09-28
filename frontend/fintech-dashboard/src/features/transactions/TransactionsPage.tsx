import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTransactions } from '../../api/transactionApi';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { EmptyState, ErrorState, Skeleton } from '../../components/ui/states';
import { formatMoney } from '../../utils/format';
import type { Transaction } from '../../types';

const PAGE_SIZE = 10;

type SortKey = 'date' | 'amount';
type SortDir = 'asc' | 'desc';

function compareAmount(a: Transaction, b: Transaction): number {
  return Number(a.amount) - Number(b.amount);
}

/** Filter + sort + paginate a transaction list client-side.
 * The list endpoint supports `limit` only, so querying is local. Pure — tested. */
export function queryTransactions(
  items: Transaction[],
  options: { search: string; status: string; sortKey: SortKey; sortDir: SortDir; page: number },
): { rows: Transaction[]; totalPages: number; total: number } {
  const needle = options.search.trim().toLowerCase();
  const filtered = items.filter((item) => {
    if (options.status !== 'ALL' && item.status.toUpperCase() !== options.status) return false;
    if (!needle) return true;
    return [item.payeeName, item.payeeReference ?? '', item.id, item.amount]
      .join(' ')
      .toLowerCase()
      .includes(needle);
  });
  const sorted = [...filtered].sort((a, b) => {
    const base =
      options.sortKey === 'amount'
        ? compareAmount(a, b)
        : new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime();
    return options.sortDir === 'asc' ? base : -base;
  });
  const totalPages = Math.max(1, Math.ceil(sorted.length / PAGE_SIZE));
  const page = Math.min(options.page, totalPages);
  return {
    rows: sorted.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE),
    totalPages,
    total: sorted.length,
  };
}

export function TransactionsPage() {
  const { data, isPending, isError, error, refetch } = useTransactions(200);
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('ALL');
  const [sortKey, setSortKey] = useState<SortKey>('date');
  const [sortDir, setSortDir] = useState<SortDir>('desc');
  const [page, setPage] = useState(1);

  const items = useMemo(() => data?.items ?? [], [data]);
  const { rows, totalPages, total } = useMemo(
    () => queryTransactions(items, { search, status, sortKey, sortDir, page }),
    [items, search, status, sortKey, sortDir, page],
  );

  if (isPending) {
    return (
      <div>
        <h1>Transactions</h1>
        <Skeleton label="Loading transactions" />
      </div>
    );
  }

  if (isError) {
    return (
      <div>
        <h1>Transactions</h1>
        <ErrorState error={error} onRetry={() => void refetch()} />
      </div>
    );
  }

  if (items.length === 0) {
    return (
      <div>
        <h1>Transactions</h1>
        <EmptyState
          title="No transactions"
          body="Payments appear here once you fund your account and pay."
        />
      </div>
    );
  }

  return (
    <div>
      <h1>Transactions</h1>
      <div
        style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 16 }}
        role="search"
        aria-label="Search and filter transactions"
      >
        <div className="field" style={{ marginBottom: 0, minWidth: 220 }}>
          <label className="field-label" htmlFor="txn-search">
            Search
          </label>
          <input
            id="txn-search"
            className="input"
            type="search"
            placeholder="Payee, reference or ID"
            value={search}
            onChange={(event) => {
              setSearch(event.target.value);
              setPage(1);
            }}
          />
        </div>
        <div className="field" style={{ marginBottom: 0 }}>
          <label className="field-label" htmlFor="txn-status">
            Status
          </label>
          <select
            id="txn-status"
            className="select"
            value={status}
            onChange={(event) => {
              setStatus(event.target.value);
              setPage(1);
            }}
          >
            {['ALL', 'PENDING', 'AUTHORIZED', 'DECLINED', 'SETTLED', 'REVERSED'].map((value) => (
              <option key={value} value={value}>
                {value === 'ALL' ? 'All statuses' : value}
              </option>
            ))}
          </select>
        </div>
        <div className="field" style={{ marginBottom: 0 }}>
          <label className="field-label" htmlFor="txn-sort">
            Sort by
          </label>
          <select
            id="txn-sort"
            className="select"
            value={`${sortKey}:${sortDir}`}
            onChange={(event) => {
              const [key, dir] = event.target.value.split(':') as [SortKey, SortDir];
              setSortKey(key);
              setSortDir(dir);
            }}
          >
            <option value="date:desc">Newest first</option>
            <option value="date:asc">Oldest first</option>
            <option value="amount:desc">Highest amount</option>
            <option value="amount:asc">Lowest amount</option>
          </select>
        </div>
      </div>

      {rows.length === 0 ? (
        <EmptyState title="No matching transactions" body="Adjust the search or status filter." />
      ) : (
        <>
          <p className="field-hint" role="status">
            Showing {rows.length} of {total} matching transactions.
          </p>
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th scope="col">Transaction</th>
                  <th scope="col">Payee</th>
                  <th scope="col">Amount</th>
                  <th scope="col">Status</th>
                  <th scope="col">Card</th>
                  <th scope="col">Date</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((txn) => (
                  <tr key={txn.id}>
                    <td className="mono">
                      <Link to={`/transactions/${txn.id}`} title={txn.id}>
                        {txn.id.slice(0, 8)}…
                      </Link>
                    </td>
                    <td>{txn.payeeName}</td>
                    <td className="mono">{formatMoney(txn.amount, txn.currency)}</td>
                    <td>
                      <Badge status={txn.status} />
                      {txn.declineReason ? (
                        <span className="field-hint" style={{ display: 'block' }}>
                          {txn.declineReason}
                        </span>
                      ) : null}
                    </td>
                    <td className="mono">{txn.cardLastFour ? `…${txn.cardLastFour}` : '—'}</td>
                    <td>{new Date(txn.createdAt).toLocaleString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <nav
            aria-label="Transaction pages"
            style={{ display: 'flex', gap: 12, alignItems: 'center', marginTop: 12 }}
          >
            <Button
              size="sm"
              disabled={page <= 1}
              onClick={() => setPage((value) => Math.max(1, value - 1))}
            >
              Previous
            </Button>
            <span role="status">
              Page {Math.min(page, totalPages)} of {totalPages}
            </span>
            <Button
              size="sm"
              disabled={page >= totalPages}
              onClick={() => setPage((value) => Math.min(totalPages, value + 1))}
            >
              Next
            </Button>
          </nav>
        </>
      )}
    </div>
  );
}
