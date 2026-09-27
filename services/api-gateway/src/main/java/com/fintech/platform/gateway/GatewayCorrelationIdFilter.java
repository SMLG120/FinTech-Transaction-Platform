package com.fintech.platform.gateway;

import com.fintech.platform.common.correlation.CorrelationId;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Establishes the correlation id at the edge, where the request first becomes one log line rather than
 * several.
 *
 * <p>Every service behind the gateway inherits this id, which is what makes a customer's report
 * traceable to a single gateway line and a single downstream service line. Without it, a trace is a
 * guess based on timestamps.
 *
 * <p>An inbound id is only honoured when it is well formed. Echoing an arbitrary caller-supplied value
 * into logs is a log-injection vector, and an unbounded one turns a response header into a way to write
 * newlines into somebody's log aggregator.
 */
@Component
class GatewayCorrelationIdFilter implements WebFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String supplied = exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER);
        String correlationId = CorrelationId.isAcceptable(supplied) ? supplied : CorrelationId.generate();

        exchange.getResponse().getHeaders().set(CorrelationId.HEADER, correlationId);
        exchange.getAttributes().put(CorrelationId.REQUEST_ATTRIBUTE, correlationId);

        return chain.filter(exchange);
    }

    /**
     * Ahead of authentication, so a rejected request is still traceable.
     *
     * <p>A {@code WebFilter} rather than a {@code GlobalFilter} on purpose: global filters are invoked
     * per matched route, so the requests that most need a correlation id — the ones rejected before a
     * route is ever chosen — would be exactly the ones that never got one.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
