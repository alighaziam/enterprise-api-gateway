# Enterprise API Gateway

An enterprise-grade API Gateway built with Java 21, Spring Boot, and Spring Cloud Gateway, integrated with WSO2 Identity Server 7.3 for OAuth 2.0 / OpenID Connect authentication.

## Architecture goals

- Centralized API routing and JWT validation
- WSO2 IS 7.3 as the identity provider
- Redis-backed rate limiting
- Resilience patterns with circuit breakers and timeouts
- Correlation IDs and structured observability
- Prometheus metrics
- Docker-based local development and integration testing

## Planned technology stack

- Java 21
- Spring Boot 4.x
- Spring Cloud Gateway
- Spring Security OAuth2 Resource Server
- Redis
- Resilience4j
- Micrometer / Prometheus
- Docker Compose
- WireMock

## Project status

Initial repository setup. Implementation and automated verification are tracked through subsequent commits.

## License

To be decided.
