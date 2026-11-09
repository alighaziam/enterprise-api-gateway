package com.alighazi.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

class CorrelationIdGlobalFilterTest {

    private final CorrelationIdGlobalFilter filter = new CorrelationIdGlobalFilter();

    @Test
    void generatesCorrelationIdWhenHeaderIsMissing() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders").build());
        AtomicReference<String> downstreamId = new AtomicReference<>();
        WebFilterChain chain = next -> {
            downstreamId.set(next.getRequest().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER_NAME));
            return next.getResponse().setComplete();
        };

        filter.filter(exchange, chain).block();

        assertThat(downstreamId.get()).matches("[0-9a-f-]{36}");
        assertThat(exchange.getResponse().getHeaders()
                .getFirst(CorrelationIdGlobalFilter.HEADER_NAME)).isEqualTo(downstreamId.get());
    }

    @Test
    void preservesSafeProvidedCorrelationId() {
        String expected = "request-123.trace_01";
        var request = MockServerHttpRequest.get("/api/orders")
                .header(CorrelationIdGlobalFilter.HEADER_NAME, expected).build();
        var exchange = MockServerWebExchange.from(request);
        AtomicReference<String> downstreamId = new AtomicReference<>();
        WebFilterChain chain = next -> {
            downstreamId.set(next.getRequest().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER_NAME));
            return next.getResponse().setComplete();
        };

        filter.filter(exchange, chain).block();

        assertThat(downstreamId.get()).isEqualTo(expected);
        assertThat(exchange.getResponse().getHeaders()
                .getFirst(CorrelationIdGlobalFilter.HEADER_NAME)).isEqualTo(expected);
    }

    @Test
    void replacesUnsafeCorrelationId() {
        var request = MockServerHttpRequest.get("/api/orders")
                .header(CorrelationIdGlobalFilter.HEADER_NAME, "bad id with spaces").build();
        var exchange = MockServerWebExchange.from(request);
        AtomicReference<String> downstreamId = new AtomicReference<>();
        WebFilterChain chain = next -> {
            downstreamId.set(next.getRequest().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER_NAME));
            return next.getResponse().setComplete();
        };

        filter.filter(exchange, chain).block();

        assertThat(downstreamId.get()).matches("[0-9a-f-]{36}");
    }

    @Test
    void executesBeforeSecurityFilters() {
        assertThat(filter.getOrder()).isEqualTo(Integer.MIN_VALUE);
    }
}
