package com.fintech.platform.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Guarantees that every metric on every service carries the tags a cross-service query needs.
 *
 * <p>Without them, answering "which service is returning 5xx" or "which environment is the one
 * with the latency spike" means joining scrape config against metric names by hand. Tags are
 * applied through a {@link MeterFilter} rather than at each call site, which is what makes it
 * impossible to forget — and it also covers meters the framework registers after startup.
 *
 * <p>{@code application} is whatever the service configured under Spring Boot's standard {@code
 * management.metrics.tags.*}. {@code environment} and {@code version} are the two every team
 * forgets, so they are filled in from {@code app.environment} / {@code app.version}. An explicit
 * tag always wins: {@code putIfAbsent} means an operator can override any of the three in a
 * deployment without changing code.
 *
 * <p>The {@code before = MetricsAutoConfiguration.class} is insurance, not the cause of anything.
 * Spring Boot collects {@link MeterFilter} beans while the {@code MeterRegistry} is being
 * initialised, so a filter contributed after that point is never picked up. Today the ordering is
 * not what makes this work, but it costs nothing and forecloses a whole category of silent
 * regression, where metrics keep exporting and simply arrive untagged. That failure mode is worth
 * being explicit about: nothing errors, and the only visible symptom is an empty dashboard.
 */
@AutoConfiguration(before = MetricsAutoConfiguration.class)
@ConditionalOnClass({MeterRegistry.class, MetricsAutoConfiguration.class})
public class ObservabilityAutoConfiguration {

    private static final String METRIC_TAGS_PREFIX = "management.metrics.tags";

    @Bean
    @ConditionalOnMissingBean(name = "platformCommonTagsFilter")
    MeterFilter platformCommonTagsFilter(Environment environment) {
        Map<String, String> tags = new LinkedHashMap<>(configuredTags(environment));

        tags.putIfAbsent("application", environment.getProperty("spring.application.name", "unknown"));
        tags.putIfAbsent("environment", environment.getProperty("app.environment", "local"));
        tags.putIfAbsent("version", environment.getProperty("app.version", "unknown"));

        List<Tag> commonTags = tags.entrySet().stream()
                .map(entry -> Tag.of(entry.getKey(), entry.getValue()))
                .toList();

        return MeterFilter.commonTags(Tags.of(commonTags));
    }

    private Map<String, String> configuredTags(Environment environment) {
        return Binder.get(environment)
                .bind(METRIC_TAGS_PREFIX, Bindable.mapOf(String.class, String.class))
                .orElseGet(Map::of);
    }
}
