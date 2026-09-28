import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { ApiError } from '../../api/client';
import { adjustDecision, rescoreDecision, useFraudDecision } from '../../api/fraudApi';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { EmptyState, ErrorState, Skeleton } from '../../components/ui/states';
import { useToast } from '../../components/ui/Toast';
import { userMessage } from '../../utils/format';
import { useAuth } from '../auth/AuthContext';
import { adjustSchema, rescoreSchema } from './schemas';
import type { AdjustValues, RescoreValues } from './schemas';

function ActorForms({ transactionId }: { transactionId: string }) {
  const { hasRole } = useAuth();
  const { notify } = useToast();
  const queryClient = useQueryClient();
  const canAct = hasRole('FRAUD_ANALYST', 'PLATFORM_ADMIN');
  const adjustForm = useForm<AdjustValues>({ resolver: zodResolver(adjustSchema) });
  const rescoreForm = useForm<RescoreValues>({ resolver: zodResolver(rescoreSchema) });

  if (!canAct) return null;

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['fraud-decision', transactionId] });
  };

  const adjust = async (values: AdjustValues) => {
    try {
      await adjustDecision(transactionId, values.score, values.reason.trim());
      notify('Score overruled. The timeline records the reason and the actor.');
      adjustForm.reset();
      refresh();
    } catch (error) {
      notify(userMessage(error), 'error');
    }
  };

  const rescore = async (values: RescoreValues) => {
    try {
      // 202: accepted, not performed — the work happens on the topic.
      await rescoreDecision(transactionId, values.reason.trim());
      notify('Re-score requested. The decision refreshes once the engine runs.');
      rescoreForm.reset();
    } catch (error) {
      notify(userMessage(error), 'error');
    }
  };

  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))',
        gap: 16,
        marginTop: 16,
      }}
    >
      <form
        onSubmit={(event) => void adjustForm.handleSubmit(adjust)(event)}
        noValidate
        aria-labelledby="adjust-heading"
      >
        <h3 id="adjust-heading" style={{ fontSize: '0.85rem' }}>
          Overrule the score
        </h3>
        <div className="field">
          <label className="field-label" htmlFor="adjust-score">
            Score (0–100)
          </label>
          <input id="adjust-score" className="input" type="number" min={0} max={100} {...adjustForm.register('score')} />
          {adjustForm.formState.errors.score ? (
            <p className="field-error" role="alert">{adjustForm.formState.errors.score.message}</p>
          ) : null}
        </div>
        <div className="field">
          <label className="field-label" htmlFor="adjust-reason">
            Reason
          </label>
          <input id="adjust-reason" className="input" {...adjustForm.register('reason')} />
          {adjustForm.formState.errors.reason ? (
            <p className="field-error" role="alert">{adjustForm.formState.errors.reason.message}</p>
          ) : null}
        </div>
        <Button type="submit">Overrule</Button>
        <p className="field-hint">Above the configured cap a decision needs a second approver.</p>
      </form>

      <form
        onSubmit={(event) => void rescoreForm.handleSubmit(rescore)(event)}
        noValidate
        aria-labelledby="rescore-heading"
      >
        <h3 id="rescore-heading" style={{ fontSize: '0.85rem' }}>
          Ask for a re-score
        </h3>
        <div className="field">
          <label className="field-label" htmlFor="rescore-reason">
            Reason
          </label>
          <input id="rescore-reason" className="input" {...rescoreForm.register('reason')} />
          {rescoreForm.formState.errors.reason ? (
            <p className="field-error" role="alert">{rescoreForm.formState.errors.reason.message}</p>
          ) : null}
        </div>
        <Button type="submit">Request re-score</Button>
      </form>
    </div>
  );
}

/** One payment's fraud decision with explanations and analyst actions.
 * Shared by the transaction investigation view and the alert detail view. */
export function DecisionPanel({ transactionId }: { transactionId: string }) {
  const { hasRole } = useAuth();
  const canRead = hasRole('FRAUD_ANALYST', 'COMPLIANCE_OFFICER', 'AUDITOR', 'PLATFORM_ADMIN');
  const decision = useFraudDecision(transactionId);

  if (!canRead) return null;

  if (decision.isPending) {
    return (
      <section className="card" aria-labelledby="risk-heading" style={{ marginTop: 16 }}>
        <h2 id="risk-heading" style={{ fontSize: '0.9rem' }}>
          Risk decision
        </h2>
        <Skeleton label="Loading risk decision" />
      </section>
    );
  }

  if (decision.isError) {
    const missing = decision.error instanceof ApiError && decision.error.status === 404;
    if (missing) {
      return (
        <section className="card" aria-labelledby="risk-heading" style={{ marginTop: 16 }}>
          <h2 id="risk-heading" style={{ fontSize: '0.9rem' }}>
            Risk decision
          </h2>
          <EmptyState
            title="Not scored yet"
            body="Fraud scoring runs after authorisation. This payment has no decision recorded yet."
          />
        </section>
      );
    }
    return (
      <section className="card" aria-labelledby="risk-heading" style={{ marginTop: 16 }}>
        <h2 id="risk-heading" style={{ fontSize: '0.9rem' }}>
          Risk decision
        </h2>
        <ErrorState error={decision.error} onRetry={() => void decision.refetch()} />
      </section>
    );
  }

  const detail = decision.data;
  if (!detail) return null;

  return (
    <section className="card" aria-labelledby="risk-heading" style={{ marginTop: 16 }}>
      <h2 id="risk-heading" style={{ fontSize: '0.9rem' }}>
        Risk decision
      </h2>
      <p style={{ fontSize: '1.25rem', fontWeight: 700, margin: '0 0 8px' }}>
        Risk score: {detail.score} / 100 <Badge status={detail.decision} />{' '}
        <Badge status={detail.band} />
      </p>
      {detail.manuallyAdjusted ? (
        <p className="field-hint">
          Manually adjusted to {detail.manualScore} — {detail.manualReason ?? 'no reason recorded'}
        </p>
      ) : null}
      {detail.reasons.length === 0 ? (
        <p className="field-hint">No rules fired for this payment.</p>
      ) : (
        <>
          <h3 style={{ fontSize: '0.85rem' }}>Reasons</h3>
          <ul style={{ margin: 0, paddingLeft: 20 }}>
            {detail.reasons.map((reason) => (
              <li key={reason.ruleId} style={{ marginBottom: 6 }}>
                <strong>{reason.ruleName}</strong> (+{reason.points}) — {reason.explanation}
              </li>
            ))}
          </ul>
        </>
      )}
      <ActorForms transactionId={transactionId} />
    </section>
  );
}
