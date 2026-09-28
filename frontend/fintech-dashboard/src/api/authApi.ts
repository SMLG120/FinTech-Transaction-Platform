/**
 * Token issuance against the local Keycloak realm (password grant).
 * Fenced to local development — production moves to Authorization Code with
 * PKCE (see ADR-0013). No secrets here: fintech-web is a public client.
 */

import { ApiError, parseError } from './client';

export interface KeycloakConfig {
  baseUrl: string;
  realm: string;
  clientId: string;
}

export function resolveKeycloakConfig(): KeycloakConfig {
  return {
    baseUrl: (import.meta.env['VITE_KEYCLOAK_URL'] as string | undefined) ?? 'http://localhost:8180',
    realm: (import.meta.env['VITE_KEYCLOAK_REALM'] as string | undefined) ?? 'fintech',
    clientId: (import.meta.env['VITE_KEYCLOAK_CLIENT_ID'] as string | undefined) ?? 'fintech-web',
  };
}

export async function passwordLogin(
  username: string,
  password: string,
  config: KeycloakConfig = resolveKeycloakConfig(),
): Promise<string> {
  const url = `${config.baseUrl}/realms/${config.realm}/protocol/openid-connect/token`;
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'password',
      client_id: config.clientId,
      username,
      password,
    }).toString(),
  });
  let data: unknown = null;
  try {
    data = await response.json();
  } catch {
    data = null;
  }
  if (!response.ok) throw new ApiError(parseError(response.status, data));
  const token =
    data !== null && typeof data === 'object'
      ? (data as Record<string, unknown>)['access_token']
      : undefined;
  if (typeof token !== 'string' || token.length === 0) {
    throw new ApiError({
      status: response.status,
      code: 'LOGIN_FAILED',
      message: 'No token was issued. Check the username and password.',
      correlationId: null,
    });
  }
  return token;
}
