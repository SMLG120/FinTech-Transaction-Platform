import { useState } from 'react';
import { useCustomerProfile } from '../../api/customerApi';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { EmptyState, ErrorState, Skeleton } from '../../components/ui/states';

/**
 * Staff lookup. Profiles render masked unless the reader is the owner —
 * the service decides that, so the UI states the masking explicitly.
 */
export function CustomersPage() {
  const [input, setInput] = useState('');
  const [lookupId, setLookupId] = useState<string | null>(null);
  const profile = useCustomerProfile(lookupId);

  return (
    <div>
      <h1>Customers</h1>
      <section className="card" aria-labelledby="lookup-heading" style={{ marginBottom: 16 }}>
        <h2 id="lookup-heading" style={{ fontSize: '0.9rem' }}>
          Look up a profile
        </h2>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            setLookupId(input.trim() || null);
          }}
        >
          <div className="field">
            <label className="field-label" htmlFor="customer-id">
              Customer ID
            </label>
            <input
              id="customer-id"
              className="input mono"
              placeholder="Customer UUID"
              autoComplete="off"
              value={input}
              onChange={(event) => setInput(event.target.value)}
            />
          </div>
          <Button variant="primary" type="submit" disabled={!input.trim()}>
            Look up
          </Button>
        </form>
      </section>

      {!lookupId ? (
        <EmptyState title="No profile selected" body="Enter a customer ID to read their (masked) profile." />
      ) : profile.isPending ? (
        <Skeleton label="Loading customer profile" />
      ) : profile.isError || !profile.data ? (
        <ErrorState error={profile.error} onRetry={() => void profile.refetch()} />
      ) : (
        <section className="card" aria-labelledby="result-heading">
          <h2 id="result-heading" style={{ fontSize: '0.9rem' }}>
            {profile.data.fullName} · <Badge status={profile.data.kycStatus} />
          </h2>
          {profile.data.masked ? (
            <p className="field-hint">
              Masked view — personal fields are obscured because you are not the owner. A support
              agent gets a 200 from the gateway and a masked profile.
            </p>
          ) : null}
          <dl
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
              gap: 12,
              margin: 0,
            }}
          >
            <div>
              <dt className="field-hint">Email</dt>
              <dd style={{ margin: 0 }}>{profile.data.email}</dd>
            </div>
            <div>
              <dt className="field-hint">Phone</dt>
              <dd style={{ margin: 0 }}>{profile.data.phone || '—'}</dd>
            </div>
            <div>
              <dt className="field-hint">Born</dt>
              <dd style={{ margin: 0 }}>{profile.data.dateOfBirth ?? profile.data.birthYear ?? '—'}</dd>
            </div>
            <div>
              <dt className="field-hint">Customer ID</dt>
              <dd className="mono" style={{ margin: 0, overflowWrap: 'anywhere' }}>
                {profile.data.id}
              </dd>
            </div>
          </dl>
        </section>
      )}
    </div>
  );
}
