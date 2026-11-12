package com.alighazi.gateway.ops;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

@Component
public class RouteGovernance {

    public static final String DISABLED_ATTRIBUTE = "gateway.route-disabled";
    private static final int EVENT_LIMIT = 8;

    private final LiveRateLimit rateLimit;
    private final ObjectProvider<CircuitBreakerRegistry> breakers;
    private final Map<String, ServiceRuntime> services = new ConcurrentHashMap<>();

    public RouteGovernance(
            LiveRateLimit rateLimit,
            ObjectProvider<CircuitBreakerRegistry> breakers,
            @Value("${ORDERS_RATE_LIMIT_REPLENISH:20}") int ordersReplenish,
            @Value("${ORDERS_RATE_LIMIT_BURST:40}") int ordersBurst,
            @Value("${CUSTOMERS_RATE_LIMIT_REPLENISH:30}") int customersReplenish,
            @Value("${CUSTOMERS_RATE_LIMIT_BURST:60}") int customersBurst) {
        this.rateLimit = rateLimit;
        this.breakers = breakers;
        services.put("gateway", new ServiceRuntime("gateway", false, 0, 0));
        services.put("redis", new ServiceRuntime("redis", false, 0, 0));
        services.put("orders", new ServiceRuntime("orders", true, ordersReplenish, ordersBurst));
        services.put("customers", new ServiceRuntime("customers", true, customersReplenish, customersBurst));
    }

    public static String serviceId(String path) {
        if (path == null) {
            return null;
        }
        if (path.equals("/api/orders") || path.startsWith("/api/orders/")) {
            return "orders";
        }
        if (path.equals("/api/customers") || path.startsWith("/api/customers/")) {
            return "customers";
        }
        return null;
    }

    public static String routeId(String serviceId) {
        return serviceId + "-service";
    }

    public boolean isEnabled(String serviceId) {
        ServiceRuntime runtime = services.get(serviceId);
        return runtime == null || runtime.enabled.get();
    }

    public int replenish(String serviceId) {
        return services.get(serviceId).replenish.get();
    }

    public int burst(String serviceId) {
        return services.get(serviceId).burst.get();
    }

    public void observe(String serviceId, boolean up, String detail) {
        ServiceRuntime runtime = services.get(serviceId);
        if (runtime == null) {
            return;
        }
        String next = up ? "UP" : "DOWN";
        synchronized (runtime) {
            if (next.equals(runtime.connectivity)) {
                return;
            }
            runtime.connectivity = next;
            runtime.since = Instant.now().toString();
            runtime.transitions.incrementAndGet();
            push(runtime, new ActivityEvent(runtime.since, serviceId, next, detail == null ? "" : detail));
        }
    }

    public void record(String serviceId, int status, boolean disabled) {
        ServiceRuntime runtime = services.get(serviceId);
        if (runtime == null) {
            return;
        }
        runtime.calls.incrementAndGet();
        if (disabled) {
            runtime.disabledHits.incrementAndGet();
            return;
        }
        if (status >= 200 && status < 300) {
            runtime.succeeded.incrementAndGet();
        } else if (status == 429) {
            runtime.limited.incrementAndGet();
        } else if (status == 401 || status == 403) {
            runtime.rejected.incrementAndGet();
        } else if (status >= 500) {
            runtime.failed.incrementAndGet();
        }
    }

    public ServiceOperations update(String serviceId, Boolean enabled, Integer replenishPerSecond, Integer burst) {
        ServiceRuntime runtime = services.get(serviceId);
        if (runtime == null || !runtime.controllable) {
            throw new UnknownService();
        }
        int nextReplenish = replenishPerSecond == null ? runtime.replenish.get() : replenishPerSecond;
        int nextBurst = burst == null ? runtime.burst.get() : burst;
        if (nextReplenish < 1 || nextReplenish > 1000 || nextBurst < 1 || nextBurst > 5000) {
            throw new IllegalArgumentException("rate_out_of_range");
        }
        if (nextBurst < nextReplenish) {
            throw new IllegalArgumentException("burst_below_rate");
        }
        if (replenishPerSecond != null || burst != null) {
            runtime.replenish.set(nextReplenish);
            runtime.burst.set(nextBurst);
            rateLimit.apply(routeId(serviceId), nextReplenish, nextBurst);
        }
        if (enabled != null && runtime.enabled.get() != enabled) {
            runtime.enabled.set(enabled);
            String at = Instant.now().toString();
            synchronized (runtime) {
                push(runtime, new ActivityEvent(at, serviceId, enabled ? "ENABLED" : "DISABLED", ""));
            }
        }
        return view(runtime);
    }

