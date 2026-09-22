# 03 — Local Infrastructure with Docker Compose

**Goal:** one command starts the whole infrastructure: Postgres, MongoDB, Redis, Kafka (KRaft), Keycloak with a pre-built realm, and NGINX as the edge proxy. It also sets up a tiny "CDN" cache.

You will then get a real JWT for `alice` with curl and inspect every claim that matters.

---

## Step 1: Postgres init script

`deploy/compose/postgres/init-dbs.sql`

```sql
-- One Postgres server, one database per service: services never share tables.
create database orders;
create database payments;
```

**Why "database per service":** if payment-svc could `select` from `orders`, the two services would be coupled at the schema level. Every column rename would then need a coordinated deploy. Sharing a *server* locally is fine; sharing a *schema* is not.

---

## Step 2: Keycloak realm

`deploy/keycloak/quickbite-realm.json`

```json
{
  "realm": "quickbite",
  "enabled": true,
  "sslRequired": "none",
  "accessTokenLifespan": 300,
  "ssoSessionIdleTimeout": 1800,
  "ssoSessionMaxLifespan": 36000,
  "revokeRefreshToken": true,
  "refreshTokenMaxReuse": 0,
  "bruteForceProtected": true,
  "roles": {
    "realm": [
      { "name": "customer" }, { "name": "rider" }, { "name": "restaurant" },
      { "name": "admin" }, { "name": "service" }
    ]
  },
  "clients": [
    {
      "clientId": "android-app",
      "name": "QuickBite Android",
      "publicClient": true,
      "standardFlowEnabled": true,
      "directAccessGrantsEnabled": false,
      "redirectUris": ["com.quickbite.app:/oauth2redirect"],
      "attributes": {
        "pkce.code.challenge.method": "S256",
        "post.logout.redirect.uris": "com.quickbite.app:/logout"
      },
      "protocolMappers": [
        {
          "name": "audience-quickbite-api",
          "protocol": "openid-connect",
          "protocolMapper": "oidc-audience-mapper",
          "config": {
            "included.custom.audience": "quickbite-api",
            "access.token.claim": "true",
            "id.token.claim": "false",
            "introspection.token.claim": "true"
          }
        }
      ]
    },
    {
      "clientId": "cli-test",
      "name": "Local curl/k6 testing only",
      "publicClient": true,
      "standardFlowEnabled": false,
      "directAccessGrantsEnabled": true,
      "protocolMappers": [
        {
          "name": "audience-quickbite-api",
          "protocol": "openid-connect",
          "protocolMapper": "oidc-audience-mapper",
          "config": {
            "included.custom.audience": "quickbite-api",
            "access.token.claim": "true",
            "id.token.claim": "false",
            "introspection.token.claim": "true"
          }
        }
      ]
    },
    {
      "clientId": "order-svc",
      "name": "order-svc machine client",
      "publicClient": false,
      "clientAuthenticatorType": "client-secret",
      "secret": "order-svc-secret",
      "standardFlowEnabled": false,
      "serviceAccountsEnabled": true,
      "protocolMappers": [
        {
          "name": "audience-quickbite-api",
          "protocol": "openid-connect",
          "protocolMapper": "oidc-audience-mapper",
          "config": {
            "included.custom.audience": "quickbite-api",
            "access.token.claim": "true",
            "id.token.claim": "false",
            "introspection.token.claim": "true"
          }
        }
      ]
    }
  ],
  "users": [
    {
      "id": "11111111-1111-1111-1111-111111111111",
      "username": "alice", "enabled": true, "email": "alice@quickbite.dev", "emailVerified": true,
      "firstName": "Alice", "lastName": "Customer",
      "credentials": [{ "type": "password", "value": "alice", "temporary": false }],
      "realmRoles": ["customer"]
    },
    {
      "id": "22222222-2222-2222-2222-222222222222",
      "username": "bob", "enabled": true, "email": "bob@quickbite.dev", "emailVerified": true,
      "firstName": "Bob", "lastName": "Rider",
      "credentials": [{ "type": "password", "value": "bob", "temporary": false }],
      "realmRoles": ["rider"]
    },
    {
      "id": "33333333-3333-3333-3333-333333333333",
      "username": "carol", "enabled": true, "email": "carol@quickbite.dev", "emailVerified": true,
      "firstName": "Carol", "lastName": "Rider",
      "credentials": [{ "type": "password", "value": "carol", "temporary": false }],
      "realmRoles": ["rider"]
    },
    {
      "id": "44444444-4444-4444-4444-444444444444",
      "username": "admin", "enabled": true, "email": "admin@quickbite.dev", "emailVerified": true,
      "firstName": "Ada", "lastName": "Admin",
      "credentials": [{ "type": "password", "value": "admin", "temporary": false }],
      "realmRoles": ["admin", "customer"]
    },
    {
      "id": "55555555-5555-5555-5555-555555555555",
      "username": "demo", "enabled": true, "email": "demo@quickbite.dev", "emailVerified": true,
      "firstName": "Demo", "lastName": "Customer",
      "credentials": [{ "type": "password", "value": "demo", "temporary": false }],
      "realmRoles": ["customer"]
    },
    {
      "id": "66666666-6666-6666-6666-000000000001",
      "username": "demo-rider-1", "enabled": true, "email": "rider1@quickbite.dev", "emailVerified": true,
      "firstName": "Ravi", "lastName": "Demo",
      "credentials": [{ "type": "password", "value": "demo-rider", "temporary": false }],
      "realmRoles": ["rider"]
    },
    {
      "id": "66666666-6666-6666-6666-000000000002",
      "username": "demo-rider-2", "enabled": true, "email": "rider2@quickbite.dev", "emailVerified": true,
      "firstName": "Meena", "lastName": "Demo",
      "credentials": [{ "type": "password", "value": "demo-rider", "temporary": false }],
      "realmRoles": ["rider"]
    },
    {
      "id": "66666666-6666-6666-6666-000000000003",
      "username": "demo-rider-3", "enabled": true, "email": "rider3@quickbite.dev", "emailVerified": true,
      "firstName": "Arjun", "lastName": "Demo",
      "credentials": [{ "type": "password", "value": "demo-rider", "temporary": false }],
      "realmRoles": ["rider"]
    },
    {
      "username": "service-account-order-svc", "enabled": true,
      "serviceAccountClientId": "order-svc",
      "realmRoles": ["service"]
    }
  ]
}
```

