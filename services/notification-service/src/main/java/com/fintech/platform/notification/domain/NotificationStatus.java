package com.fintech.platform.notification.domain;

/**
 * Where a notification stands in its own delivery.
 *
 * <p>Three states, and the missing fourth is the design: there is no {@code RETRYING}. A delivery that
 * failed is {@code FAILED} with a {@code nextAttemptAt} in the future, and the scheduler picks it up;
 * a separate state would let a row claim to be in flight while no thread holds it, which is how a
 * notification gets stuck "retrying" forever after a restart. The attempts counter and the next-attempt
 * timestamp say everything a fourth state would, and they survive a restart.
 */
public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED
}
