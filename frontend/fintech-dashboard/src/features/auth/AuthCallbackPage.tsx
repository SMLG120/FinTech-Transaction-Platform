import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from './AuthContext';

/**
 * The registered redirect URI. Redeems ?code&state once, then replaces this history entry so the
 * authorization response never lingers in the address bar, history, or a copied link.
 */
export function AuthCallbackPage() {
  const { finishLogin } = useAuth();
  const navigate = useNavigate();

  useEffect(() => {
    const href = window.location.href;
    finishLogin(href)
      .then((returnTo) => navigate(returnTo, { replace: true }))
      .catch(() => navigate('/login', { replace: true }));
  }, [finishLogin, navigate]);

  return (
    <div className="auth-wrap">
      <p role="status">Completing sign-in…</p>
    </div>
  );
}
