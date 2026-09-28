import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { eligibilityResponseSchema, transactionViewSchema } from './contractSchemas';

/**
 * Frontend side of the wire contracts: the backend's canonical sample
 * responses must satisfy the shapes this dashboard parses. The fixtures live
 * once, in `contracts/`; this test reads them by path so a copy can never
 * drift. A backend field rename breaks here before it breaks a page.
 */
function fixture(name: string): unknown {
  // vite-node rewrites import.meta.url with an /@fs/ prefix; strip it back.
  const here = fileURLToPath(import.meta.url).replace(/^\/@fs/, '');
  const path = join(dirname(here), '..', '..', '..', '..', 'contracts', 'src', 'main', 'resources', 'contracts', name);
  return JSON.parse(readFileSync(path, 'utf-8')) as unknown;
}

describe('backend wire contracts', () => {
  it('serves an eligibility response the dashboard can parse', () => {
    const parsed = eligibilityResponseSchema.parse(fixture('eligibility-response.json'));

    expect(parsed.kycStatus).toBe('APPROVED');
    expect(parsed.masked).toBe(false);
  });

  it('serves a transaction view the dashboard can parse', () => {
    const parsed = transactionViewSchema.parse(fixture('transaction-view.json'));

    expect(parsed.status).toBe('SETTLED');
    expect(parsed.amount).toBe('25.00');
    expect(parsed.currency).toBe('GBP');
  });

  it('refuses payloads missing the relied-on fields', () => {
    // The test above is only a contract if it can fail: an empty object —
    // what a renamed-everything response looks like to the parser — is refused.
    expect(() => eligibilityResponseSchema.parse({})).toThrow();
    expect(() => transactionViewSchema.parse({})).toThrow();
  });
});
