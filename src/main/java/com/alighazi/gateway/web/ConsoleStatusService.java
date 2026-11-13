package com.alighazi.gateway.web;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.alighazi.gateway.filter.CorrelationIdGlobalFilter;
import com.alighazi.gateway.ops.RouteGovernance;

import reactor.core.publisher.Mono;

@Service
public class ConsoleStatusService {

    private final ReactiveRedisConnectionFactory redis;
    private final WebClient webClient;
    private final String ordersUri;
    private final String customersUri;
    private final RouteGovernance governance;
    private final String application;
    private final String issuer;
    private final String audience;
    private final boolean localTokensEnabled;
    private final int circuitWindow;
    private final int circuitFailurePercent;
    private final String circuitOpenWait;

    public ConsoleStatusService(
            ReactiveRedisConnectionFactory redis,
            WebClient.Builder webClientBuilder,
            RouteGovernance governance,
            @Value("${ORDERS_SERVICE_URI:http://localhost:8081}") String ordersUri,
            @Value("${CUSTOMERS_SERVICE_URI:http://localhost:8082}") String customersUri,
            @Value("${spring.application.name:enterprise-api-gateway}") String application,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${security.jwt.audience:}") String audience,
            @Value("${local-dev.tokens-enabled:false}") boolean localTokensEnabled,
            @Value("${resilience4j.circuitbreaker.configs.default.sliding-window-size:20}") int circuitWindow,
            @Value("${resilience4j.circuitbreaker.configs.default.failure-rate-threshold:50}") int circuitFailurePercent,
            @Value("${resilience4j.circuitbreaker.configs.default.wait-duration-in-open-state:10s}") String circuitOpenWait) {
        this.redis = redis;
        this.webClient = webClientBuilder.build();
        this.governance = governance;
        this.ordersUri = trimTrailingSlash(ordersUri);
        this.customersUri = trimTrailingSlash(customersUri);
        this.application = application;
        this.issuer = issuer;
        this.audience = audience == null ? "" : audience.trim();
        this.localTokensEnabled = localTokensEnabled;
        this.circuitWindow = circuitWindow;
        this.circuitFailurePercent = circuitFailurePercent;
        this.circuitOpenWait = circuitOpenWait;
    }

    public Mono<ConsoleSnapshot> snapshot() {
        List<com.alighazi.gateway.ops.RouteGovernance.ServiceOperations> custom = governance.operations().stream()
                .filter(operation -> !operation.system())
                .toList();
        List<Mono<ServiceProbe>> probes = new ArrayList<>();
        probes.add(gateway());
        probes.add(probeRedis());
        probes.add(probeOrders());
        probes.add(probeCustomers());
        custom.forEach(operation -> probes.add(probeReachable(operation.id(), operation.id(), operation.backend())));
        return Mono.zip(probes, results -> {
            List<ServiceProbe> services = new ArrayList<>();
            for (Object result : results) {
                services.add((ServiceProbe) result);
            }
            services.forEach(probe -> governance.observe(probe.id(), "UP".equals(probe.state()), probe.detail()));
            OperatingContext context = liveContext();
            List<RouteInfo> routes = new ArrayList<>();
            routes.add(new RouteInfo("GET", "/api/orders/**", "orders.read",
                    limitLabel(context.ordersReplenishPerSecond(), context.ordersBurst()), ordersUri));
            routes.add(new RouteInfo("GET", "/api/customers/**", "customers.read",
                    limitLabel(context.customersReplenishPerSecond(), context.customersBurst()), customersUri));
            governance.operations().stream().filter(operation -> !operation.system()).forEach(operation -> routes.add(
                    new RouteInfo("GET", "/api/" + operation.id() + "/**", operation.scope(),
                            limitLabel(operation.replenishPerSecond(), operation.burst()), operation.backend())));
            return new ConsoleSnapshot(
                    Instant.now().toString(),
                    List.copyOf(services),
                    List.copyOf(routes),
                    context,
                    governance.operations(),
                    governance.activity(),
                    governance.callers(),
                    governance.calls());
        });
    }

