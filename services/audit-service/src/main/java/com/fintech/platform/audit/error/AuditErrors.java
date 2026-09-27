package com.fintech.platform.audit.error;

import com.fintech.platform.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Error codes specific to audit-service.
 *
 * <p>Same {@link ErrorCode} contract and wire format as every other service, so a client handles a
 * platform error without knowing which service raised it.
 *
 * <p><b>No code mentions an actor, a subject or a digest.</b> An error message is the most likely
 * thing in a service to end up in a log, a support ticket or a monitoring label, and this service's
 * identifiers are pseudonyms precisely so they do not travel. The codes describe what the caller did
 * wrong and nothing about whose action was recorded.
 */
public final class AuditErrors {

    private AuditErrors() {}

    /** No audit record matches the id supplied. 404. */
    public static final ErrorCode NOT_FOUND =
            ErrorCode.of("AUDIT_RECORD_NOT_FOUND", HttpStatus.NOT_FOUND, "No audit record exists with that id");

    /**
     * The caller is neither an auditor, a compliance officer nor an administrator.
     *
     * <p>403 rather than 404, for the same reason as the equivalent code in fraud-service: an
     * authenticated caller who lacks the role is entitled to be told the endpoint exists and that
     * they may not use it. Answering 404 would make a permissions mistake look like a missing
     * record.
     */
    public static final ErrorCode FORBIDDEN = ErrorCode.of(
            "AUDIT_FORBIDDEN",
            HttpStatus.FORBIDDEN,
            "This endpoint is for auditors, compliance officers and platform administrators");
}
