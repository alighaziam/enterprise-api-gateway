package com.alighazi.gateway.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class RouteGovernanceTest {

    @Test
    void recordsConnectionChangesOnceAndCountsCalls() {
        RouteGovernance governance = governance();

        governance.observe("orders", true, "up");
        governance.observe("orders", true, "up");
        governance.observe("orders", false, "connection refused");

        assertThat(governance.activity()).extracting(RouteGovernance.ActivityEvent::kind)
                .containsExactly("DOWN", "UP");
        assertThat(governance.operations().get(0).transitions()).isEqualTo(2);
        assertThat(governance.operations().get(0).connectivity()).isEqualTo("DOWN");

        governance.record("orders", 200, false);
        governance.record("orders", 429, false);
        governance.record("orders", 401, false);
        governance.record("orders", 503, true);

        RouteGovernance.ServiceOperations orders = governance.operations().get(0);
        assertThat(orders.calls()).isEqualTo(4);
        assertThat(orders.succeeded()).isEqualTo(1);
        assertThat(orders.limited()).isEqualTo(1);
        assertThat(orders.rejected()).isEqualTo(1);
        assertThat(orders.disabledHits()).isEqualTo(1);
        assertThat(orders.failed()).isZero();
    }

    @Test
    void attributesEachCallerAndSeparatesConsumerFromProvider() {
        RouteGovernance governance = governance();

        governance.record("orders", 200, false, "alice");
        governance.record("orders", 200, false, "alice");
        governance.record("orders", 403, false, "alice");
        governance.record("customers", 502, false, "bob");
        governance.observe("customers", false, "connection refused");

        assertThat(governance.callers())
                .filteredOn(usage -> "alice".equals(usage.caller()) && "orders".equals(usage.serviceId()))
                .singleElement()
                .satisfies(usage -> {
                    assertThat(usage.succeeded()).isEqualTo(2);
                    assertThat(usage.consumerFailures()).isEqualTo(1);
                    assertThat(usage.providerFailures()).isZero();
                });
        assertThat(governance.callers())
                .filteredOn(usage -> "bob".equals(usage.caller()))
                .singleElement()
                .satisfies(usage -> {
                    assertThat(usage.serviceId()).isEqualTo("customers");
                    assertThat(usage.providerFailures()).isEqualTo(1);
                });
        assertThat(governance.calls()).extracting(RouteGovernance.CallEvent::side)
                .containsExactly("PROVIDER", "CONSUMER", "SUCCESS", "SUCCESS");
        assertThat(governance.operations().get(0).outage()).isEqualTo("NONE");
        assertThat(governance.operations().get(1).outage()).isEqualTo("PROVIDER");
    }

    @Test
    void updatesRateLimitAndEnablement() {
        List<String> applied = new ArrayList<>();
        RouteGovernance governance = new RouteGovernance(
                (routeId, replenish, burst) -> applied.add(routeId + ":" + replenish + ":" + burst),
                emptyBreakers(), 20, 40, 30, 60, "http://localhost:8081", "http://localhost:8082", "orders-service,customers-service,billing-service");

        RouteGovernance.ServiceOperations updated = governance.update("orders", false, 5, 9);

        assertThat(updated.enabled()).isFalse();
        assertThat(updated.replenishPerSecond()).isEqualTo(5);
        assertThat(updated.burst()).isEqualTo(9);
        assertThat(applied).containsExactly("orders-service:5:9");
        assertThat(governance.activity()).extracting(RouteGovernance.ActivityEvent::kind)
                .contains("DISABLED");
        assertThat(governance.isEnabled("orders")).isFalse();
    }

    @Test
    void createsAndDeletesACustomServiceButKeepsSystemServices() {
        RouteGovernance governance = governance();

        RouteGovernance.ServiceOperations created = governance.create(
                "billing", "http://billing-service:8081", "orders.read", 5, 8, true);

        assertThat(created.system()).isFalse();
        assertThat(created.backend()).isEqualTo("http://billing-service:8081");
        assertThat(governance.resolve("/api/billing/77")).isEqualTo("billing");
        assertThat(governance.operations()).extracting(RouteGovernance.ServiceOperations::id)
                .contains("orders", "customers", "billing");

        governance.delete("billing");
        assertThat(governance.resolve("/api/billing/77")).isNull();
        assertThatThrownBy(() -> governance.delete("orders")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("system_service");
        assertThatThrownBy(() -> governance.create("orders", "http://orders-service:9", "orders.read", 1, 1, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reserved_id");
    }

    @Test
    void rejectsPrivateIpAndUnapprovedBackendHosts() {
        RouteGovernance governance = governance();
        assertThatThrownBy(() -> governance.create(
                "billing", "http://127.0.0.1:8081", "orders.read", 5, 8, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("backend_host_not_allowed");
        assertThatThrownBy(() -> governance.create(
                "billing", "http://169.254.169.254/latest/meta-data", "orders.read", 5, 8, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("backend_host_not_allowed");
        assertThatThrownBy(() -> governance.create(
                "billing", "http://attacker.example:8081", "orders.read", 5, 8, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("backend_host_not_allowed");
        assertThatThrownBy(() -> governance.create(
                "billing", "http://user:pass@orders-service:8081", "orders.read", 5, 8, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid_backend");
    }

    @Test
    void rejectsBurstBelowRateAndUnknownService() {
        RouteGovernance governance = governance();

        assertThatThrownBy(() -> governance.update("orders", null, 10, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("burst_below_rate");
        assertThatThrownBy(() -> governance.update("redis", false, null, null))
                .isInstanceOf(RouteGovernance.UnknownService.class);
        assertThat(governance.replenish("orders")).isEqualTo(20);
    }

    private static RouteGovernance governance() {
        return new RouteGovernance((routeId, replenish, burst) -> { }, emptyBreakers(), 20, 40, 30, 60,
                "http://localhost:8081", "http://localhost:8082", "orders-service,customers-service");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry> emptyBreakers() {
        ObjectProvider<io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry> provider =
                org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
