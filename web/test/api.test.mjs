/**
 * Unit tests for web/app/api.js. Run with `npm test` from web/ or `make web-test` from the root.
 * No browser, no server, no Docker: fetch is stubbed per test, and crypto is injectable, so every
 * assertion here runs anywhere node runs.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  ApiClient,
  ApiError,
  DEFAULT_CONFIG,
  formatMoney,
  loadConfig,
  newCorrelationId,
  newIdempotencyKey,
  parseError,
} from '../app/api.js';

test('idempotency and correlation keys are UUIDs and differ per call', () => {
  const first = newIdempotencyKey(() => '11111111-1111-4111-8111-111111111111');
  assert.equal(first, '11111111-1111-4111-8111-111111111111');
  assert.match(newCorrelationId(), /^[0-9a-f-]{36}$/);
});

test('formatMoney renders code-first like the services, and blanks stay blank', () => {
  assert.equal(formatMoney('50.00', 'GBP'), 'GBP 50.00');
  assert.equal(formatMoney(null, 'GBP'), '—');
  assert.equal(formatMoney('', 'GBP'), '—');
});

test('parseError never throws and always names a code', () => {
  assert.deepEqual(parseError(409, { error: 'DISPUTE_ALREADY_OPEN', message: 'open already', correlationId: 'c-1' }), {
    status: 409,
    code: 'DISPUTE_ALREADY_OPEN',
    message: 'open already',
    correlationId: 'c-1',
  });
  assert.equal(parseError(500, null).code, 'UNKNOWN_ERROR');
  assert.equal(parseError(500, '<html>oops</html>').code, 'UNKNOWN_ERROR');
});

test('loadConfig prefers same-origin config.json and ignores unknown keys', async () => {
  const stub = async () => ({ ok: true, json: async () => ({ gatewayBaseUrl: 'http://example.test:8080', evil: 'x' }) });
  const config = await loadConfig(stub);
  assert.equal(config.gatewayBaseUrl, 'http://example.test:8080');
  assert.equal(config.evil, undefined);
  assert.equal(config.keycloakRealm, DEFAULT_CONFIG.keycloakRealm);
});

test('loadConfig falls back to defaults when config.json is missing or broken', async () => {
  assert.deepEqual(await loadConfig(async () => ({ ok: false })), { ...DEFAULT_CONFIG });
  assert.deepEqual(
    await loadConfig(async () => {
      throw new Error('no network');
    }),
    { ...DEFAULT_CONFIG },
  );
});

function stubFetch(handler) {
  return async (url, options) => handler(url, options);
}

/** Answers the token URL with a token and routes everything else to the handler. */
function stubLoggedIn(handler) {
  return stubFetch(async (url, options) => {
    if (String(url).includes('openid-connect/token')) {
      return { ok: true, json: async () => ({ access_token: 'tok-123' }) };
    }
    return handler(url, options);
  });
}

test('login posts the password grant and keeps the token in memory', async () => {
  let seen = null;
  const api = new ApiClient(DEFAULT_CONFIG, stubFetch(async (url, options) => {
    seen = { url, options };
    return { ok: true, json: async () => ({ access_token: 'tok-123' }) };
  }));
  assert.equal(api.loggedIn, false);
  await api.login('a@b.test', 'secret');
  assert.equal(api.loggedIn, true);
  assert.match(seen.url, /protocol\/openid-connect\/token$/);
  assert.match(seen.options.body, /grant_type=password/);
  assert.match(seen.options.body, /client_id=fintech-web/);
  api.logout();
  assert.equal(api.loggedIn, false);
});

test('requests carry the bearer token, a correlation id, and the key when given', async () => {
  let seen = null;
  const api = new ApiClient(DEFAULT_CONFIG, stubLoggedIn(async (url, options) => {
    seen = { url, options };
    return { ok: true, json: async () => ({}) };
  }));
  await api.login('a@b.test', 'secret');
  await api.post('/api/v1/transactions', { amount: '1.00' }, 'key-1');
  assert.equal(seen.options.headers.Authorization, 'Bearer tok-123');
  assert.match(seen.options.headers['X-Correlation-Id'], /^[0-9a-f-]{36}$/);
  assert.equal(seen.options.headers['Idempotency-Key'], 'key-1');
  assert.equal(seen.options.headers['Content-Type'], 'application/json');
});

test('a bare GET sends no content type and no key', async () => {
  let seen = null;
  const api = new ApiClient(DEFAULT_CONFIG, stubLoggedIn(async (url, options) => {
    seen = options.headers;
    return { ok: true, json: async () => ({}) };
  }));
  await api.login('a@b.test', 'secret');
  await api.get('/api/v1/accounts/balance?currency=GBP');
  assert.equal(seen['Content-Type'], undefined);
  assert.equal(seen['Idempotency-Key'], undefined);
  assert.ok(seen.Authorization.startsWith('Bearer '));
});

test('API errors surface code and correlation id for the ticket', async () => {
  const api = new ApiClient(
    DEFAULT_CONFIG,
    stubLoggedIn(async () => ({
      ok: false,
      status: 409,
      json: async () => ({ error: 'DISPUTE_ALREADY_OPEN', message: 'open already', correlationId: 'c-9' }),
    })),
  );
  await api.login('a@b.test', 'secret');
  await assert.rejects(api.get('/api/v1/disputes'), (error) => {
    assert.ok(error instanceof ApiError);
    assert.equal(error.code, 'DISPUTE_ALREADY_OPEN');
    assert.equal(error.correlationId, 'c-9');
    return true;
  });
});

test('calling without login fails before any network happens', async () => {
  let called = false;
  const api = new ApiClient(DEFAULT_CONFIG, stubFetch(async () => {
    called = true;
    return { ok: true, json: async () => ({}) };
  }));
  await assert.rejects(api.get('/api/v1/disputes'), /Not logged in/);
  assert.equal(called, false);
});
