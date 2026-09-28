import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { eraseMyProfile, useMyKycHistory, useMyProfile, useUpdateProfile } from '../../api/customerApi';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { EmptyState, ErrorState, Skeleton } from '../../components/ui/states';
import { useToast } from '../../components/ui/Toast';
import { userMessage } from '../../utils/format';
import { useAuth } from '../auth/AuthContext';
import { updateProfileSchema } from './schemas';
import type { UpdateProfileValues } from './schemas';

export function ProfilePage() {
  const profile = useMyProfile();
  const { notify } = useToast();
  const { logout } = useAuth();
  const navigate = useNavigate();
  const update = useUpdateProfile();
  const [erasing, setErasing] = useState(false);
  const [confirmErase, setConfirmErase] = useState(false);

  const kyc = useMyKycHistory(Boolean(profile.data));

  const form = useForm<UpdateProfileValues>({ resolver: zodResolver(updateProfileSchema) });

  // Prefill once the profile arrives (reset only on id change, not keystroke).
  const [prefilledFor, setPrefilledFor] = useState<string | null>(null);
  if (profile.data && prefilledFor !== profile.data.id) {
    setPrefilledFor(profile.data.id);
    form.reset({
      fullName: profile.data.masked ? '' : profile.data.fullName,
      dateOfBirth: profile.data.dateOfBirth ?? '',
      nationality: 'GB',
      email: profile.data.masked ? '' : profile.data.email,
      phone: profile.data.phone ?? '',
      address: {
        line1: profile.data.address?.line1 ?? '',
        line2: profile.data.address?.line2 ?? '',
        city: profile.data.address?.city ?? '',
        postalCode: profile.data.address?.postalCode ?? '',
        country: profile.data.address?.country ?? 'GB',
      },
    });
  }

  const save = (values: UpdateProfileValues) => {
    update.mutate(
      {
        ...values,
        address: { ...values.address, line2: values.address.line2 || undefined },
      },
      {
        onSuccess: () => notify('Profile updated.'),
        onError: (error) => notify(userMessage(error), 'error'),
      },
    );
  };

  const erase = async () => {
    setErasing(true);
    try {
      await eraseMyProfile();
      notify('Profile erased. Signing you out.');
      logout();
      navigate('/login', { replace: true });
    } catch (error) {
      notify(userMessage(error), 'error');
      setErasing(false);
      setConfirmErase(false);
    }
  };

  if (profile.isPending) {
    return (
      <div>
        <h1>Profile</h1>
        <Skeleton label="Loading profile" />
      </div>
    );
  }

  if (profile.isError || !profile.data) {
    return (
      <div>
        <h1>Profile</h1>
        <ErrorState
          error={profile.error ?? new Error('No profile found for this login.')}
          onRetry={() => void profile.refetch()}
        />
        <p className="field-hint">
          A login without a customer profile has nothing to show here — register one first.
        </p>
      </div>
    );
  }

  const data = profile.data;

  return (
    <div>
      <h1>Profile</h1>

      <section className="card" aria-labelledby="profile-heading" style={{ marginBottom: 16 }}>
        <h2 id="profile-heading" style={{ fontSize: '0.9rem' }}>
          {data.masked ? data.fullName : data.fullName} · <Badge status={data.kycStatus} />
        </h2>
        {data.masked ? (
          <p className="field-hint">
            Personal fields are masked — you are reading this profile as staff, not as its owner.
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
            <dd style={{ margin: 0 }}>{data.email}</dd>
          </div>
          <div>
            <dt className="field-hint">Phone</dt>
            <dd style={{ margin: 0 }}>{data.phone || '—'}</dd>
          </div>
          <div>
            <dt className="field-hint">Born</dt>
            <dd style={{ margin: 0 }}>{data.dateOfBirth ?? data.birthYear ?? '—'}</dd>
          </div>
          <div>
            <dt className="field-hint">Address</dt>
            <dd style={{ margin: 0 }}>
              {data.address
                ? `${data.address.line1}, ${data.address.city} ${data.address.postalCode}, ${data.address.country}`
                : '—'}
            </dd>
          </div>
        </dl>
      </section>

      <section className="card" aria-labelledby="kyc-heading" style={{ marginBottom: 16 }}>
        <h2 id="kyc-heading" style={{ fontSize: '0.9rem' }}>
          Identity checks
        </h2>
        {kyc.isPending ? (
          <Skeleton label="Loading identity checks" />
        ) : kyc.isError ? (
          <ErrorState error={kyc.error} onRetry={() => void kyc.refetch()} />
        ) : (kyc.data ?? []).length === 0 ? (
          <EmptyState title="No checks yet" body="Submit an identity document to start verification." />
        ) : (
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th scope="col">Outcome</th>
                  <th scope="col">Submitted</th>
                  <th scope="col">Decided</th>
                  <th scope="col">Details</th>
                </tr>
              </thead>
              <tbody>
                {(kyc.data ?? []).map((check) => (
                  <tr key={check.id}>
                    <td>
                      <Badge status={check.outcome} />
                    </td>
                    <td>{new Date(check.submittedAt).toLocaleString()}</td>
                    <td>{check.decidedAt ? new Date(check.decidedAt).toLocaleString() : '—'}</td>
                    <td>
                      {check.failureReasons.length > 0 ? check.failureReasons.join('; ') : '—'}
                      {check.checks.length > 0 ? (
                        <ul style={{ margin: '4px 0 0', paddingLeft: 18 }}>
                          {check.checks.map((item) => (
                            <li key={item.checkName}>
                              {item.checkName}: {item.passed ? 'passed' : `failed — ${item.reason}`}
                            </li>
                          ))}
                        </ul>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {!data.masked ? (
        <section className="card" aria-labelledby="edit-heading" style={{ marginBottom: 16 }}>
          <h2 id="edit-heading" style={{ fontSize: '0.9rem' }}>
            Update profile
          </h2>
          <form onSubmit={(event) => void form.handleSubmit(save)(event)} noValidate>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 12 }}>
              <div className="field">
                <label className="field-label" htmlFor="profile-name">Full name</label>
                <input id="profile-name" className="input" {...form.register('fullName')} />
                {form.formState.errors.fullName ? <p className="field-error" role="alert">{form.formState.errors.fullName.message}</p> : null}
              </div>
              <div className="field">
                <label className="field-label" htmlFor="profile-dob">Date of birth</label>
                <input id="profile-dob" className="input" type="date" {...form.register('dateOfBirth')} />
                {form.formState.errors.dateOfBirth ? <p className="field-error" role="alert">{form.formState.errors.dateOfBirth.message}</p> : null}
              </div>
              <div className="field">
                <label className="field-label" htmlFor="profile-nationality">Nationality</label>
                <input id="profile-nationality" className="input" maxLength={2} {...form.register('nationality')} />
                {form.formState.errors.nationality ? <p className="field-error" role="alert">{form.formState.errors.nationality.message}</p> : null}
              </div>
              <div className="field">
                <label className="field-label" htmlFor="profile-email">Email</label>
                <input id="profile-email" className="input" type="email" {...form.register('email')} />
                {form.formState.errors.email ? <p className="field-error" role="alert">{form.formState.errors.email.message}</p> : null}
              </div>
              <div className="field">
                <label className="field-label" htmlFor="profile-phone">Phone</label>
                <input id="profile-phone" className="input" {...form.register('phone')} />
                {form.formState.errors.phone ? <p className="field-error" role="alert">{form.formState.errors.phone.message}</p> : null}
              </div>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 12 }}>
              <div className="field">
                <label className="field-label" htmlFor="profile-line1">Address line 1</label>
                <input id="profile-line1" className="input" {...form.register('address.line1')} />
                {form.formState.errors.address?.line1 ? <p className="field-error" role="alert">{form.formState.errors.address.line1.message}</p> : null}
              </div>
              <div className="field">
                <label className="field-label" htmlFor="profile-city">City</label>
                <input id="profile-city" className="input" {...form.register('address.city')} />
                {form.formState.errors.address?.city ? <p className="field-error" role="alert">{form.formState.errors.address.city.message}</p> : null}
              </div>
              <div className="field">
                <label className="field-label" htmlFor="profile-postcode">Postal code</label>
                <input id="profile-postcode" className="input" {...form.register('address.postalCode')} />
                {form.formState.errors.address?.postalCode ? <p className="field-error" role="alert">{form.formState.errors.address.postalCode.message}</p> : null}
              </div>
              <div className="field">
                <label className="field-label" htmlFor="profile-country">Country</label>
                <input id="profile-country" className="input" maxLength={2} {...form.register('address.country')} />
                {form.formState.errors.address?.country ? <p className="field-error" role="alert">{form.formState.errors.address.country.message}</p> : null}
              </div>
            </div>
            <Button variant="primary" type="submit" disabled={update.isPending}>
              {update.isPending ? 'Saving…' : 'Save changes'}
            </Button>
          </form>
        </section>
      ) : null}

      {!data.masked ? (
        <section className="card" aria-labelledby="erase-heading">
          <h2 id="erase-heading" style={{ fontSize: '0.9rem' }}>
            Erase profile
          </h2>
          {!confirmErase ? (
            <>
              <p className="field-hint">
                Irreversible: the data is scrubbed and the profile can no longer be found.
              </p>
              <Button variant="danger" onClick={() => setConfirmErase(true)}>
                Erase my profile…
              </Button>
            </>
          ) : (
            <div role="alertdialog" aria-labelledby="erase-heading" aria-modal="true">
              <p>
                <strong>This cannot be undone.</strong> Confirm erasure of your customer profile?
              </p>
              <div style={{ display: 'flex', gap: 8 }}>
                <Button variant="danger" disabled={erasing} onClick={() => void erase()}>
                  {erasing ? 'Erasing…' : 'Yes, erase everything'}
                </Button>
                <Button disabled={erasing} onClick={() => setConfirmErase(false)}>
                  Back
                </Button>
              </div>
            </div>
          )}
        </section>
      ) : null}
    </div>
  );
}
