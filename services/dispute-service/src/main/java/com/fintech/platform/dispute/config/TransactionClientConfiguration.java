package com.fintech.platform.dispute.config;

import com.fintech.platform.common.identity.InternalIdentityCodec;
import com.fintech.platform.dispute.service.TransactionLookup;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wiring for the one outbound call this service makes.
 *
 * <p>Not a shared client starter, because dispute-service makes exactly one call, to exactly one
 * service, for exactly one question. A general-purpose HTTP client module would be more machinery
 * than the one use, and would be one more place for a timeout to be forgotten.
 */
@Configuration
public class TransactionClientConfiguration {

    /**
     * The payment lookup, with timeouts it cannot run without.
     *
     * <p>Both timeouts are set, and the read timeout is the one that matters. A connect timeout only
     * covers a peer that is not listening; a peer that accepts the connection and then stalls — the
     * common failure when a downstream is overwhelmed rather than down — is only bounded by the read
     * timeout. Without it a stalled transaction-service holds dispute-service's request threads until
     * the pool is exhausted, turning one slow dependency into a fully unavailable service.
     *
     * <p>Two seconds is short on purpose. The caller is waiting to be told whether their case
     * exists; a longer budget would only make them wait longer for the same 503.
     */
    @Bean
    public TransactionLookup transactionLookup(
            RestClient.Builder builder,
            InternalIdentityCodec codec,
            Clock clock,
            @Value("${platform.dispute.transaction-service.base-url:http://localhost:8084}") String baseUrl,
            @Value("${platform.dispute.transaction-service.timeout-ms:2000}") long timeoutMillis) {

        Duration timeout = Duration.ofMillis(timeoutMillis);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());

        RestClient http = builder.baseUrl(baseUrl).requestFactory(factory).build();
        return new TransactionLookup(http, codec, timeout);
    }
}
