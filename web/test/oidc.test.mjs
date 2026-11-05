/**
 * Unit tests for web/app/oidc.js — the Authorization Code + PKCE sign-in. No browser or Keycloak:
 * storage, fetch, crypto and clock are injected, so each security property is asserted directly.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { DEFAULT_CONFIG } from '../app/api.js';
import {
  PENDING_KEY,
  PENDING_TTL_MS,
  base64UrlEncode,
  beginLogin,
  completeLogin,
  isCallback,
  issuer,
  logoutUrl,
  pkceChallenge,
  refreshTokens,
  safeReturnTo,
} from '../app/oidc.js';

const REDIRECT = 'http://localhost:3001/';

function memoryStorage() {
  const map = new Map();
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(k, String(v)),
    removeItem: (k) => map.delete(k),
    size: () => map.size,
  };
}

function jwt(claims) {
  const enc = (o) => base64UrlEncode(new TextEncoder().encode(JSON.stringify(o)));
  return `${enc({ alg: 'RS256' })}.${enc(claims)}.sig`;
}

/** Starts a login and returns what the browser would come back with. */
async function started(storage, overrides = {}) {
  const url = new URL(await beginLogin({ config: DEFAULT_CONFIG, redirectUri: REDIRECT, storage, ...overrides }));
  const pending = JSON.parse(storage.getItem(PENDING_KEY));
  return { url, pending };
}

function tokenEndpoint(idClaims, seen = {}) {
  return async (url, options) => {
    seen.url = url;
    seen.body = new URLSearchParams(options.body);
    return {
      ok: true,
      json: async () => ({
        access_token: 'at-1',
        refresh_token: 'rt-1',
        id_token: jwt(idClaims),
        expires_in: 300,
      }),
    };
  };
}

test('PKCE challenge matches the RFC 7636 appendix B test vector', async () => {
  assert.equal(
    await pkceChallenge('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk'),
    'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM',
  );
});

test('beginLogin builds a code+S256 request and never sends the verifier', async () => {
  const storage = memoryStorage();
  const { url, pending } = await started(storage);
  assert.equal(url.origin + url.pathname, `${issuer(DEFAULT_CONFIG)}/protocol/openid-connect/auth`);
  const p = url.searchParams;
  assert.equal(p.get('response_type'), 'code');
  assert.equal(p.get('client_id'), 'fintech-web');
  assert.equal(p.get('redirect_uri'), REDIRECT);
  assert.equal(p.get('code_challenge_method'), 'S256');
  assert.match(p.get('scope'), /\bopenid\b/);
  assert.equal(p.get('state'), pending.state);
  assert.equal(p.get('nonce'), pending.nonce);
  assert.equal(p.get('code_challenge'), await pkceChallenge(pending.verifier));
  assert.ok(!url.toString().includes(pending.verifier), 'verifier must stay in the browser');
  assert.equal(p.get('password'), null);
  assert.match(pending.verifier, /^[A-Za-z0-9_-]{43}$/);
});

test('every login gets fresh state, nonce and verifier', async () => {
  const a = (await started(memoryStorage())).pending;
  const b = (await started(memoryStorage())).pending;
  assert.notEqual(a.state, b.state);
  assert.notEqual(a.nonce, b.nonce);
  assert.notEqual(a.verifier, b.verifier);
});

test('completeLogin exchanges code + verifier and returns the tokens', async () => {
  const storage = memoryStorage();
  const { pending } = await started(storage, { returnTo: '/cards' });
  const seen = {};
  const result = await completeLogin({
    config: DEFAULT_CONFIG,
    href: `${REDIRECT}?code=abc&state=${pending.state}`,
    storage,
    fetchImpl: tokenEndpoint({ nonce: pending.nonce, iss: issuer(DEFAULT_CONFIG), aud: 'fintech-web' }, seen),
  });
  assert.equal(result.tokens.accessToken, 'at-1');
  assert.equal(result.tokens.refreshToken, 'rt-1');
  assert.equal(result.returnTo, '/cards');
  assert.match(seen.url, /openid-connect\/token$/);
  assert.equal(seen.body.get('grant_type'), 'authorization_code');
  assert.equal(seen.body.get('code'), 'abc');
  assert.equal(seen.body.get('code_verifier'), pending.verifier);
  assert.equal(seen.body.get('redirect_uri'), REDIRECT);
  assert.equal(storage.size(), 0, 'the pending entry is single use');
});

test('a state that does not match is refused before any network call (login CSRF)', async () => {
  const storage = memoryStorage();
  await started(storage);
  let called = false;
  await assert.rejects(
    completeLogin({
      config: DEFAULT_CONFIG,
      href: `${REDIRECT}?code=abc&state=attacker`,
      storage,
      fetchImpl: async () => {
        called = true;
      },
    }),
    (e) => e.code === 'LOGIN_STATE_MISMATCH',
  );
  assert.equal(called, false);
  assert.equal(storage.size(), 0);
});

