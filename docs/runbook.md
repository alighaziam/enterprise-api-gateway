# Operations runbook

## Local prerequisites

- JDK 21
- Maven 3.9+
- Docker Engine and Docker Compose v2
- Reachable WSO2 IS 7.3 with JWT access-token support

## Start

```bash
cp .env.example .env
# Edit .env and set the real WSO2 issuer and optional audience.
mvn --batch-mode clean verify
docker compose up --build -d
docker compose ps
docker compose logs -f gateway
```

## Smoke checks

Health:

```bash
curl -i http://localhost:8080/actuator/health
```

WireMock backends directly:

```bash
curl -i http://localhost:8081/orders/123
curl -i http://localhost:8082/customers/123
```

Gateway routes require a real access token issued by WSO2 with the appropriate API scope:

```bash
curl -i http://localhost:8080/api/orders/123 \
  -H "Authorization: Bearer <token>" \
  -H "X-Correlation-ID: local-smoke-001"

curl -i http://localhost:8080/api/customers/123 \
  -H "Authorization: Bearer <token>"
```

## Stop

```bash
docker compose down
```

To remove local Redis data as well:

```bash
docker compose down -v
```

## Troubleshooting

### 401 Unauthorized

- Confirm the request has an access token, not an ID token.
- Check token signature, expiration and `iss`.
- Confirm `WSO2_ISSUER_URI` exactly equals the token's `iss` and the discovery metadata issuer.
- Confirm the gateway can reach discovery metadata and the JWKS URL.
- Confirm the WSO2 CA is trusted by the gateway JVM.

### 403 Forbidden

- The token is valid but does not contain the route's required scope.
- Check WSO2 application scope configuration and token claims.
- Orders requires `orders.read`; Customers requires `customers.read`.

### 429 Too Many Requests

- Inspect Redis health and gateway logs.
- Check the route's replenish and burst settings.
- Redis rate limiting is keyed by authenticated principal.

### 503 Service Unavailable

- Check WireMock/backend health and configured backend URI.
- Inspect circuit-breaker state and downstream latency/error rates.
- Wait for the circuit breaker open-state duration or restart the affected backend.

### Gateway fails to start

- Confirm Redis is healthy.
- Check the application logs for invalid YAML or unresolved environment variables.
- Confirm the configured issuer is syntactically valid. Ensure the gateway container can resolve its hostname and trust the certificate.

## Production caution

Do not expose actuator endpoints publicly, disable TLS validation, commit secrets, or use the local WireMock services in production. Tune rate limits and circuit breakers through load tests before production rollout.
