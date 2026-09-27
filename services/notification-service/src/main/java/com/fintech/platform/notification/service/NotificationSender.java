package com.fintech.platform.notification.service;

import com.fintech.platform.notification.persistence.NotificationEntity;

/**
 * Delivers one notification.
 *
 * <p>An interface with a single simulated implementation, because the seam is the point. The day a
 * real provider is integrated, the retry and logging behaviour in {@link NotificationService} must not
 * change — only what "send" means. Tests substitute a stub that throws on command, which is the only
 * way to exercise the FAILED path without waiting for a provider to be down.
 */
public interface NotificationSender {

    /**
     * Sends one notification.
     *
     * @param notification the rendered message to deliver
     * @throws RuntimeException when the delivery fails and must be retried
     */
    void send(NotificationEntity notification);
}
