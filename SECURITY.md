# Security Policy

## Reporting a vulnerability

Do not open a public issue for a suspected security vulnerability. Contact the repository maintainers privately through GitHub Security Advisories.

## Security controls in this project

- JWT signature and issuer validation using WSO2 Identity Server's OIDC metadata and JWK set.
- Optional audience validation through `WSO2_REQUIRED_AUDIENCE`.
- Scope-based authorization for Orders and Customers APIs.
- Redis-backed per-principal request rate limiting.
- Circuit breakers and bounded connection/response timeouts.
- Correlation ID validation before forwarding request headers.

## Production deployment checklist

- Use a publicly resolvable HTTPS issuer that exactly matches the JWT `iss` claim.
- Import the WSO2 signing/discovery TLS CA into the gateway JVM truststore. Never disable TLS verification.
- Set `WSO2_REQUIRED_AUDIENCE` to the audience expected by this API.
- Configure WSO2 scopes `orders.read` and `customers.read` and grant only required scopes to clients.
- Protect Redis with authentication, network policies, and TLS where supported.
- Keep secrets in a secret manager; do not commit client secrets, private keys, or truststores.
- Restrict actuator exposure and place management endpoints on a private network.
- Configure production rate limits based on measured traffic and tenant requirements.
- Set backend allowlists, ingress controls, and resource limits.
- Review dependency and container image updates regularly.
