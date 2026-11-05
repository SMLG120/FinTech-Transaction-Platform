import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { apiClient } from '../../api/client';
import { beginLogin, completeLogin, logoutUrl, refreshTokens } from '../../api/authApi';
import type { TokenSet } from '../../api/authApi';
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
  /** Redirects to Keycloak (Authorization Code + PKCE). Resolves only if the redirect fails. */
  login: (returnTo?: string) => Promise<void>;
  /** Redeems the authorization response on /auth/callback; resolves to the in-app return path. */
  finishLogin: (href: string) => Promise<string>;
  logout: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

/**
 * React StrictMode mounts effects twice in development. The authorization code and the pending
 * PKCE entry are single use, so the second mount must share the first exchange, not start one.
 */
const inFlight = new Map<string, Promise<{ tokens: TokenSet; returnTo: string }>>();

/** Refresh this many seconds before the access token expires (300 s in this realm). */
const REFRESH_LEEWAY_S = 30;

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(null);
  const [roles, setRoles] = useState<Role[]>([]);
  const [username, setUsername] = useState<string | null>(null);
  const [loginError, setLoginError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  // Refresh and ID tokens live in memory with the access token — never storage, never the URL.
  // A reload therefore costs one redirect, which Keycloak's SSO cookie answers without a prompt.
  const session = useRef<{ refreshToken: string | null; idToken: string | null }>({ refreshToken: null, idToken: null });
  const refreshTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clear = useCallback(() => {
    if (refreshTimer.current) clearTimeout(refreshTimer.current);
    refreshTimer.current = null;
    session.current = { refreshToken: null, idToken: null };
    apiClient.setToken(null);
    setToken(null);
    setRoles([]);
    setUsername(null);
  }, []);

  const install = useCallback(
    (tokens: TokenSet) => {
      session.current = {
        refreshToken: tokens.refreshToken,
        idToken: tokens.idToken ?? session.current.idToken,
      };
      apiClient.setToken(tokens.accessToken);
      const payload = decodeJwtPayload(tokens.accessToken);
      setToken(tokens.accessToken);
      setRoles(rolesFromPayload(payload));
      setUsername(usernameFromPayload(payload));

      if (refreshTimer.current) clearTimeout(refreshTimer.current);
      const delayMs = Math.max(5, tokens.expiresIn - REFRESH_LEEWAY_S) * 1000;
      refreshTimer.current = setTimeout(() => {
        const refreshToken = session.current.refreshToken;
        // A failed refresh means the SSO session ended (idle timeout, admin logout, password
        // change): drop to sign-in rather than keep calling with a token the gateway will refuse.
        (refreshToken ? refreshTokens(refreshToken) : Promise.reject(new Error('no refresh token')))
          .then(install)
          .catch(() => {
            clear();
            setLoginError('Your session ended. Please sign in again.');
          });
      }, delayMs);
    },
    [clear],
  );

  useEffect(() => () => {
    if (refreshTimer.current) clearTimeout(refreshTimer.current);
  }, []);

  const login = useCallback(async (returnTo = '/') => {
    setIsLoading(true);
    setLoginError(null);
    try {
      window.location.assign(await beginLogin({ returnTo }));
    } catch (error) {
      setLoginError(error instanceof Error ? error.message : 'Could not start sign-in. Please retry.');
      setIsLoading(false);
    }
  }, []);

  const finishLogin = useCallback(
    async (href: string) => {
      setIsLoading(true);
      setLoginError(null);
      let exchange = inFlight.get(href);
      if (!exchange) {
        exchange = completeLogin({ href });
        inFlight.set(href, exchange);
      }
      try {
        const { tokens, returnTo } = await exchange;
        install(tokens);
        return returnTo;
      } catch (error) {
        clear();
        setLoginError(error instanceof Error ? error.message : 'Sign in failed. Please retry.');
        throw error;
      } finally {
        setIsLoading(false);
      }
    },
    [clear, install],
  );

  const logout = useCallback(() => {
    const idToken = session.current.idToken;
    clear();
    setLoginError(null);
    // End the Keycloak SSO session too; otherwise "Sign in" would silently log straight back in.
    window.location.assign(logoutUrl(idToken));
  }, [clear]);

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
      finishLogin,
      logout,
    }),
    [token, roles, username, loginError, isLoading, hasRole, login, finishLogin, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}
