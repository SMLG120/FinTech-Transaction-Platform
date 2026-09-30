package com.fintech.platform.auth.customer;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CustomerProvisioningProperties.class)
class CustomerProvisioningConfiguration {}
