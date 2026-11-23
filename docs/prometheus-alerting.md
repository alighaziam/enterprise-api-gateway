# Gateway metrics and alerting

## Authenticated scrape

The gateway exposes Prometheus metrics at:

    GET /actuator/prometheus

The endpoint, /actuator/metrics, and Gateway diagnostics require the
gateway.admin OAuth scope. Only /actuator/health and /actuator/info are
public; all other actuator endpoints are denied unless explicitly authorized.
Use a dedicated WSO2 machine identity for Prometheus, grant only gateway.admin,
rotate its short-lived token, and mount it as a read-only secret. Never store
the token in Git, a container image, or a plain-text deployment manifest.

The sample scrape configuration is deploy/prometheus/prometheus.yml. It
expects a private DNS target named gateway:8080 and a token file at
/etc/prometheus/secrets/gateway-admin-token. Adapt the target to the private
service address in your deployment and mount the token file at the configured
path. Keep Prometheus and the gateway on a private network; if TLS is
terminated at an ingress, scrape the HTTPS endpoint and configure its CA
rather than disabling certificate validation.

## Route outcome metric

The gateway exports the bounded-cardinality counter:

    gateway_route_requests_total{outcome="success|limited|consumer|provider|operator",status_class="2xx|3xx|4xx|5xx|other"}

The metric intentionally does not include service IDs, caller identities,
URLs, raw status codes, or other high-cardinality/sensitive values. It is an
aggregate view across managed routes.

## Alerts

Rules are in deploy/prometheus/gateway-alerts.yml.

| Alert | Default condition | Meaning |
|---|---|---|
| GatewayProviderFailureRatioHigh | provider failures > 5% for 5m | Downstream/provider failures are elevated. |
| GatewayRoute5xxRateHigh | > 0.5 5xx responses/sec for 3m | Gateway or downstream services are returning sustained server errors. |
| GatewayRateLimitPressure | > 1 limited request/sec for 10m | Review client traffic, tenant limits, and rate policy. |
| GatewayMetricsScrapeUnavailable | scrape target down for 2m | Check gateway health, network reachability, token expiry, and scope. |

Thresholds are starting points, not universal capacity limits. Tune them with
measured traffic and service-level objectives. A rate-limit alert is an
operational signal, not automatically an outage.

## Local validation

The CI workflow validates both files with Prometheus promtool. To run the
same checks locally:

    mkdir -p deploy/prometheus/secrets
    printf 'local-placeholder' > deploy/prometheus/secrets/gateway-admin-token
    docker run --rm --entrypoint=/bin/promtool -v "$PWD/deploy/prometheus:/etc/prometheus:ro" prom/prometheus:v3.5.0 check config /etc/prometheus/prometheus.yml
    docker run --rm --entrypoint=/bin/promtool -v "$PWD/deploy/prometheus:/etc/prometheus:ro" prom/prometheus:v3.5.0 check rules /etc/prometheus/gateway-alerts.yml

The placeholder is only for syntax validation. Replace it with a real,
short-lived WSO2 bearer token at runtime; do not commit it.