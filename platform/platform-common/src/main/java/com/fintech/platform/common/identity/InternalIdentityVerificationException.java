package com.fintech.platform.common.identity;

/**
 * Signals that a set of internal identity headers cannot be trusted.
 *
 * <p>A caller handles this as an authentication failure and nothing more. The message names the
 * specific reason for the operator's benefit and is never returned to the client.
 */
public class InternalIdentityVerificationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InternalIdentityVerificationException(String message) {
        super(message);
    }

    public InternalIdentityVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
