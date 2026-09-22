# 17 — Keycloak in Production Mode (HTTPS, Postgres, Optimized Image)

**First, a common misunderstanding.** Tokens issued by `start-dev` are **real tokens**: RS256-signed JWTs with the same claims, keys and validation rules as in production. What dev mode changes is the *infrastructure around them*:

| Aspect | `start-dev` (files 03–16) | `start` (production mode, this file) |
|---|---|---|
| Transport | Plain HTTP allowed | HTTPS required; HTTP only behind a trusted proxy (`http-enabled` + `proxy-headers`) |
| Hostname | Loose; derived from the request | Must be configured (`KC_HOSTNAME`), otherwise startup fails |
| Database | Embedded H2 **inside the container** (lost on container re-create) | External database (Postgres), survives restarts, re-creates and upgrades |
| Startup | Re-builds its configuration on each start | Build once (`kc.sh build`), then `start --optimized`: faster, reproducible |
| Caches / themes | Dev-friendly (no theme caching) | Production caching; clustering-capable (Infinispan) |
| Realm `sslRequired` | `none` in our realm | `external`: HTTPS for every non-private client |
| Admin account | `admin/admin` | A temporary bootstrap admin from secrets; replace it with a real admin |

If `scripts/token.sh` returns no token, production mode won't fix that. The script prints the actual reason; see `14-troubleshooting.md` → *Getting a token*.

---

## Target topology

```
 phone / browser / curl                                   services (gateway, order-svc, ...)
        │ https://localhost:8443  (issuer)                       │ http://keycloak:8180  (backchannel:
        ▼                                                        │  JWKS keys, client-credentials tokens)
 NGINX :8443  ── TLS termination, X-Forwarded-* headers ──┐      │
                                                          ▼      ▼
                                            Keycloak (start --optimized, proxy-headers=xforwarded)
                                                          │
                                                   Postgres (database "keycloak")
```

- **TLS terminates at NGINX** (the "edge"), the same pattern as a cloud load balancer. Keycloak speaks HTTP inside the private network and trusts `X-Forwarded-*` **only from that proxy**.
- The **issuer** (`iss` claim) becomes `https://localhost:8443/realms/quickbite`. The backchannel URLs used by services (`http://keycloak:8180/...`) stay the same: the frontend/backchannel split from file 01.
- Keycloak's HTTP port is **not** exposed to your LAN. It's bound to `127.0.0.1` only so that IDE-run services can still fetch keys.

> **Why proxy headers are dangerous without a proxy:** if clients could reach Keycloak directly, they could forge `X-Forwarded-Host` and make Keycloak generate password-reset links to an attacker's domain. Enable `proxy-headers` only when the proxy is the sole path in.

---

## Step 1: A locally trusted TLS certificate (mkcert)

```bash
# Ubuntu: sudo apt install libnss3-tools && download mkcert from GitHub releases; macOS: brew install mkcert
mkcert -install                          # creates a local CA and trusts it (system store, browsers, curl)
mkdir -p deploy/nginx/certs
mkcert -cert-file deploy/nginx/certs/localhost.pem -key-file deploy/nginx/certs/localhost-key.pem \
       localhost 127.0.0.1 ::1
echo "deploy/nginx/certs/" >> .gitignore  # private keys never go to git
```

In real production you'd use a certificate from a public CA (Let's Encrypt via certbot or cert-manager). Everything else in this file stays the same.

## Step 2: A database for Keycloak

For a fresh setup, add this line to `deploy/compose/postgres/init-dbs.sql`:

```sql
create database keycloak;
```

Init scripts only run when the Postgres volume is **first** created. If yours already exists, create the database once by hand:

```bash
docker compose -f deploy/compose/docker-compose.yml exec postgres psql -U quickbite -c "create database keycloak;"
```

> In production Keycloak gets **its own** database user with rights only on that database. Sharing the `quickbite` user is a local shortcut.

## Step 3: An optimized Keycloak image

`deploy/keycloak/Dockerfile`

```dockerfile
# Stage 1: bake the BUILD-TIME options into the server (database vendor, health, metrics).
FROM quay.io/keycloak/keycloak:26.4.0 AS builder
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true
RUN /opt/keycloak/bin/kc.sh build

# Stage 2: the runtime image, started with --optimized (skips the build step at every start)
FROM quay.io/keycloak/keycloak:26.4.0
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
```

