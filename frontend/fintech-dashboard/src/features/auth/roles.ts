/** Realm roles from Keycloak `realm_access.roles` — the UI gating vocabulary.
 * Backend authorization remains authoritative; these only decide what to show. */

export const ROLES = [
  'CUSTOMER',
  'SUPPORT_AGENT',
  'AUDITOR',
  'COMPLIANCE_OFFICER',
  'FRAUD_ANALYST',
  'SETTLEMENT_OPERATOR',
  'PLATFORM_ADMIN',
] as const;

export type Role = (typeof ROLES)[number];

export function isRole(value: unknown): value is Role {
  return typeof value === 'string' && (ROLES as readonly string[]).includes(value);
}

/** Decode a JWT payload without verifying — verification happens at the gateway.
 * Used only to read display claims (roles, username) for UI gating. */
export function decodeJwtPayload(token: string): Record<string, unknown> {
  const parts = token.split('.');
  if (parts.length < 2 || !parts[1]) throw new Error('Malformed token');
  const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
  return JSON.parse(atob(padded)) as Record<string, unknown>;
}

export function rolesFromPayload(payload: Record<string, unknown>): Role[] {
  const access = payload['realm_access'];
  if (access === null || typeof access !== 'object') return [];
  const raw = (access as Record<string, unknown>)['roles'];
  if (!Array.isArray(raw)) return [];
  return raw.filter(isRole);
}

export function usernameFromPayload(payload: Record<string, unknown>): string {
  for (const key of ['preferred_username', 'email', 'sub']) {
    const value = payload[key];
    if (typeof value === 'string' && value.length > 0) return value;
  }
  return 'signed in';
}
