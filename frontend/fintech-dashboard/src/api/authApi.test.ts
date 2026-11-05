import { describe, expect, it } from 'vitest';
import {
  PENDING_KEY,
  PENDING_TTL_MS,
  base64UrlEncode,
  beginLogin,
  completeLogin,
  issuer,
  logoutUrl,
  pkceChallenge,
  refreshTokens,
  safeReturnTo,
} from './authApi';
import type { KeycloakConfig } from './authApi';

const config: KeycloakConfig = { baseUrl: 'http://localhost:8180', realm: 'fintech', clientId: 'fintech-web' };
const REDIRECT = 'http://localhost:5173/auth/callback';

function memoryStorage(): Storage {
  const map = new Map<string, string>();
  return {
    get length() {
      return map.size;
    },
    clear: () => map.clear(),
    key: (i: number) => [...map.keys()][i] ?? null,
    getItem: (k: string) => map.get(k) ?? null,
    setItem: (k: string, v: string) => void map.set(k, v),
    removeItem: (k: string) => void map.delete(k),
  };
}

function jwt(claims: Record<string, unknown>): string {
  const enc = (o: unknown) => base64UrlEncode(new TextEncoder().encode(JSON.stringify(o)));
  return `${enc({ alg: 'RS256' })}.${enc(claims)}.sig`;
}

async function started(storage: Storage, now = Date.now) {
  const url = new URL(await beginLogin({ config, redirectUri: REDIRECT, storage, returnTo: '/cards', now }));
  const pending = JSON.parse(storage.getItem(PENDING_KEY) ?? '{}') as { state: string; nonce: string; verifier: string };
  return { url, pending };
}

function tokenEndpoint(claims: Record<string, unknown>, seen: { body?: URLSearchParams } = {}): typeof fetch {
  return (async (_url: string, init: RequestInit) => {
    seen.body = new URLSearchParams(String(init.body));
    return new Response(
      JSON.stringify({ access_token: 'at-1', refresh_token: 'rt-1', id_token: jwt(claims), expires_in: 300 }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    );
  }) as unknown as typeof fetch;
}

describe('Authorization Code + PKCE', () => {
  it('derives the RFC 7636 appendix B challenge', async () => {
    expect(await pkceChallenge('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk')).toBe(
      'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM',
    );
  });

  it('builds a code + S256 authorize request without leaking the verifier', async () => {
    const { url, pending } = await started(memoryStorage());
    const p = url.searchParams;
    expect(url.pathname).toBe('/realms/fintech/protocol/openid-connect/auth');
    expect(p.get('response_type')).toBe('code');
    expect(p.get('code_challenge_method')).toBe('S256');
    expect(p.get('redirect_uri')).toBe(REDIRECT);
    expect(p.get('state')).toBe(pending.state);
    expect(p.get('code_challenge')).toBe(await pkceChallenge(pending.verifier));
    expect(url.toString()).not.toContain(pending.verifier);
  });

  it('redeems code + verifier once and returns the stored path', async () => {
    const storage = memoryStorage();
    const { pending } = await started(storage);
    const seen: { body?: URLSearchParams } = {};
    const args = {
      href: `${REDIRECT}?code=abc&state=${pending.state}`,
      config,
      storage,
      fetchImpl: tokenEndpoint({ nonce: pending.nonce, iss: issuer(config), aud: ['fintech-web', 'fintech-api'] }, seen),
    };
    const result = await completeLogin(args);
    expect(result.tokens.accessToken).toBe('at-1');
    expect(result.returnTo).toBe('/cards');
    expect(seen.body?.get('grant_type')).toBe('authorization_code');
    expect(seen.body?.get('code_verifier')).toBe(pending.verifier);
    await expect(completeLogin(args)).rejects.toMatchObject({ code: 'LOGIN_STATE_MISMATCH' });
  });

  it('refuses a foreign state before calling Keycloak', async () => {
    const storage = memoryStorage();
    await started(storage);
    let called = false;
    const fetchImpl = (async () => {
      called = true;
      return new Response('{}');
    }) as unknown as typeof fetch;
    await expect(
      completeLogin({ href: `${REDIRECT}?code=abc&state=forged`, config, storage, fetchImpl }),
    ).rejects.toMatchObject({ code: 'LOGIN_STATE_MISMATCH' });
    expect(called).toBe(false);
  });

  it('rejects an ID token minted for a different login, issuer or client', async () => {
    for (const bad of [
      { nonce: 'other' },
      { iss: 'https://evil.test/realms/fintech' },
      { aud: 'another-client' },
    ]) {
      const storage = memoryStorage();
      const { pending } = await started(storage);
      const claims = { nonce: pending.nonce, iss: issuer(config), aud: 'fintech-web', ...bad };
      await expect(
        completeLogin({ href: `${REDIRECT}?code=abc&state=${pending.state}`, config, storage, fetchImpl: tokenEndpoint(claims) }),
      ).rejects.toMatchObject({ code: 'LOGIN_TOKEN_REJECTED' });
    }
  });

  it('expires an abandoned login', async () => {
    const storage = memoryStorage();
    const { pending } = await started(storage, () => 0);
    await expect(
      completeLogin({ href: `${REDIRECT}?code=abc&state=${pending.state}`, config, storage, now: () => PENDING_TTL_MS + 1 }),
    ).rejects.toMatchObject({ code: 'LOGIN_EXPIRED' });
  });

  it('refreshes with the refresh_token grant and logs out at Keycloak', async () => {
    const seen: { body?: URLSearchParams } = {};
    await refreshTokens('rt-0', config, tokenEndpoint({}, seen));
    expect(seen.body?.get('grant_type')).toBe('refresh_token');
    const url = new URL(logoutUrl('id-1', 'http://localhost:5173/login', config));
    expect(url.pathname).toBe('/realms/fintech/protocol/openid-connect/logout');
    expect(url.searchParams.get('id_token_hint')).toBe('id-1');
  });

  it('only returns to same-app paths', () => {
    expect(safeReturnTo('/fraud')).toBe('/fraud');
    for (const bad of ['//evil.test', 'https://evil.test', '/\\evil.test', null]) expect(safeReturnTo(bad)).toBe('/');
  });
});
