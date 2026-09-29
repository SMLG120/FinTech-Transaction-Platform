import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { AlertCircle, CheckCircle2, Info, Lock } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link } from 'react-router-dom';
import {
  createPayment,
  fundAccount,
  reverseTransaction,
  settleTransaction,
} from '../../api/transactionApi';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { Card, CardHeader } from '../../components/ui/Card';
import { PageHeader } from '../../components/ui/PageHeader';
import { useToast } from '../../components/ui/Toast';
import { formatMoney, userMessage } from '../../utils/format';
import type { Transaction } from '../../types';
import { fundSchema, paySchema } from './schemas';
import type { FundValues, PayValues } from './schemas';

function FieldError({ message }: { message?: string }) {
  if (!message) return null;
  return (
    <p className="field-error" role="alert">
      <AlertCircle size={14} aria-hidden="true" /> {message}
    </p>
  );
}

export function PayPage() {
  const { notify } = useToast();
  const queryClient = useQueryClient();
  const [created, setCreated] = useState<Transaction | null>(null);

  const fundForm = useForm<FundValues>({
    resolver: zodResolver(fundSchema),
    defaultValues: { amount: '', currency: 'GBP' },
  });
  const payForm = useForm<PayValues>({
    resolver: zodResolver(paySchema),
    defaultValues: { amount: '', currency: 'GBP', cardToken: '', payeeName: '', payeeReference: '', channel: 'WEB' },
  });

  const payDraft = payForm.watch(['amount', 'currency', 'payeeName']);

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['transactions'] });
    void queryClient.invalidateQueries({ queryKey: ['balance'] });
  };

  const fund = useMutation({
    mutationFn: (values: FundValues) => fundAccount(values.amount.trim(), values.currency),
    onSuccess: () => {
      notify('Funds added to your account.', 'success');
      fundForm.reset();
      refresh();
    },
    onError: (error) => notify(userMessage(error), 'error'),
  });

  const pay = useMutation({
    mutationFn: (values: PayValues) =>
      createPayment({
        amount: values.amount.trim(),
        currency: values.currency,
        cardToken: values.cardToken.trim(),
        payeeName: values.payeeName.trim(),
        payeeReference: values.payeeReference?.trim() || undefined,
        channel: values.channel,
      }),
    onSuccess: (txn) => {
      // Authorisation holds; settlement moves. The two-step shape mirrors the
      // API rather than hiding it — settle explicitly below.
      setCreated(txn);
      payForm.reset();
      refresh();
    },
    onError: (error) => notify(userMessage(error), 'error'),
  });

  const settle = useMutation({
    mutationFn: (id: string) => settleTransaction(id),
    onSuccess: (txn) => {
      setCreated(txn);
      notify('Payment settled — funds moved.', 'success');
      refresh();
    },
    onError: (error) => notify(userMessage(error), 'error'),
  });

  const reverse = useMutation({
    mutationFn: (id: string) => reverseTransaction(id),
    onSuccess: (txn) => {
      setCreated(txn);
      notify('Payment reversed — hold released.', 'success');
      refresh();
    },
    onError: (error) => notify(userMessage(error), 'error'),
  });

  return (
    <div>
      <PageHeader
        eyebrow="Money"
        title="Pay & fund"
        sub="Top up your balance, then authorise a payment. Authorisation holds funds — settlement moves them."
      />

      <div className="alert alert-info" role="note">
        <Lock size={18} aria-hidden="true" />
        <div>
          <strong>Two-step money movement</strong>
          <p>
            1. Authorise (hold) → 2. Settle (capture) or reverse (release). Every submit carries
            its own idempotency key — double-clicking cannot charge twice.
          </p>
        </div>
      </div>

      <div className="grid-2">
        <Card labelledBy="fund-heading">
          <CardHeader
            titleId="fund-heading"
            title="Add funds"
            sub="Top up the account that payments draw on"
          />
          <form onSubmit={(event) => void fundForm.handleSubmit((v) => fund.mutate(v))(event)} noValidate>
            <div className="form-row">
              <div className="field">
                <label className="field-label" htmlFor="fund-amount">
                  Amount
                </label>
                <input
                  id="fund-amount"
                  className="input"
                  inputMode="decimal"
                  placeholder="100.00"
                  autoComplete="off"
                  aria-invalid={fundForm.formState.errors.amount ? 'true' : 'false'}
                  aria-describedby="fund-hint"
                  {...fundForm.register('amount')}
                />
                <FieldError message={fundForm.formState.errors.amount?.message} />
              </div>
              <div className="field">
                <label className="field-label" htmlFor="fund-currency">
                  Currency
                </label>
                <select id="fund-currency" className="select" {...fundForm.register('currency')}>
                  <option>GBP</option>
                  <option>USD</option>
                  <option>EUR</option>
                </select>
                <FieldError message={fundForm.formState.errors.currency?.message} />
              </div>
            </div>
            <div className="form-actions">
              <Button variant="primary" type="submit" disabled={fund.isPending}>
                {fund.isPending ? 'Adding…' : 'Add funds'}
              </Button>
            </div>
          </form>
          <p className="field-hint" id="fund-hint" style={{ marginTop: 10 }}>
            <Info size={13} aria-hidden="true" style={{ verticalAlign: -2 }} /> One idempotency key
            per click: double-clicking cannot top up twice.
          </p>
        </Card>

        <Card labelledBy="pay-heading">
          <CardHeader
            titleId="pay-heading"
            title="Make a payment"
            sub="Authorises immediately; capture it below"
          />
          <form onSubmit={(event) => void payForm.handleSubmit((v) => pay.mutate(v))(event)} noValidate>
            <div className="field">
              <label className="field-label" htmlFor="pay-token">
                Card token
              </label>
              <input
                id="pay-token"
                className="input mono"
                placeholder="tok_…"
                autoComplete="off"
                aria-invalid={payForm.formState.errors.cardToken ? 'true' : 'false'}
                {...payForm.register('cardToken')}
              />
              <FieldError message={payForm.formState.errors.cardToken?.message} />
              {!payForm.formState.errors.cardToken && (
                <p className="field-hint">Tokenised PAN from Cards — raw numbers never touch this form.</p>
              )}
            </div>
            <div className="form-row">
              <div className="field">
                <label className="field-label" htmlFor="pay-amount">
                  Amount
                </label>
                <input
                  id="pay-amount"
                  className="input"
                  inputMode="decimal"
                  placeholder="25.00"
                  autoComplete="off"
                  aria-invalid={payForm.formState.errors.amount ? 'true' : 'false'}
                  {...payForm.register('amount')}
                />
                <FieldError message={payForm.formState.errors.amount?.message} />
              </div>
              <div className="field">
                <label className="field-label" htmlFor="pay-currency">
                  Currency
                </label>
                <select id="pay-currency" className="select" {...payForm.register('currency')}>
                  <option>GBP</option>
                  <option>USD</option>
                  <option>EUR</option>
                </select>
                <FieldError message={payForm.formState.errors.currency?.message} />
              </div>
            </div>
            <div className="field">
              <label className="field-label" htmlFor="pay-payee">
                Payee name
              </label>
              <input
                id="pay-payee"
                className="input"
                placeholder="Acme Books"
                autoComplete="off"
                aria-invalid={payForm.formState.errors.payeeName ? 'true' : 'false'}
                {...payForm.register('payeeName')}
              />
              <FieldError message={payForm.formState.errors.payeeName?.message} />
            </div>
            <div className="field">
              <label className="field-label" htmlFor="pay-reference">
                Reference <span className="optional">(optional)</span>
              </label>
              <input
                id="pay-reference"
                className="input"
                placeholder="Invoice #1234"
                autoComplete="off"
                {...payForm.register('payeeReference')}
              />
              <FieldError message={payForm.formState.errors.payeeReference?.message} />
            </div>

            {payDraft[0] && payDraft[2] ? (
              <div
                className="alert alert-info"
                role="status"
                style={{ marginBottom: 12 }}
                aria-label="Payment summary"
              >
                <Info size={16} aria-hidden="true" />
                <div>
                  <strong>
                    You are paying {payDraft[1]} {payDraft[0]} to {payDraft[2]}
                  </strong>
                  <p>Check the amount, currency, and payee before submitting.</p>
                </div>
              </div>
            ) : null}

            <div className="form-actions">
              <Button variant="primary" type="submit" disabled={pay.isPending}>
                {pay.isPending ? 'Authorising…' : 'Authorise payment'}
              </Button>
            </div>
          </form>
        </Card>
      </div>

      {created ? (
        <Card labelledBy="created-heading">
          <div style={{ marginTop: 16 }} />
          <CardHeader
            titleId="created-heading"
            title={
              created.status === 'AUTHORIZED'
                ? 'Authorised — capture or release it'
                : `Payment ${created.status.toLowerCase()}`
            }
            sub="Confirm the outcome before leaving this page"
            actions={<Badge status={created.status} />}
          />
          <div className="alert alert-success" role="status">
            <CheckCircle2 size={18} aria-hidden="true" />
            <div>
              <strong>
                {formatMoney(created.amount, created.currency)} → {created.payeeName}
              </strong>
              <p>
                <Link to={`/transactions/${created.id}`} className="mono">
                  {created.id}
                </Link>{' '}
                · Authorisation holds funds; settlement moves them.
              </p>
            </div>
          </div>
          {created.status === 'AUTHORIZED' ? (
            <div className="form-actions">
              <Button
                variant="primary"
                size="sm"
                disabled={settle.isPending}
                onClick={() => settle.mutate(created.id)}
              >
                {settle.isPending ? 'Settling…' : 'Settle (capture funds)'}
              </Button>
              <Button size="sm" disabled={reverse.isPending} onClick={() => reverse.mutate(created.id)}>
                {reverse.isPending ? 'Reversing…' : 'Reverse (release hold)'}
              </Button>
            </div>
          ) : null}
        </Card>
      ) : null}
    </div>
  );
}
