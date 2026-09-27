package com.fintech.platform.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Guards the gateway's dependency on the transport-agnostic common module.
 *
 * <p>The gateway is the only service that does not depend on the servlet common module, so it is
 * also the only one where the shared metric-tag auto-configuration can go missing without any other
 * service noticing. The tags are what let a single Grafana query span the gateway and the services
 * behind it, so losing them here quietly splits the platform's metrics in two.
 */
// Keycloak and the ADR-0004 signing key became required configuration in Phase 2, so every context
// declares them. Supplied inline rather than from src/test/resources, because a test-scope
// application.yml shadows src/main/resources/application.yml entirely — which silently removed the
// management endpoints this very test asserts on.
@SpringBootTest(
        properties = {
            "platform.keycloak.issuer=http://localhost:8180/realms/fintech",
            "platform.keycloak.jwk-set-uri=http://localhost:8180/realms/fintech/protocol/openid-connect/certs",
            "platform.keycloak.audience=fintech-api",
            "platform.security.internal-identity.signing-key=b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801"
        })
@AutoConfigureObservability
class GatewayMetricTagTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("tags its metrics with the gateway's own application name")
    void appliesCommonTags() {
        var counter = meterRegistry.counter("test.metric.tag.probe");

        assertThat(counter.getId().getTag("application")).isEqualTo("api-gateway");
        assertThat(counter.getId().getTag("environment")).isNotBlank();
        assertThat(counter.getId().getTag("version")).isNotBlank();
    }
}
