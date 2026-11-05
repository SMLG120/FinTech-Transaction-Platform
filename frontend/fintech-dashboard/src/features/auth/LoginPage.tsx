import { AlertCircle, Lock } from 'lucide-react';
import { Link, useLocation } from 'react-router-dom';
import { Button } from '../../components/ui/Button';
import { useAuth } from './AuthContext';

export function LoginPage() {
  const { login, isLoading, loginError } = useAuth();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? '/';
  const registrationComplete = (location.state as { registrationComplete?: boolean } | null)?.registrationComplete;

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
            You sign in on the bank&apos;s identity page. This dashboard never sees your password.
          </p>
          {loginError ? (
            <div className="alert alert-danger" role="alert">
              <AlertCircle size={16} aria-hidden="true" />
              <div>
                <strong>Sign-in failed</strong>
                <p>{loginError}</p>
              </div>
            </div>
          ) : null}
          {registrationComplete ? (
            <div className="alert alert-success" role="status">
              Account created. Sign in to continue.
            </div>
          ) : null}
          <Button
            variant="primary"
            type="button"
            disabled={isLoading}
            className="btn-block"
            onClick={() => void login(from)}
          >
            {isLoading ? 'Redirecting…' : 'Sign in securely'}
          </Button>
          <p className="field-hint" style={{ marginTop: 12 }}>
            New customer? <Link to="/register">Create an account</Link>
          </p>
          <p className="field-hint" style={{ marginTop: 12, display: 'flex', gap: 6, alignItems: 'center' }}>
            <Lock size={13} aria-hidden="true" /> Authorization Code + PKCE. Tokens live in memory only and die
            with the tab.
          </p>
        </div>
      </div>
    </div>
  );
}
