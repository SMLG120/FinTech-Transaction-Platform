import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link } from 'react-router-dom';
import { openDispute, useDisputes } from '../../api/disputeApi';
import { useTransactions } from '../../api/transactionApi';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { PageHeader } from '../../components/ui/PageHeader';
import { EmptyState, ErrorState, Skeleton } from '../../components/ui/states';
import { useToast } from '../../components/ui/Toast';
import { userMessage } from '../../utils/format';
import { useAuth } from '../auth/AuthContext';
import { openDisputeSchema } from './schemas';
import type { OpenDisputeValues } from './schemas';

export function DisputesPage() {
  const { hasRole } = useAuth();
  const { notify } = useToast();
  // Staff see the queue; customers see their own cases — split server-side.
  const canOpen = hasRole('CUSTOMER', 'PLATFORM_ADMIN');
  const [status, setStatus] = useState('ALL');
  const [page, setPage] = useState(0);
  const disputes = useDisputes(status === 'ALL' ? undefined : status, page, 20);
  // Only settled payments can be disputed — offer the caller's own as options.
  const transactions = useTransactions(100);
  const settled = (transactions.data?.items ?? []).filter((txn) => txn.status === 'SETTLED');

  const form = useForm<OpenDisputeValues>({
    resolver: zodResolver(openDisputeSchema),
    defaultValues: { transactionId: '', reason: 'NOT_RECEIVED', description: '' },
  });
  const [opening, setOpening] = useState(false);

  const submit = async (values: OpenDisputeValues) => {
    setOpening(true);
    try {
      await openDispute(values.transactionId.trim(), values.reason, values.description.trim());
      notify('Dispute opened. A support agent will review the case.');
      form.reset();
      await disputes.refetch();
    } catch (error) {
      notify(userMessage(error), 'error');
    } finally {
      setOpening(false);
    }
  };

  return (
    <div>
      <PageHeader
        eyebrow="Money"
        title="Disputes"
        sub="Chargeback-style cases on settled payments. Staff see the queue; customers see their own cases."
      />

      {canOpen ? (
        <section className="card" aria-labelledby="open-heading" style={{ marginBottom: 16 }}>
          <h2 id="open-heading" style={{ fontSize: '0.9rem' }}>
            Open a dispute
          </h2>
          <p className="field-hint">
            Only settled payments can be disputed — and only your own. A second case on the same
            payment is refused.
          </p>
          <form onSubmit={(event) => void form.handleSubmit(submit)(event)} noValidate>
            <div className="field">
              <label className="field-label" htmlFor="dispute-txn">
                Transaction
              </label>
              {settled.length > 0 ? (
                <select
                  id="dispute-txn"
                  className="select"
                  {...form.register('transactionId')}
                >
                  <option value="">Select a settled payment…</option>
                  {settled.map((txn) => (
                    <option key={txn.id} value={txn.id}>
                      {txn.payeeName} — {txn.currency} {txn.amount}
                    </option>
                  ))}
                </select>
              ) : (
                <input
                  id="dispute-txn"
                  className="input mono"
                  placeholder="Transaction ID"
                  autoComplete="off"
                  {...form.register('transactionId')}
                />
              )}
              {form.formState.errors.transactionId ? (
                <p className="field-error" role="alert">
                  {form.formState.errors.transactionId.message}
                </p>
              ) : null}
            </div>
            <div className="field">
              <label className="field-label" htmlFor="dispute-reason">
                Reason
              </label>
              <select id="dispute-reason" className="select" {...form.register('reason')}>
                <option value="FRAUD">FRAUD</option>
                <option value="NOT_RECEIVED">NOT_RECEIVED</option>
                <option value="DUPLICATE">DUPLICATE</option>
                <option value="DEFECTIVE">DEFECTIVE</option>
                <option value="OTHER">OTHER</option>
              </select>
            </div>
            <div className="field">
              <label className="field-label" htmlFor="dispute-desc">
                What happened
              </label>
              <input
                id="dispute-desc"
                className="input"
                placeholder="The books never arrived"
                {...form.register('description')}
              />
              {form.formState.errors.description ? (
                <p className="field-error" role="alert">
                  {form.formState.errors.description.message}
                </p>
              ) : null}
            </div>
            <Button variant="primary" type="submit" disabled={opening}>
              {opening ? 'Opening…' : 'Open dispute'}
            </Button>
          </form>
        </section>
      ) : null}

      <div className="field" style={{ maxWidth: 240 }}>
        <label className="field-label" htmlFor="dispute-status">
          Status
        </label>
        <select
          id="dispute-status"
          className="select"
          value={status}
          onChange={(event) => {
            setStatus(event.target.value);
            setPage(0);
          }}
        >
          <option value="ALL">All</option>
          <option value="OPEN">Open</option>
          <option value="RESOLVED_REFUNDED">Refunded</option>
          <option value="RESOLVED_REJECTED">Rejected</option>
        </select>
      </div>

      {disputes.isPending ? (
        <Skeleton label="Loading disputes" />
      ) : disputes.isError ? (
        <ErrorState error={disputes.error} onRetry={() => void disputes.refetch()} />
      ) : disputes.data.content.length === 0 ? (
        <EmptyState title="No disputes" body="Open cases appear here with their status." />
      ) : (
        <>
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th scope="col">Case</th>
                  <th scope="col">Reason</th>
                  <th scope="col">Status</th>
                  <th scope="col">Statements</th>
                  <th scope="col">Opened</th>
                </tr>
              </thead>
              <tbody>
                {disputes.data.content.map((dispute) => (
                  <tr key={dispute.id}>
                    <td>
                      <Link to={`/disputes/${dispute.id}`} title={dispute.description}>
                        {dispute.id.slice(0, 8)}…
                      </Link>
                    </td>
                    <td>{dispute.reason}</td>
                    <td>
                      <Badge status={dispute.status} />
                    </td>
                    <td>{dispute.evidenceCount}</td>
                    <td>{new Date(dispute.createdAt).toLocaleString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <nav
            aria-label="Dispute pages"
            style={{ display: 'flex', gap: 12, alignItems: 'center', marginTop: 12 }}
          >
            <Button size="sm" disabled={disputes.data.first} onClick={() => setPage((p) => p - 1)}>
              Previous
            </Button>
            <span role="status">
              Page {disputes.data.page + 1} of {Math.max(disputes.data.totalPages, 1)} ·{' '}
              {disputes.data.totalElements} cases
            </span>
            <Button size="sm" disabled={disputes.data.last} onClick={() => setPage((p) => p + 1)}>
              Next
            </Button>
          </nav>
        </>
      )}
    </div>
  );
}
