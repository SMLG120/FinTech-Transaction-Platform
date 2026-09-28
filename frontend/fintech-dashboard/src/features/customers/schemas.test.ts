import { describe, expect, it } from 'vitest';
import { updateProfileSchema } from './schemas';

const valid = {
  fullName: 'Dana Okonkwo',
  dateOfBirth: '1991-04-17',
  nationality: 'gb',
  email: 'dana@example.test',
  phone: '+447700900123',
  address: {
    line1: '12 Alder Way',
    city: 'Manchester',
    postalCode: 'M1 4BT',
    country: 'gb',
  },
};

describe('updateProfileSchema', () => {
  it('accepts a full profile and normalises country codes', () => {
    const parsed = updateProfileSchema.parse(valid);
    expect(parsed.nationality).toBe('GB');
    expect(parsed.address.country).toBe('GB');
  });

  it('rejects future birth dates and bad emails', () => {
    expect(updateProfileSchema.safeParse({ ...valid, dateOfBirth: '2991-01-01' }).success).toBe(
      false,
    );
    expect(updateProfileSchema.safeParse({ ...valid, email: 'not-an-email' }).success).toBe(
      false,
    );
  });
});
