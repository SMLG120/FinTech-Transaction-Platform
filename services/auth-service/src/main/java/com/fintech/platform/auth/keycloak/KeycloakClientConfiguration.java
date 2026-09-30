package com.fintech.platform.auth.keycloak;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(KeycloakAdminProperties.class)
class KeycloakClientConfiguration {}
