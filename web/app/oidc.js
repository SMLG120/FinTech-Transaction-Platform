/**
 * Browser sign-in for the FinTech web UI: OAuth 2.0 Authorization Code with PKCE (RFC 7636),
 * against the Keycloak `fintech-web` public client.
 *
 * Why this and not the password grant: the UI never sees the user's password. Keycloak renders the
 * login page on its own origin, so password policy, lockout, MFA and SSO all live in one place,
 * and an XSS hole in this UI cannot read a credential that was never typed into it.
 *
 * Flow:
 *   1. beginLogin()    makes a one-time code_verifier, state and nonce, keeps them in
 *                      sessionStorage (this tab only, and only until the redirect returns) and
 *                      returns the authorize URL to navigate to. Only the SHA-256 challenge leaves.
 *   2. Keycloak authenticates and redirects back with ?code&state.
 *   3. completeLogin() checks state (CSRF / login injection), consumes the pending entry exactly
 *                      once, exchanges code + verifier for tokens, and checks the ID token's nonce,
 *                      issuer and audience (token injection / mix-up).
 *
 * Tokens stay in memory with the caller. Nothing here touches the DOM or `location`: every side
 * effect (crypto, storage, fetch, clock) is injectable, so the module runs under `node --test`.
 */
import { ApiError, parseError } from './api.js';

export const PENDING_KEY = 'fintech.oidc.pending';
/** A login that takes longer than this between redirect and return is treated as abandoned. */
export const PENDING_TTL_MS = 10 * 60 * 1000;

export function issuer(config) {
  return `${config.keycloakBaseUrl}/realms/${config.keycloakRealm}`;
}

export function endpoints(config) {
  const base = `${issuer(config)}/protocol/openid-connect`;
  return { authorize: `${base}/auth`, token: `${base}/token`, logout: `${base}/logout` };
}

export function base64UrlEncode(bytes) {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** 32 random bytes → 43 base64url characters: the RFC 7636 recommended verifier size. */
export function randomToken(cryptoImpl = globalThis.crypto, byteLength = 32) {
  return base64UrlEncode(cryptoImpl.getRandomValues(new Uint8Array(byteLength)));
}

export async function pkceChallenge(verifier, cryptoImpl = globalThis.crypto) {
  const digest = await cryptoImpl.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64UrlEncode(new Uint8Array(digest));
}

/** Decodes a JWT payload without verifying it. Only for claims checks on a token we just fetched. */
export function decodeJwtPayload(jwt) {
  const part = typeof jwt === 'string' ? jwt.split('.')[1] : undefined;
  if (!part) return null;
  try {
    const base64 = part.replace(/-/g, '+').replace(/_/g, '/');
    const binary = atob(base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '='));
    const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
    return JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    return null;
  }
}

function loginError(code, message, status = 400) {
  return new ApiError({ status, code, message, correlationId: null });
}

/**
 * Starts a sign-in. Returns the URL to navigate to; the caller does `location.assign(url)`.
 * `returnTo` is an in-app path to land on afterwards — only same-app relative paths survive.
 */
export async function beginLogin({ config, redirectUri, storage, returnTo = '/', cryptoImpl = globalThis.crypto, now = Date.now }) {
  const verifier = randomToken(cryptoImpl);
  const state = randomToken(cryptoImpl);
  const nonce = randomToken(cryptoImpl);
  storage.setItem(
    PENDING_KEY,
    JSON.stringify({ verifier, state, nonce, redirectUri, returnTo: safeReturnTo(returnTo), createdAt: now() }),
  );
  const url = new URL(endpoints(config).authorize);
  url.search = new URLSearchParams({
    response_type: 'code',
    client_id: config.keycloakClientId,
    redirect_uri: redirectUri,
    scope: 'openid profile email',
    state,
    nonce,
    code_challenge: await pkceChallenge(verifier, cryptoImpl),
    code_challenge_method: 'S256',
  }).toString();
  return url.toString();
}

