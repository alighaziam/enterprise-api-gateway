# WSO2 Identity Server 7.3 integration

The gateway acts as an OAuth 2.0 JWT Resource Server. WSO2 IS issues access tokens; the gateway validates their signature, issuer, expiry and (optionally) audience using the provider's OpenID Connect metadata and JWKS endpoint.

## 1. Confirm the issuer

Open the WSO2 OIDC discovery document for your deployment and inspect its `issuer` and `jwks_uri` values. Depending on the configured discovery path, the metadata endpoint is commonly exposed under the issuer's `/.well-known/openid-configuration` path. Use the exact issuer value from the metadata and from the JWT `iss` claim.

For a typical local installation the issuer is often:

```text
https://localhost:9443/oauth2/token
```

The standard local JWKS endpoint is commonly:

```text
https://localhost:9443/oauth2/jwks
```

Do not assume either value: confirm the `issuer` and `jwks_uri` fields in your IS 7.3 metadata. WSO2 documents JWT access-token validation through the public JWKS endpoint and requires the issuer and audience claims to be checked by resource servers. The issuer URL configured in the gateway must exactly match the token's `iss` claim.

## 2. Configure the WSO2 application

1. Create or open the OAuth2/OIDC application that obtains access tokens for this API.
2. Configure access tokens as JWTs (not opaque tokens).
3. Define the API scopes `orders.read` and `customers.read`.
4. Grant only the required scopes to each client.
5. If audience validation is enabled, configure the access-token audience to include the value selected for `WSO2_REQUIRED_AUDIENCE`.
6. Obtain an access token and verify that it contains `iss`, `exp`, `aud` (when required), and the expected scopes.

The gateway maps OAuth scopes to Spring Security authorities using the `SCOPE_` prefix. Therefore `orders.read` becomes `SCOPE_orders.read`. WSO2's default JWT `scope` claim is a space-separated string; keep that standard format unless you have verified the converter behavior for a customized array format.

## 3. Configure the gateway

Set environment variables before starting the application:

```bash
export WSO2_ISSUER_URI="https://your-wso2-host:9443/oauth2/token"
export WSO2_REQUIRED_AUDIENCE="your-api-audience"
# Optional: set this to jwks_uri from the discovery document if discovery is non-standard.
export WSO2_JWKS_URI="https://your-wso2-host:9443/oauth2/jwks"
```

When `WSO2_JWKS_URI` is supplied, the gateway fetches keys directly from that endpoint but still validates the JWT issuer and timestamp claims.

Leave the audience empty only when the deployment intentionally does not enforce audience validation. For production, configure an explicit audience.

For Docker Compose, copy `.env.example` to `.env` and update the issuer. The hostname must resolve from inside the gateway container and the TLS certificate must be trusted by the JVM.

## 4. TLS trust

Never disable certificate validation. For a private CA or self-signed development certificate, import the WSO2 server certificate/CA into a dedicated Java truststore and mount it read-only into the gateway container. Configure the JVM with:

```text
-Djavax.net.ssl.trustStore=/run/secrets/wso2-truststore.p12
-Djavax.net.ssl.trustStorePassword=<provided-through-secret-manager>
-Djavax.net.ssl.trustStoreType=PKCS12
```

Do not commit the truststore or its password.

## 5. Verify

Fetch the discovery metadata from the gateway runtime network and confirm that:

- the metadata issuer exactly equals `WSO2_ISSUER_URI`;
- `jwks_uri` is reachable from the gateway;
- the TLS certificate chain is trusted;
- an access token has a valid signature, expected issuer, valid time claims, required audience (if configured), and the needed API scope.

Then call:

```bash
curl -i http://localhost:8080/api/orders/123 \
  -H "Authorization: Bearer <access-token>"
```

A valid token with `orders.read` should reach the Orders backend. Missing/invalid tokens return 401; a valid token without the required scope returns 403; rate-limit exhaustion returns 429; an open circuit returns 503.


## Official WSO2 references

- [Access tokens and JWT validation](https://is.docs.wso2.com/en/latest/references/tokens/access-tokens/)
- [Validate JWT with JWKS](https://is.docs.wso2.com/en/next/guides/authentication/oidc/validate-jwt-with-jwks/)
- [OIDC application settings](https://is.docs.wso2.com/en/latest/references/app-settings/oidc-settings-for-app/)
