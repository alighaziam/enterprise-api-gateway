package com.alighazi.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AudienceValidatorTest {

    private final AudienceValidator validator = new AudienceValidator("gateway-api");

    @Test
    void acceptsTokenThatContainsRequiredAudience() {
        Jwt jwt = jwtWithAudience(List.of("gateway-api", "other"));

        assertThat(validator.validate(jwt).hasErrors()).isFalse();
    }

    @Test
    void rejectsTokenWithoutAudienceClaim() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("client")
                .build();

        assertThat(validator.validate(jwt).getErrors())
                .extracting(error -> error.getDescription())
                .containsExactly("Required audience is missing");
    }

    @Test
    void rejectsTokenWithDifferentAudience() {
        assertThat(validator.validate(jwtWithAudience(List.of("other-api"))).hasErrors()).isTrue();
    }

    private static Jwt jwtWithAudience(List<String> audience) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("client")
                .audience(audience)
                .build();
    }
}