    private OperatingContext liveContext() {
        return new OperatingContext(
                application,
                issuer,
                audience,
                localTokensEnabled,
                CorrelationIdGlobalFilter.HEADER_NAME,
                governance.replenish("orders"),
                governance.burst("orders"),
                governance.replenish("customers"),
                governance.burst("customers"),
                circuitWindow,
                circuitFailurePercent,
                circuitOpenWait);
    }

    private Mono<ServiceProbe> gateway() {
        return Mono.just(ServiceProbe.up("gateway", "دروازه", "فرآیند در حال پاسخ است", 0));
    }

    private Mono<ServiceProbe> probeRedis() {
        long started = System.nanoTime();
        return Mono.defer(() -> redis.getReactiveConnection().ping())
                .timeout(Duration.ofSeconds(2))
                .map(pong -> ServiceProbe.up("redis", "Redis", "پاسخ " + pong, elapsedMs(started)))
                .onErrorResume(ex -> Mono.just(ServiceProbe.down("redis", "Redis", describe(ex), elapsedMs(started))));
    }

    private Mono<ServiceProbe> probeOrders() {
        return probeHttp("orders", "سفارش‌ها", ordersUri + "/orders/console");
    }

    private Mono<ServiceProbe> probeCustomers() {
        return probeHttp("customers", "مشتریان", customersUri + "/customers/console");
    }

    private Mono<ServiceProbe> probeReachable(String id, String label, String url) {
        long started = System.nanoTime();
        return webClient.get()
                .uri(url)
                .exchangeToMono(response -> Mono.just(
                        ServiceProbe.up(id, label, response.statusCode().value() + " از سرویس پشتیبان", elapsedMs(started))))
                .timeout(Duration.ofSeconds(2))
                .onErrorResume(ex -> Mono.just(ServiceProbe.down(id, label, describe(ex), elapsedMs(started))));
    }

    private Mono<ServiceProbe> probeHttp(String id, String label, String url) {
        long started = System.nanoTime();
        return webClient.get()
                .uri(url)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(2))
                .map(response -> ServiceProbe.up(id, label, response.getStatusCode().value() + " از سرویس پشتیبان", elapsedMs(started)))
                .onErrorResume(ex -> Mono.just(ServiceProbe.down(id, label, describe(ex), elapsedMs(started))));
    }

    static String limitLabel(int perSecond, int burst) {
        return persianDigits(perSecond) + " در ثانیه، انباشت " + persianDigits(burst);
    }

    static String persianDigits(int value) {
        String western = Integer.toString(value);
        StringBuilder persian = new StringBuilder(western.length());
        for (int i = 0; i < western.length(); i++) {
            persian.append((char) ('۰' + (western.charAt(i) - '0')));
        }
        return persian.toString();
    }

    static String describe(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "بدون پاسخ";
        }
        String compact = message.replaceAll("\\s+", " ").trim();
        return compact.length() > 140 ? compact.substring(0, 140) : compact;
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record ConsoleSnapshot(
            String checkedAt,
            List<ServiceProbe> services,
            List<RouteInfo> routes,
            OperatingContext context,
            List<com.alighazi.gateway.ops.RouteGovernance.ServiceOperations> operations,
            List<com.alighazi.gateway.ops.RouteGovernance.ActivityEvent> activity,
            List<com.alighazi.gateway.ops.RouteGovernance.CallerUsage> callers,
            List<com.alighazi.gateway.ops.RouteGovernance.CallEvent> calls) {
    }

    public record OperatingContext(
            String application,
            String issuer,
            String audience,
            boolean localTokensEnabled,
            String correlationHeader,
            int ordersReplenishPerSecond,
            int ordersBurst,
            int customersReplenishPerSecond,
            int customersBurst,
            int circuitWindow,
            int circuitFailurePercent,
            String circuitOpenWait) {
    }

    public record ServiceProbe(String id, String label, String state, String detail, long latencyMs) {
        static ServiceProbe up(String id, String label, String detail, long latencyMs) {
            return new ServiceProbe(id, label, "UP", detail, latencyMs);
        }

        static ServiceProbe down(String id, String label, String detail, long latencyMs) {
            return new ServiceProbe(id, label, "DOWN", detail, latencyMs);
        }
    }

    public record RouteInfo(String method, String path, String scope, String limit, String backend) {
    }
}