**What each part teaches:**

| Setting | Why |
|---|---|
| `android-app` is **public** with **PKCE S256** | A mobile app cannot keep a secret: anyone can decompile the APK. PKCE proves that the app that *started* the login is the one *finishing* it, so a stolen authorization code is useless. |
| `redirectUris: com.quickbite.app:/oauth2redirect` | A custom URI scheme that only your app registers; Keycloak refuses any other redirect. |
| Audience mapper → `aud: quickbite-api` | Tokens say *who they are for*. Services reject tokens minted for other APIs (the "confused deputy" defense). |
| `accessTokenLifespan: 300` | 5-minute access tokens keep a leaked token's damage window small. The refresh token renews them silently. |
| `revokeRefreshToken: true`, `refreshTokenMaxReuse: 0` | **Refresh-token rotation**: each refresh token works once. Reuse of an old one signals theft. |
| `cli-test` with direct access grants | Password grant, **for local testing only** (curl, k6). OAuth 2.1 removes this grant for real apps. |
| `order-svc` confidential + service account with role `service` | Machine identity for background jobs (client credentials). |
| `sslRequired: none` | Local HTTP only. Never in production. |
| `demo` + `demo-rider-1..3` | Accounts used by **Demo Mode** (file 16): a demo customer, and three virtual riders that `demo-svc` logs in as. They exist in every environment, but only do something when `DEMO_MODE=true`. |
| Fixed user `id`s (`1111…`, `2222…`) | The token's `sub` claim is the user id. Fixed ids let other services seed **test data per user**; payment-svc pre-loads test cards for alice and admin (file 07). Keycloak honours `id` only when the user is first imported, so re-import if you added ids later. |

---

## Step 3: NGINX edge (reverse proxy + mini CDN)

`deploy/nginx/default.conf.template`

```nginx
# The official nginx image runs envsubst on *.template files, but only for env vars that are defined,
# so nginx's own $variables are left alone.

map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}

# Per-client-IP rate limit at the edge (the gateway adds a per-USER limit behind it).
limit_req_zone $binary_remote_addr zone=perip:10m rate=50r/s;

# "CDN" simulation: cache public GET responses at the edge.
proxy_cache_path /var/cache/nginx/edge levels=1:2 keys_zone=edge:10m max_size=100m inactive=10m use_temp_path=off;

upstream gateway {
    server ${GATEWAY_UPSTREAM};
    keepalive 32;               # reuse TCP connections to the gateway instead of a handshake per request
}

server {
    listen 80;
    client_max_body_size 1m;

    location = /nginx-health { access_log off; return 200 "ok\n"; }

    # WebSockets: long-lived, upgraded connections
    location /ws/ {
        proxy_pass http://gateway;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_set_header Host $host;
        proxy_read_timeout 1h;
        proxy_send_timeout 1h;
    }

    # Public, cacheable catalog reads -> served from the edge cache when fresh
    location /api/restaurants {
        limit_req zone=perip burst=100 nodelay;
        proxy_cache edge;
        proxy_cache_methods GET HEAD;
        proxy_cache_key "$request_method$request_uri";
        proxy_cache_lock on;                      # request collapsing: one miss goes upstream, others wait
        proxy_cache_use_stale error timeout updating http_500 http_502 http_503;
        add_header X-Cache-Status $upstream_cache_status always;
        proxy_pass http://gateway;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    location / {
        limit_req zone=perip burst=100 nodelay;
        proxy_pass http://gateway;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

**Why NGINX in front of the gateway?** It takes the "edge" jobs:

- WebSocket upgrades and long timeouts.
- Coarse per-IP rate limits.
- An **edge cache** that behaves like a CDN: `proxy_cache_lock` collapses a stampede of misses into one upstream call, and `use_stale` serves old content if the backend dies.

In the cloud, a real CDN plus a cloud load balancer take this role, and in Kubernetes (file 12) the Gateway API implementation does.

---

## Step 4: The Compose file

`deploy/compose/docker-compose.yml`

```yaml
name: quickbite

