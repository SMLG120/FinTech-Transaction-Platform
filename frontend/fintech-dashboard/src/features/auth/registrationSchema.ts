import { z } from 'zod';

export const registrationSchema = z.object({
  fullName: z.string().trim().min(1, 'Enter your full name.').max(200),
  dateOfBirth: z.string().min(1, 'Enter your date of birth.').refine(
    (value) => value < new Date().toISOString().slice(0, 10),
    'Date of birth must be in the past.',
  ),
  nationality: z.string().regex(/^[A-Za-z]{2}$/, 'Use a 2-letter country code.'),
  email: z.string().trim().toLowerCase().email('Enter a valid email address.').max(320),
  password: z.string().min(12, 'Use at least 12 characters.').max(128),
  phone: z.string().max(32).optional(),
  address: z.object({
    line1: z.string().trim().min(1, 'Enter your address.').max(200),
    line2: z.string().max(200).optional(),
    city: z.string().trim().min(1, 'Enter your city.').max(100),
    postalCode: z.string().trim().min(1, 'Enter your postal code.').max(20),
    country: z.string().regex(/^[A-Za-z]{2}$/, 'Use a 2-letter country code.'),
  }),
});

export type RegistrationFormValues = z.infer<typeof registrationSchema>;
