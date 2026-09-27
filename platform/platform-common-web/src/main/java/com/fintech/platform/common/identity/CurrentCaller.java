package com.fintech.platform.common.identity;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The authenticated caller's identity, for use inside a service.
 *
 * <p>Reading the identity from the request rather than from a field on a base controller is what makes
 * the gateway the only place authorisation happens. A method that asks {@link #require()} is asking
 * "what did the gateway establish for this call", and it is the same question for a controller, a
 * message listener and a scheduled job that happens to have a request.
 *
 * <p>When there is no verified identity, {@link #require()} throws rather than returning a synthetic
 * one. A caller that is not authenticated and a caller who is an anonymous placeholder are not the
 * same thing, and collapsing them is how a background job ends up executing as a made-up user.
 */
@Component
public class CurrentCaller {

    /**
     * @return the verified identity
     * @throws IllegalStateException if called outside a request that {@link InternalIdentityFilter} accepted
     */
    public InternalIdentity require() {
        InternalIdentity identity = find().orElseThrow(
                        () -> new IllegalStateException("no verified internal identity on the current request"));
        return identity;
    }

    /** @return the verified identity, or empty when the call is not on a verified request */
    public java.util.Optional<InternalIdentity> find() {
        ServletRequestAttributes attributes = currentRequestAttributes();
        if (attributes == null) {
            return java.util.Optional.empty();
        }
        Object identity = attributes.getRequest().getAttribute(InternalIdentityFilter.REQUEST_ATTRIBUTE);
        return identity instanceof InternalIdentity
                ? java.util.Optional.of((InternalIdentity) identity)
                : java.util.Optional.empty();
    }

    private static ServletRequestAttributes currentRequestAttributes() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servlet ? servlet : null;
    }
}
