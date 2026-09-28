import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { apiClient } from '../../api/client';
import { passwordLogin } from '../../api/authApi';
import { decodeJwtPayload, rolesFromPayload, usernameFromPayload } from './roles';
import type { Role } from './roles';

interface AuthState {
  token: string | null;
  roles: Role[];
  username: string | null;
  loginError: string | null;
  isLoading: boolean;
  /** UI gating only — the gateway and services enforce the real policy. */
  hasRole: (...wanted: Role[]) => boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(null);
  const [roles, setRoles] = useState<Role[]>([]);
  const [username, setUsername] = useState<string | null>(null);
  const [loginError, setLoginError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);

  const login = useCallback(async (email: string, password: string) => {
    setIsLoading(true);
    setLoginError(null);
    try {
      const issued = await passwordLogin(email, password);
      // Token lives in memory and dies with the tab — never localStorage,
      // never a cookie, never the URL (ADR-0013).
      apiClient.setToken(issued);
      const payload = decodeJwtPayload(issued);
      setToken(issued);
      setRoles(rolesFromPayload(payload));
      setUsername(usernameFromPayload(payload));
    } catch (error) {
      apiClient.setToken(null);
      setToken(null);
      setRoles([]);
      setUsername(null);
      setLoginError(
        error instanceof Error ? error.message : 'Sign in failed. Please retry.',
      );
      throw error;
    } finally {
      setIsLoading(false);
    }
  }, []);

  const logout = useCallback(() => {
    apiClient.setToken(null);
    setToken(null);
    setRoles([]);
    setUsername(null);
    setLoginError(null);
  }, []);

  const hasRole = useCallback(
    (...wanted: Role[]) => wanted.some((role) => roles.includes(role)),
    [roles],
  );

  const value = useMemo<AuthState>(
    () => ({
      token,
      roles,
      username,
      loginError,
      isLoading,
      hasRole,
      login,
      logout,
    }),
    [token, roles, username, loginError, isLoading, hasRole, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}
