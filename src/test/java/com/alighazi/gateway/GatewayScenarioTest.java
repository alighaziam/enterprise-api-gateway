package com.alighazi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "20s")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GatewayScenarioTest {

    private static final String ISSUER = "http://gateway-test";
    private static final String AUDIENCE = "gateway-api";

    private static HttpServer jwksServer;
    private static HttpServer ordersServer;
    private static HttpServer customersServer;
    private static RSAKey signingKey;

    @Autowired
    private WebTestClient client;

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) throws Exception {
        startFixtures();
        registry.add("WSO2_ISSUER_URI", () -> ISSUER);
        registry.add("WSO2_JWKS_URI", () -> "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/jwks");
        registry.add("WSO2_REQUIRED_AUDIENCE", () -> AUDIENCE);
        registry.add("ORDERS_SERVICE_URI", () -> base(ordersServer));
        registry.add("CUSTOMERS_SERVICE_URI", () -> base(customersServer));
        registry.add("REDIS_HOST", () -> "127.0.0.1");
        registry.add("REDIS_PORT", () -> "6379");
        registry.add("local-dev.tokens-enabled", () -> "false");
        registry.add("ORDERS_RATE_LIMIT_REPLENISH", () -> "1");
        registry.add("ORDERS_RATE_LIMIT_BURST", () -> "2");
        registry.add("CUSTOMERS_RATE_LIMIT_REPLENISH", () -> "50");
        registry.add("CUSTOMERS_RATE_LIMIT_BURST", () -> "50");
    }

    @AfterAll
    static void stopFixtures() {
        if (jwksServer != null) {
            jwksServer.stop(0);
        }
        if (ordersServer != null) {
            ordersServer.stop(0);
        }
        if (customersServer != null) {
            customersServer.stop(0);
        }
    }

    @Nested
    class Smoke {

        @Test
        void healthIsPublicAndUp() {
            client.get().uri("/actuator/health")
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().exists("X-Correlation-ID")
                    .expectBody()
                    .jsonPath("$.status").isEqualTo("UP");
        }

        @Test
        void consolePageIsServed() {
            client.get().uri("/")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(String.class)
                    .value(body -> assertThat(body)
                            .contains("کنسول عملیاتی")
                            .contains("Operations console")
                            .contains("Vazirmatn-wght.woff2"));

            client.get().uri("/fonts/Vazirmatn-wght.woff2")
                    .exchange()
                    .expectStatus().isOk();
        }

        @Test
        void swaggerUiAndOpenApiAreServed() {
            client.get().uri("/swagger-ui.html")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(String.class)
                    .value(body -> assertThat(body).contains("swagger-ui-bundle.js"));

            client.get().uri("/openapi.json")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(String.class)
                    .value(body -> assertThat(body).contains("/api/orders/{orderId}").contains("/api/customers/{customerId}"));
        }

        @Test
        void localDevTokensStayDisabledByDefault() {
            client.get().uri("/api/console/token")
                    .exchange()
                    .expectStatus().isNotFound();
            client.get().uri("/jwks")
                    .exchange()
                    .expectStatus().isNotFound();
        }

        @Test
        void consoleStatusReportsLiveDependencies() {
            client.get().uri("/api/console/status")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.services[0].state").isEqualTo("UP")
                    .jsonPath("$.services[1].state").isEqualTo("UP")
                    .jsonPath("$.services[2].state").isEqualTo("UP")
                    .jsonPath("$.services[3].state").isEqualTo("UP")
                    .jsonPath("$.context.issuer").isEqualTo(ISSUER)
                    .jsonPath("$.context.audience").isEqualTo(AUDIENCE)
                    .jsonPath("$.context.localTokensEnabled").isEqualTo(false)
                    .jsonPath("$.context.ordersReplenishPerSecond").isEqualTo(1)
                    .jsonPath("$.routes[0].limit").isEqualTo("۱ در ثانیه، انباشت ۲")
                    .jsonPath("$.routes[1].limit").isEqualTo("۵۰ در ثانیه، انباشت ۵۰")
                    .jsonPath("$.operations[0].id").isEqualTo("orders")
                    .jsonPath("$.operations[0].enabled").isEqualTo(true)
                    .jsonPath("$.operations[0].calls").isNumber()
                    .jsonPath("$.operations[1].id").isEqualTo("customers")
                    .jsonPath("$.activity").isArray();
        }
    }

    @Nested
    class Security {

        @Test
        void missingTokenIsUnauthorizedAndCarriesCorrelationId() {
            client.get().uri("/api/orders/123")
                    .exchange()
                    .expectStatus().isUnauthorized()
                    .expectHeader().valueMatches("X-Correlation-ID", "[0-9a-f-]{36}");
        }

        @Test
        void preservesCallerCorrelationIdOnUnauthorizedResponse() {
            client.get().uri("/api/orders/123")
                    .header("X-Correlation-ID", "scenario-401")
                    .exchange()
                    .expectStatus().isUnauthorized()
                    .expectHeader().valueEquals("X-Correlation-ID", "scenario-401");
        }

        @Test
        void replacesUnsafeCorrelationId() {
            client.get().uri("/api/orders/123")
                    .header("X-Correlation-ID", "bad id")
                    .exchange()
                    .expectStatus().isUnauthorized()
                    .expectHeader().valueMatches("X-Correlation-ID", "[0-9a-f-]{36}");
        }

        @Test
        void malformedBearerTokenIsUnauthorized() {
            client.get().uri("/api/orders/123")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        @Test
        void expiredTokenIsUnauthorized() {
            client.get().uri("/api/orders/123")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token("expired-user", "orders.read", AUDIENCE, ISSUER, Instant.now().minusSeconds(180))))
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        @Test
        void wrongIssuerIsUnauthorized() {
            client.get().uri("/api/orders/123")
                    .header(HttpHeaders.AUTHORIZATION, bearer(token("issuer-user", "orders.read", AUDIENCE, "http://other-issuer", Instant.now().plusSeconds(300))))
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        @Test
        void missingAudienceIsUnauthorized() {
            client.get().uri("/api/orders/" + UUID.randomUUID())
                    .header(HttpHeaders.AUTHORIZATION, bearer(token("aud-user", "orders.read", null, ISSUER, Instant.now().plusSeconds(300))))
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        @Test
        void validTokenWithoutOrdersScopeIsForbidden() {
            client.get().uri("/api/orders/" + UUID.randomUUID())
                    .header(HttpHeaders.AUTHORIZATION, bearer(token("customer-only", "customers.read", AUDIENCE, ISSUER, Instant.now().plusSeconds(300))))
                    .exchange()
                    .expectStatus().isForbidden();
        }

        @Test
        void writeMethodIsForbiddenEvenWithReadScope() {
            client.post().uri("/api/orders/" + UUID.randomUUID())
                    .header(HttpHeaders.AUTHORIZATION, bearer(readToken("writer")))
                    .exchange()
                    .expectStatus().isForbidden();
        }

        @Test
        void fallbackIsNotPublic() {
            client.get().uri("/fallback/orders")
                    .exchange()
                    .expectStatus().isUnauthorized();

            client.get().uri("/fallback/orders")
                    .header(HttpHeaders.AUTHORIZATION, bearer(readToken("fallback-user")))
                    .exchange()
                    .expectStatus().isForbidden();
        }
    }

    @Nested
    class Routing {

        @Test
        void ordersReadScopeReachesOrdersBackend() {
            String id = UUID.randomUUID().toString();
            client.get().uri("/api/orders/" + id)
                    .header(HttpHeaders.AUTHORIZATION, bearer(readToken("orders-" + id)))
                    .header("X-Correlation-ID", "orders-" + id)
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals("X-Correlation-ID", "orders-" + id)
                    .expectBody(String.class)
                    .value(body -> assertThat(body).contains("\"service\":\"orders\"").contains("/orders/" + id));
        }

        @Test
        void customersReadScopeReachesCustomersBackend() {
            String id = UUID.randomUUID().toString();
            client.get().uri("/api/customers/" + id)
                    .header(HttpHeaders.AUTHORIZATION, bearer(token("customers-" + id, "customers.read", AUDIENCE, ISSUER, Instant.now().plusSeconds(300))))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(String.class)
                    .value(body -> assertThat(body).contains("\"service\":\"customers\"").contains("/customers/" + id));
        }

        @Test
        void ordersReadScopeCannotReadCustomers() {
            client.get().uri("/api/customers/" + UUID.randomUUID())
                    .header(HttpHeaders.AUTHORIZATION, bearer(readToken("cross-" + UUID.randomUUID())))
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void ordersRateLimitReturnsTooManyRequests() {
            String subject = "burst-" + UUID.randomUUID();
            String authorization = bearer(readToken(subject));
            List<Integer> statuses = java.util.stream.IntStream.range(0, 4)
                    .mapToObj(ignored -> client.get().uri("/api/orders/limited")
                            .header(HttpHeaders.AUTHORIZATION, authorization)
                            .exchange()
                            .expectBody(String.class)
                            .returnResult()
                            .getStatus()
                            .value())
                    .toList();

            assertThat(statuses).contains(200, 429);
        }

        @Test
        void operatorCanDisableAndRestoreOrders() {
            try {
                client.post().uri("/api/console/routes/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .bodyValue(java.util.Map.of("enabled", false))
                        .exchange()
                        .expectStatus().isOk()
                        .expectBody()
                        .jsonPath("$.enabled").isEqualTo(false);

                client.get().uri("/api/orders/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(readToken("disabled-" + UUID.randomUUID())))
                        .exchange()
                        .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                        .expectBody(String.class)
                        .value(body -> assertThat(body).contains("service_disabled"));
            } finally {
                client.post().uri("/api/console/routes/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .bodyValue(java.util.Map.of("enabled", true))
                        .exchange()
                        .expectStatus().isOk();
            }

            client.get().uri("/api/orders/" + UUID.randomUUID())
                    .header(HttpHeaders.AUTHORIZATION, bearer(readToken("restored-" + UUID.randomUUID())))
                    .exchange()
                    .expectStatus().isOk();
        }

        @Test
        void operatorCanTightenTheOrdersRateLimit() {
            try {
                client.post().uri("/api/console/routes/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .bodyValue(java.util.Map.of("replenishPerSecond", 1, "burst", 1))
                        .exchange()
                        .expectStatus().isOk()
                        .expectBody()
                        .jsonPath("$.replenishPerSecond").isEqualTo(1)
                        .jsonPath("$.burst").isEqualTo(1);

                String subject = "tight-" + UUID.randomUUID();
                String authorization = bearer(readToken(subject));
                List<Integer> statuses = java.util.stream.IntStream.range(0, 2)
                        .mapToObj(ignored -> client.get().uri("/api/orders/tight")
                                .header(HttpHeaders.AUTHORIZATION, authorization)
                                .exchange()
                                .expectBody(String.class)
                                .returnResult()
                                .getStatus()
                                .value())
                        .toList();
                assertThat(statuses).containsExactly(200, 429);
            } finally {
                client.post().uri("/api/console/routes/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .bodyValue(java.util.Map.of("replenishPerSecond", 1, "burst", 2))
                        .exchange()
                        .expectStatus().isOk();
            }
        }
    }

    private String readToken(String subject) {
        return token(subject, "orders.read", AUDIENCE, ISSUER, Instant.now().plusSeconds(300));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String token(String subject, String scope, String audience, String issuer, Instant expiresAt) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(subject)
                    .claim("scope", scope)
                    .expirationTime(Date.from(expiresAt));
            if (audience != null) {
                claims.audience(audience);
            }
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).type(JOSEObjectType.JWT).build(),
                    claims.build());
            jwt.sign(new RSASSASigner(signingKey.toRSAPrivateKey()));
            return jwt.serialize();
        } catch (Exception ex) {
            throw new IllegalStateException("Could not sign test token", ex);
        }
    }

    private static void startFixtures() throws Exception {
        if (signingKey != null) {
            return;
        }
        signingKey = new RSAKeyGenerator(2048).keyID("scenario-key").generate();
        String jwks = new JWKSet(signingKey.toPublicJWK()).toString();
        jwksServer = server("/jwks", jwks);
        ordersServer = server("/", null);
        customersServer = server("/", null);
    }

    private static HttpServer server(String path, String fixedBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            String body = fixedBody != null ? fixedBody : backendBody(exchange);
            writeJson(exchange, body);
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return server;
    }

    private static String backendBody(HttpExchange exchange) {
        String service = exchange.getLocalAddress().getPort() == ordersServer.getAddress().getPort() ? "orders" : "customers";
        return "{\"service\":\"" + service + "\",\"path\":\"" + exchange.getRequestURI().getPath() + "\"}";
    }

    private static void writeJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String base(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
