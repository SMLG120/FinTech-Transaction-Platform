import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { ShieldAlert } from 'lucide-react';
import { useForm } from 'react-hook-form';
import { ApiError } from '../../api/client';
import { adjustDecision, rescoreDecision, useFraudDecision } from '../../api/fraudApi';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { Card } from '../../components/ui/Card';
import { EmptyState, ErrorState, Skeleton } from '../../components/ui/states';
import { useToast } from '../../components/ui/Toast';
import { userMessage } from '../../utils/format';
import { useAuth } from '../auth/AuthContext';
import { adjustSchema, rescoreSchema } from './schemas';
import type { AdjustValues, RescoreValues } from './schemas';

function RiskBar({ score }: { score: number }) {
  return (
    <div role="img" aria-label={`Risk score ${score} of 100`}>
      <div
        style={{
          height: 8,
          borderRadius: 4,
          background: 'var(--surface-muted)',
          border: '1px solid var(--border)',
          overflow: 'hidden',
        }}
      >
        <div
          style={{
            width: `${Math.min(100, Math.max(0, score))}%`,
            height: '100%',
            background: score >= 75 ? 'var(--danger)' : score >= 50 ? '#c77414' : 'var(--success)',
            transition: 'width 200ms ease',
          }}
        />
      </div>
    </div>
  );
}

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
      notify('Score overruled. The timeline records the reason and the actor.', 'success');
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
      notify('Re-score requested. The decision refreshes once the engine runs.', 'success');
      rescoreForm.reset();
    } catch (error) {
      notify(userMessage(error), 'error');
    }
  };

  return (
    <div className="grid-2-tight" style={{ marginTop: 16, marginBottom: 0 }}>
      <form
        onSubmit={(event) => void adjustForm.handleSubmit(adjust)(event)}
        noValidate
        aria-labelledby="adjust-heading"
        style={{
          border: '1px solid var(--border)',
          borderRadius: 'var(--radius-sm)',
          padding: 16,
          background: 'var(--bg-subtle)',
        }}
      >
        <h3 id="adjust-heading">Overrule the score</h3>
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
          <input id="adjust-reason" className="input" placeholder="Why is the engine wrong?" {...adjustForm.register('reason')} />
          {adjustForm.formState.errors.reason ? (
            <p className="field-error" role="alert">{adjustForm.formState.errors.reason.message}</p>
          ) : null}
        </div>
        <Button type="submit">Overrule</Button>
        <p className="field-hint" style={{ marginTop: 8 }}>
          Above the configured cap a decision needs a second approver.
        </p>
      </form>

      <form
        onSubmit={(event) => void rescoreForm.handleSubmit(rescore)(event)}
        noValidate
        aria-labelledby="rescore-heading"
        style={{
          border: '1px solid var(--border)',
          borderRadius: 'var(--radius-sm)',
          padding: 16,
          background: 'var(--bg-subtle)',
        }}
      >
        <h3 id="rescore-heading">Ask for a re-score</h3>
        <div className="field">
          <label className="field-label" htmlFor="rescore-reason">
            Reason
          </label>
          <input id="rescore-reason" className="input" placeholder="What changed since scoring?" {...rescoreForm.register('reason')} />
          {rescoreForm.formState.errors.reason ? (
            <p className="field-error" role="alert">{rescoreForm.formState.errors.reason.message}</p>
          ) : null}
        </div>
        <Button type="submit">Request re-score</Button>
        <p className="field-hint" style={{ marginTop: 8 }}>
          Accepted immediately (202); the engine re-scores asynchronously.
        </p>
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
      <Card labelledBy="risk-heading">
        <h2 id="risk-heading" style={{ fontSize: '0.92rem' }}>
          Risk decision
        </h2>
        <Skeleton label="Loading risk decision" />
      </Card>
    );
  }

  if (decision.isError) {
    const missing = decision.error instanceof ApiError && decision.error.status === 404;
    if (missing) {
      return (
        <Card labelledBy="risk-heading">
          <h2 id="risk-heading" style={{ fontSize: '0.92rem' }}>
            Risk decision
          </h2>
          <EmptyState
            title="Not scored yet"
            body="Fraud scoring runs after authorisation. This payment has no decision recorded yet."
          />
        </Card>
      );
    }
    return (
      <Card labelledBy="risk-heading">
        <h2 id="risk-heading" style={{ fontSize: '0.92rem' }}>
          Risk decision
        </h2>
        <ErrorState error={decision.error} onRetry={() => void decision.refetch()} />
      </Card>
    );
  }

  const detail = decision.data;
  if (!detail) return null;

  return (
    <Card labelledBy="risk-heading">
      <h2 id="risk-heading" style={{ fontSize: '0.92rem', display: 'flex', gap: 8, alignItems: 'center' }}>
        <ShieldAlert size={16} aria-hidden="true" /> Risk decision
      </h2>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', margin: '8px 0 4px' }}>
        <span style={{ fontSize: '1.4rem', fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>
          {detail.score} / 100
        </span>
        <Badge status={detail.decision} />
        <Badge status={detail.band} />
        {detail.manuallyAdjusted ? <Badge status="Manual override" tone="warning" /> : null}
      </div>
      <RiskBar score={detail.score} />
      {detail.manuallyAdjusted ? (
        <p className="field-hint" style={{ marginTop: 8 }}>
          Manually adjusted to {detail.manualScore} — {detail.manualReason ?? 'no reason recorded'}
        </p>
      ) : null}
      {detail.reasons.length === 0 ? (
        <p className="field-hint" style={{ marginTop: 8 }}>No rules fired for this payment.</p>
      ) : (
        <>
          <h3 style={{ fontSize: '0.85rem', marginTop: 12 }}>Triggered rules</h3>
          <ul style={{ margin: 0, paddingLeft: 20, fontSize: '0.89rem' }}>
            {detail.reasons.map((reason) => (
              <li key={reason.ruleId} style={{ marginBottom: 6 }}>
                <strong>{reason.ruleName}</strong>{' '}
                <span className="mono" style={{ color: 'var(--ink-muted)' }}>
                  +{reason.points}
                </span>{' '}
                — {reason.explanation}
              </li>
            ))}
          </ul>
        </>
      )}
      <ActorForms transactionId={transactionId} />
    </Card>
  );
}
