# Enterprise API Gateway

A reactive, security-first API Gateway built with Java 21, Spring Boot 3.5, and Spring Cloud Gateway. It validates WSO2 Identity Server 7.3 JWT access tokens and applies API scopes, Redis rate limits, circuit breakers, and correlation IDs before forwarding requests to backend services.

## Implemented capabilities

- Spring Cloud Gateway WebFlux routing for Orders and Customers APIs
- WSO2 JWT signature, issuer, expiry and optional audience validation using the configured JWKS endpoint
- Scope-based authorization: `orders.read` and `customers.read`
- Redis-backed per-authenticated-principal rate limiting
- Resilience4j circuit breakers with JSON 503 fallback responses
- Bounded connection and response timeouts
- Correlation ID generation before Spring Security, including 401/403 responses
- Actuator health/info, Micrometer metrics and Prometheus endpoint
- Docker Compose environment with Redis and WireMock backend services
- GitHub Actions CI workflow running Maven verification, Compose validation and Docker build

## Technology stack

| Component | Version |
|---|---|
| Java | 21 |
| Spring Boot | 3.5.5 |
| Spring Cloud | 2025.0.0 |
| Spring Cloud Gateway | 4.3.0 (managed by Spring Cloud BOM) |
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
Correlation ID WebFilter
  |
Spring Security
  |-- JWT signature / issuer / expiry / optional audience validation
  |-- OAuth scope authorization
  |
Spring Cloud Gateway
  |-- Redis rate limiting
  |-- Circuit breaker + timeouts
  |
  +---- /api/orders/** ----> Orders service
  |
  +---- /api/customers/** -> Customers service

WSO2 IS 7.3 supplies signing keys from /oauth2/jwks.
Prometheus scrapes /actuator/prometheus (authenticated).
```

See [Architecture decisions](docs/architecture.md), [WSO2 IS 7.3 setup](docs/WSO2-IS-7.3.md), and [Operations runbook](docs/runbook.md).

## Quick start

Requirements: JDK 21, Maven 3.9+, and Docker Compose v2.

1. Configure WSO2 IS 7.3 and the expected scopes. Follow [the WSO2 integration guide](docs/WSO2-IS-7.3.md).
2. Copy the environment template and set the actual issuer/JWKS host and optional audience:

   ```bash
   cp .env.example .env
   ```

3. Build and run tests:

   ```bash
   mvn --batch-mode clean verify
   ```

4. Start Redis, the sample backends, and the gateway:

   ```bash
   docker compose up -d --build
   ```

   Stop the stack:

   ```bash
   docker compose down
   ```

The gateway listens on http://localhost:8080 unless `GATEWAY_PORT` in `.env` says otherwise. Sample backends are on ports 8081 (Orders) and 8082 (Customers). If port 8080 is already taken, set `GATEWAY_PORT=8090` before `docker compose up`. When Docker Hub cannot provide `eclipse-temurin:21-jre`, set `GATEWAY_DOCKERFILE=Dockerfile.offline` and put `docker/jre/openjdk-21.0.2_linux-x64_bin.tar.gz` in the project; that archive is not committed. Inside Compose the process still listens on port 8080, so `WSO2_JWKS_URI` stays `http://127.0.0.1:8080/jwks` even when the published host port is 8090.

Local console and Swagger are served by the gateway at `/` and `/swagger-ui.html`. All `/api/console/**` operations require the `gateway.admin` OAuth scope; the token-mint endpoint is available only when local development tokens are enabled and accepts requests from loopback clients only. The console sends the entered bearer token for status and route-management operations. Set `LOCAL_DEV_TOKENS=true` together with `WSO2_ISSUER_URI=http://local-gateway` and `WSO2_JWKS_URI` pointed at the gateway itself (`http://127.0.0.1:8080/jwks`, or `http://127.0.0.1:8090/jwks` when the gateway is on 8090) when you want the console to mint a short-lived read token. Leave that flag unset when the gateway must accept only WSO2 tokens. Never enable local token minting on a network-accessible deployment.

## API routes and authorization

| Gateway path | Backend path | Required OAuth scope | Default rate limit |
|---|---|---|---|
| `GET /api/orders/**` | `/orders/**` | `orders.read` | 20 requests/sec, burst 40 |
| `GET /api/customers/**` | `/customers/**` | `customers.read` | 30 requests/sec, burst 60 |

The route filters are configured per service. Rate limits are keyed by the authenticated principal. Adjust the replenish and burst values with the environment variables below.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `WSO2_ISSUER_URI` | `https://localhost:9443/oauth2/token` in app config | Exact JWT `iss` claim and configured issuer |
| `WSO2_JWKS_URI` | `https://localhost:9443/oauth2/jwks` in app config | WSO2 signing-key endpoint; override for custom paths |
| `WSO2_REQUIRED_AUDIENCE` | empty | If set, required value in JWT `aud` |
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `ORDERS_SERVICE_URI` | `http://localhost:8081` | Orders backend |
| `CUSTOMERS_SERVICE_URI` | `http://localhost:8082` | Customers backend |
| `ORDERS_RATE_LIMIT_REPLENISH` | `20` | Orders tokens replenished per second |
| `ORDERS_RATE_LIMIT_BURST` | `40` | Orders bucket capacity |
| `CUSTOMERS_RATE_LIMIT_REPLENISH` | `30` | Customers tokens replenished per second |
| `CUSTOMERS_RATE_LIMIT_BURST` | `60` | Customers bucket capacity |

**Important:** the issuer configured in the gateway must exactly match the `iss` claim in WSO2 access tokens. The configured JWKS endpoint must be reachable from the gateway. In Docker, the WSO2 hostname must resolve from the gateway container, and the JVM must trust its TLS certificate. Do not disable TLS verification.

## Operational endpoints

- `GET /actuator/health`
- `GET /actuator/info`
- `GET /actuator/prometheus`

Health and info are public. Prometheus, metrics, gateway diagnostics and other actuator endpoints require authentication. Restrict actuator exposure to an internal network in production.

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

GitHub Actions runs the same command for pushes and pull requests targeting `main`, validates Compose, and builds the container image. The workflow status is the authoritative CI result; a successful file commit alone does not mean the build passed.

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
