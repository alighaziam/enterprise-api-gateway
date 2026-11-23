package com.alighazi.gateway.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RouteAuditServiceTest {

    @Test
    void storesSanitizedBoundedAuditMetadata() {
        RouteAuditStore store = mock(RouteAuditStore.class);
        when(store.append(any())).thenReturn(Mono.empty());
        RouteAuditService service = new RouteAuditService(store);

        StepVerifier.create(service.record("operator\n-1", "create", "billing",
                        "succeeded", "backend_host_not_allowed", "request-123"))
                .verifyComplete();

        ArgumentCaptor<RouteAuditStore.Entry> entry = ArgumentCaptor.forClass(RouteAuditStore.Entry.class);
        verify(store).append(entry.capture());
        assertThat(entry.getValue().actor()).isEqualTo("operator-1");
        assertThat(entry.getValue().action()).isEqualTo("CREATE");
        assertThat(entry.getValue().serviceId()).isEqualTo("billing");
        assertThat(entry.getValue().outcome()).isEqualTo("SUCCEEDED");
        assertThat(entry.getValue().reason()).isEqualTo("backend_host_not_allowed");
        assertThat(entry.getValue().correlationId()).isEqualTo("request-123");
        assertThat(entry.getValue().occurredAt()).isNotBlank();
    }

    @Test
    void rejectsInvalidActionsOutcomesAndLimits() {
        RouteAuditService service = new RouteAuditService(mock(RouteAuditStore.class));

        StepVerifier.create(service.record("operator", "RESET", "billing",
                        "SUCCEEDED", "", "request"))
                .expectErrorMessage("invalid_audit_action")
                .verify();

        StepVerifier.create(service.record("operator", "CREATE", "billing",
                        "UNKNOWN", "", "request"))
                .expectErrorMessage("invalid_audit_outcome")
                .verify();

        StepVerifier.create(service.latest(101))
                .expectErrorMessage("invalid_audit_limit")
                .verify();
    }

    @Test
    void returnsOnlyRequestedRecentEvents() {
        RouteAuditStore store = mock(RouteAuditStore.class);
        List<RouteAuditStore.Entry> entries = List.of(new RouteAuditStore.Entry(
                "audit-1", "operator", "DELETE", "billing", "SUCCEEDED", "", "",
                "2026-11-23T06:00:00Z"));
        when(store.latest(20)).thenReturn(Mono.just(entries));

        StepVerifier.create(new RouteAuditService(store).latest(20))
                .expectNext(entries)
                .verifyComplete();
    }
}
