/**
 * Browser sign-in: OAuth 2.0 Authorization Code with PKCE (RFC 7636) against the Keycloak
 * `fintech-web` public client. Same protocol and checks as web/app/oidc.js.
 *
 * The dashboard never sees a password: Keycloak renders the login page on its own origin, so
 * password policy, lockout, MFA and SSO live in one place, and an XSS hole here cannot read a
 * credential that was never typed into this app.
 *
 *   beginLogin()    one-time code_verifier, state and nonce → sessionStorage (this tab, until the
 *                   redirect returns); returns the authorize URL. Only the S256 challenge leaves.
 *   completeLogin() checks state (login CSRF), consumes the pending entry exactly once, redeems
 *                   code + verifier, and checks the ID token's nonce, issuer and audience.
 *
 * Tokens are returned to the caller and kept in memory only. No secrets here: public client.
 */

import { ApiError, parseError } from './client';

export interface KeycloakConfig {
  baseUrl: string;
  realm: string;
  clientId: string;
}

export interface TokenSet {
  accessToken: string;
  refreshToken: string | null;
  idToken: string | null;
  expiresIn: number;
}

interface PendingLogin {
  verifier: string;
  state: string;
  nonce: string;
  redirectUri: string;
  returnTo: string;
  createdAt: number;
}

export const PENDING_KEY = 'fintech.oidc.pending';
/** A login that takes longer than this between redirect and return is treated as abandoned. */
export const PENDING_TTL_MS = 10 * 60 * 1000;
export const CALLBACK_PATH = '/auth/callback';

export function resolveKeycloakConfig(): KeycloakConfig {
  return {
    baseUrl: (import.meta.env['VITE_KEYCLOAK_URL'] as string | undefined) ?? 'http://localhost:8180',
    realm: (import.meta.env['VITE_KEYCLOAK_REALM'] as string | undefined) ?? 'fintech',
    clientId: (import.meta.env['VITE_KEYCLOAK_CLIENT_ID'] as string | undefined) ?? 'fintech-web',
  };
}

/** Exactly the redirect URI registered on fintech-web. */
export function defaultRedirectUri(): string {
  return `${window.location.origin}${CALLBACK_PATH}`;
}

export function issuer(config: KeycloakConfig): string {
  return `${config.baseUrl}/realms/${config.realm}`;
}

export function endpoints(config: KeycloakConfig) {
  const base = `${issuer(config)}/protocol/openid-connect`;
  return { authorize: `${base}/auth`, token: `${base}/token`, logout: `${base}/logout` };
}

export function base64UrlEncode(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** 32 random bytes → 43 base64url characters: the RFC 7636 recommended verifier size. */
export function randomToken(byteLength = 32): string {
  return base64UrlEncode(crypto.getRandomValues(new Uint8Array(byteLength)));
}

export async function pkceChallenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64UrlEncode(new Uint8Array(digest));
}

/** Only "/something" paths, never "//host" or "https://…": an open redirect after login is phishing. */
export function safeReturnTo(path: unknown): string {
  return typeof path === 'string' && path.startsWith('/') && !path.startsWith('//') && !path.includes('\\')
    ? path
    : '/';
}

function loginError(code: string, message: string, status = 400): ApiError {
  return new ApiError({ status, code, message, correlationId: null });
}