test('a callback cannot be replayed: the second attempt has no pending login', async () => {
  const storage = memoryStorage();
  const { pending } = await started(storage);
  const args = {
    config: DEFAULT_CONFIG,
    href: `${REDIRECT}?code=abc&state=${pending.state}`,
    storage,
    fetchImpl: tokenEndpoint({ nonce: pending.nonce, iss: issuer(DEFAULT_CONFIG), aud: 'fintech-web' }),
  };
  await completeLogin(args);
  await assert.rejects(completeLogin(args), (e) => e.code === 'LOGIN_STATE_MISMATCH');
});

test('an ID token with the wrong nonce, issuer or audience is rejected (token injection)', async () => {
  for (const bad of [
    { nonce: 'other', iss: issuer(DEFAULT_CONFIG), aud: 'fintech-web' },
    { nonce: null, iss: 'https://evil.test/realms/fintech', aud: 'fintech-web' },
    { nonce: null, iss: issuer(DEFAULT_CONFIG), aud: 'some-other-client' },
  ]) {
    const storage = memoryStorage();
    const { pending } = await started(storage);
    const claims = { ...bad, nonce: bad.nonce ?? pending.nonce };
    await assert.rejects(
      completeLogin({
        config: DEFAULT_CONFIG,
        href: `${REDIRECT}?code=abc&state=${pending.state}`,
        storage,
        fetchImpl: tokenEndpoint(claims),
      }),
      (e) => e.code === 'LOGIN_TOKEN_REJECTED',
    );
  }
});

test('an abandoned login expires', async () => {
  const storage = memoryStorage();
  const { pending } = await started(storage, { now: () => 0 });
  await assert.rejects(
    completeLogin({
      config: DEFAULT_CONFIG,
      href: `${REDIRECT}?code=abc&state=${pending.state}`,
      storage,
      now: () => PENDING_TTL_MS + 1,
    }),
    (e) => e.code === 'LOGIN_EXPIRED',
  );
});

test('an error redirect from Keycloak surfaces its description', async () => {
  const storage = memoryStorage();
  const { pending } = await started(storage);
  await assert.rejects(
    completeLogin({
      config: DEFAULT_CONFIG,
      href: `${REDIRECT}?error=access_denied&error_description=User+cancelled&state=${pending.state}`,
      storage,
    }),
    (e) => e.code === 'ACCESS_DENIED' && e.message === 'User cancelled',
  );
});

test('a token endpoint failure surfaces Keycloak\'s error description', async () => {
  const storage = memoryStorage();
  const { pending } = await started(storage);
  await assert.rejects(
    completeLogin({
      config: DEFAULT_CONFIG,
      href: `${REDIRECT}?code=used&state=${pending.state}`,
      storage,
      fetchImpl: async () => ({
        ok: false,
        status: 400,
        json: async () => ({ error: 'invalid_grant', error_description: 'Code not valid' }),
      }),
    }),
    (e) => e.status === 400 && e.code === 'invalid_grant' && e.message === 'Code not valid',
  );
});

test('refreshTokens uses the refresh_token grant on the public client', async () => {
  const seen = {};
  const tokens = await refreshTokens({ config: DEFAULT_CONFIG, refreshToken: 'rt-0', fetchImpl: tokenEndpoint({}, seen) });
  assert.equal(seen.body.get('grant_type'), 'refresh_token');
  assert.equal(seen.body.get('refresh_token'), 'rt-0');
  assert.equal(seen.body.get('client_id'), 'fintech-web');
  assert.equal(tokens.accessToken, 'at-1');
});

test('logoutUrl ends the Keycloak session and returns to the app', () => {
  const url = new URL(logoutUrl({ config: DEFAULT_CONFIG, idToken: 'id-1', postLogoutRedirectUri: REDIRECT }));
  assert.match(url.pathname, /openid-connect\/logout$/);
  assert.equal(url.searchParams.get('id_token_hint'), 'id-1');
  assert.equal(url.searchParams.get('post_logout_redirect_uri'), REDIRECT);
});

test('return paths are same-app only: no open redirect after sign-in', () => {
  assert.equal(safeReturnTo('/disputes'), '/disputes');
  for (const bad of ['//evil.test', 'https://evil.test', '/\\evil.test', undefined, 42]) {
    assert.equal(safeReturnTo(bad), '/');
  }
});

test('isCallback recognises only authorization responses', () => {
  assert.equal(isCallback(`${REDIRECT}?code=a&state=b`), true);
  assert.equal(isCallback(`${REDIRECT}?error=x&state=b`), true);
  assert.equal(isCallback(`${REDIRECT}?code=a`), false);
  assert.equal(isCallback(REDIRECT), false);
});
