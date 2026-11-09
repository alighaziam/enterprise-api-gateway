# WSO2 Identity Server 7.3 integration

The gateway acts as an OAuth 2.0 JWT Resource Server. WSO2 IS issues access tokens; the gateway validates their signature, issuer, expiry and (optionally) audience using the WSO2 public JWKS endpoint.

## 1. Confirm the issuer and JWKS endpoint

For a typical local installation the issuer and JWKS endpoint are often:

```text
Issuer: https://localhost:9443/oauth2/token
JWKS:   https://localhost:9443/oauth2/jwks
```

Do not assume these values: confirm the issuer from the JWT `iss` claim and your WSO2 IS 7.3 configuration. The gateway issuer validator requires an exact match. WSO2 documents `/oauth2/jwks` as the public signing-key endpoint used by resource servers.

## 2. Configure the WSO2 application

1. Create or open the OAuth2/OIDC application that obtains access tokens for this API.
2. Configure access tokens as JWTs (not opaque tokens).
3. Define the API scopes `orders.read` and `customers.read`.
4. Grant only the required scopes to each client.
5. If audience validation is enabled, configure the access-token audience to include the value selected for `WSO2_REQUIRED_AUDIENCE`.
6. Obtain an access token and verify that it contains `iss`, `exp`, `aud` (when required), and the expected scopes.

The gateway maps OAuth scopes to Spring Security authorities using the `SCOPE_` prefix. Therefore `orders.read` becomes `SCOPE_orders.read`. WSO2's default JWT `scope` claim is a space-separated string; retain that standard format unless you have explicitly tested a customized array format.

## 3. Configure the gateway

Copy `.env.example` to `.env` and update the WSO2 host and audience:

```bash
cp .env.example .env
```

The default JWKS path is `/oauth2/jwks`. If your WSO2 deployment uses a custom endpoint path, set `WSO2_JWKS_URI` to the actual public JWKS URL. Set `WSO2_ISSUER_URI` to exactly the token's `iss` claim. Set `WSO2_REQUIRED_AUDIENCE` when your API audience is configured.

The gateway fetches signing keys directly from the configured JWKS endpoint and validates issuer and timestamp claims locally. It does not need to call the OIDC discovery endpoint at startup.

## 4. TLS trust

Never disable certificate validation. For a private CA or self-signed development certificate, import the WSO2 server certificate/CA into a dedicated Java truststore and mount it read-only into the gateway container. Configure the JVM with:

```text
-Djavax.net.ssl.trustStore=/run/secrets/wso2-truststore.p12
-Djavax.net.ssl.trustStorePassword=<provided-through-secret-manager>
-Djavax.net.ssl.trustStoreType=PKCS12
```

Do not commit the truststore or its password. Pass JVM options through `JAVA_TOOL_OPTIONS` or configure the container's Java runtime appropriately.

## 5. Verify

From the gateway runtime network, confirm that the JWKS endpoint is reachable and returns a JSON object containing a `keys` array. Confirm the TLS certificate chain is trusted. Obtain a JWT access token and verify its signature, issuer, time claims, required audience (if configured), and scopes.

Then call:

```bash
curl -i http://localhost:8080/api/orders/123 \
  -H "Authorization: Bearer <access-token>"
```

A valid token with `orders.read` should reach the Orders backend. Missing/invalid tokens return 401; a valid token without the required scope returns 403; rate-limit exhaustion returns 429; an open circuit returns 503.

## Official WSO2 references

- [Validate JWT with JWKS](https://is.docs.wso2.com/en/7.0.0/guides/authentication/oidc/validate-jwt-with-jwks/)
- [WSO2 OAuth and JWKS endpoint configuration](https://is.docs.wso2.com/en/latest/references/configuration-catalog/)
- [Access token formats and validation](https://is.docs.wso2.com/en/latest/references/tokens/access-tokens/)
