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
    void updatesRateLimitAndEnablement() {
        List<String> applied = new ArrayList<>();
        RouteGovernance governance = new RouteGovernance(
                (routeId, replenish, burst) -> applied.add(routeId + ":" + replenish + ":" + burst),
                emptyBreakers(), 20, 40, 30, 60);

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
        return new RouteGovernance((routeId, replenish, burst) -> { }, emptyBreakers(), 20, 40, 30, 60);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry> emptyBreakers() {
        ObjectProvider<io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry> provider =
                org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
