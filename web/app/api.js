/**
 * API client for the FinTech web UI.
 *
 * Pure logic and fetch wrappers with no DOM dependency, so this module runs unchanged in the
 * browser and under `node --test`. Anything that touches `document` or `localStorage` lives in
 * app.js, never here — that split is what makes this file testable without a browser.
 *
 * Conventions, shared with the backend verify scripts:
 * - Every mutating call that moves money carries an Idempotency-Key the caller generates. One key
 *   per user-initiated submit; a retry after a network failure reuses the same key, which is safe
 *   precisely because the key exists.
 * - Every call carries an X-Correlation-Id the caller generates, so a support ticket can quote one
 *   id that ties the browser, the gateway and the service logs together.
 * - The access token lives in memory on the object that owns it. Never localStorage, never a
 *   cookie, never the URL: anything persisted outlives the session and becomes exfiltration bait
 *   for the first XSS hole.
 */

export const DEFAULT_CONFIG = Object.freeze({
  gatewayBaseUrl: 'http://localhost:8080',
  keycloakBaseUrl: 'http://localhost:8180',
  keycloakRealm: 'fintech',
  keycloakClientId: 'fintech-web',
});

/** Loads same-origin config.json over the defaults. Missing file means defaults. */
export async function loadConfig(fetchImpl = fetch) {
  try {
    const response = await fetchImpl('config.json', { cache: 'no-store' });
    if (!response.ok) return { ...DEFAULT_CONFIG };
    const overrides = await response.json();
    return { ...DEFAULT_CONFIG, ...pickKnown(overrides) };
  } catch {
    return { ...DEFAULT_CONFIG };
  }
}

function pickKnown(overrides) {
  const known = {};
  if (overrides && typeof overrides === 'object') {
    for (const key of Object.keys(DEFAULT_CONFIG)) {
      if (typeof overrides[key] === 'string' && overrides[key].length > 0) {
        known[key] = overrides[key];
      }
    }
  }
  return known;
}

/** A fresh idempotency key: one per user-initiated submit, reused only across retries of it. */
export function newIdempotencyKey(randomUuid = crypto.randomUUID.bind(crypto)) {
  return randomUuid();
}

/** A fresh correlation id for one call. */
export function newCorrelationId(randomUuid = crypto.randomUUID.bind(crypto)) {
  return randomUuid();
}

/** Renders a decimal amount string next to its code, the way the services do. */
export function formatMoney(amount, currency) {
  if (amount === null || amount === undefined || amount === '') return '—';
  return `${currency} ${amount}`;
}

/**
 * Parses an error response into {status, code, message, correlationId}. Never throws: a login
 * page that crashes on an unexpected error shape is worse than the error.
 */
export function parseError(status, body) {
  if (body && typeof body === 'object') {
    return {
      status,
      code: typeof body.error === 'string' ? body.error : 'UNKNOWN_ERROR',
      message: typeof body.message === 'string' ? body.message : 'Request failed',
      correlationId: typeof body.correlationId === 'string' ? body.correlationId : null,
    };
  }
  return { status, code: 'UNKNOWN_ERROR', message: 'Request failed', correlationId: null };
}

export class ApiError extends Error {
  constructor(parsed) {
    super(parsed.message);
    this.name = 'ApiError';
    this.status = parsed.status;
    this.code = parsed.code;
    this.correlationId = parsed.correlationId;
  }
}

export class ApiClient {
  #config;
  #token = null;
  #fetch;

  constructor(config, fetchImpl = fetch) {
    this.#config = { ...DEFAULT_CONFIG, ...config };
    this.#fetch = fetchImpl;
  }

  get loggedIn() {
    return this.#token !== null;
  }

  logout() {
    this.#token = null;
  }

  tokenUrl() {
    return `${this.#config.keycloakBaseUrl}/realms/${this.#config.keycloakRealm}/protocol/openid-connect/token`;
  }

  /** Password grant against the dev realm. Production uses code+PKCE; see ADR-0013. */
  async login(username, password) {
    const params = new URLSearchParams({
      grant_type: 'password',
      client_id: this.#config.keycloakClientId,
      username,
      password,
    });
    const response = await this.#fetch(this.tokenUrl(), {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: params.toString(),
    });
    if (!response.ok) {
      throw new ApiError(parseError(response.status, await safeJson(response)));
    }
    const data = await response.json();
    if (!data || typeof data.access_token !== 'string') {
      throw new ApiError({ status: response.status, code: 'LOGIN_FAILED', message: 'No token issued', correlationId: null });
    }
    this.#token = data.access_token;
    return data.access_token;
  }

  async request(method, path, { body, idempotencyKey } = {}) {
    if (!this.#token) throw new Error('Not logged in');
    const headers = {
      Authorization: `Bearer ${this.#token}`,
      'X-Correlation-Id': newCorrelationId(),
    };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
    const response = await this.#fetch(`${this.#config.gatewayBaseUrl}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const data = await safeJson(response);
    if (!response.ok) throw new ApiError(parseError(response.status, data));
    return data;
  }

  get(path) {
    return this.request('GET', path);
  }

  post(path, body, idempotencyKey) {
    return this.request('POST', path, { body, idempotencyKey });
  }

  // -- customer surface ---------------------------------------------------------------

  registerCustomer(profile) {
    return this.post('/api/v1/customers', profile, newIdempotencyKey());
  }

  myProfile() {
    return this.get('/api/v1/customers/me');
  }

  submitKyc(customerId, kyc) {
    return this.post(`/api/v1/customers/${customerId}/kyc`, kyc, newIdempotencyKey());
  }

  balance(currency = 'GBP') {
    return this.get(`/api/v1/accounts/balance?currency=${encodeURIComponent(currency)}`);
  }

  fund(amount, currency = 'GBP', key = newIdempotencyKey()) {
    return this.post(
      `/api/v1/accounts/fund?amount=${encodeURIComponent(amount)}&currency=${encodeURIComponent(currency)}`,
      undefined,
      key,
    );
  }

  pay({ amount, currency, cardToken, payeeName, payeeReference }, key = newIdempotencyKey()) {
    return this.post('/api/v1/transactions', { amount, currency, cardToken, payeeName, payeeReference }, key);
  }

  settle(transactionId) {
    return this.post(`/api/v1/transactions/${transactionId}/settle`, undefined);
  }

  listTransactions(limit = 20) {
    return this.get(`/api/v1/transactions?limit=${encodeURIComponent(String(limit))}`);
  }

  issueCard(customerId, brand = 'DEBIT') {
    return this.post('/api/v1/cards', { customerId, brand }, newIdempotencyKey());
  }

  listCards() {
    return this.get('/api/v1/cards');
  }

  cardAction(cardId, action) {
    return this.post(`/api/v1/cards/${cardId}/${action}`, undefined);
  }

  openDispute(transactionId, reason, description) {
    return this.post('/api/v1/disputes', { transactionId, reason, description }, newIdempotencyKey());
  }

  listDisputes() {
    return this.get('/api/v1/disputes');
  }
}

async function safeJson(response) {
  try {
    return await response.json();
  } catch {
    return null;
  }
}
