package com.alighazi.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

class CorrelationIdGlobalFilterTest {

    private final CorrelationIdGlobalFilter filter = new CorrelationIdGlobalFilter();

    @Test
    void generatesCorrelationIdWhenHeaderIsMissing() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders").build());
        AtomicReference<String> downstreamId = new AtomicReference<>();
        GatewayFilterChain chain = next -> {
            downstreamId.set(next.getRequest().getHeaders()
                    .getFirst(CorrelationIdGlobalFilter.HEADER_NAME));
            return next.getResponse().setComplete();
        };

        filter.filter(exchange, chain).block();

        assertThat(downstreamId.get()).isNotBlank();
        assertThat(exchange.getResponse().getHeaders()
                .getFirst(CorrelationIdGlobalFilter.HEADER_NAME)).isEqualTo(downstreamId.get());
    }

    @Test
    void preservesProvidedCorrelationId() {
        String expected = "request-123";
        var request = MockServerHttpRequest.get("/api/orders")
                .header(CorrelationIdGlobalFilter.HEADER_NAME, expected)
                .build();
        var exchange = MockServerWebExchange.from(request);
        AtomicReference<String> downstreamId = new AtomicReference<>();
        GatewayFilterChain chain = next -> {
            downstreamId.set(next.getRequest().getHeaders()
                    .getFirst(CorrelationIdGlobalFilter.HEADER_NAME));
            return next.getResponse().setComplete();
        };

        filter.filter(exchange, chain).block();

        assertThat(downstreamId.get()).isEqualTo(expected);
        assertThat(exchange.getResponse().getHeaders()
                .getFirst(CorrelationIdGlobalFilter.HEADER_NAME)).isEqualTo(expected);
    }
}