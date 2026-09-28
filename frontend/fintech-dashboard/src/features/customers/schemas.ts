import { z } from 'zod';

/** Client mirror of UpdateProfileRequest. Backend validation is authoritative. */

const countryCode = z
  .string()
  .length(2, 'Use a 2-letter country code.')
  .regex(/^[A-Za-z]{2}$/, 'Use a 2-letter country code.')
  .transform((value) => value.toUpperCase());

export const updateProfileSchema = z.object({
  fullName: z.string().min(1, 'Enter your full name.').max(200, 'Name is too long.'),
  dateOfBirth: z
    .string()
    .min(1, 'Enter your date of birth.')
    .refine((value) => {
      const date = new Date(`${value}T00:00:00Z`);
      return !Number.isNaN(date.getTime()) && date < new Date();
    }, 'Enter a valid past date.'),
  nationality: countryCode,
  email: z.string().min(1, 'Enter your email.').email('Enter a valid email.').max(320),
  phone: z.string().max(32, 'Phone number is too long.'),
  address: z.object({
    line1: z.string().min(1, 'Enter the first address line.').max(200),
    line2: z.string().max(200).optional(),
    city: z.string().min(1, 'Enter the city.').max(100),
    postalCode: z.string().min(1, 'Enter the postal code.').max(20),
    country: countryCode,
  }),
});

export type UpdateProfileValues = z.infer<typeof updateProfileSchema>;
