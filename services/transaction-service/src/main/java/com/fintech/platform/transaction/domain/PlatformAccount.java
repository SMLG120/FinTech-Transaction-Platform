package com.fintech.platform.transaction.domain;

/**
 * The owner reference used for the platform's own accounts.
 *
 * <p>Platform accounts are not owned by anyone, but the {@code owner_ref} column is not nullable — a
 * nullable column in a unique constraint constrains nothing in Postgres, which would leave the
 * constraint that stops two rows claiming to be the same funding account doing nothing at all. So
 * platform accounts carry this value in the same column, and it can never collide with a customer
 * reference because customer references are digests of a length a collision with this literal is not
 * available for.
 */
public final class PlatformAccount {

    public static final String OWNER_REF = "platform";

    private PlatformAccount() {}
}
