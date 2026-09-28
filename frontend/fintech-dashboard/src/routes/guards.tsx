import { Navigate, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useAuth } from '../features/auth/AuthContext';
import type { Role } from '../features/auth/roles';

/** Any signed-in caller. Backend authorization remains authoritative. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { token } = useAuth();
  const location = useLocation();
  if (!token) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return <>{children}</>;
}

/** Role-gated UI: renders a 403 explanation instead of the page. */
export function RequireRole({ roles, children }: { roles: Role[]; children: ReactNode }) {
  const { hasRole } = useAuth();
  if (!hasRole(...roles)) {
    return (
      <div className="state-block" role="alert">
        <h2>Not permitted</h2>
        <p>Your roles do not permit this view. The backend enforces the same rule.</p>
      </div>
    );
  }
  return <>{children}</>;
}
