-- customer-service: customers and their identity checks.
--
-- PII storage rules that the rest of the schema exists to support:
--
--   * Every PII value is AES-GCM ciphertext (TEXT). Plaintext never reaches this database.
--   * Equality lookups on a PII column use a paired "blind index": a keyed digest of the normalised
--     value. Encryption is randomised, so two encryptions of one email differ and a ciphertext column
--     cannot answer "have I seen this address before". The blind index can, and it never stores the
--     value it was derived from.
--   * subject is the Keycloak user id and the salt for that customer's key derivation. Erasure sets
--     it to NULL, which is what makes the retained ciphertext permanently unreadable, so the unique
--     constraint on it has to be partial: after several customers erase, there are several NULLs.
--   * subject_digest is a keyed digest of the subject, kept after erasure so a repeat erasure request
--     can be recognised as already-fulfilled rather than creating a second tombstone.

CREATE TABLE customers (
    id UUID PRIMARY KEY,

    -- NULL once erased. See the note above on the partial unique index.
    subject VARCHAR(255),

    -- Retained through erasure. 64 hex chars: a keyed SHA-256.
    -- VARCHAR rather than CHAR: bpchar pads with spaces and blank-pads on comparison, which is not
    -- something a value that gets equality-tested in a unique index needs.
    subject_digest VARCHAR(64) NOT NULL,

    -- Encrypted PII, paired with its blind index where the column is searchable.
    --
    -- Nullable, and deliberately so. These are NOT NULL for a live profile and NULL for an erased
    -- one, which a column-level NOT NULL cannot express: declaring it would make the erasure that the
    -- rest of this schema is built around impossible to perform. The two *_present checks below
    -- enforce both directions instead, so a row is never half-live and never half-erased.
    full_name_encrypted TEXT,
    full_name_bidx VARCHAR(64),

    date_of_birth_encrypted TEXT,

    email_encrypted TEXT,
    email_bidx VARCHAR(64),

    phone_encrypted TEXT,
    phone_bidx VARCHAR(64),

    address_encrypted TEXT,

    -- Identity-check state. The legal transitions live in KycStateMachine, not in the database.
    kyc_status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',

    -- Set when the profile is erased. Non-null means the row is a tombstone and the PII columns are NULL.
    erased_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    -- Optimistic locking. Two concurrent profile updates must not silently interleave.
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT customers_kyc_status_known CHECK (
        kyc_status IN ('NOT_STARTED', 'PENDING_REVIEW', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'EXPIRED')
    ),

    -- A live profile has a subject and all three mandatory PII values. Stated explicitly because
    -- making the columns nullable for the sake of erasure removes the guarantee for free otherwise.
    CONSTRAINT customers_live_has_identity CHECK (
        erased_at IS NOT NULL
        OR (
            subject IS NOT NULL
            AND full_name_encrypted IS NOT NULL
            AND full_name_bidx IS NOT NULL
            AND date_of_birth_encrypted IS NOT NULL
            AND email_encrypted IS NOT NULL
            AND email_bidx IS NOT NULL
        )
    ),

    -- An erasure is all-or-nothing. If erased_at is set then every PII column and the subject must be
    -- gone; a partial erasure would leave the row looking live with unreadable ciphertext in it, which
    -- is the worst of both outcomes.
    CONSTRAINT customers_erasure_is_complete CHECK (
        erased_at IS NULL
        OR (
            subject IS NULL
            AND full_name_encrypted IS NULL
            AND full_name_bidx IS NULL
            AND date_of_birth_encrypted IS NULL
            AND email_encrypted IS NULL
            AND email_bidx IS NULL
            AND phone_encrypted IS NULL
            AND phone_bidx IS NULL
            AND address_encrypted IS NULL
        )
    )
);

-- One live profile per subject. NULLs are excluded because erasure deliberately produces them, and a
-- plain UNIQUE(subject) would reject the second erased customer.
CREATE UNIQUE INDEX customers_subject_unique ON customers (subject) WHERE subject IS NOT NULL;

-- One row per subject across all time, including after erasure. This is what stops a customer whose
-- profile was erased from silently re-registering as a new profile while their financial history
-- remains in other services.
CREATE UNIQUE INDEX customers_subject_digest_unique ON customers (subject_digest);

-- Supports the duplicate-email check at registration without scanning every ciphertext.
CREATE INDEX customers_email_bidx_idx ON customers (email_bidx) WHERE email_bidx IS NOT NULL;
CREATE INDEX customers_phone_bidx_idx ON customers (phone_bidx) WHERE phone_bidx IS NOT NULL;

