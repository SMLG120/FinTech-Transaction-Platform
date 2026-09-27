package com.fintech.platform.common.identity;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches only when an internal-identity signing key is present and not blank.
 *
 * <p>{@code @ConditionalOnProperty} asks whether the property exists, which is not quite the question
 * here. The shipped configuration resolves the key from the environment with an empty default, so that
 * a service missing its key still starts. A present-but-empty value satisfies the built-in condition
 * while leaving nothing to verify with, and registering a filter in that state would reject every
 * request rather than none.
 *
 * <p>Each half of that looks defensible alone and together they are a fail-open, so the split is made
 * explicit: absent or blank means no filter is registered and no endpoint believes a caller; present
 * but unusable means the codec rejects it and startup fails, reporting the misconfiguration once rather
 * than as a 401 on every request.
 *
 * <p>The property name comes from the prefix constant rather than a literal so that renaming the
 * property cannot leave this condition quietly matching against a name nothing sets.
 */
public class OnInternalIdentityKeyPresentCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        // getProperty resolves placeholders and relaxed names, so INTERNAL_IDENTITY_SIGNING_KEY in the
        // environment and signing-key in a yaml file arrive here identically.
        String key = context.getEnvironment().getProperty(InternalIdentityProperties.PROPERTY_PREFIX + ".signing-key");
        return key != null && !key.isBlank();
    }
}
