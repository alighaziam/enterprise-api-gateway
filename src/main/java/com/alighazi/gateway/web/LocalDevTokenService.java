package com.alighazi.gateway.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

@Service
public class LocalDevTokenService {

    static final List<String> ALLOWED_SCOPES = List.of("orders.read", "customers.read");
    private static final Duration TTL = Duration.ofMinutes(15);

    private final RSAKey signingKey;
    private final String issuer;
    private final String audience;

    public LocalDevTokenService(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${security.jwt.audience:}") String audience) throws Exception {
        this.signingKey = new RSAKeyGenerator(2048).keyID("local-dev").generate();
        this.issuer = issuer;
        this.audience = audience == null ? "" : audience.trim();
    }

    public DevToken issue(String requestedScopes) {
        String scope = normalizeScopes(requestedScopes);
        Instant expiresAt = Instant.now().plus(TTL);
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject("local-console")
                    .claim("scope", scope)
                    .issueTime(new Date())
                    .expirationTime(Date.from(expiresAt));
            if (!audience.isBlank()) {
                claims.audience(audience);
            }
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).type(JOSEObjectType.JWT).build(),
                    claims.build());
            jwt.sign(new RSASSASigner(signingKey.toRSAPrivateKey()));
            return new DevToken(jwt.serialize(), "Bearer", TTL.toSeconds(), scope);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not sign local development token", ex);
        }
    }

    public Map<String, Object> publicJwkSet() {
        return new JWKSet(signingKey.toPublicJWK()).toJSONObject();
    }

    static String normalizeScopes(String requestedScopes) {
        if (requestedScopes == null || requestedScopes.isBlank()) {
            throw new IllegalArgumentException("At least one scope is required");
        }
        Set<String> scopes = new LinkedHashSet<>();
        for (String scope : requestedScopes.trim().split("\\s+")) {
            if (!ALLOWED_SCOPES.contains(scope)) {
                throw new IllegalArgumentException("Unsupported scope: " + scope);
            }
            scopes.add(scope);
        }
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("At least one scope is required");
        }
        return String.join(" ", scopes);
    }

    public record DevToken(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresIn,
            String scope) {
    }
}