    public List<ServiceOperations> operations() {
        return List.of(view(services.get("orders")), view(services.get("customers")));
    }

    public List<ActivityEvent> activity() {
        List<ActivityEvent> events = new ArrayList<>();
        for (ServiceRuntime runtime : services.values()) {
            events.addAll(runtime.events);
        }
        events.sort(Comparator.comparing(ActivityEvent::at).reversed());
        return events.size() > 20 ? List.copyOf(events.subList(0, 20)) : List.copyOf(events);
    }

    private ServiceOperations view(ServiceRuntime runtime) {
        return new ServiceOperations(
                runtime.id,
                runtime.controllable,
                runtime.enabled.get(),
                runtime.connectivity,
                runtime.since,
                runtime.replenish.get(),
                runtime.burst.get(),
                runtime.calls.get(),
                runtime.succeeded.get(),
                runtime.limited.get(),
                runtime.rejected.get(),
                runtime.failed.get(),
                runtime.disabledHits.get(),
                runtime.transitions.get(),
                circuitState(runtime.id));
    }

    private String circuitState(String serviceId) {
        if (!"orders".equals(serviceId) && !"customers".equals(serviceId)) {
            return "UNKNOWN";
        }
        CircuitBreakerRegistry registry = breakers.getIfAvailable();
        if (registry == null) {
            return "UNKNOWN";
        }
        String name = serviceId + "CircuitBreaker";
        return registry.getAllCircuitBreakers().stream()
                .filter(breaker -> breaker.getName().equals(name))
                .map(breaker -> breaker.getState().name())
                .findFirst()
                .orElse("UNKNOWN");
    }

    private static void push(ServiceRuntime runtime, ActivityEvent event) {
        runtime.events.addFirst(event);
        while (runtime.events.size() > EVENT_LIMIT) {
            runtime.events.pollLast();
        }
    }

    public static final class UnknownService extends IllegalArgumentException {
        public UnknownService() {
            super("unknown_service");
        }
    }

    public record ServiceOperations(
            String id,
            boolean controllable,
            boolean enabled,
            String connectivity,
            String since,
            int replenishPerSecond,
            int burst,
            long calls,
            long succeeded,
            long limited,
            long rejected,
            long failed,
            long disabledHits,
            int transitions,
            String circuit) {
    }

    public record ActivityEvent(String at, String serviceId, String kind, String detail) {
    }

    private static final class ServiceRuntime {
        private final String id;
        private final boolean controllable;
        private final AtomicInteger replenish;
        private final AtomicInteger burst;
        private final java.util.concurrent.atomic.AtomicBoolean enabled = new java.util.concurrent.atomic.AtomicBoolean(true);
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong succeeded = new AtomicLong();
        private final AtomicLong limited = new AtomicLong();
        private final AtomicLong rejected = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
        private final AtomicLong disabledHits = new AtomicLong();
        private final AtomicInteger transitions = new AtomicInteger();
        private final ConcurrentLinkedDeque<ActivityEvent> events = new ConcurrentLinkedDeque<>();
        private String connectivity = "UNKNOWN";
        private String since = "";

        private ServiceRuntime(String id, boolean controllable, int replenish, int burst) {
            this.id = id;
            this.controllable = controllable;
            this.replenish = new AtomicInteger(replenish);
            this.burst = new AtomicInteger(burst);
        }
    }
}
