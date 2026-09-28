import { describe, expect, it } from 'vitest';
import { decodeJwtPayload, rolesFromPayload } from './roles';

function unsigned(payload: unknown): string {
  const encoded = btoa(JSON.stringify(payload))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
  return `header.${encoded}.signature`;
}

describe('role parsing (UI gating only)', () => {
  it('extracts realm roles from a token payload', () => {
    const token = unsigned({
      preferred_username: 'agent@fintech.test',
      realm_access: { roles: ['SUPPORT_AGENT', 'NOT_A_ROLE'] },
    });
    expect(rolesFromPayload(decodeJwtPayload(token))).toEqual(['SUPPORT_AGENT']);
  });

  it('returns no roles when the claim is absent', () => {
    const token = unsigned({ preferred_username: 'nobody' });
    expect(rolesFromPayload(decodeJwtPayload(token))).toEqual([]);
  });

  it('rejects malformed tokens', () => {
    expect(() => decodeJwtPayload('not-a-token')).toThrow();
  });
});
