/**
 * Centralised API client. The only place that touches `fetch` for gateway calls.
 * Mirrors web/app/api.js conventions: per-call X-Correlation-Id, per-submit
 * Idempotency-Key, token kept in memory (owned by the auth layer, never
 * localStorage), and parsed errors carrying the backend correlationId.
 */

export interface ApiConfig {
  gatewayBaseUrl: string;
}

export interface ParsedApiError {
  status: number;
  code: string;
  message: string;
  correlationId: string | null;
}

export class ApiError extends Error {
  status: number;
  code: string;
  correlationId: string | null;

  constructor(parsed: ParsedApiError) {
    super(parsed.message);
    this.name = 'ApiError';
    this.status = parsed.status;
    this.code = parsed.code;
    this.correlationId = parsed.correlationId;
  }
}

export function parseError(status: number, body: unknown): ParsedApiError {
  if (body !== null && typeof body === 'object') {
    const record = body as Record<string, unknown>;
    // Backend envelope is {error:{code,message}, correlationId,...} but be
    // liberal: never let an unexpected shape crash an error path.
    const nested = record['error'];
    const nestedCode =
      nested !== null && typeof nested === 'object'
        ? (nested as Record<string, unknown>)['code']
        : undefined;
    return {
      status,
      code:
        typeof record['error'] === 'string'
          ? (record['error'] as string)
          : typeof nestedCode === 'string'
            ? nestedCode
            : typeof record['code'] === 'string'
              ? (record['code'] as string)
              : 'UNKNOWN_ERROR',
      message:
        typeof record['message'] === 'string'
          ? (record['message'] as string)
          : 'Request failed',
      correlationId:
        typeof record['correlationId'] === 'string'
          ? (record['correlationId'] as string)
          : null,
    };
  }
  return { status, code: 'UNKNOWN_ERROR', message: 'Request failed', correlationId: null };
}

export function newId(): string {
  return crypto.randomUUID();
}

async function safeJson(response: Response): Promise<unknown> {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

export function resolveConfig(): ApiConfig {
  return {
    gatewayBaseUrl:
      (import.meta.env['VITE_GATEWAY_URL'] as string | undefined) ??
      'http://localhost:8080',
  };
}

export class ApiClient {
  private token: string | null = null;

  constructor(private readonly config: ApiConfig = resolveConfig()) {}

  get loggedIn(): boolean {
    return this.token !== null;
  }

  setToken(token: string | null): void {
    this.token = token;
  }

  logout(): void {
    this.token = null;
  }

  async request<T>(
    method: string,
    path: string,
    options: { body?: unknown; idempotencyKey?: string } = {},
  ): Promise<T> {
    if (!this.token) throw new Error('Not logged in');
    const headers: Record<string, string> = {
      Authorization: `Bearer ${this.token}`,
      'X-Correlation-Id': newId(),
    };
    if (options.body !== undefined) headers['Content-Type'] = 'application/json';
    if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey;
    const response = await fetch(`${this.config.gatewayBaseUrl}${path}`, {
      method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    });
    const data = await safeJson(response);
    if (!response.ok) throw new ApiError(parseError(response.status, data));
    return data as T;
  }

  get<T>(path: string): Promise<T> {
    return this.request<T>('GET', path);
  }

  post<T>(path: string, body?: unknown, idempotencyKey?: string): Promise<T> {
    return this.request<T>('POST', path, { body, idempotencyKey });
  }

  del<T>(path: string): Promise<T> {
    return this.request<T>('DELETE', path);
  }
}

export const apiClient = new ApiClient();
