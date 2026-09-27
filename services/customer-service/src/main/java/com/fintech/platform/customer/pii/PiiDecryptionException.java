package com.fintech.platform.customer.pii;

/**
 * Thrown when a stored value cannot be read back.
 *
 * <p>Always the same type, whatever went wrong, and never carrying the value that failed. A decryption
 * failure is either a wrong key, a rotated key, a tampered row or a corrupted row, and the caller can
 * do the same thing about all four: stop, and ask a human. Distinguishing them in the type would
 * mostly tell an attacker which one they achieved, and the message is where a diagnostic belongs rather
 * than than in a class hierarchy a caller has to learn.
 */
public class PiiDecryptionException extends RuntimeException {

    public PiiDecryptionException(String message) {
        super(message);
    }

    public PiiDecryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
