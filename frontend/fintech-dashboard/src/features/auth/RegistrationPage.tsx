import { zodResolver } from '@hookform/resolvers/zod';
import { AlertCircle, ArrowLeft, Lock } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { Button } from '../../components/ui/Button';
import { ApiError, apiClient } from '../../api/client';
import { registrationSchema } from './registrationSchema';
import type { RegistrationFormValues } from './registrationSchema';

export function RegistrationPage() {
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const { register, handleSubmit, formState: { errors } } = useForm<RegistrationFormValues>({
    resolver: zodResolver(registrationSchema),
    defaultValues: { nationality: 'GB', address: { country: 'GB' } },
  });

  const onSubmit = async (values: RegistrationFormValues) => {
    if (isLoading) return;
    setError(null);
    setIsLoading(true);
    try {
      await apiClient.register(values);
      navigate('/login', { replace: true, state: { registrationComplete: true } });
    } catch (cause) {
      if (cause instanceof ApiError && cause.code === 'EMAIL_ALREADY_REGISTERED') {
        setError('An account already exists for this email. Sign in or use a different email address.');
      } else if (cause instanceof ApiError && cause.status === 409) {
        setError('An account could not be created with these details. Please sign in or try again.');
      } else if (cause instanceof ApiError && cause.status === 400) {
        setError('Please check the information entered and try again.');
      } else {
        setError('Registration could not be completed. Please try again later.');
      }
    } finally {
      setIsLoading(false);
    }
  };

  const fieldError = (message?: string) => message ? <p className="field-error" role="alert"><AlertCircle size={14} /> {message}</p> : null;
  return (
    <div className="auth-wrap">
      <div className="auth-card">
        <div className="auth-brand"><span className="auth-brand-mark" aria-hidden="true">F$</span><h1>Meridian Bank</h1><p>Secure FinTech Transaction Platform</p></div>
        <div className="auth-panel">
          <h2>Create your customer account</h2>
          <p className="lede">Create a local development account. No privileged roles can be selected here.</p>
          <form onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
            <div className="field"><label className="field-label" htmlFor="reg-name">Full name</label><input id="reg-name" className="input" autoComplete="name" {...register('fullName')} />{fieldError(errors.fullName?.message)}</div>
            <div className="field"><label className="field-label" htmlFor="reg-dob">Date of birth</label><input id="reg-dob" className="input" type="date" {...register('dateOfBirth')} />{fieldError(errors.dateOfBirth?.message)}</div>
            <div className="field"><label className="field-label" htmlFor="reg-email">Email</label><input id="reg-email" className="input" type="email" autoComplete="email" {...register('email')} />{fieldError(errors.email?.message)}</div>
            <div className="field"><label className="field-label" htmlFor="reg-password">Password</label><input id="reg-password" className="input" type="password" autoComplete="new-password" {...register('password')} />{fieldError(errors.password?.message)}</div>
            <div className="field"><label className="field-label" htmlFor="reg-phone">Phone</label><input id="reg-phone" className="input" type="tel" autoComplete="tel" {...register('phone')} />{fieldError(errors.phone?.message)}</div>
            <div className="field"><label className="field-label" htmlFor="reg-line1">Address</label><input id="reg-line1" className="input" autoComplete="address-line1" {...register('address.line1')} />{fieldError(errors.address?.line1?.message)}</div>
            <div className="field"><label className="field-label" htmlFor="reg-line2">Address line 2</label><input id="reg-line2" className="input" autoComplete="address-line2" {...register('address.line2')} /></div>
            <div className="field"><label className="field-label" htmlFor="reg-city">City</label><input id="reg-city" className="input" autoComplete="address-level2" {...register('address.city')} />{fieldError(errors.address?.city?.message)}</div>
            <div className="field"><label className="field-label" htmlFor="reg-postcode">Postal code</label><input id="reg-postcode" className="input" autoComplete="postal-code" {...register('address.postalCode')} />{fieldError(errors.address?.postalCode?.message)}</div>
            <div className="field"><label className="field-label" htmlFor="reg-nationality">Nationality</label><input id="reg-nationality" className="input" maxLength={2} {...register('nationality')} />{fieldError(errors.nationality?.message)}</div>
            <div className="field"><label className="field-label" htmlFor="reg-country">Address country</label><input id="reg-country" className="input" maxLength={2} {...register('address.country')} />{fieldError(errors.address?.country?.message)}</div>
            {error ? <div className="alert alert-danger" role="alert"><AlertCircle size={16} /><div><strong>Registration failed</strong><p>{error}</p></div></div> : null}
            <Button variant="primary" type="submit" disabled={isLoading} className="btn-block">{isLoading ? 'Creating account…' : 'Create customer account'}</Button>
            <p className="field-hint" style={{ marginTop: 12, display: 'flex', gap: 6, alignItems: 'center' }}><Lock size={13} /> Credentials are securely managed by the identity service.</p>
            <Link className="field-hint" to="/login"><ArrowLeft size={13} /> Back to sign in</Link>
          </form>
        </div>
      </div>
    </div>
  );
}
