package com.fintech.platform.customer.kyc;

/** The individual identity checks a {@link KycProvider} can perform. */
public enum Check {
    /** The name on the document matches the claimed name. */
    NAME_MATCHES_DOCUMENT,

    /** The date of birth is old enough to be an adult. */
    AGE_ELIGIBLE,

    /** The document is not expired. */
    DOCUMENT_CURRENT,

    /** The address is in a supported jurisdiction. */
    JURISDICTION_SUPPORTED,

    /** The document is not on a known list of lost or stolen references. */
    DOCUMENT_NOT_REPORTED_LOST
}
