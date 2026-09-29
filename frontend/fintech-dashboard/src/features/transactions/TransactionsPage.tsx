import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTransactions } from '../../api/transactionApi';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { PageHeader } from '../../components/ui/PageHeader';
import { EmptyState, ErrorState, TableSkeleton } from '../../components/ui/states';
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

function formatDateTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
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
        <PageHeader
          eyebrow="Money"
          title="Transactions"
          sub="Searchable ledger of every payment, with status and settlement state."
        />
        <TableSkeleton label="Loading transactions" />
      </div>
    );
  }

  if (isError) {
    return (
      <div>
        <PageHeader
          eyebrow="Money"
          title="Transactions"
          sub="Searchable ledger of every payment, with status and settlement state."
        />
        <ErrorState error={error} onRetry={() => void refetch()} />
      </div>
    );
  }

  if (items.length === 0) {
    return (
      <div>
        <PageHeader
          eyebrow="Money"
          title="Transactions"
          sub="Searchable ledger of every payment, with status and settlement state."
          actions={
            <Link to="/pay" className="btn btn-primary">
              Make a payment
            </Link>
          }
        />
        <EmptyState
          title="No transactions"
          body="Payments appear here once you fund your account and pay."
          action={
            <Link to="/pay" className="btn btn-primary">
              Go to Pay & fund
            </Link>
          }
        />
      </div>
    );
  }

  return (
    <div>
      <PageHeader
        eyebrow="Money"
        title="Transactions"
        sub="Searchable ledger of every payment, with status and settlement state."
        actions={
          <Link to="/pay" className="btn btn-primary">
            New payment
          </Link>
        }
      />

      <div className="toolbar" role="search" aria-label="Search and filter transactions">
        <div className="field field-grow">
          <label className="field-label" htmlFor="txn-search">
            Search
          </label>
          <input
            id="txn-search"
            className="input"
            type="search"
            placeholder="Payee, reference, ID, or amount"
            value={search}
            onChange={(event) => {
              setSearch(event.target.value);
              setPage(1);
            }}
          />
        </div>
        <div className="field">
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
                {value === 'ALL' ? 'All statuses' : value.charAt(0) + value.slice(1).toLowerCase()}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
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
        {(search || status !== 'ALL') && (
          <div className="field">
            <span className="field-label" aria-hidden="true">
              &nbsp;
            </span>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                setSearch('');
                setStatus('ALL');
                setPage(1);
              }}
            >
              Clear filters
            </Button>
          </div>
        )}
      </div>

      {rows.length === 0 ? (
        <EmptyState
          icon="search"
          title="No matching transactions"
          body="Adjust the search or status filter — nothing in the ledger matches."
          action={
            <Button
              onClick={() => {
                setSearch('');
                setStatus('ALL');
                setPage(1);
              }}
            >
              Reset filters
            </Button>
          }
        />
      ) : (
        <>
          <p className="toolbar-count" role="status">
            Showing {rows.length} of {total} matching transactions · Page {Math.min(page, totalPages)} of{' '}
            {totalPages}
          </p>
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th scope="col">Transaction</th>
                  <th scope="col">Payee</th>
                  <th scope="col" className="num">
                    Amount
                  </th>
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
                    <td className="row-main">
                      {txn.payeeName}
                      {txn.payeeReference ? (
                        <span className="sub-cell mono">{txn.payeeReference}</span>
                      ) : null}
                    </td>
                    <td className="num amount">{formatMoney(txn.amount, txn.currency)}</td>
                    <td>
                      <Badge status={txn.status} />
                      {txn.declineReason ? (
                        <span className="sub-cell">{txn.declineReason}</span>
                      ) : null}
                    </td>
                    <td className="mono">{txn.cardLastFour ? `…${txn.cardLastFour}` : '—'}</td>
                    <td style={{ whiteSpace: 'nowrap' }}>{formatDateTime(txn.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <nav className="pagination" aria-label="Transaction pages">
            <Button
              size="sm"
              disabled={page <= 1}
              onClick={() => setPage((value) => Math.max(1, value - 1))}
            >
              Previous
            </Button>
            <span className="pagination-status" role="status">
              Page {Math.min(page, totalPages)} of {totalPages} · {total} records
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
