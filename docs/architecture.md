# Architecture and design decisions

## Context

The gateway is the single ingress layer for internal Orders and Customers APIs. WSO2 Identity Server 7.3 remains the authorization server; the gateway is a JWT resource server and does not handle user login or issue tokens.

## Request flow

1. Client obtains a JWT access token from WSO2 IS using an approved OAuth 2.0 flow.
2. The correlation-ID WebFilter validates or generates `X-Correlation-ID` before Spring Security runs, including for 401/403 responses.
3. Spring Security validates the JWT signature using WSO2's configured public JWKS endpoint and validates issuer/time claims. Audience validation is enabled when `WSO2_REQUIRED_AUDIENCE` is non-empty.
4. Route-specific authorization checks the required scope.
5. Redis rate limiter enforces a per-principal quota.
6. Circuit breaker protects the downstream service.
7. Gateway forwards the request and propagates the correlation ID.
8. The gateway returns the downstream response or a consistent 503 fallback.

## Design decisions

### Reactive gateway

Spring Cloud Gateway WebFlux is selected because the gateway is I/O-bound and needs non-blocking proxying. Do not add Spring MVC or servlet-only dependencies to this application.

### JWT validation instead of token introspection

JWT validation avoids a synchronous introspection call for every request. This requires WSO2 to issue signed JWT access tokens and the gateway to trust the configured JWKS endpoint's TLS certificate. If opaque tokens are required, introduce a separately designed reactive opaque-token introspection path.

### Authorization scopes

The current API policies are:
- `GET /api/orders/**` requires `orders.read`.
- `GET /api/customers/**` requires `customers.read`.
- Other methods on these API paths are denied until dedicated write scopes and policies are implemented.

Spring maps OAuth scopes to `SCOPE_orders.read` and `SCOPE_customers.read`.

### Rate limiting

Redis token buckets are keyed by authenticated principal. This prevents a caller from bypassing quotas by changing an arbitrary client-supplied header. For multi-tenant deployments, consider a composite tenant + client identity key and define explicit policies for service accounts.

### Resilience

Circuit breakers are isolated per downstream service. The initial defaults are conservative placeholders and must be tuned with measured latency/error profiles. The gateway uses a 3-second connection timeout and 10-second response timeout.

### Correlation IDs

Only IDs matching `[A-Za-z0-9._-]{1,128}` are accepted. Invalid or absent values are replaced with a UUID. The value is forwarded to the backend and returned to the caller. The WebFilter runs before Spring Security so rejected requests also carry the identifier.

## Boundaries and non-goals

- The gateway does not own business logic, persistence, or domain orchestration.
- The gateway does not replace WSO2 identity lifecycle, consent, or client registration.
- WireMock containers are local development fixtures, not production services.
- Production TLS, Redis HA, secrets management, deployment manifests, tracing and alerting are environment responsibilities.
