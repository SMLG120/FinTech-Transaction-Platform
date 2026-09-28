import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useParams } from 'react-router-dom';
import { addEvidence, useDispute, useResolveDispute } from '../../api/disputeApi';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { EmptyState, ErrorState, Skeleton } from '../../components/ui/states';
import { useToast } from '../../components/ui/Toast';
import { userMessage } from '../../utils/format';
import { useAuth } from '../auth/AuthContext';
import { evidenceSchema, isResolved, resolveSchema } from './schemas';
import type { EvidenceValues, ResolveValues } from './schemas';

export function DisputeDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { hasRole } = useAuth();
  const { notify } = useToast();
  const dispute = useDispute(id);
  // Only support staff decide; the service enforces the same rule.
  const canDecide = hasRole('SUPPORT_AGENT', 'PLATFORM_ADMIN');
  const [pleading, setPleading] = useState(false);
  const resolve = useResolveDispute();

  const pleadForm = useForm<EvidenceValues>({
    resolver: zodResolver(evidenceSchema),
    defaultValues: { body: '' },
  });
  const decideForm = useForm<ResolveValues>({
    resolver: zodResolver(resolveSchema),
    defaultValues: { outcome: 'REFUND', resolution: '' },
  });

  const plead = async (values: EvidenceValues) => {
    if (!id) return;
    setPleading(true);
    try {
      await addEvidence(id, values.body.trim());
      pleadForm.reset();
      await dispute.refetch();
    } catch (error) {
      notify(userMessage(error), 'error');
    } finally {
      setPleading(false);
    }
  };

  const decide = (values: ResolveValues) => {
    if (!id) return;
    resolve.mutate(
      { id, outcome: values.outcome, resolution: values.resolution.trim() },
      {
        onSuccess: (detail) => {
          notify(
            detail.dispute.status === 'RESOLVED_REFUNDED'
              ? 'Case resolved with a refund — the ledger reverses the capture.'
              : 'Case rejected with the reason recorded.',
          );
        },
        onError: (error) => notify(userMessage(error), 'error'),
      },
    );
  };

  if (dispute.isPending) {
    return (
      <div>
        <h1>Dispute</h1>
        <Skeleton label="Loading dispute" />
      </div>
    );
  }

  if (dispute.isError) {
    return (
      <div>
        <h1>Dispute</h1>
        <ErrorState error={dispute.error} onRetry={() => void dispute.refetch()} />
      </div>
    );
  }

  if (!dispute.data) {
    return (
      <div>
        <h1>Dispute</h1>
        <EmptyState title="Not found" body="That case does not exist or is not yours." />
      </div>
    );
  }

  const { dispute: detail, evidence } = dispute.data;
  const open = !isResolved(detail.status);

  return (
    <div>
      <h1>Dispute case</h1>

      <section className="card" aria-labelledby="case-heading" style={{ marginBottom: 16 }}>
        <h2 id="case-heading" style={{ fontSize: '0.9rem' }}>
          {detail.reason} · <Badge status={detail.status} />
        </h2>
        <dl
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
            gap: 12,
            margin: 0,
          }}
        >
          <div>
            <dt className="field-hint">Case ID</dt>
            <dd className="mono" style={{ margin: 0, overflowWrap: 'anywhere' }}>
              {detail.id}
            </dd>
          </div>
          <div>
            <dt className="field-hint">Payment</dt>
            <dd style={{ margin: 0 }}>
              <Link to={`/transactions/${detail.transactionId}`} className="mono">
                {detail.transactionId.slice(0, 8)}…
              </Link>
            </dd>
          </div>
          <div>
            <dt className="field-hint">Opened</dt>
            <dd style={{ margin: 0 }}>{new Date(detail.createdAt).toLocaleString()}</dd>
          </div>
          {detail.resolvedAt ? (
            <div>
              <dt className="field-hint">Resolved</dt>
              <dd style={{ margin: 0 }}>{new Date(detail.resolvedAt).toLocaleString()}</dd>
            </div>
          ) : null}
        </dl>
        <p>{detail.description}</p>
        {detail.resolution ? (
          <p>
            <strong>Resolution:</strong> {detail.resolution}
          </p>
        ) : null}
      </section>

      <section className="card" aria-labelledby="file-heading" style={{ marginBottom: 16 }}>
        <h2 id="file-heading" style={{ fontSize: '0.9rem' }}>
          Case file ({evidence.length})
        </h2>
        {evidence.length === 0 ? (
          <p className="field-hint">No statements yet — both sides plead here, in the open.</p>
        ) : (
          <ol style={{ margin: 0, paddingLeft: 20 }}>
            {evidence.map((statement) => (
              <li key={statement.id} style={{ marginBottom: 10 }}>
                <p style={{ margin: '0 0 4px' }}>{statement.body}</p>
                <p className="field-hint" style={{ margin: 0 }}>
                  {statement.submittedByMe ? 'Your statement' : 'Other party'} ·{' '}
                  {new Date(statement.submittedAt).toLocaleString()}
                </p>
              </li>
            ))}
          </ol>
        )}
        {open ? (
          <form onSubmit={(event) => void pleadForm.handleSubmit(plead)(event)} noValidate>
            <div className="field">
              <label className="field-label" htmlFor="evidence-body">
                Add a statement
              </label>
              <input
                id="evidence-body"
                className="input"
                placeholder="What should the reviewer know?"
                {...pleadForm.register('body')}
              />
              {pleadForm.formState.errors.body ? (
                <p className="field-error" role="alert">
                  {pleadForm.formState.errors.body.message}
                </p>
              ) : null}
            </div>
            <Button type="submit" disabled={pleading}>
              {pleading ? 'Submitting…' : 'Submit statement'}
            </Button>
          </form>
        ) : (
          <p className="field-hint">This case is decided — the file no longer accepts statements.</p>
        )}
      </section>

      {canDecide && open ? (
        <section className="card" aria-labelledby="decide-heading">
          <h2 id="decide-heading" style={{ fontSize: '0.9rem' }}>
            Decide (support)
          </h2>
          <p className="field-hint">
            A refund announces the outcome and the ledger reverses the capture — the decision is
            not a refund until the postings say so. A reason is required either way.
          </p>
          <form onSubmit={(event) => void decideForm.handleSubmit(decide)(event)} noValidate>
            <div className="field">
              <label className="field-label" htmlFor="decide-outcome">
                Outcome
              </label>
              <select id="decide-outcome" className="select" {...decideForm.register('outcome')}>
                <option value="REFUND">REFUND — return the money</option>
                <option value="REJECT">REJECT — end the case with words</option>
              </select>
            </div>
            <div className="field">
              <label className="field-label" htmlFor="decide-resolution">
                Resolution
              </label>
              <input
                id="decide-resolution"
                className="input"
                placeholder="Why this outcome?"
                {...decideForm.register('resolution')}
              />
              {decideForm.formState.errors.resolution ? (
                <p className="field-error" role="alert">
                  {decideForm.formState.errors.resolution.message}
                </p>
              ) : null}
            </div>
            <Button variant="primary" type="submit" disabled={resolve.isPending}>
              {resolve.isPending ? 'Deciding…' : 'Record decision'}
            </Button>
          </form>
        </section>
      ) : null}
    </div>
  );
}
