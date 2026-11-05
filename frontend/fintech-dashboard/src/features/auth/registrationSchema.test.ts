import { describe, expect, it } from 'vitest';
import { registrationSchema } from './registrationSchema';

const validRegistration = {
  fullName: 'Ada Lovelace',
  dateOfBirth: '1815-12-10',
  nationality: 'GB',
  email: '  ADA@Example.Test  ',
  password: 'correct-horse-battery-staple',
  address: {
    line1: '1 Analytical Engine Way',
    city: 'London',
    postalCode: 'EC1A 1AA',
    country: 'GB',
  },
};

describe('registrationSchema', () => {
  it('normalizes a valid email before account creation', () => {
    expect(registrationSchema.parse(validRegistration).email).toBe('ada@example.test');
  });

  it('rejects malformed email addresses', () => {
    expect(registrationSchema.safeParse({ ...validRegistration, email: 'not-an-email' }).success).toBe(false);
  });
});