**Build-time vs runtime options:** options like the DB vendor, health and metrics are compiled into the server by `kc.sh build`. Hostname, DB URL and credentials are read at every start. Changing a build-time option means rebuilding the image; with `--optimized`, Keycloak refuses to start if they don't match.

## Step 4: A production realm file and secrets

Create a production variant of the realm. It requires HTTPS for external clients, and it takes the client secret from an environment variable instead of the JSON.

```bash
mkdir -p deploy/keycloak/prod
jq '.sslRequired = "external"
    | (.clients[] | select(.clientId == "order-svc") | .secret) = "${ORDER_SVC_SECRET}"' \
   deploy/keycloak/quickbite-realm.json > deploy/keycloak/prod/quickbite-realm.json
```

Keycloak resolves `${ORDER_SVC_SECRET}` from its environment during import, so no secret is committed to git.

> **In real production also delete the `cli-test` client:** `del(.clients[] | select(.clientId == "cli-test"))`. The password grant is removed in OAuth 2.1, and every login should go through the browser with PKCE. Keep it locally, or `token.sh`, e2e and k6 stop working.

`deploy/compose/.env.keycloak`: **never commit this file** (`echo "deploy/compose/.env.keycloak" >> .gitignore`).

```bash
# Temporary admin used only to create a real admin account (Step 7)
KC_BOOTSTRAP_ADMIN_USERNAME=bootstrap-admin
KC_BOOTSTRAP_ADMIN_PASSWORD=REPLACE_ME   # paste the output of: openssl rand -base64 24
KC_DB_PASSWORD=quickbite
# Must match what order-svc uses (ORDER_SVC_SECRET env var; default "order-svc-secret")
ORDER_SVC_SECRET=order-svc-secret
```

`env_file` doesn't run shell commands, so paste literal values.

## Step 5: NGINX terminates TLS for Keycloak

`deploy/nginx/keycloak-tls.conf.template`

```nginx
# HTTPS front door for Keycloak. Keycloak itself speaks plain HTTP on the private Docker network.
server {
    listen 8443 ssl;
    http2 on;
    server_name localhost;

    ssl_certificate     /etc/nginx/certs/localhost.pem;
    ssl_certificate_key /etc/nginx/certs/localhost-key.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;
    add_header Strict-Transport-Security "max-age=31536000" always;

    # Keycloak sends large cookies/headers (tokens, session state): default buffers are too small
    proxy_buffer_size   128k;
    proxy_buffers       4 256k;

    # Production: expose the admin console on a separate, internal-only hostname (KC_HOSTNAME_ADMIN)
    # and block it here. Kept open locally for learning.
    # location /admin/ { deny all; }

    location / {
        proxy_pass http://keycloak:8180;
        proxy_set_header Host              $host;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;       # overwrite, never pass through client values
        proxy_set_header X-Forwarded-Host  $host;
        proxy_set_header X-Forwarded-Port  8443;
    }
}
```

## Step 6: The Compose override

`deploy/compose/docker-compose.keycloak-prod.yml`

```yaml
# Layer on top of docker-compose.yml (and docker-compose.apps.yml if you run the services in Docker).
# !override needs Docker Compose v2.24+ (docker compose version).
services:
  keycloak:
    build: { context: ../keycloak, dockerfile: Dockerfile }
    image: quickbite/keycloak:26.4.0-optimized
    command: ["start", "--optimized", "--import-realm"]
    env_file: [.env.keycloak]
    environment: !override
      KC_HTTP_ENABLED: "true"                        # HTTP only because NGINX terminates TLS in front
      KC_HTTP_PORT: "8180"                           # keep the same internal port -> services' backchannel URLs unchanged
      KC_HOSTNAME: https://localhost:8443            # PUBLIC url -> the new "iss"
      KC_HOSTNAME_BACKCHANNEL_DYNAMIC: "true"        # services may still use http://keycloak:8180
      KC_PROXY_HEADERS: xforwarded                   # trust X-Forwarded-* (NGINX is the only public path)
      KC_DB_URL: jdbc:postgresql://postgres:5432/keycloak
      KC_DB_USERNAME: quickbite
      KC_LOG_LEVEL: info
    ports: !override
      - "127.0.0.1:8180:8180"                        # backchannel for IDE-run services only; not reachable from your LAN
    volumes: !override
      - ../keycloak/prod/quickbite-realm.json:/opt/keycloak/data/import/quickbite-realm.json:ro
    depends_on:
      postgres: { condition: service_healthy }

  nginx:
    ports:
      - "8443:8443"                                  # appended to the existing 8000:80
    volumes:
      - ../nginx/keycloak-tls.conf.template:/etc/nginx/templates/keycloak-tls.conf.template:ro
      - ../nginx/certs:/etc/nginx/certs:ro
    depends_on:
      keycloak: { condition: service_healthy }       # NGINX resolves "keycloak" at startup
```

