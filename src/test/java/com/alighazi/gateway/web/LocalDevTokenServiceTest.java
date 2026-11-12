package com.alighazi.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Date;

import org.junit.jupiter.api.Test;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;

class LocalDevTokenServiceTest {

    @Test
    void issuesSignedTokenWithRequestedReadScopes() throws Exception {
        LocalDevTokenService service = new LocalDevTokenService("http://local-gateway", "gateway-api");

        LocalDevTokenService.DevToken token = service.issue("customers.read orders.read orders.read");
        SignedJWT jwt = SignedJWT.parse(token.accessToken());
        JWKSet jwks = JWKSet.parse(service.publicJwkSet());

        assertThat(token.tokenType()).isEqualTo("Bearer");
        assertThat(token.scope()).isEqualTo("customers.read orders.read");
        assertThat(jwt.verify(new RSASSAVerifier(jwks.getKeyByKeyId("local-dev").toRSAKey()))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("http://local-gateway");
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("gateway-api");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("scope")).isEqualTo("customers.read orders.read");
        assertThat(jwt.getJWTClaimsSet().getExpirationTime()).isAfter(new Date());
    }

    @Test
    void rejectsUnknownScopes() {
        assertThatThrownBy(() -> LocalDevTokenService.normalizeScopes("orders.write"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orders.write");
    }

    @Test
    void publicJwkSetDoesNotContainPrivateMaterial() throws Exception {
        LocalDevTokenService service = new LocalDevTokenService("http://local-gateway", "");
        String serialized = service.publicJwkSet().toString();

        assertThat(serialized).doesNotContain("PRIVATE");
        assertThat(JWKSet.parse(service.publicJwkSet()).getKeys()).allMatch(key -> key.isPrivate() == false);
    }
}
