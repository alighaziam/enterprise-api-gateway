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

## Direct TLS deployment\n\nDirect HTTPS is optional and disabled by default. Follow [TLS deployment](tls-deployment.md), keep the PKCS12 keystore outside the repository, mount it read-only with `docker-compose.tls.yml`, and inject passwords through the deployment secret manager. Validate certificate expiry and rotation before rollout. `GATEWAY_TLS_CLIENT_AUTH=need` enforces mutual TLS and must only be used when clients present certificates trusted by the configured truststore.\n\n## Prometheus metrics and alerts

The Prometheus endpoint and Gateway diagnostics require a WSO2 token with the
`gateway.admin` scope. Configure a dedicated machine identity and mount its
short-lived bearer token read-only at the path configured in
`deploy/prometheus/prometheus.yml`. The sample alert rules are in
`deploy/prometheus/gateway-alerts.yml`; see [Prometheus metrics and alerting](prometheus-alerting.md)
for thresholds, validation commands, and the operator response guidance.

## Production caution

Do not expose actuator endpoints publicly, disable TLS validation, commit secrets, or use the local WireMock services in production. Tune rate limits and circuit breakers through load tests before production rollout.


## Operator route audit

Route create/update/delete actions are recorded in Redis. Query the newest events with an operator token containing `gateway.admin`:

```bash
curl -i "http://localhost:8080/api/console/audit?limit=50" \
  -H "Authorization: Bearer <gateway-admin-token>"
```

The endpoint accepts limits from 1 to 100 and returns newest first. Redis retains at most 200 entries. Records include actor subject, action, service ID, outcome, bounded reason code, correlation ID and timestamp; backend URLs, bearer tokens and raw exception text are intentionally excluded. If the audit store is unavailable, route management continues and the gateway logs a bounded warning; alert on those warnings because the audit trail may have a gap.
