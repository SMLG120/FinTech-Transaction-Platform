-- Phase 10 fix: the status column was created one character too narrow.
--
-- RESOLVED_REFUNDED and RESOLVED_REJECTED are both 17 characters; V1 declared VARCHAR(16), so the
-- first resolution ever attempted failed at commit with "value too long". The live stack proved it
-- before any test could: Hibernate validates the mapping, not the length, and no unit test writes
-- a row. Widened rather than shortened, because the status names are the API contract and the
-- column follows them rather than the other way round.

ALTER TABLE disputes ALTER COLUMN status TYPE VARCHAR(32);