-- Supports the staff search list and "who is currently approved" queries.
CREATE INDEX customers_kyc_status_idx ON customers (kyc_status);
CREATE INDEX customers_erased_at_idx ON customers (erased_at) WHERE erased_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Identity checks
-- ---------------------------------------------------------------------------
-- One row per submission attempt. A customer who is rejected and resubmits gets a new row rather than
-- overwriting the old one: a regulator asking "what was decided on this date, and on what evidence"
-- needs the history, and so does a support agent handling a dispute.
CREATE TABLE kyc_checks (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES customers (id),

    -- The provider's own handle for the assessment. NULL while the check is still in flight, because no
    -- provider has seen it yet -- the assessment is what brings a reference back. A client-invented
    -- placeholder would be a handle that looks real and resolves to nothing, so the column stays empty
    -- and kyc_checks_reference_present_when_decided below stops it from staying empty for a decided row.
    provider_reference VARCHAR(128),

    -- The status this check concluded with, which is also the customer's status at the time.
    outcome VARCHAR(32) NOT NULL,

    -- Why it failed, when it did. Non-empty iff outcome is REJECTED.
    failure_reasons TEXT,

    -- Encrypted copy of the submitted document reference and printed name, so a dispute can be
    -- investigated later without keeping the plaintext.
    document_reference_encrypted TEXT,
    printed_name_encrypted TEXT,

    submitted_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ,

    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT kyc_checks_outcome_known CHECK (
        outcome IN ('NOT_STARTED', 'PENDING_REVIEW', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'EXPIRED')
    ),

    -- A rejection with no reason is not actionable for the applicant and not defensible for an
    -- auditor, so the database refuses it as well as the code.
    CONSTRAINT kyc_checks_rejection_has_reason CHECK (outcome <> 'REJECTED' OR failure_reasons IS NOT NULL)
);

-- The customer's current check. Partial, so resubmission adds a row and retargets this index rather
-- than overwriting history.
CREATE UNIQUE INDEX kyc_checks_current_per_customer ON kyc_checks (customer_id) WHERE decided_at IS NULL;
-- A decided check has to be traceable to the provider that decided it.
ALTER TABLE kyc_checks ADD CONSTRAINT kyc_checks_reference_present_when_decided
    CHECK (decided_at IS NULL OR provider_reference IS NOT NULL);

-- The provider's handle, unique per customer and indexed so a lookup by it is not a table scan.
-- Partial, because in-flight rows have no reference yet and Postgres permits any number of NULLs
-- under a unique index, which is exactly what those rows need.
--
-- Scoped to the customer deliberately, and not unique platform-wide. The synthetic provider echoes
-- the submitted document reference back as its handle, so a global unique index would mean one
-- customer could permanently block another's identity check simply by submitting a document number
-- that the other customer also holds -- turning a uniqueness rule into a denial-of-service against
-- a named victim. Per-customer uniqueness is the invariant that actually matters: resolving one of a
-- customer's own checks by reference has to be unambiguous, or a manual decision could land on the
-- wrong row, which would be a compliance decision made by accident.
CREATE UNIQUE INDEX kyc_checks_provider_reference_uidx
    ON kyc_checks (customer_id, provider_reference) WHERE provider_reference IS NOT NULL;

CREATE INDEX kyc_checks_customer_submitted_idx ON kyc_checks (customer_id, submitted_at DESC);

-- updated_at is maintained by the application rather than by a trigger. A trigger would be invisible
-- at the call site, and the code that wrote a timestamp is the first thing an auditor asks for.

-- ---------------------------------------------------------------------------
-- Per-check results
-- ---------------------------------------------------------------------------
-- One row per check performed, rather than a JSON blob on kyc_checks. A blob would be simpler and
-- would stop two of the questions this data exists to answer from being asked at all: which check
-- rejects the most customers, and how many approvals rested on a check whose rule has since changed.
-- Both are ordinary SQL over rows, and neither is possible over an opaque string.
CREATE TABLE kyc_check_results (
    id UUID PRIMARY KEY,
    kyc_check_id UUID NOT NULL REFERENCES kyc_checks (id) ON DELETE CASCADE,
    check_name VARCHAR(64) NOT NULL,
    passed BOOLEAN NOT NULL,
    -- Non-null exactly when passed is false. A failure with no reason is not actionable for the
    -- applicant and not defensible for an auditor.
    reason TEXT,

    CONSTRAINT kyc_check_results_known CHECK (
        check_name IN (
            'NAME_MATCHES_DOCUMENT',
            'AGE_ELIGIBLE',
            'DOCUMENT_CURRENT',
            'JURISDICTION_SUPPORTED',
            'DOCUMENT_NOT_REPORTED_LOST'
        )
    ),
    -- Enforced here as well as in KycProvider.CheckResult: this is the column an auditor reads, and a
    -- failed check with a NULL reason must not be insertable at all, whoever is doing the inserting.
    CONSTRAINT kyc_check_results_failure_has_reason CHECK (passed OR reason IS NOT NULL)
);

CREATE INDEX kyc_check_results_by_check_idx ON kyc_check_results (check_name, passed);
CREATE INDEX kyc_check_results_by_check_id_idx ON kyc_check_results (kyc_check_id);
