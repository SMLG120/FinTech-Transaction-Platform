package com.fintech.platform.auth.customer;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.customer-service")
public record CustomerProvisioningProperties(String baseUrl, long timeoutMs) {
    public CustomerProvisioningProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("platform.customer-service.base-url must be configured");
        }
    }

}
