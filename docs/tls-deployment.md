# Direct TLS deployment

The gateway can terminate HTTPS itself. Direct TLS is **disabled by default**; existing local HTTP development remains unchanged.

## 1. Create or obtain a server certificate

For a local smoke environment, create a self-signed PKCS12 keystore:

```bash
mkdir -p secrets
keytool -genkeypair \
  -alias gateway \
  -keyalg RSA -keysize 3072 -validity 365 \
  -storetype PKCS12 \
  -keystore secrets/gateway-keystore.p12 \
  -dname "CN=localhost" \
  -ext "SAN=dns:localhost,ip:127.0.0.1"
```

Enter a strong password when prompted. Do not put the password on the command line, in shell history, in Git, or in an image layer. For production, use a certificate issued by your organization's approved CA and a managed secret store. The self-signed certificate above is only for local verification.

## 2. Configure secrets and paths

Set these values in the deployment environment (or inject them from a secret manager). The `.env.example` file contains non-secret placeholders:

- `GATEWAY_TLS_ENABLED=true`
- `GATEWAY_TLS_KEY_STORE_HOST_PATH=./secrets/gateway-keystore.p12`
- `GATEWAY_TLS_KEY_STORE=/run/secrets/gateway-keystore.p12`
- `GATEWAY_TLS_KEY_STORE_PASSWORD=<injected secret>`
- `GATEWAY_TLS_KEY_ALIAS=gateway`
- `GATEWAY_TLS_CLIENT_AUTH=none`

The keystore is mounted read-only by the optional Compose override. Ensure the host file is readable by the gateway container's runtime user. Never commit the `secrets/` directory, keystore, private key, or password.

## 3. Start the gateway with the TLS override

```bash
docker compose -f docker-compose.yml -f docker-compose.tls.yml up --build -d
docker compose ps
docker compose logs -f gateway
```

The host port remains `GATEWAY_PORT` (8080 by default), but the gateway now speaks HTTPS. Check health with the issuing CA certificate:

```bash
curl --cacert ./secrets/gateway-ca.pem https://localhost:8080/actuator/health
```

For a self-signed local certificate, export the certificate from the keystore and use it as the CA file for this local smoke check. Do not use `curl -k` or disable certificate validation in clients.

## Mutual TLS (optional)

Set `GATEWAY_TLS_CLIENT_AUTH=need` only when every client is expected to present a certificate. Configure `GATEWAY_TLS_TRUST_STORE`, `GATEWAY_TLS_TRUST_STORE_PASSWORD`, and `GATEWAY_TLS_TRUST_STORE_TYPE` with a truststore containing the approved client CA chain. Use `want` only when client certificates are optional. The default `none` does not request client certificates.

## Rotation and operations

Rotate certificates before expiry: issue the replacement, update the mounted keystore atomically, restart/roll the gateway, and verify the presented certificate and health endpoint. Monitor certificate expiry externally. Prefer TLS termination at a managed ingress when certificate automation, centralized policy, or multi-instance rotation is already provided there. Do not trust forwarded headers from arbitrary clients; configure trusted proxy handling only at the ingress boundary.
