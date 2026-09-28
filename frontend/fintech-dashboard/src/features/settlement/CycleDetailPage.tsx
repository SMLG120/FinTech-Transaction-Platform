import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useParams } from 'react-router-dom';
import {
  acknowledgeBreak,
  reconcileCycle,
  resolveBreak,
  useCloseCycle,
  useCycle,
  useDeclareActual,
} from '../../api/settlementApi';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { EmptyState, ErrorState, Skeleton } from '../../components/ui/states';
import { useToast } from '../../components/ui/Toast';
import { formatMoney, userMessage } from '../../utils/format';
import { useAuth } from '../auth/AuthContext';
import { declareActualSchema, isOpen, resolveBreakSchema } from './schemas';
import type { DeclareActualValues, ResolveBreakValues } from './schemas';

export function CycleDetailPage() {
  const { reference } = useParams<{ reference: string }>();
  const decoded = reference ? decodeURIComponent(reference) : undefined;
  const { hasRole } = useAuth();
  const { notify } = useToast();
  const cycle = useCycle(decoded);
  // Reads are supervision; the four actions are operators and admins only.
  const canAct = hasRole('SETTLEMENT_OPERATOR', 'PLATFORM_ADMIN');
  const close = useCloseCycle();
  const declare = useDeclareActual();
  const [reconciling, setReconciling] = useState(false);
  const [workingBreak, setWorkingBreak] = useState<string | null>(null);

  const actualForm = useForm<DeclareActualValues>({
    resolver: zodResolver(declareActualSchema),
    defaultValues: { reference: decoded ?? '', actualAmount: '', currency: '' },
  });
  const breakForm = useForm<ResolveBreakValues>({ resolver: zodResolver(resolveBreakSchema) });

  const doClose = () => {
    if (!decoded) return;
    close.mutate(decoded, {
      onSuccess: () => notify('Period closed. Its lines and total are now final.'),
      onError: (error) => notify(userMessage(error), 'error'),
    });
  };

  const doDeclare = (values: DeclareActualValues) => {
    declare.mutate(
      { reference: values.reference, actualAmount: values.actualAmount.trim(), currency: values.currency },
      {
        // 200 either way: a mismatch lands in the body as a finding, not as a failure.
        onSuccess: (updated) =>
          notify(
            updated.difference === null || updated.difference === '0.00'
              ? 'Actual declared — the period balances.'
              : `Actual declared — difference ${updated.difference}. A finding is recorded; the period cannot be confirmed while it is open.`,
          ),
        onError: (error) => notify(userMessage(error), 'error'),
      },
    );
  };

  const doReconcile = async () => {
    if (!decoded) return;
    setReconciling(true);
    try {
      await reconcileCycle(decoded);
      notify('Period confirmed.');
      await cycle.refetch();
    } catch (error) {
      notify(userMessage(error), 'error');
    } finally {
      setReconciling(false);
    }
  };

  const ack = async (breakId: string) => {
    setWorkingBreak(breakId);
    try {
      await acknowledgeBreak(breakId);
      await cycle.refetch();
    } catch (error) {
      notify(userMessage(error), 'error');
    } finally {
      setWorkingBreak(null);
    }
  };

  const resolve = (breakId: string, values: ResolveBreakValues) => {
    setWorkingBreak(breakId);
    resolveBreak(breakId, values.resolution.trim())
      .then(() => {
        notify('Break resolved with the explanation recorded.');
        breakForm.reset();
        return cycle.refetch();
      })
      .catch((error: unknown) => notify(userMessage(error), 'error'))
      .finally(() => setWorkingBreak(null));
  };

  if (cycle.isPending) {
    return (
      <div>
        <h1>Period</h1>
        <Skeleton label="Loading statement" />
      </div>
    );
  }

  if (cycle.isError) {
    return (
      <div>
        <h1>Period</h1>
        <ErrorState error={cycle.error} onRetry={() => void cycle.refetch()} />
      </div>
    );
  }

  if (!cycle.data) {
    return (
      <div>
        <h1>Period</h1>
        <EmptyState title="Not found" body="That period reference does not exist." />
      </div>
    );
  }

  const { cycle: detail, lines, breaks } = cycle.data;

  return (
    <div>
      <h1 className="mono" style={{ overflowWrap: 'anywhere' }}>
        {detail.reference}
      </h1>

      <section className="card" aria-labelledby="cycle-heading" style={{ marginBottom: 16 }}>
        <h2 id="cycle-heading" style={{ fontSize: '0.9rem' }}>
          {detail.businessDate} · {detail.currency} · <Badge status={detail.status} />
        </h2>
        <dl
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))',
            gap: 12,
            margin: 0,
          }}
        >
          <div>
            <dt className="field-hint">Expected</dt>
            <dd className="mono" style={{ margin: 0 }}>
              {formatMoney(detail.expected, detail.currency)}
            </dd>
          </div>
          <div>
            <dt className="field-hint">Actual</dt>
            <dd className="mono" style={{ margin: 0 }}>
              {detail.actual === null ? '— not declared' : formatMoney(detail.actual, detail.currency)}
            </dd>
          </div>
          <div>
            <dt className="field-hint">Difference</dt>
            <dd className="mono" style={{ margin: 0 }}>
              {detail.difference === null ? '—' : formatMoney(detail.difference, detail.currency)}
            </dd>
          </div>
          <div>
            <dt className="field-hint">Lines / open breaks</dt>
            <dd style={{ margin: 0 }}>
              {detail.lineCount} / {detail.openBreaks}
            </dd>
          </div>
        </dl>

        {canAct && isOpen(detail.status) ? (
          <div style={{ marginTop: 12 }}>
            <Button variant="primary" disabled={close.isPending} onClick={doClose}>
              {close.isPending ? 'Closing…' : 'Close period'}
            </Button>
            <p className="field-hint">
              Irreversible: a closed statement is never edited. Late money becomes a finding.
            </p>
          </div>
        ) : null}

        {canAct && detail.status === 'CLOSED' ? (
          <div style={{ marginTop: 12, display: 'flex', gap: 16, flexWrap: 'wrap' }}>
            <form
              onSubmit={(event) => void actualForm.handleSubmit(doDeclare)(event)}
              noValidate
              aria-labelledby="actual-heading"
              style={{ flex: '1 1 280px' }}
            >
              <h3 id="actual-heading" style={{ fontSize: '0.85rem' }}>
                Declare the actual
              </h3>
              <p className="field-hint">
                The independently sourced figure — the bank's number, not ours.
              </p>
              <div className="field">
                <label className="field-label" htmlFor="actual-amount">
                  Actual amount
                </label>
                <input
                  id="actual-amount"
                  className="input"
                  inputMode="decimal"
                  {...actualForm.register('actualAmount')}
                />
                {actualForm.formState.errors.actualAmount ? (
                  <p className="field-error" role="alert">
                    {actualForm.formState.errors.actualAmount.message}
                  </p>
                ) : null}
              </div>
              <div className="field">
                <label className="field-label" htmlFor="actual-currency">
                  Currency
                </label>
                <input
                  id="actual-currency"
                  className="input"
                  placeholder="USD"
                  maxLength={3}
                  {...actualForm.register('currency')}
                />
                {actualForm.formState.errors.currency ? (
                  <p className="field-error" role="alert">
                    {actualForm.formState.errors.currency.message}
                  </p>
                ) : null}
              </div>
              <Button variant="primary" type="submit" disabled={declare.isPending}>
                {declare.isPending ? 'Declaring…' : 'Declare actual'}
              </Button>
            </form>
            <div style={{ flex: '1 1 200px' }}>
              <h3 style={{ fontSize: '0.85rem' }}>Confirm</h3>
              <p className="field-hint">
                Only while no finding is open — the acknowledging operator is recorded on the
                finding.
              </p>
              <Button disabled={reconciling} onClick={() => void doReconcile()}>
                {reconciling ? 'Confirming…' : 'Confirm period'}
              </Button>
            </div>
          </div>
        ) : null}
      </section>

      <section className="card" aria-labelledby="lines-heading" style={{ marginBottom: 16 }}>
        <h2 id="lines-heading" style={{ fontSize: '0.9rem' }}>
          Statement lines ({lines.length})
        </h2>
        {lines.length === 0 ? (
          <p className="field-hint">No lines recorded for this period yet.</p>
        ) : (
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th scope="col">Payment</th>
                  <th scope="col">Kind</th>
                  <th scope="col">Amount</th>
                  <th scope="col">Recorded</th>
                </tr>
              </thead>
              <tbody>
                {lines.map((line) => (
                  <tr key={line.id}>
                    <td>
                      <Link to={`/transactions/${line.transactionId}`} className="mono">
                        {line.transactionId.slice(0, 8)}…
                      </Link>
                    </td>
                    <td className="mono">{line.kind}</td>
                    <td className="mono">{formatMoney(line.amount, detail.currency)}</td>
                    <td>{new Date(line.createdAt).toLocaleString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="field-hint">
          Refunds carry as negative lines — a reversal is a movement in the opposite direction, not
          a positive amount with a label.
        </p>
      </section>

      <section className="card" aria-labelledby="findings-heading">
        <h2 id="findings-heading" style={{ fontSize: '0.9rem' }}>
          Findings ({breaks.length})
        </h2>
        {breaks.length === 0 ? (
          <p className="field-hint">No findings on this period.</p>
        ) : (
          <ol style={{ margin: 0, paddingLeft: 20 }}>
            {breaks.map((found) => (
              <li key={found.id} style={{ marginBottom: 16 }}>
                <p style={{ margin: '0 0 4px' }}>
                  <Badge status={found.status} /> <strong className="mono">{found.kind}</strong> —{' '}
                  {found.detail}
                </p>
                <p className="field-hint" style={{ margin: '0 0 8px' }}>
                  Expected {found.expected ?? '—'} · actual {found.actual ?? '—'} · raised{' '}
                  {new Date(found.createdAt).toLocaleString()}
                  {found.acknowledgedBy ? ' · acknowledged' : ''}
                  {found.resolution ? ` · ${found.resolution}` : ''}
                </p>
                {canAct && found.status === 'OPEN' ? (
                  <Button
                    size="sm"
                    disabled={workingBreak === found.id}
                    onClick={() => void ack(found.id)}
                  >
                    {workingBreak === found.id ? 'Working…' : 'Acknowledge'}
                  </Button>
                ) : null}
                {canAct && found.status !== 'RESOLVED' ? (
                  <form
                    onSubmit={(event) =>
                      void breakForm.handleSubmit((v) => resolve(found.id, v))(event)
                    }
                    noValidate
                    style={{ marginTop: 8 }}
                  >
                    <div className="field">
                      <label className="field-label" htmlFor={`resolve-${found.id}`}>
                        Resolution
                      </label>
                      <input
                        id={`resolve-${found.id}`}
                        className="input"
                        placeholder="What is the money doing?"
                        {...breakForm.register('resolution')}
                      />
                      {breakForm.formState.errors.resolution ? (
                        <p className="field-error" role="alert">
                          {breakForm.formState.errors.resolution.message}
                        </p>
                      ) : null}
                    </div>
                    <Button size="sm" type="submit" disabled={workingBreak === found.id}>
                      Resolve break
                    </Button>
                  </form>
                ) : null}
              </li>
            ))}
          </ol>
        )}
      </section>
    </div>
  );
}
