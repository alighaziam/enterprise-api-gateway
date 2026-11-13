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
    private static final int EVENT_LIMIT = 30;
    private static final java.util.Set<String> RESERVED = java.util.Set.of("gateway", "redis", "orders", "customers", "console");

    private static final int CALL_LIMIT = 40;
    private static final int CALLER_LIMIT = 24;

    private final LiveRateLimit rateLimit;
    private final ObjectProvider<CircuitBreakerRegistry> breakers;
    private final java.util.Set<String> allowedBackendHosts;
    private final boolean allowLoopbackBackend;
    private final Map<String, ServiceRuntime> services = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<CallEvent> calls = new ConcurrentLinkedDeque<>();

    public RouteGovernance(
            LiveRateLimit rateLimit,
            ObjectProvider<CircuitBreakerRegistry> breakers,
            @Value("${ORDERS_RATE_LIMIT_REPLENISH:20}") int ordersReplenish,
            @Value("${ORDERS_RATE_LIMIT_BURST:40}") int ordersBurst,
            @Value("${CUSTOMERS_RATE_LIMIT_REPLENISH:30}") int customersReplenish,
            @Value("${CUSTOMERS_RATE_LIMIT_BURST:60}") int customersBurst,
            @Value("${ORDERS_SERVICE_URI:http://localhost:8081}") String ordersUri,
            @Value("${CUSTOMERS_SERVICE_URI:http://localhost:8082}") String customersUri,
            @Value("${GATEWAY_ROUTE_ALLOWED_HOSTS:orders-mock,customers-mock}") String allowedHosts,
            @Value("${GATEWAY_ROUTE_ALLOW_LOOPBACK:false}") boolean allowLoopbackBackend) {
        this.rateLimit = rateLimit;
        this.breakers = breakers;
        this.allowLoopbackBackend = allowLoopbackBackend;
        this.allowedBackendHosts = java.util.Arrays.stream(allowedHosts.split(","))
                .map(String::trim).filter(host -> !host.isEmpty())
                .map(host -> host.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        services.put("gateway", new ServiceRuntime("gateway", false, true, "", "", 0, 0));
        services.put("redis", new ServiceRuntime("redis", false, true, "", "", 0, 0));
        services.put("orders", new ServiceRuntime("orders", true, true, trimSlash(ordersUri), "orders.read", ordersReplenish, ordersBurst));
        services.put("customers", new ServiceRuntime("customers", true, true, trimSlash(customersUri), "customers.read", customersReplenish, customersBurst));
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

    public String resolve(String path) {
        String id = pathSegment(path);
        if (id == null) {
            return null;
        }
        ServiceRuntime runtime = services.get(id);
        return runtime != null && runtime.controllable ? id : null;
    }

    public ServiceOperations create(String id, String backend, String scope, int replenish, int burst, boolean enabled) {
        String normalized = normalizeId(id);
        if (services.containsKey(normalized)) {
            throw new IllegalArgumentException("duplicate_service");
        }
        String cleanBackend = validateBackend(backend);
        String cleanScope = validateScope(scope);
        validateRates(replenish, burst);
        ServiceRuntime runtime = new ServiceRuntime(normalized, true, false, cleanBackend, cleanScope, replenish, burst);
        runtime.enabled.set(enabled);
        services.put(normalized, runtime);
        rateLimit.apply(routeId(normalized), replenish, burst);
        return view(runtime);
    }

    public void delete(String serviceId) {
        ServiceRuntime runtime = services.get(serviceId);
        if (runtime == null || !runtime.controllable) {
            throw new UnknownService();
        }
        if (runtime.system) {
            throw new IllegalArgumentException("system_service");
        }
        services.remove(serviceId);
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
        record(serviceId, status, disabled, "anonymous");
    }

    public void record(String serviceId, int status, boolean disabled, String caller) {
        ServiceRuntime runtime = services.get(serviceId);
        if (runtime == null) {
            return;
        }
        String side = sideOf(status, disabled);
        String who = sanitizeCaller(caller);
        String at = Instant.now().toString();
        runtime.calls.incrementAndGet();
        runtime.lastStatus = status;
        runtime.lastSide = side;
        if ("OPERATOR".equals(side)) {
            runtime.disabledHits.incrementAndGet();
        } else if ("SUCCESS".equals(side)) {
            runtime.succeeded.incrementAndGet();
        } else if ("LIMITED".equals(side)) {
            runtime.limited.incrementAndGet();
        } else if ("PROVIDER".equals(side)) {
            runtime.failed.incrementAndGet();
        } else {
            runtime.rejected.incrementAndGet();
        }
        rememberCaller(runtime, who, side, status, at);
        calls.addFirst(new CallEvent(at, who, serviceId, status, side));
        while (calls.size() > CALL_LIMIT) {
            calls.pollLast();
        }
    }

    public ServiceOperations update(String serviceId, Boolean enabled, Integer replenishPerSecond, Integer burst) {
        return update(serviceId, enabled, replenishPerSecond, burst, null, null);
    }

    public ServiceOperations update(
            String serviceId, Boolean enabled, Integer replenishPerSecond, Integer burst, String backend, String scope) {
        ServiceRuntime runtime = services.get(serviceId);
        if (runtime == null || !runtime.controllable) {
            throw new UnknownService();
        }
        if (runtime.system && (backend != null || scope != null)) {
            throw new IllegalArgumentException("system_service");
        }
        int nextReplenish = replenishPerSecond == null ? runtime.replenish.get() : replenishPerSecond;
        int nextBurst = burst == null ? runtime.burst.get() : burst;
        validateRates(nextReplenish, nextBurst);
        if (backend != null) {
            runtime.backend = validateBackend(backend);
        }
        if (scope != null) {
            runtime.scope = validateScope(scope);
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
        List<ServiceOperations> listed = new ArrayList<>();
        listed.add(view(services.get("orders")));
        listed.add(view(services.get("customers")));
        services.values().stream()
                .filter(runtime -> runtime.controllable && !runtime.system)
                .sorted(Comparator.comparing(runtime -> runtime.id))
                .forEach(runtime -> listed.add(view(runtime)));
        return List.copyOf(listed);
    }

    public List<ActivityEvent> activity() {
        List<ActivityEvent> events = new ArrayList<>();
        for (ServiceRuntime runtime : services.values()) {
            events.addAll(runtime.events);
        }
        events.sort(Comparator.comparing(ActivityEvent::at).reversed());
        return events.size() > 100 ? List.copyOf(events.subList(0, 100)) : List.copyOf(events);
    }

    public List<CallerUsage> callers() {
        List<CallerUsage> listed = new ArrayList<>();
        for (ServiceRuntime runtime : services.values()) {
            if (!runtime.controllable) {
                continue;
            }
            for (CallerCounter counter : runtime.callers.values()) {
                listed.add(counter.view(runtime.id));
            }
        }
        listed.sort(Comparator.comparing(CallerUsage::lastAt).reversed());
        return listed.size() > 80 ? List.copyOf(listed.subList(0, 80)) : List.copyOf(listed);
    }

    public List<CallEvent> calls() {
        return calls.size() > CALL_LIMIT ? List.copyOf(new ArrayList<>(calls).subList(0, CALL_LIMIT)) : List.copyOf(calls);
    }

    private ServiceOperations view(ServiceRuntime runtime) {
        String circuit = circuitState(runtime.id);
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
                circuit,
                runtime.system,
                runtime.backend,
                runtime.scope,
                outageOf(runtime, circuit));
    }

    private static String sideOf(int status, boolean disabled) {
        if (disabled) {
            return "OPERATOR";
        }
        if (status >= 200 && status < 300) {
            return "SUCCESS";
        }
        if (status == 429) {
            return "LIMITED";
        }
        if (status >= 500 || status <= 0) {
            return "PROVIDER";
        }
        return "CONSUMER";
    }

    private static String outageOf(ServiceRuntime runtime, String circuit) {
        if (!runtime.enabled.get()) {
            return "OPERATOR";
        }
        if ("DOWN".equals(runtime.connectivity) || "OPEN".equals(circuit) || "PROVIDER".equals(runtime.lastSide)) {
            return "PROVIDER";
        }
        if ("UP".equals(runtime.connectivity) && runtime.succeeded.get() == 0 && runtime.rejected.get() > 0) {
            return "CONSUMER";
        }
        return "NONE";
    }

    private static String sanitizeCaller(String caller) {
        if (caller == null || caller.isBlank()) {
            return "anonymous";
        }
        String compact = caller.replaceAll("[\\p{Cntrl}]", "").trim();
        if (compact.isEmpty()) {
            return "anonymous";
        }
        return compact.length() > 80 ? compact.substring(0, 80) : compact;
    }

    private static void rememberCaller(ServiceRuntime runtime, String caller, String side, int status, String at) {
        CallerCounter counter = runtime.callers.computeIfAbsent(caller, CallerCounter::new);
        switch (side) {
            case "SUCCESS" -> counter.succeeded.incrementAndGet();
            case "PROVIDER" -> counter.providerFailures.incrementAndGet();
            case "LIMITED" -> counter.limited.incrementAndGet();
            case "OPERATOR" -> counter.disabled.incrementAndGet();
            default -> counter.consumerFailures.incrementAndGet();
        }
        counter.lastStatus.set(status);
        counter.lastAt = at;
        if (runtime.callers.size() <= CALLER_LIMIT) {
            return;
        }
        runtime.callers.entrySet().stream()
                .min(Comparator.comparing(entry -> entry.getValue().lastAt))
                .filter(oldest -> !oldest.getKey().equals(caller))
                .ifPresent(oldest -> runtime.callers.remove(oldest.getKey(), oldest.getValue()));
    }

    private static String pathSegment(String path) {
        if (path == null || !path.startsWith("/api/")) {
            return null;
        }
        String rest = path.substring("/api/".length());
        int slash = rest.indexOf('/');
        String id = (slash < 0 ? rest : rest.substring(0, slash)).toLowerCase();
        if (id.isBlank() || "console".equals(id)) {
            return null;
        }
        return id;
    }

    private static String normalizeId(String id) {
        if (id == null || !id.matches("[a-z][a-z0-9-]{1,30}")) {
            throw new IllegalArgumentException("invalid_id");
        }
        if (RESERVED.contains(id)) {
            throw new IllegalArgumentException("reserved_id");
        }
        return id;
    }

    private String validateBackend(String backend) {
        if (backend == null || backend.isBlank()) {
            throw new IllegalArgumentException("invalid_backend");
        }
        java.net.URI uri;
        try {
            uri = java.net.URI.create(backend.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid_backend");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null || uri.getRawFragment() != null
                || scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("invalid_backend");
        }
        String normalizedHost = host.toLowerCase(java.util.Locale.ROOT);
        // Literal IPs, loopback, link-local and metadata endpoints are never valid route targets.
        boolean loopback = allowLoopbackBackend && isLoopbackHost(normalizedHost);
        if ((normalizedHost.matches("[0-9.]+") || normalizedHost.contains(":")
                || normalizedHost.equals("localhost") || normalizedHost.endsWith(".localhost")) && !loopback
                || normalizedHost.endsWith(".local") || normalizedHost.endsWith(".internal")
                || (!loopback && !allowedBackendHosts.contains(normalizedHost))) {
            throw new IllegalArgumentException("backend_host_not_allowed");
        }
        return trimSlash(uri.toString());
    }

    private static boolean isLoopbackHost(String host) {
        if (!host.equals("localhost") && !host.matches("[0-9.]+") && !host.contains(":")) {
            return false;
        }
        try {
            return java.net.InetAddress.getByName(host).isLoopbackAddress();
        } catch (java.net.UnknownHostException exception) {
            return false;
        }
    }

    private static String validateScope(String scope) {
        if (!"orders.read".equals(scope) && !"customers.read".equals(scope)) {
            throw new IllegalArgumentException("invalid_scope");
        }
        return scope;
    }

    private static void validateRates(int replenish, int burst) {
        if (replenish < 1 || replenish > 1000 || burst < 1 || burst > 5000) {
            throw new IllegalArgumentException("rate_out_of_range");
        }
        if (burst < replenish) {
            throw new IllegalArgumentException("burst_below_rate");
        }
    }

    private static String trimSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
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
            String circuit,
            boolean system,
            String backend,
            String scope,
            String outage) {
    }

    public record CallerUsage(
            String caller,
            String serviceId,
            long succeeded,
            long consumerFailures,
            long providerFailures,
            long limited,
            long disabled,
            int lastStatus,
            String lastAt) {
    }

    public record CallEvent(String at, String caller, String serviceId, int status, String side) {
    }

    public record ActivityEvent(String at, String serviceId, String kind, String detail) {
    }

    private static final class ServiceRuntime {
        private final String id;
        private final boolean controllable;
        private final boolean system;
        private volatile String backend;
        private volatile String scope;
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
        private final Map<String, CallerCounter> callers = new ConcurrentHashMap<>();
        private String connectivity = "UNKNOWN";
        private String since = "";
        private volatile int lastStatus;
        private volatile String lastSide = "";

        private ServiceRuntime(
                String id, boolean controllable, boolean system, String backend, String scope, int replenish, int burst) {
            this.id = id;
            this.controllable = controllable;
            this.system = system;
            this.backend = backend;
            this.scope = scope;
            this.replenish = new AtomicInteger(replenish);
            this.burst = new AtomicInteger(burst);
        }
    }

    private static final class CallerCounter {
        private final String caller;
        private final AtomicLong succeeded = new AtomicLong();
        private final AtomicLong consumerFailures = new AtomicLong();
        private final AtomicLong providerFailures = new AtomicLong();
        private final AtomicLong limited = new AtomicLong();
        private final AtomicLong disabled = new AtomicLong();
        private final AtomicInteger lastStatus = new AtomicInteger();
        private volatile String lastAt = "";

        private CallerCounter(String caller) {
            this.caller = caller;
        }

        private CallerUsage view(String serviceId) {
            return new CallerUsage(
                    caller,
                    serviceId,
                    succeeded.get(),
                    consumerFailures.get(),
                    providerFailures.get(),
                    limited.get(),
                    disabled.get(),
                    lastStatus.get(),
                    lastAt);
        }
    }
}
