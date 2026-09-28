import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { useLocation, useNavigate } from 'react-router-dom';
import { z } from 'zod';
import { Button } from '../../components/ui/Button';
import { Card } from '../../components/ui/Card';
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
      <Card>
        <div className="auth-card">
          <h1>Sign in</h1>
          <p className="field-hint">
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
                  {errors.email.message}
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
                aria-invalid={errors.password ? 'true' : 'false'}
                {...register('password')}
              />
              {errors.password ? (
                <p className="field-error" role="alert">
                  {errors.password.message}
                </p>
              ) : null}
            </div>
            {loginError ? (
              <p className="field-error" role="alert">
                {loginError}
              </p>
            ) : null}
            <Button variant="primary" type="submit" disabled={isLoading}>
              {isLoading ? 'Signing in…' : 'Sign in'}
            </Button>
          </form>
        </div>
      </Card>
    </div>
  );
}
