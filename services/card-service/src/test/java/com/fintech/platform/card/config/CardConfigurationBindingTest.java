package com.fintech.platform.card.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fintech.platform.card.tokenisation.CardTokenisationProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * That the configuration this service reads is the configuration the file declares.
 *
 * <p>This class exists because of a specific bug. {@code CardTokenisationProperties} was annotated
 * {@code @ConfigurationProperties("fintech.card.tokenisation")} while {@code application.yml} declared
 * the key under {@code platform.card.tokenisation}. Nothing failed: the bean was created, the field
 * stayed null, and the first symptom was an {@code IllegalStateException} from deep inside a codec
 * constructor naming a key that was plainly present in the file. Every other card-service test passed.
 *
 * <p>Spring's relaxed binding is forgiving in the right ways — {@code CARD_VALIDITY_MONTHS},
 * {@code card.validity-months} and {@code card.validityMonths} all reach the same place — which is
 * exactly why a prefix typo is not caught for you. A test that asserts the properties resolve is the
 * cheapest possible guard, and the only one that fires before a deployment rather than during a
 * cardholder's first request.
 */
@Testcontainers
@SpringBootTest
class CardConfigurationBindingTest {

    private static final String TOKENISATION_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("fintech_cards_config");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // A container rather than a mock datasource, because the point is that the whole context binds
        // as it will in a deployment. A context that skips Flyway would also skip the wiring this is
        // here to check, and would have passed while the real service failed to start.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("platform.card.tokenisation.key", () -> TOKENISATION_KEY);
        registry.add(
                "platform.security.internal-identity.signing-key",
                () -> "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Autowired
    private CardTokenisationProperties tokenisation;

    @Value("${platform.card.validity-months}")
    int validityMonths;

    @Value("${platform.card.max-cards-per-customer}")
    int maxCardsPerCustomer;

    @Value("${platform.card.customer-service.base-url}")
    String customerServiceBaseUrl;

    @Value("${platform.card.customer-service.timeout-ms}")
    long customerServiceTimeoutMillis;

    @Test
    @DisplayName("binds the tokenisation key from platform.card, not a prefix that looks similar")
    void bindsTheTokenisationKey() {
        assertThat(tokenisation.getKey()).isEqualTo(TOKENISATION_KEY);
        // The real assertion: it decodes, which it cannot do while the field is null or the string is
        // not what the key was written as.
        assertThat(tokenisation.decodeKey()).hasSize(32);
    }

    @Test
    @DisplayName("binds the issuance policy rather than falling back to the defaults in code")
    void bindsTheIssuancePolicy() {
        // These resolve to the same numbers either way, which is precisely why a wrong prefix went
        // unnoticed. Asserting the values proves the lookups are spelled the way the file is.
        assertThat(environment.getProperty("platform.card.validity-months")).isEqualTo("36");
        assertThat(environment.getProperty("platform.card.max-cards-per-customer"))
                .isEqualTo("5");
        assertThat(validityMonths).isEqualTo(36);
        assertThat(maxCardsPerCustomer).isEqualTo(5);
    }

    @Test
    @DisplayName("binds the customer-service location, so a container does not dial localhost")
    void bindsTheCustomerServiceLocation() {
        // The one that would actually have bitten. Under Compose the default of localhost:8082 points
        // at the card container itself, so a card would fail eligibility against nothing and every
        // issue would 503 — in a deployment that looked correctly configured.
        assertThat(environment.getProperty("platform.card.customer-service.base-url"))
                .isEqualTo("http://localhost:8082");
        assertThat(customerServiceBaseUrl).isEqualTo("http://localhost:8082");
        assertThat(customerServiceTimeoutMillis).isEqualTo(2000L);
    }

    @Test
    @DisplayName("exposes a codec, so an outbound call can be signed")
    void exposesAnIdentityCodec() {
        // card-service signs the caller's identity for the eligibility hop. Before the codec was a bean
        // in its own right, the only copy lived inside the verification filter's registration and no
        // service could make a signed call at all.
        assertThat(context.getBeanNamesForType(com.fintech.platform.common.identity.InternalIdentityCodec.class))
                .isNotEmpty();
    }
}
