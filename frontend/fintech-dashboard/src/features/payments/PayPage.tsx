import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
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
import { useToast } from '../../components/ui/Toast';
import { formatMoney, userMessage } from '../../utils/format';
import type { Transaction } from '../../types';
import { fundSchema, paySchema } from './schemas';
import type { FundValues, PayValues } from './schemas';

function FieldError({ message }: { message?: string }) {
  if (!message) return null;
  return (
    <p className="field-error" role="alert">
      {message}
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

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['transactions'] });
    void queryClient.invalidateQueries({ queryKey: ['balance'] });
  };

  const fund = useMutation({
    mutationFn: (values: FundValues) => fundAccount(values.amount.trim(), values.currency),
    onSuccess: () => {
      notify('Funds added.');
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
      notify('Payment settled.');
      refresh();
    },
    onError: (error) => notify(userMessage(error), 'error'),
  });

  const reverse = useMutation({
    mutationFn: (id: string) => reverseTransaction(id),
    onSuccess: (txn) => {
      setCreated(txn);
      notify('Payment reversed.');
      refresh();
    },
    onError: (error) => notify(userMessage(error), 'error'),
  });

  return (
    <div>
      <h1>Pay &amp; fund</h1>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))',
          gap: 16,
        }}
      >
        <section className="card" aria-labelledby="fund-heading">
          <h2 id="fund-heading" style={{ fontSize: '0.9rem' }}>
            Add funds
          </h2>
          <form onSubmit={(event) => void fundForm.handleSubmit((v) => fund.mutate(v))(event)} noValidate>
            <div className="field">
              <label className="field-label" htmlFor="fund-amount">
                Amount
              </label>
              <input
                id="fund-amount"
                className="input"
                inputMode="decimal"
                placeholder="100.00"
                aria-invalid={fundForm.formState.errors.amount ? 'true' : 'false'}
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
            <Button variant="primary" type="submit" disabled={fund.isPending}>
              {fund.isPending ? 'Adding…' : 'Add funds'}
            </Button>
          </form>
          <p className="field-hint" style={{ marginTop: 8 }}>
            One idempotency key per click: double-clicking cannot top up twice.
          </p>
        </section>

        <section className="card" aria-labelledby="pay-heading">
          <h2 id="pay-heading" style={{ fontSize: '0.9rem' }}>
            Pay
          </h2>
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
            </div>
            <div style={{ display: 'flex', gap: 12 }}>
              <div className="field" style={{ flex: 2 }}>
                <label className="field-label" htmlFor="pay-amount">
                  Amount
                </label>
                <input
                  id="pay-amount"
                  className="input"
                  inputMode="decimal"
                  placeholder="25.00"
                  aria-invalid={payForm.formState.errors.amount ? 'true' : 'false'}
                  {...payForm.register('amount')}
                />
                <FieldError message={payForm.formState.errors.amount?.message} />
              </div>
              <div className="field" style={{ flex: 1 }}>
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
                Payee
              </label>
              <input
                id="pay-payee"
                className="input"
                placeholder="Acme Books"
                aria-invalid={payForm.formState.errors.payeeName ? 'true' : 'false'}
                {...payForm.register('payeeName')}
              />
              <FieldError message={payForm.formState.errors.payeeName?.message} />
            </div>
            <div className="field">
              <label className="field-label" htmlFor="pay-reference">
                Reference <span className="field-hint">(optional)</span>
              </label>
              <input id="pay-reference" className="input" {...payForm.register('payeeReference')} />
              <FieldError message={payForm.formState.errors.payeeReference?.message} />
            </div>
            <Button variant="primary" type="submit" disabled={pay.isPending}>
              {pay.isPending ? 'Paying…' : 'Pay now'}
            </Button>
          </form>
        </section>
      </div>

      {created ? (
        <section className="card" aria-labelledby="created-heading" style={{ marginTop: 16 }}>
          <h2 id="created-heading" style={{ fontSize: '0.9rem' }}>
            Payment {created.status === 'AUTHORIZED' ? 'authorised — capture it' : 'result'}
          </h2>
          <p>
            <Link to={`/transactions/${created.id}`} className="mono">
              {created.id}
            </Link>{' '}
            · {formatMoney(created.amount, created.currency)} · <Badge status={created.status} />
          </p>
          {created.status === 'AUTHORIZED' ? (
            <div style={{ display: 'flex', gap: 8 }}>
              <Button
                variant="primary"
                size="sm"
                disabled={settle.isPending}
                onClick={() => settle.mutate(created.id)}
              >
                {settle.isPending ? 'Settling…' : 'Settle (capture)'}
              </Button>
              <Button size="sm" disabled={reverse.isPending} onClick={() => reverse.mutate(created.id)}>
                Reverse instead
              </Button>
            </div>
          ) : null}
        </section>
      ) : null}
    </div>
  );
}
