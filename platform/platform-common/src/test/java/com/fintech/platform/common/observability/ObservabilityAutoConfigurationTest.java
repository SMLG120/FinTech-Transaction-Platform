package com.fintech.platform.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/**
 * Proves the common-tag filter actually tags meters.
 *
 * <p>This exists because the filter is the mechanism every cross-service dashboard query groups by,
 * and it fails silently: a filter that never applies leaves metrics perfectly valid and perfectly
 * unqueryable by environment, which looks like an empty dashboard rather than like a bug. The
 * behaviour is asserted here against a real registry instead of being assumed from the
 * {@code MeterFilter} API.
 */
class ObservabilityAutoConfigurationTest {

    private static MeterRegistry registryWithFilter() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "transaction-service");
        environment.setProperty("app.environment", "local");
        environment.setProperty("app.version", "0.1.0-SNAPSHOT");

        MeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new ObservabilityAutoConfiguration().platformCommonTagsFilter(environment));
        return registry;
    }

    @Test
    @DisplayName("a meter registered after the filter carries application, environment and version")
    void tagsEveryMeter() {
        Counter counter = registryWithFilter().counter("payments.authorised");

        assertThat(counter.getId().getTags())
                .extracting(Tag::getKey, Tag::getValue)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("application", "transaction-service"),
                        org.assertj.core.groups.Tuple.tuple("environment", "local"),
                        org.assertj.core.groups.Tuple.tuple("version", "0.1.0-SNAPSHOT"));
    }

    @Test
    @DisplayName("an explicitly configured tag wins, so an operator can override it per environment")
    void explicitConfigurationWins() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "transaction-service");
        environment.setProperty("app.environment", "local");
        // Set by management.metrics.tags.* in application.yml. This is the escape hatch that lets a
        // deployment relabel a service without a code change.
        environment.setProperty("management.metrics.tags.environment", "staging");

        MeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new ObservabilityAutoConfiguration().platformCommonTagsFilter(environment));

        assertThat(registry.counter("payments.declined").getId().getTag("environment"))
                .isEqualTo("staging");
    }

    @Test
    @DisplayName("is picked up as an auto-configuration, not just usable when called by hand")
    void isAppliedAsAnAutoConfiguration() {
        // The two tests above invoke the factory method directly, so they pass even if Spring never
        // registers the bean. This one goes through the real import mechanism: a class listed in
        // AutoConfiguration.imports can fail to apply for reasons that have nothing to do with its own
        // logic, and nothing else in the suite would notice.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MeterFilter.class);
                });
    }
}