The health check from file 03 (management port 9000, `/health/ready`) keeps working, because health is baked into the image.

## Step 7: Start it

```bash
cd deploy/compose
docker compose stop keycloak && docker compose rm -f keycloak        # remove the dev-mode container
docker compose -f docker-compose.yml -f docker-compose.keycloak-prod.yml up -d --build keycloak nginx
docker compose -f docker-compose.yml -f docker-compose.keycloak-prod.yml ps keycloak    # wait for "healthy"
```

With services in Docker too (file 09), add `-f docker-compose.apps.yml` before the override. Update the root `.env` shortcut:

```bash
COMPOSE_FILE=deploy/compose/docker-compose.yml:deploy/compose/docker-compose.apps.yml:deploy/compose/docker-compose.keycloak-prod.yml
```

**Replace the bootstrap admin.** The bootstrap account is meant to be temporary:

1. Log in at `https://localhost:8443/admin` with the bootstrap credentials.
2. In the **master** realm, create a user (e.g. `ops-admin`), set its password, and assign the `admin` realm role.
3. Log in as that user and **delete `bootstrap-admin`**.
4. Remove the two `KC_BOOTSTRAP_*` lines from `.env.keycloak`.

## Step 8: Point everything at the new issuer

Only the **issuer** changes. The backchannel URLs stay `http://keycloak:8180/...` (or `http://localhost:8180/...` from your IDE).

| Where | Change |
|---|---|
| Services in Docker (`docker-compose.apps.yml`, `x-app-env`) | `KC_ISSUER: https://localhost:8443/realms/quickbite` |
| Services from the IDE | `export KC_ISSUER=https://localhost:8443/realms/quickbite` (backchannel defaults stay) |
| Kubernetes values (`common.yaml`) | `KC_ISSUER: https://localhost:8443/realms/quickbite` and port-forward NGINX 8443 |
| Scripts (`token.sh`, `create-load-users.sh`, k6) | `export KC_URL=https://localhost:8443` (token.sh reads it); change the hard-coded `http://localhost:8180` in `create-load-users.sh` and the k6 script |
| order-svc | `ORDER_SVC_SECRET` must equal the value in `.env.keycloak` |

### Android

1. `app/build.gradle.kts`: `buildConfigField("String", "ISSUER", "\"https://localhost:8443/realms/quickbite\"")`
2. `scripts/android-reverse.sh`: add `adb -s "$d" reverse tcp:8443 tcp:8443`.
3. **Trust the mkcert CA on the device (debug only).** Push the CA with `adb push "$(mkcert -CAROOT)/rootCA.pem" /sdcard/Download/`, then on the device go to Settings → Security → *Encryption & credentials* → *Install a certificate* → *CA certificate*. Then let **debug** builds trust user-installed CAs in `res/xml/network_security_config.xml`:

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">localhost</domain>   <!-- API on :8000 is still plain HTTP locally -->
        <domain includeSubdomains="false">10.0.2.2</domain>
    </domain-config>
    <debug-overrides>
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />        <!-- mkcert CA; NEVER in release builds -->
        </trust-anchors>
    </debug-overrides>
</network-security-config>
```

4. A bonus: the Keycloak issuer is now HTTPS, so AppAuth's default connection builder works again. The `DevConnectionBuilder` from file 10 is no longer needed for the login flow.

## Step 9: Verify

```bash
export KC_URL=https://localhost:8443

# 1) Production profile: the dev warning must be GONE
docker compose logs keycloak | grep -E "development mode|started in|Listening on"
#   expect: "... started in Xs. Listening on: http://0.0.0.0:8180. Management interface listening on http://0.0.0.0:9000."
#   and NOT: "Running the server in development mode. DO NOT use this configuration in production."

# 2) Discovery over TLS with the new issuer
curl -s $KC_URL/realms/quickbite/.well-known/openid-configuration | jq -r .issuer    # https://localhost:8443/realms/quickbite