function claimsOf(jwt: string | null): Record<string, unknown> | null {
  const part = jwt?.split('.')[1];
  if (!part) return null;
  try {
    const base64 = part.replace(/-/g, '+').replace(/_/g, '/');
    const binary = atob(base64 + '='.repeat((4 - (base64.length % 4)) % 4));
    const parsed: unknown = JSON.parse(new TextDecoder().decode(Uint8Array.from(binary, (c) => c.charCodeAt(0))));
    return parsed !== null && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

/** Starts a sign-in. Returns the authorize URL; the caller navigates to it. */
export async function beginLogin({
  returnTo = '/',
  config = resolveKeycloakConfig(),
  redirectUri = defaultRedirectUri(),
  storage = window.sessionStorage,
  now = Date.now,
}: {
  returnTo?: string;
  config?: KeycloakConfig;
  redirectUri?: string;
  storage?: Storage;
  now?: () => number;
} = {}): Promise<string> {
  const pending: PendingLogin = {
    verifier: randomToken(),
    state: randomToken(),
    nonce: randomToken(),
    redirectUri,
    returnTo: safeReturnTo(returnTo),
    createdAt: now(),
  };
  storage.setItem(PENDING_KEY, JSON.stringify(pending));
  const url = new URL(endpoints(config).authorize);
  url.search = new URLSearchParams({
    response_type: 'code',
    client_id: config.clientId,
    redirect_uri: redirectUri,
    scope: 'openid profile email',
    state: pending.state,
    nonce: pending.nonce,
    code_challenge: await pkceChallenge(pending.verifier),
    code_challenge_method: 'S256',
  }).toString();
  return url.toString();
}

function readPending(storage: Storage): PendingLogin | null {
  const raw = storage.getItem(PENDING_KEY);
  storage.removeItem(PENDING_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Partial<PendingLogin>;
    return typeof parsed.state === 'string' && typeof parsed.verifier === 'string' ? (parsed as PendingLogin) : null;
  } catch {
    return null;
  }
}

/**
 * Finishes a sign-in from the callback URL. Throws ApiError on every failure. The pending entry is
 * removed before anything else, so one authorization response can be redeemed at most once.
 */
export async function completeLogin({
  href,
  config = resolveKeycloakConfig(),
  storage = window.sessionStorage,
  fetchImpl = fetch,
  now = Date.now,
}: {
  href: string;
  config?: KeycloakConfig;
  storage?: Storage;
  fetchImpl?: typeof fetch;
  now?: () => number;
}): Promise<{ tokens: TokenSet; returnTo: string }> {
  const params = new URL(href).searchParams;
  const pending = readPending(storage);
  if (!pending || params.get('state') !== pending.state) {
    throw loginError('LOGIN_STATE_MISMATCH', 'This sign-in was not started here or has already been used. Please sign in again.');
  }
  if (now() - pending.createdAt > PENDING_TTL_MS) {
    throw loginError('LOGIN_EXPIRED', 'The sign-in took too long. Please sign in again.');
  }
  const error = params.get('error');
  if (error) {
    throw loginError(error.toUpperCase(), params.get('error_description') ?? 'Sign-in was cancelled or refused.');
  }

  const tokens = await tokenRequest(config, fetchImpl, {
    grant_type: 'authorization_code',
    client_id: config.clientId,
    code: params.get('code') ?? '',
    redirect_uri: pending.redirectUri,
    code_verifier: pending.verifier,
  });

  const claims = claimsOf(tokens.idToken);
  const aud = claims?.['aud'];
  const audience = Array.isArray(aud) ? aud : [aud];
  if (!claims || claims['nonce'] !== pending.nonce || claims['iss'] !== issuer(config) || !audience.includes(config.clientId)) {
    throw loginError('LOGIN_TOKEN_REJECTED', 'The identity provider returned a token for a different sign-in.');
  }
  return { tokens, returnTo: safeReturnTo(pending.returnTo) };
}

/** Exchanges a refresh token. Keycloak rotates it; the caller must keep the new one. */
export function refreshTokens(
  refreshToken: string,
  config: KeycloakConfig = resolveKeycloakConfig(),
  fetchImpl: typeof fetch = fetch,
): Promise<TokenSet> {
  return tokenRequest(config, fetchImpl, {
    grant_type: 'refresh_token',
    client_id: config.clientId,
    refresh_token: refreshToken,
  });
}

/** RP-initiated logout: ends the Keycloak SSO session, not just this tab's memory. */
export function logoutUrl(
  idToken: string | null,
  postLogoutRedirectUri = `${window.location.origin}/login`,
  config: KeycloakConfig = resolveKeycloakConfig(),
): string {
  const url = new URL(endpoints(config).logout);
  const params = new URLSearchParams({ client_id: config.clientId, post_logout_redirect_uri: postLogoutRedirectUri });
  if (idToken) params.set('id_token_hint', idToken);
  url.search = params.toString();
  return url.toString();
}

async function tokenRequest(
  config: KeycloakConfig,
  fetchImpl: typeof fetch,
  form: Record<string, string>,
): Promise<TokenSet> {
  const response = await fetchImpl(endpoints(config).token, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(form).toString(),
  });
  let data: unknown = null;
  try {
    data = await response.json();
  } catch {
    data = null;
  }
  const record = data !== null && typeof data === 'object' ? (data as Record<string, unknown>) : {};
  if (!response.ok) {
    const parsed = parseError(response.status, data);
    // Keycloak's OAuth errors are {error, error_description}; surface the description.
    if (typeof record['error_description'] === 'string') parsed.message = record['error_description'];
    throw new ApiError(parsed);
  }
  const accessToken = record['access_token'];
  if (typeof accessToken !== 'string' || accessToken.length === 0) {
    throw loginError('LOGIN_FAILED', 'No token was issued.', response.status);
  }
  const expiresIn = record['expires_in'];
  return {
    accessToken,
    refreshToken: typeof record['refresh_token'] === 'string' ? record['refresh_token'] : null,
    idToken: typeof record['id_token'] === 'string' ? record['id_token'] : null,
    expiresIn: typeof expiresIn === 'number' && Number.isFinite(expiresIn) ? expiresIn : 300,
  };
}
