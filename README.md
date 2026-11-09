# Enterprise API Gateway

An enterprise API Gateway built with Java 21, Spring Boot 4, and Spring Cloud Gateway. WSO2 Identity Server 7.3 is the intended OAuth 2.0 / OpenID Connect identity provider.

## Current implementation

- Reactive Spring Cloud Gateway application
- OAuth 2.0 JWT resource-server security
- Public health, info, and Prometheus actuator endpoints
- Configurable routing for Orders and Customers services
- Correlation ID generation and propagation
- Redis service in the local Docker Compose environment
- Resilience4j circuit-breaker configuration baseline
- Dockerfile and GitHub Actions Maven CI workflow
- Unit tests for correlation ID behavior

## Technology

- Java 21
- Spring Boot 4.0.1
- Spring Cloud 2025.1.0
- Spring Security OAuth2 Resource Server
- Redis
- Resilience4j
- Micrometer / Prometheus
- Docker Compose
- Maven

## Run locally

Requirements: JDK 21, Maven 3.9+, and Docker Compose.

```bash
mvn clean verify
docker compose up --build
```

The gateway listens on http://localhost:8080.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `WSO2_ISSUER_URI` | `http://localhost:9443/oauth2/token` | JWT issuer configured in WSO2 IS |
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `ORDERS_SERVICE_URI` | `http://localhost:8081` | Orders backend |
| `CUSTOMERS_SERVICE_URI` | `http://localhost:8082` | Customers backend |

Set `WSO2_ISSUER_URI` to the exact issuer value present in access tokens issued by your WSO2 deployment. Ensure the gateway can reach the WSO2 discovery/JWK endpoints and trusts its TLS certificate.

## Routes

- `/api/orders/**` → Orders backend (the `/api` prefix is stripped)
- `/api/customers/**` → Customers backend (the `/api` prefix is stripped)

Both routes require a valid bearer token. Health, info, and Prometheus endpoints are public.

## Observability

- `GET /actuator/health`
- `GET /actuator/info`
- `GET /actuator/prometheus`

The gateway generates an `X-Correlation-ID` when one is not supplied and propagates it to the downstream service and response.

## Verification status

The source files and CI workflow have been committed. The Maven build has not yet been executed in this environment; the GitHub Actions workflow will run `mvn verify` on pushes and pull requests to `main`.

## Next engineering steps

1. Verify WSO2 issuer and audience validation against a real IS 7.3 tenant.
2. Add Redis-backed gateway rate limiting with per-route policies.
3. Add downstream service timeouts, retries, and circuit-breaker filters.
4. Add integration tests with WireMock and Testcontainers.
5. Add TLS, secrets management, and production deployment profiles.

## License

To be decided.
