import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiClient, type RegistrationRequest } from './client';

const request: RegistrationRequest = {
  fullName: 'Ada Lovelace',
  dateOfBirth: '1815-12-10',
  nationality: 'GB',
  email: 'ada@example.test',
  password: 'correct-horse-battery-staple',
  address: {
    line1: '1 Analytical Engine Way',
    city: 'London',
    postalCode: 'EC1A 1AA',
    country: 'GB',
  },
};

describe('ApiClient.register', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('posts the backend registration contract without authentication', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ customerId: 'customer-id', message: 'Registration successful' }), {
        status: 201,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    const client = new ApiClient({ gatewayBaseUrl: 'http://gateway.test' });

    await expect(client.register(request)).resolves.toEqual({
      customerId: 'customer-id',
      message: 'Registration successful',
    });
    expect(fetchMock).toHaveBeenCalledWith('http://gateway.test/register', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Correlation-Id': expect.any(String),
      },
      body: JSON.stringify(request),
    });
  });

  it('exposes the parsed backend error without internal exception details', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({
        error: { code: 'EMAIL_ALREADY_REGISTERED' },
        message: 'An account already exists for that email address.',
        correlationId: 'correlation-id',
      }), { status: 409 }),
    );
    const client = new ApiClient({ gatewayBaseUrl: 'http://gateway.test' });

    await expect(client.register(request)).rejects.toMatchObject({
      status: 409,
      code: 'EMAIL_ALREADY_REGISTERED',
      message: 'An account already exists for that email address.',
      correlationId: 'correlation-id',
    });
  });
});
