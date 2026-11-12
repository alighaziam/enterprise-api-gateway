package com.alighazi.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers("/actuator/health", "/actuator/info").permitAll()
                        .pathMatchers("/fallback/**").denyAll()
                        .pathMatchers(HttpMethod.GET, "/api/orders/**").hasAuthority("SCOPE_orders.read")
                        .pathMatchers(HttpMethod.GET, "/api/customers/**").hasAuthority("SCOPE_customers.read")
                        .pathMatchers("/api/orders/**", "/api/customers/**").denyAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
                .build();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${security.jwt.jwk-set-uri:}") String jwkSetUri,
            @Value("${security.jwt.audience:}") String requiredAudience) {

        NimbusReactiveJwtDecoder decoder = jwkSetUri == null || jwkSetUri.isBlank()
                ? NimbusReactiveJwtDecoder.withIssuerLocation(issuerUri).build()
                : NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuerUri);

        if (requiredAudience == null || requiredAudience.isBlank()) {
            decoder.setJwtValidator(issuerValidator);
        } else {
            OAuth2TokenValidator<Jwt> audienceValidator = jwt -> {
                var audience = jwt.getAudience();
                return audience != null && audience.contains(requiredAudience)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(
                                new OAuth2Error("invalid_token", "Required audience is missing", null));
            };
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator));
        }
        return decoder;
    }
}