/** Only "/something" paths, never "//host" or "https://…": an open redirect after login is phishing. */
export function safeReturnTo(path) {
  return typeof path === 'string' && path.startsWith('/') && !path.startsWith('//') && !path.includes('\\')
    ? path
    : '/';
}

/** True when the current URL is an authorization response this module should handle. */
export function isCallback(href) {
  const params = new URL(href).searchParams;
  return params.has('state') && (params.has('code') || params.has('error'));
}

/**
 * Finishes a sign-in from the callback URL. Returns {tokens, returnTo}. Throws ApiError on every
 * failure; the pending entry is removed first, so a code can never be redeemed twice from here.
 */
export async function completeLogin({ config, href, storage, fetchImpl = fetch, now = Date.now }) {
  const params = new URL(href).searchParams;
  const raw = storage.getItem(PENDING_KEY);
  storage.removeItem(PENDING_KEY);

  let pending = null;
  try {
    pending = raw ? JSON.parse(raw) : null;
  } catch {
    pending = null;
  }
  if (!pending || typeof pending.state !== 'string' || params.get('state') !== pending.state) {
    throw loginError('LOGIN_STATE_MISMATCH', 'This sign-in was not started here or has already been used. Please sign in again.');
  }
  if (now() - pending.createdAt > PENDING_TTL_MS) {
    throw loginError('LOGIN_EXPIRED', 'The sign-in took too long. Please sign in again.');
  }
  if (params.has('error')) {
    throw loginError(
      params.get('error').toUpperCase(),
      params.get('error_description') || 'Sign-in was cancelled or refused.',
    );
  }

  const tokens = await tokenRequest(config, fetchImpl, {
    grant_type: 'authorization_code',
    client_id: config.keycloakClientId,
    code: params.get('code'),
    redirect_uri: pending.redirectUri,
    code_verifier: pending.verifier,
  });

  const claims = decodeJwtPayload(tokens.idToken);
  const audience = claims && (Array.isArray(claims.aud) ? claims.aud : [claims.aud]);
  if (
    !claims ||
    claims.nonce !== pending.nonce ||
    claims.iss !== issuer(config) ||
    !audience.includes(config.keycloakClientId)
  ) {
    throw loginError('LOGIN_TOKEN_REJECTED', 'The identity provider returned a token for a different sign-in.');
  }
  return { tokens, returnTo: safeReturnTo(pending.returnTo) };
}

/** Exchanges a refresh token. Keycloak rotates it; the caller must keep the new one. */
export function refreshTokens({ config, refreshToken, fetchImpl = fetch }) {
  return tokenRequest(config, fetchImpl, {
    grant_type: 'refresh_token',
    client_id: config.keycloakClientId,
    refresh_token: refreshToken,
  });
}

/** RP-initiated logout: ends the Keycloak SSO session, not just this tab's memory. */
export function logoutUrl({ config, idToken, postLogoutRedirectUri }) {
  const url = new URL(endpoints(config).logout);
  const params = new URLSearchParams({ client_id: config.keycloakClientId, post_logout_redirect_uri: postLogoutRedirectUri });
  if (idToken) params.set('id_token_hint', idToken);
  url.search = params.toString();
  return url.toString();
}

async function tokenRequest(config, fetchImpl, form) {
  const response = await fetchImpl(endpoints(config).token, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(form).toString(),
  });
  let data = null;
  try {
    data = await response.json();
  } catch {
    data = null;
  }
  if (!response.ok) {
    const parsed = parseError(response.status, data);
    // Keycloak's OAuth errors are {error, error_description}; surface the description.
    if (data && typeof data.error_description === 'string') parsed.message = data.error_description;
    throw new ApiError(parsed);
  }
  if (!data || typeof data.access_token !== 'string' || data.access_token.length === 0) {
    throw loginError('LOGIN_FAILED', 'No token was issued.', response.status);
  }
  return {
    accessToken: data.access_token,
    refreshToken: typeof data.refresh_token === 'string' ? data.refresh_token : null,
    idToken: typeof data.id_token === 'string' ? data.id_token : null,
    expiresIn: Number.isFinite(data.expires_in) ? data.expires_in : 300,
  };
}
