package com.fintech.platform.notification.error;

import com.fintech.platform.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Error codes specific to notification-service.
 *
 * <p>Same {@link ErrorCode} contract and wire format as every other service, so a client handles a
 * platform error without knowing which service raised it.
 *
 * <p><b>No code mentions a recipient, a card or a digest.</b> An error message is the most likely
 * thing in a service to end up in a log, a support ticket or a monitoring label, and this service's
 * identifiers are pseudonyms precisely so they do not travel. The codes describe what the caller did
 * wrong and nothing about whose payment the notification is about.
 */
public final class NotificationErrors {

    private NotificationErrors() {}

    /** No notification matches the id supplied. 404. */
    public static final ErrorCode NOT_FOUND =
            ErrorCode.of("NOTIFICATION_NOT_FOUND", HttpStatus.NOT_FOUND, "No notification exists with that id");

    /**
     * The notification already went out, so retrying it would be the duplicate the event-id
     * uniqueness was supposed to prevent. 409.
     *
     * <p>404 would be wrong — the notification exists — and 200 with no action would be worse,
     * because a support agent who asked for a retry and got silence would ask again, and then the
     * one retry that finally lands looks like the cause of a message the customer received twice.
     */
    public static final ErrorCode ALREADY_SENT = ErrorCode.of(
            "NOTIFICATION_ALREADY_SENT",
            HttpStatus.CONFLICT,
            "That notification was already sent; a sent message is not retried");

    /**
     * The caller may read the delivery log and retry a failed send, or they may not. 403.
     *
     * <p>403 rather than 404, for the same reason as the equivalent code in fraud-service: an
     * authenticated caller who lacks the role is entitled to be told the endpoint exists and that
     * they may not use it. Answering 404 would make a permissions mistake look like a missing
     * notification.
     */
    public static final ErrorCode FORBIDDEN = ErrorCode.of(
            "NOTIFICATION_FORBIDDEN",
            HttpStatus.FORBIDDEN,
            "This endpoint is for support agents and platform administrators");
}
