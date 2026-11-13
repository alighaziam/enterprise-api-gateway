package com.alighazi.gateway.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.alighazi.gateway.web.ConsoleStatusService.ConsoleSnapshot;
import com.alighazi.gateway.web.ConsoleStatusService.RouteInfo;
import com.alighazi.gateway.web.ConsoleStatusService.ServiceProbe;

import reactor.core.publisher.Mono;

class ConsoleControllerTest {

    @Test
    void exposesOperationalSnapshot() {
        ConsoleStatusService service = mock(ConsoleStatusService.class);
        when(service.snapshot()).thenReturn(Mono.just(new ConsoleSnapshot(
                "2026-11-12T18:00:00Z",
                List.of(ServiceProbe.up("gateway", "دروازه", "فعال", 0)),
                List.of(new RouteInfo("GET", "/api/orders/**", "orders.read", "۲۰ در ثانیه، انباشت ۴۰", "http://localhost:8081")),
                new ConsoleStatusService.OperatingContext(
                        "enterprise-api-gateway", "http://gateway-test", "gateway-api", false,
                        "X-Correlation-ID", 20, 40, 30, 60, 20, 50, "10s"),
                List.of(),
                List.of(),
                List.of(),
                List.of())));

        WebTestClient.bindToController(new ConsoleController(service, mock(com.alighazi.gateway.ops.RouteCatalog.class)))
                .build()
                .get()
                .uri("/api/console/status")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.services[0].state").isEqualTo("UP")
                .jsonPath("$.routes[0].path").isEqualTo("/api/orders/**")
                .jsonPath("$.context.correlationHeader").isEqualTo("X-Correlation-ID")
                .jsonPath("$.context.localTokensEnabled").isEqualTo(false);
    }

    @Test
    void limitLabelUsesTheConfiguredRates() {
        org.assertj.core.api.Assertions.assertThat(ConsoleStatusService.limitLabel(20, 40))
                .isEqualTo("۲۰ در ثانیه، انباشت ۴۰");
        org.assertj.core.api.Assertions.assertThat(ConsoleStatusService.limitLabel(1, 2))
                .isEqualTo("۱ در ثانیه، انباشت ۲");
    }

    @Test
    void describesBlankFailuresWithoutLeakingEmptyText() {
        org.assertj.core.api.Assertions.assertThat(ConsoleStatusService.describe(new IllegalStateException("  ")))
                .isEqualTo("بدون پاسخ");
    }
}
