package com.fintech.platform.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fintech.platform.transaction.persistence.IdempotencyRepository;
import com.fintech.platform.transaction.persistence.JournalEntryRepository;
import com.fintech.platform.transaction.persistence.LedgerAccountRepository;
import com.fintech.platform.transaction.persistence.OutboxRepository;
import com.fintech.platform.transaction.persistence.TransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Proves the shared metric tags reach a real running service.
 *
 * <p>Deliberately a full context test rather than a unit test of the filter. The filter itself is
 * verified in {@code ObservabilityAutoConfigurationTest}; what can go wrong here is ordering -- if
 * the {@link MeterRegistry} is created before the {@code MeterFilter} bean is contributed, the
 * filter is registered too late and every cross-service dashboard query is silently unanswerable.
 * A unit test of the filter cannot detect that, and the symptom in production is an empty graph
 * rather than an error.
 */
@SpringBootTest(
        properties = {
            // This test is about metric tags, so the database is excluded outright rather than mocked.
            // A @SpringBootTest that starts a real DataSource is really a test of "can Flyway reach
            // postgres right now", which fails on any machine without a database and then retries for
            // minutes -- hiding the actual assertion behind an infrastructure error.
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",

            // The subject digest key is required with no default, deliberately: a service that starts
            // without one must not run on a key that came from a sample file. That rule applies to this
            // context too, so the test supplies one. It is a throwaway 32-byte value and never
            // digests anything -- nothing here calls the digester.
            "platform.security.subject-digest.key=eoejXxxDplx9GXusrUH7EqNKm6DTocES99CPSnfvGVI=",

            // No background poller. With the datasource excluded, the relay would log a connection
            // failure every 500ms for the life of the context, which is noise over a test whose only
            // subject is whether meters carry tags.
            "app.outbox.relay-enabled=false"
        })
@AutoConfigureObservability
class MetricTagPropagationTest {

    /**
     * The repositories, stubbed.
     *
     * <p>The DataSource and JPA are excluded above, so the repositories cannot be created, and the
     * services that Phase 5 added depend on them. Declaring them as mocks is the honest way to close
     * that gap: the test says outright that it has no database, rather than quietly depending on one
     * being reachable. Nothing here calls them, so a mock costs nothing and asserts nothing.
     *
     * <p>Listing them is a small tax that grows with the service, and worth paying. The alternative is
     * to let a real database in to satisfy a dependency this test does not exercise, which trades a
     * five-line list for a suite that fails on any machine without Postgres and then hides the actual
     * assertion behind a connection error.
     */
    @MockitoBean
    private IdempotencyRepository idempotencyRepository;

    @MockitoBean
    private JournalEntryRepository journalEntryRepository;

    @MockitoBean
    private LedgerAccountRepository ledgerAccountRepository;

    @MockitoBean
    private OutboxRepository outboxRepository;

    @MockitoBean
    private TransactionRepository transactionRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("every meter carries application, environment and version")
    void sharedTagsReachTheRegistry() {
        var counter = meterRegistry.counter("test.metric.tag.probe");

        assertThat(counter.getId().getTag("application"))
                .as("the service's own name, so a cross-service query can group by it")
                .isEqualTo("transaction-service");
        assertThat(counter.getId().getTag("environment"))
                .as("which deployment this is, so staging and production metrics cannot be mixed")
                .isNotBlank();
        assertThat(counter.getId().getTag("version"))
                .as("which build is affected, so a regression can be tied to a release")
                .isNotBlank();
    }

    @Test
    @DisplayName("a pre-existing JVM meter is tagged too, not just ones registered after startup")
    void frameworkMetersAreTagged() {
        // JVM meters are created early, during actuator auto-configuration. If the filter arrives after
        // that, every application meter looks fine while the JVM metrics -- the ones an outage is
        // actually diagnosed with -- carry no tags at all.
        var jvmMeter = meterRegistry.find("jvm.memory.used").meters().stream()
                .findFirst()
                .orElseThrow();

        assertThat(jvmMeter.getId().getTag("application")).isEqualTo("transaction-service");
        assertThat(jvmMeter.getId().getTag("environment")).isNotBlank();
    }
}
