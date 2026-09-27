package com.fintech.platform.customer.error;

import com.fintech.platform.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Error codes specific to customer-service.
 *
 * <p>These extend the same {@link ErrorCode} contract as {@code CommonErrorCodes} and use the same
 * wire format, so a client handles a platform error without knowing which service raised it.
 *
 * <p>The codes are deliberately fine-grained. A caller needs to distinguish "you are not the owner of
 * this profile" from "no such customer" from "this customer has been erased", because the three
 * demand different responses: a 403, a 404, and a 410 respectively. Collapsing them into one
 * {@code NOT_FOUND} would hide the erasure case, which is the one a customer exercising a deletion
 * right most needs an unambiguous answer about.
 */
public final class CustomerErrorCodes {

    private CustomerErrorCodes() {}

    public static final ErrorCode CUSTOMER_NOT_FOUND =
            ErrorCode.of("CUSTOMER_NOT_FOUND", HttpStatus.NOT_FOUND, "No customer profile exists for this subject");

    /**
     * The caller is the erased customer, asking about their own former profile.
     *
     * <p>410 rather than 404 because the caller already knows a profile existed, and "gone" is the
     * answer they need: someone who requested deletion under a retention policy has to be able to
     * confirm it took effect without phoning support. Hiding it behind 404 would be theatre that makes
     * the one case where a definite answer matters the only case that cannot give one.
     */
    public static final ErrorCode CUSTOMER_NOT_ERASED = ErrorCode.of(
            "CUSTOMER_NOT_ERASED",
            HttpStatus.GONE,
            "This customer profile has been erased and its personal data is no longer held");

    public static final ErrorCode CUSTOMER_ALREADY_REGISTERED = ErrorCode.of(
            "CUSTOMER_ALREADY_REGISTERED", HttpStatus.CONFLICT, "A customer profile already exists for this identity");

    public static final ErrorCode CUSTOMER_ALREADY_ERASED = ErrorCode.of(
            "CUSTOMER_ALREADY_ERASED", HttpStatus.CONFLICT, "This customer profile has already been erased");

    public static final ErrorCode NOT_THE_OWNER =
            ErrorCode.of("NOT_THE_OWNER", HttpStatus.FORBIDDEN, "This profile belongs to another customer");

    public static final ErrorCode KYC_INVALID_TRANSITION = ErrorCode.of(
            "KYC_INVALID_TRANSITION",
            HttpStatus.CONFLICT,
            "The requested identity-check step is not allowed from the current state");

    public static final ErrorCode KYC_ALREADY_APPROVED =
            ErrorCode.of("KYC_ALREADY_APPROVED", HttpStatus.CONFLICT, "This customer is already approved");

    public static final ErrorCode KYC_NOT_APPROVED =
            ErrorCode.of("KYC_NOT_APPROVED", HttpStatus.CONFLICT, "This customer is not currently approved");

    public static final ErrorCode KYC_CHECK_NOT_FOUND =
            ErrorCode.of("KYC_CHECK_NOT_FOUND", HttpStatus.NOT_FOUND, "No identity check exists with that reference");

    public static final ErrorCode KYC_PROVIDER_UNAVAILABLE = ErrorCode.of(
            "KYC_PROVIDER_UNAVAILABLE",
            HttpStatus.SERVICE_UNAVAILABLE,
            "The identity-check provider is temporarily unavailable");

    public static final ErrorCode PII_NOT_DECRYPTABLE = ErrorCode.of(
            "PII_NOT_DECRYPTABLE",
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Stored personal data could not be read; contact support");
}
