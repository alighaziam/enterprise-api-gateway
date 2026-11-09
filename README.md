# Enterprise API Gateway

A reactive, security-first API Gateway built with Java 21, Spring Boot 4.1, and Spring Cloud Gateway 5. It validates WSO2 Identity Server 7.3 JWT access tokens and applies API scopes, Redis rate limits, circuit breakers, and correlation IDs before forwarding requests to backend services.

## Implemented capabilities

- Spring Cloud Gateway WebFlux routing for Orders and Customers APIs
- WSO2 JWT signature, issuer, expiry and optional audience validation using OIDC discovery/JWKS
- Scope-based authorization: `orders.read` and `customers.read`
- Redis-backed per-authenticated-principal rate limiting
- Resilience4j circuit breakers with JSON 503 fallback responses
- Bounded connection and response timeouts
- Correlation ID generation, validation and propagation
- Actuator health/info, Micrometer metrics and Prometheus endpoint
- Docker Compose environment with Redis and WireMock backend services
- GitHub Actions CI workflow running Maven verification

## Technology stack

| Component | Version |
|---|---|
| Java | 21 |
| Spring Boot | 4.1.2 |
| Spring Cloud | 2025.1.2 |
| Spring Cloud Gateway | 5.x (managed by Spring Cloud BOM) |
| WSO2 Identity Server | 7.3 (external identity provider) |
| Redis | 7.4 |
| Resilience4j | Managed by Spring Cloud |
| Maven | 3.9+ |

## Architecture

```text
Client
  |
  | HTTPS + Bearer JWT
  v
Spring Cloud Gateway
  |-- JWT signature / issuer / expiry / optional audience validation
  |-- OAuth scope authorization
  |-- Correlation ID
  |-- Redis rate limiting
  |-- Circuit breaker + timeouts
  |
  +---- /api/orders/** ----> Orders service
  |
  +---- /api/customers/** -> Customers service

WSO2 IS 7.3 supplies OIDC discovery metadata and signing keys.
Prometheus scrapes /actuator/prometheus.
```

See [Architecture decisions](docs/architecture.md), [WSO2 IS 7.3 setup](docs/WSO2-IS-7.3.md), and [Operations runbook](docs/runbook.md).

## Quick start

Requirements: JDK 21, Maven 3.9+, and Docker Compose v2.

1. Configure WSO2 IS 7.3 and the expected scopes. Follow [the WSO2 integration guide](docs/WSO2-IS-7.3.md).
2. Copy the environment template and set the actual issuer:

   ```bash
   cp .env.example .env
   ```

3. Build and run tests:

   ```bash
   mvn --batch-mode clean verify
   ```

4. Start Redis, WireMock backends, and the gateway:

   ```bash
   docker compose up --build
   ```

The gateway listens on http://localhost:8080. The WireMock backends are exposed on ports 8081 (Orders) and 8082 (Customers). The local backends are examples only; WSO2 remains an external dependency.

## API routes and authorization

| Gateway path | Backend path | Required OAuth scope | Default rate limit |
|---|---|---|---|
| `GET /api/orders/**` | `/orders/**` | `orders.read` | 20 requests/sec, burst 40 |
| `GET /api/customers/**` | `/customers/**` | `customers.read` | 30 requests/sec, burst 60 |

The route filters are configured per service. Rate limits are keyed by the authenticated principal. Adjust the replenish and burst values with the environment variables below.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `WSO2_ISSUER_URI` | `https://localhost:9443/oauth2/token` in app config | Exact JWT `iss` and OIDC issuer |
| `WSO2_REQUIRED_AUDIENCE` | empty | If set, required value in JWT `aud` |
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `ORDERS_SERVICE_URI` | `http://localhost:8081` | Orders backend |
| `CUSTOMERS_SERVICE_URI` | `http://localhost:8082` | Customers backend |
| `ORDERS_RATE_LIMIT_REPLENISH` | `20` | Orders tokens replenished per second |
| `ORDERS_RATE_LIMIT_BURST` | `40` | Orders bucket capacity |
| `CUSTOMERS_RATE_LIMIT_REPLENISH` | `30` | Customers tokens replenished per second |
| `CUSTOMERS_RATE_LIMIT_BURST` | `60` | Customers bucket capacity |

**Important:** the issuer configured in the gateway must exactly match the `iss` claim in WSO2 access tokens. In Docker, the issuer hostname must also resolve from the gateway container, and the JVM must trust its TLS certificate. Do not disable TLS verification.

## Operational endpoints

- `GET /actuator/health`
- `GET /actuator/info`
- `GET /actuator/prometheus`

Only the listed health/info/Prometheus endpoints are publicly permitted by the security configuration. Restrict actuator exposure further in production.

## Example request

```bash
curl -i http://localhost:8080/api/orders/123 \
  -H "Authorization: Bearer <access-token>" \
  -H "X-Correlation-ID: demo-request-001"
```

Expected behavior:
- 200 when the JWT is valid and contains `orders.read`.
- 401 for missing/invalid/expired tokens.
- 403 when the token is valid but lacks the route scope.
- 429 when the Redis rate limit is exceeded.
- 503 when the downstream circuit breaker is open.

## Verification

Run all local unit tests and package verification:

```bash
mvn --batch-mode clean verify
```

GitHub Actions runs the same command for pushes and pull requests targeting `main`. The workflow status is the authoritative CI result; a successful file commit alone does not mean the build passed.

## Production readiness

This repository provides an implementation baseline, not a claim of production certification. Before production deployment:

1. Validate the actual WSO2 IS 7.3 issuer, JWKS reachability, token claims, scopes and audience.
2. Install the WSO2 CA certificate in the JVM truststore.
3. Configure Redis authentication/TLS, persistence, HA and network restrictions.
4. Put the gateway behind TLS termination and configure trusted proxy headers.
5. Restrict management endpoints to an internal network.
6. Set rate limits and circuit-breaker thresholds from load-test results.
7. Add distributed tracing, centralized logs, dashboards and alerting.
8. Run load, failure-mode, security and penetration tests.
9. Define service-specific write scopes and method-level policies before exposing write operations.
10. Pin container images by digest and scan dependencies/images in CI.

## License

To be decided.
