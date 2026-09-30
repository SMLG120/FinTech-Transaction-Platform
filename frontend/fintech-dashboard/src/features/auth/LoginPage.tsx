import { zodResolver } from '@hookform/resolvers/zod';
import { AlertCircle, Lock } from 'lucide-react';
import { useForm } from 'react-hook-form';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { z } from 'zod';
import { Button } from '../../components/ui/Button';
import { useAuth } from './AuthContext';

const schema = z.object({
  email: z.string().min(1, 'Enter your email address.').email('Enter a valid email address.'),
  password: z.string().min(1, 'Enter your password.'),
});

type FormValues = z.infer<typeof schema>;

export function LoginPage() {
  const { login, isLoading, loginError } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? '/';
  const registrationComplete = (location.state as { registrationComplete?: boolean } | null)?.registrationComplete;

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<FormValues>({ resolver: zodResolver(schema) });

  const onSubmit = async (values: FormValues) => {
    try {
      await login(values.email.trim(), values.password);
      navigate(from, { replace: true });
    } catch {
      // loginError in context already carries the message; stay on the page.
    }
  };

  return (
    <div className="auth-wrap">
      <div className="auth-card">
        <div className="auth-brand">
          <span className="auth-brand-mark" aria-hidden="true">
            F$
          </span>
          <h1>Meridian Bank</h1>
          <p>Secure FinTech Transaction Platform</p>
        </div>
        <div className="auth-panel">
          <h2>Sign in</h2>
          <p className="lede">
            Local development sign-in only — synthetic test identities, no real money.
          </p>
          <form onSubmit={(event) => void handleSubmit(onSubmit)(event)} noValidate>
            <div className="field">
              <label className="field-label" htmlFor="login-email">
                Email
              </label>
              <input
                id="login-email"
                className="input"
                type="email"
                autoComplete="username"
                placeholder="you@fintech.test"
                aria-invalid={errors.email ? 'true' : 'false'}
                {...register('email')}
              />
              {errors.email ? (
                <p className="field-error" role="alert">
                  <AlertCircle size={14} aria-hidden="true" /> {errors.email.message}
                </p>
              ) : null}
            </div>
            <div className="field">
              <label className="field-label" htmlFor="login-password">
                Password
              </label>
              <input
                id="login-password"
                className="input"
                type="password"
                autoComplete="current-password"
                placeholder="••••••••••"
                aria-invalid={errors.password ? 'true' : 'false'}
                {...register('password')}
              />
              {errors.password ? (
                <p className="field-error" role="alert">
                  <AlertCircle size={14} aria-hidden="true" /> {errors.password.message}
                </p>
              ) : null}
            </div>
            {loginError ? (
              <div className="alert alert-danger" role="alert">
                <AlertCircle size={16} aria-hidden="true" />
                <div>
                  <strong>Sign-in failed</strong>
                  <p>{loginError}</p>
                </div>
              </div>
            ) : null}
            {registrationComplete ? <div className="alert alert-success" role="status">Account created. Sign in to continue.</div> : null}
            <Button variant="primary" type="submit" disabled={isLoading} className="btn-block">
              {isLoading ? 'Signing in…' : 'Sign in securely'}
            </Button>
            <p className="field-hint" style={{ marginTop: 12 }}>New customer? <Link to="/register">Create an account</Link></p>
            <p className="field-hint" style={{ marginTop: 12, display: 'flex', gap: 6, alignItems: 'center' }}>
              <Lock size={13} aria-hidden="true" /> Session token lives in memory only — it dies
              with the tab.
            </p>
          </form>
        </div>
      </div>
    </div>
  );
}
