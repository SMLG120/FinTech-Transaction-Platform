package com.fintech.platform.dispute.service;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dispute's own configuration, under {@code app.dispute}.
 *
 * <p>The transaction-service address lives here rather than bare under {@code platform.*} because
 * it is this service's dependency to declare: a shared name would let an operator point every
 * service at one transaction-service by changing one value, and the blast radius of that typo is
 * every payment on the platform.
 */
@ConfigurationProperties(prefix = "app.dispute")
public class DisputeProperties implements InitializingBean {

    private final Outbox outbox = new Outbox();

    @Override
    public void afterPropertiesSet() {
        outbox.validate();
    }

    /**
     * The relay that drains this service's outbox.
     *
     * <p>Gated separately from the other services' identically named switches on purpose. The relay
     * switch is deliberately one name across the platform because every relay does the same job —
     * but each service reads its own, so disabling publishing here stops dispute announcements and
     * nothing else.
     */
    public static class Outbox {

        private boolean relayEnabled = true;

        public void validate() {}

        public boolean isRelayEnabled() {
            return relayEnabled;
        }

        public void setRelayEnabled(boolean relayEnabled) {
            this.relayEnabled = relayEnabled;
        }
    }

    public Outbox getOutbox() {
        return outbox;
    }
}