# 3) A token, and its claims
TOKEN=$(scripts/token.sh alice alice) && scripts/jwt-decode.sh "$TOKEN" | jq '{iss, aud, sid, realm_access}'

# 4) Services accept it (after Step 8)
curl -s -H "Authorization: Bearer $TOKEN" localhost:8000/api/auth/session | jq .username      # "alice"

# 5) Plain HTTP is not reachable from outside (LAN IP instead of localhost)
curl -s -m 3 http://$(hostname -I | awk '{print $1}'):8180/realms/quickbite || echo "blocked from LAN: good"

# 6) State survives re-creating the container (it's in Postgres now)
docker compose rm -sf keycloak && docker compose up -d keycloak    # (with the same -f files / COMPOSE_FILE)
#   -> users, sessions and signing keys are still there; logs say the realm import was SKIPPED (realm exists)
```

| Check | Pass condition |
|---|---|
| 1 | No "development mode" warning |
| 2 | `https://localhost:8443/realms/quickbite` |
| 3 | `iss` = the HTTPS issuer; `aud` contains `quickbite-api` |
| 4 | `"alice"` (the gateway validates the new issuer) |
| 5 | Unreachable from the LAN |
| 6 | Tokens issued before the re-create still validate (same keys) |

> **Consequence of #6:** `--import-realm` no longer resets anything, because the realm now lives in a real database. To change realm configuration from then on, use the admin console, the Admin REST API, or better **keycloak-config-cli** (realm-as-code, applied in CI). Don't edit the JSON and expect it to be re-imported.

---

## Production checklist (beyond this local setup)

| Area | Do |
|---|---|
| TLS | Public CA certificate; TLS 1.2+; HSTS; renew automatically |
| Admin | Separate admin hostname (`KC_HOSTNAME_ADMIN`), reachable only from VPN/internal; MFA for admins; no bootstrap admin left |
| Secrets | DB password, client secrets and bootstrap credentials from Vault / cloud secret manager (placeholders in the realm file) |
| Clients | Remove password-grant clients (`cli-test`); public clients = PKCE only; exact redirect URIs; short access-token lifetimes; refresh-token rotation |
| Database | Dedicated DB user; backups + tested restore; the realm's signing keys live there, so losing it invalidates every session |
| High availability | 2+ replicas with the Infinispan distributed cache; on Kubernetes use the **Keycloak Operator** (`Keycloak` + `KeycloakRealmImport` CRs) |
| Network | Only the proxy can reach Keycloak (NetworkPolicy/security groups), because proxy headers are trusted; management port 9000 internal only |
| Abuse | Brute-force detection on; rate-limit `/protocol/openid-connect/token` at the edge; monitor failed logins (metrics on 9000) |
| Upgrades | Pin the image version; read the upgrade guide; the DB schema migrates on start, so back up first |

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Keycloak exits: `database "keycloak" does not exist` | Step 2 skipped on an existing volume | Run the `create database keycloak;` command |
| Keycloak exits: `hostname is not configured` | `KC_HOSTNAME` missing: production mode refuses to guess | Set it (Step 6) |
| Keycloak warns "build time options differ" / refuses `--optimized` | You changed a build option (e.g. `KC_DB`) at runtime | Put it in the Dockerfile and `--build` again |
| Login page: **"HTTPS required"** | Keycloak thinks the request is HTTP: `X-Forwarded-Proto` missing, or `KC_PROXY_HEADERS` not set | Check the NGINX `proxy_set_header` lines and the env var |
| `403` on admin console actions through the proxy | Proxy headers not parsed (origin check fails) | `KC_PROXY_HEADERS=xforwarded` |
| Services: `401 The iss claim is not valid` | `KC_ISSUER` still `http://localhost:8180/...` | Step 8 |
| `curl: SSL certificate problem` | The mkcert CA isn't trusted by that tool | `mkcert -install`, or `curl --cacert "$(mkcert -CAROOT)/rootCA.pem"` |
| NGINX: `host not found in upstream "keycloak"` | NGINX started before Keycloak existed | The `depends_on` in Step 6; `docker compose up -d nginx` again |
| Android: `CertPathValidatorException` / blank login tab | CA not installed on the device, or not trusted by a debug build | Step 8 → Android, points 3–4 |
| `unknown tag !override` | Docker Compose older than v2.24 | Upgrade Docker Compose |
