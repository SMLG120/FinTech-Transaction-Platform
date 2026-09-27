package com.fintech.platform.common.web;

import com.fintech.platform.common.identity.CurrentCaller;
import com.fintech.platform.common.identity.InternalIdentityCodec;
import com.fintech.platform.common.identity.InternalIdentityFilter;
import com.fintech.platform.common.identity.InternalIdentityProperties;
import com.fintech.platform.common.identity.OnInternalIdentityKeyPresentCondition;
import jakarta.servlet.Filter;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Conditional;

/**
 * Wires the platform's cross-cutting web behaviour into any servlet service that puts
 * {@code platform-common-web} on the classpath.
 *
 * <p>Both beans are conditional, so a service can replace either one locally (for example to run
 * with correlation-id propagation disabled in a load test) without forking the platform module.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(Filter.class)
@EnableConfigurationProperties(InternalIdentityProperties.class)
@ComponentScan(basePackageClasses = CurrentCaller.class)
public class WebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(CorrelationIdFilter.order());
        registration.setName("correlationIdFilter");
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    ApiExceptionHandler apiExceptionHandler() {
        return new ApiExceptionHandler();
    }

    /**
     * The codec that signs and verifies the platform's internal identity.
     *
     * <p>Exposed as a bean in its own right rather than being constructed inside the filter below,
     * because verifying an inbound identity is only half the job. A service that calls another service
     * on behalf of the current caller has to <em>sign</em> that caller again for the hop, and there is
     * no way to do that from the filter registration: it owns a private codec and returns it as an
     * opaque {@code FilterRegistrationBean}. Before this bean existed the platform's services could
     * receive a signed identity but could not produce one, which made any service-to-service call that
     * forwarded the caller impossible without re-implementing the canonical form by hand — and a
     * second implementation of a signed format is a second chance to sign something the verifier will
     * reject, or worse, accept.
     *
     * <p>Same condition as the filter, for the same reason: a service configured with no signing key
     * must not have a codec to sign with, because it could then be used to assert an identity the
     * service itself is not verifying.
     */
    @Bean
    @ConditionalOnMissingBean
    @Conditional(OnInternalIdentityKeyPresentCondition.class)
    InternalIdentityCodec internalIdentityCodec(InternalIdentityProperties properties) {
        return properties.toCodec(Clock.systemUTC());
    }

    /**
     * Enforcement of the signed identity headers, registered only when a signing key is configured.
     *
     * <p>The condition is on the key rather than on a flag, so there is no configuration in which a
     * service believes an identity it has not verified. A service with no key starts and serves
     * nothing that trusts a caller; a service with a key that cannot be used fails to start.
     */
    @Bean
    @ConditionalOnMissingBean
    @Conditional(OnInternalIdentityKeyPresentCondition.class)
    FilterRegistrationBean<InternalIdentityFilter> internalIdentityFilterRegistration(
            InternalIdentityCodec codec, InternalIdentityProperties properties) {

        FilterRegistrationBean<InternalIdentityFilter> registration =
                new FilterRegistrationBean<>(new InternalIdentityFilter(codec, properties));
        registration.addUrlPatterns("/*");
        registration.setOrder(InternalIdentityFilter.order());
        registration.setName("internalIdentityFilter");
        return registration;
    }
}
