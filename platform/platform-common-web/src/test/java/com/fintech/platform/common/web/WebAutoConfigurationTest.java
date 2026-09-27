package com.fintech.platform.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fintech.platform.common.identity.InternalIdentityFilter;
import com.fintech.platform.common.identity.InternalIdentityProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * These tests exist because of a defect that unit tests could not see.
 *
 * <p>The filter used to be gated on an {@code enabled} boolean that the shipped configuration never
 * set. A boolean nobody sets is {@code false}, so every service came up with a correct signing key and
 * verification quietly switched off. The unit tests constructed the properties object directly and
 * passed {@code true}, so they agreed with each other and disagreed with production. Only binding real
 * property names into the real auto-configuration exercises the path that was broken, so every case
 * here starts from a property source rather than a constructed object.
 */
class WebAutoConfigurationTest {

    private static final String SIGNING_KEY = "0".repeat(64);

    private final WebApplicationContextRunner runner =
            new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(WebAutoConfiguration.class));

    @SuppressWarnings("unchecked")
    private static InternalIdentityFilter filterOf(AssertableWebApplicationContext context) {
        FilterRegistrationBean<InternalIdentityFilter> registration =
                (FilterRegistrationBean<InternalIdentityFilter>) context.getBean("internalIdentityFilterRegistration");
        return registration.getFilter();
    }

    /** Dispatches an unsigned request through the filter and reports the outcome. */
    private static RequestOutcome call(AssertableWebApplicationContext context, String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filterOf(context).doFilter(new MockHttpServletRequest("GET", path), response, chain);
        return new RequestOutcome(response.getStatus(), chain.getRequest() != null);
    }

    private record RequestOutcome(int status, boolean reachedApplication) {}

    @Test
    @DisplayName("a signing key alone is enough to switch enforcement on")
    void signing_key_alone_enables_enforcement() throws Exception {
        // This is the shipped configuration: a key and a freshness bound, and nothing else. It must
        // enforce, because there is no longer any flag that could say otherwise.
        runner.withPropertyValues("platform.security.internal-identity.signing-key=" + SIGNING_KEY)
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    RequestOutcome outcome = call(context, "/api/accounts");

                    assertThat(outcome.status()).isEqualTo(401);
                    assertThat(outcome.reachedApplication())
                            .as("request must not reach application code")
                            .isFalse();
                });
    }

    @Test
    @DisplayName("the properties bind to the documented defaults when only a key is set")
    void properties_bind_with_defaults() {
        runner.withPropertyValues("platform.security.internal-identity.signing-key=" + SIGNING_KEY)
                .run(context -> {
                    InternalIdentityProperties properties = context.getBean(InternalIdentityProperties.class);

                    assertThat(properties.signingKey()).isEqualTo(SIGNING_KEY);
                    assertThat(properties.maxAgeSeconds()).isEqualTo(60);
                    assertThat(properties.excludedPaths()).isEqualTo(InternalIdentityProperties.DEFAULT_EXCLUDED_PATHS);
                });
    }

    @Test
    @DisplayName("an explicit freshness bound overrides the default")
    void freshness_bound_is_configurable() {
        runner.withPropertyValues(
                        "platform.security.internal-identity.signing-key=" + SIGNING_KEY,
                        "platform.security.internal-identity.max-age-seconds=5")
                .run(context -> assertThat(context.getBean(InternalIdentityProperties.class)
                                .maxAgeSeconds())
                        .isEqualTo(5));
    }

    @Test
    @DisplayName("without a signing key the filter is absent, so nothing trusts the headers")
    void no_signing_key_means_no_filter() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("internalIdentityFilterRegistration");
        });
    }

    @Test
    @DisplayName("a blank signing key starts the service with no filter, not with a filter that trusts")
    void blank_signing_key_means_no_filter() {
        // The shipped configuration resolves the key with an empty default. A present-but-empty value
        // satisfies @ConditionalOnProperty, so without the non-blank check the filter would register
        // with nothing to verify against and reject every request instead of none.
        runner.withPropertyValues("platform.security.internal-identity.signing-key=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("internalIdentityFilterRegistration");
                });
    }

    @Test
    @DisplayName("a malformed key stops the service starting rather than disabling enforcement")
    void malformed_key_fails_startup() {
        // Half the required length. Registering nothing here would be the worst outcome: the service
        // would look healthy while accepting unverified identity claims, so it must refuse to start.
        runner.withPropertyValues("platform.security.internal-identity.signing-key=00")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("scrape and health paths stay reachable so monitoring and probes keep working")
    void operational_paths_are_reachable_without_an_identity() throws Exception {
        runner.withPropertyValues("platform.security.internal-identity.signing-key=" + SIGNING_KEY)
                .run(context -> {
                    for (String path : List.of("/actuator/health", "/actuator/prometheus", "/actuator/info")) {
                        RequestOutcome outcome = call(context, path);

                        assertThat(outcome.status()).as(path).isNotEqualTo(401);
                        assertThat(outcome.reachedApplication()).as(path).isTrue();
                    }
                });
    }

    @Test
    @DisplayName("the remaining actuator endpoints require a verified identity")
    void sensitive_actuator_paths_are_protected() throws Exception {
        runner.withPropertyValues("platform.security.internal-identity.signing-key=" + SIGNING_KEY)
                .run(context -> {
                    for (String path :
                            List.of("/actuator/env", "/actuator/heapdump", "/actuator/loggers", "/actuator/metrics")) {
                        RequestOutcome outcome = call(context, path);

                        assertThat(outcome.status()).as(path).isEqualTo(401);
                        assertThat(outcome.reachedApplication()).as(path).isFalse();
                    }
                });
    }
}