services:
  postgres:
    image: postgres:17-alpine
    environment:
      POSTGRES_USER: quickbite
      POSTGRES_PASSWORD: quickbite
    ports: ["5432:5432"]
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./postgres/init-dbs.sql:/docker-entrypoint-initdb.d/init-dbs.sql:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U quickbite"]
      interval: 5s
      retries: 20

  mongo:
    image: mongo:8.0
    ports: ["27017:27017"]
    volumes: [mongodata:/data/db]
    healthcheck:
      test: ["CMD", "mongosh", "--quiet", "--eval", "db.adminCommand('ping').ok"]
      interval: 5s
      retries: 20

  redis:
    image: redis:7.4-alpine
    # volatile-lru: under memory pressure evict ONLY keys that have a TTL (caches),
    # never the GEO set or rider locks, which have no TTL or must not vanish.
    command: ["redis-server", "--appendonly", "yes", "--maxmemory", "256mb", "--maxmemory-policy", "volatile-lru"]
    ports: ["6379:6379"]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      retries: 20

  kafka:
    image: apache/kafka:4.1.0
    ports: ["9092:9092"]
    environment:
      CLUSTER_ID: 4L6g3nShT-eMCtK--X86sw
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller          # KRaft: no ZooKeeper
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENERS: INTERNAL://:19092,CONTROLLER://:9093,EXTERNAL://:9092
      KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:19092,EXTERNAL://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: INTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT,EXTERNAL:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
      KAFKA_NUM_PARTITIONS: 3
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"        # topics are declared by services -> no typo-topics
    healthcheck:
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 > /dev/null 2>&1"]
      interval: 10s
      retries: 20

  kafka-ui:
    image: ghcr.io/kafbat/kafka-ui:latest
    profiles: ["tools"]
    ports: ["8090:8080"]
    environment:
      KAFKA_CLUSTERS_0_NAME: local
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:19092
    depends_on:
      kafka: { condition: service_healthy }

  keycloak:
    image: quay.io/keycloak/keycloak:26.4.0
    command: ["start-dev", "--import-realm", "--http-port=8180"]
    environment:
      KC_BOOTSTRAP_ADMIN_USERNAME: admin
      KC_BOOTSTRAP_ADMIN_PASSWORD: admin
      KC_HOSTNAME: http://localhost:8180            # FRONTEND url -> goes into every token's "iss"
      KC_HOSTNAME_BACKCHANNEL_DYNAMIC: "true"       # containers may reach it as http://keycloak:8180
      KC_HEALTH_ENABLED: "true"
    ports: ["8180:8180"]
    volumes:
      - ../keycloak/quickbite-realm.json:/opt/keycloak/data/import/quickbite-realm.json:ro
    healthcheck:
      test: ["CMD-SHELL", "exec 3<>/dev/tcp/127.0.0.1/9000; printf 'GET /health/ready HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3; grep -q '\"UP\"' <&3"]
      interval: 10s
      retries: 30

  nginx:
    image: nginx:stable-alpine
    ports: ["8000:80"]
    environment:
      # While you run services from the IDE, the gateway is on your host machine:
      GATEWAY_UPSTREAM: host.docker.internal:8080
    extra_hosts:
      - "host.docker.internal:host-gateway"          # makes host.docker.internal work on Linux too
    volumes:
      - ../nginx/default.conf.template:/etc/nginx/templates/default.conf.template:ro

volumes:
  pgdata:
  mongodata:
```

**Things to notice:**

- **Two Kafka listeners.** Containers use `kafka:19092`, and your IDE-run services use `localhost:9092`. Kafka returns the *advertised* address to clients, so each network needs its own listener. This is the #1 local Kafka mistake.
- **KRaft mode:** `process.roles=broker,controller` runs metadata consensus (Raft) inside Kafka itself. Kafka 4.x has no ZooKeeper at all.
- **`auto.create.topics.enable=false`:** a typo in a topic name fails loudly instead of silently creating a new topic.
- **Healthchecks everywhere:** file 09 uses `depends_on: condition: service_healthy`, so services start only when their dependencies are really ready, not merely "container started".
- **`name: quickbite`:** fixes the Compose project name, so the network is always `quickbite_default` (the Spark job in file 13 joins it).

---

## Step 5: Start it

```bash
cd deploy/compose
docker compose up -d
docker compose ps          # wait until everything shows "healthy" (Keycloak takes ~30–60 s)
docker compose --profile tools up -d kafka-ui   # optional: http://localhost:8090
```

Useful commands:

```bash
docker compose logs -f keycloak
docker compose down            # stop, keep data
docker compose down -v         # stop and WIPE data (fresh start)
```

---

## Step 6: Get a real token and read it

`scripts/token.sh`

```bash
#!/usr/bin/env bash
# usage: scripts/token.sh alice alice   -> prints an access token on stdout
# On failure it prints Keycloak's actual error to stderr and exits 1 (so TOKEN=$(...) never silently ends up empty).
set -uo pipefail
[ $# -eq 2 ] || { echo "usage: $0 <username> <password>" >&2; exit 2; }
command -v jq >/dev/null || { echo "token.sh: jq is not installed (sudo apt install jq / brew install jq)" >&2; exit 1; }
KC=${KC_URL:-http://localhost:8180}

RESP=$(curl -sS -w '\n%{http_code}' -X POST "$KC/realms/quickbite/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=cli-test \
  --data-urlencode "username=$1" --data-urlencode "password=$2" -d scope=openid 2>&1) || {
  echo "token.sh: cannot reach Keycloak at $KC ($RESP). Is it running and healthy? docker compose ps keycloak" >&2; exit 1; }

CODE=$(tail -n1 <<<"$RESP"); BODY=$(sed '$d' <<<"$RESP")
TOKEN=$(jq -r '.access_token // empty' <<<"$BODY" 2>/dev/null)
if [ "$CODE" != "200" ] || [ -z "$TOKEN" ]; then
  echo "token.sh: HTTP $CODE from Keycloak: $BODY" >&2
  exit 1
fi
echo "$TOKEN"
```

`scripts/jwt-decode.sh`

```bash
#!/usr/bin/env bash
# usage: scripts/jwt-decode.sh <token>   -> pretty-prints the payload (NO signature check: debugging only)
p=$(echo -n "$1" | cut -d. -f2 | tr '_-' '/+')
while [ $(( ${#p} % 4 )) -ne 0 ]; do p="$p="; done
echo "$p" | base64 -d 2>/dev/null | jq .
```

```bash
chmod +x scripts/*.sh
TOKEN=$(scripts/token.sh alice alice) && echo "got token (${#TOKEN} chars)"
scripts/jwt-decode.sh "$TOKEN" | jq '{iss, aud, sub, sid, azp, exp, realm_access}'
```

If there is no token, `token.sh` prints Keycloak's error. Match it against the table in `14-troubleshooting.md` → *Getting a token*.

---

## Verify

The decoded token must show:

| Claim | Expected | If wrong |
|---|---|---|
| `iss` | `http://localhost:8180/realms/quickbite` | `KC_HOSTNAME` not applied: `docker compose up -d --force-recreate keycloak` |
| `aud` | contains `quickbite-api` | Audience mapper missing: the realm JSON didn't import; see note below |
| `sub` | `11111111-1111-1111-1111-111111111111` (alice's fixed id) | Realm imported before ids were added: recreate the Keycloak container |
| `sid` | a session id string | See file 14 (the gateway falls back to `session_state`) |
| `realm_access.roles` | contains `customer` | Role mapping missing in the import |

Also check:

```bash
# Service account works (client credentials):
curl -s -X POST http://localhost:8180/realms/quickbite/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=order-svc -d client_secret=order-svc-secret \
  | jq -r .access_token | xargs scripts/jwt-decode.sh | jq '.realm_access.roles'   # -> includes "service"

# JWKS (the public signing keys every service will cache):
curl -s http://localhost:8180/realms/quickbite/protocol/openid-connect/certs | jq '.keys[].kid'

# NGINX is up (the gateway isn't yet, so / returns 502; that's expected for now):
curl -s localhost:8000/nginx-health
```

> **Realm import runs only when the realm does not exist yet.** After editing the JSON, run `docker compose rm -sf keycloak && docker compose up -d keycloak`. In `start-dev` mode, Keycloak's data lives inside the container, so recreating it re-imports the realm.

**Checkpoint:**

1. Explain why the phone and the services agree on `iss` even though they reach Keycloak by different hostnames.
2. Why can't the Android app use a client secret?
