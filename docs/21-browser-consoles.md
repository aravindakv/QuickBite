# 21 — Browser Consoles for the Local Stack (DB, Cache, Kafka, Auth)

**Goal:** inspect every backing service from `http://localhost:...` in your browser instead of memorising `psql`, `redis-cli`, `mongosh` and `kafka-*.sh` flags. Useful while debugging chapters 05–13, and for showing the system to someone else.

| Console | URL | Backs | Image |
|---|---|---|---|
| **Kafka UI** (kafbat) | `http://localhost:8090` | Topics, messages, consumer lag, DLT | `ghcr.io/kafbat/kafka-ui` |
| **Adminer** | `http://localhost:8091` | PostgreSQL (`orders`, `payments`, `keycloak`) | `adminer` |
| **mongo-express** | `http://localhost:8092` | MongoDB (`catalog`) | `mongo-express` |
| **RedisInsight** | `http://localhost:8093` | Redis keys, memory, live commands | `redis/redisinsight` |
| **Keycloak admin** | `http://localhost:8180` | Realm, users, sessions, clients | already running |
| **Spark UI** | `http://localhost:4040` | Streaming batches, stages (while the job runs, file 13) | already running |

> **Why these run as containers, not as desktop apps:** your browser can reach `localhost`, but not Docker-internal names like `kafka:19092` or `postgres:5432`. Each console runs **inside** the Compose network and publishes only its own HTTP port to your machine.

---

## Step 1: The tools overlay

`deploy/compose/docker-compose.tools.yml`

```yaml
# Local inspection consoles. NEVER run this file anywhere but your own machine:
# each console holds unauthenticated, full read/write access to a datastore.
# Every port is bound to 127.0.0.1, so nothing is reachable from your LAN.
services:
  kafka-ui:
    image: ghcr.io/kafbat/kafka-ui:latest
    ports: ["127.0.0.1:8090:8080"]
    environment:
      KAFKA_CLUSTERS_0_NAME: local
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:19092
      DYNAMIC_CONFIG_ENABLED: "true"
    depends_on:
      kafka: { condition: service_healthy }

  adminer:
    image: adminer:latest
    ports: ["127.0.0.1:8091:8080"]
    environment:
      ADMINER_DEFAULT_SERVER: postgres        # pre-fills the "Server" field
      ADMINER_DESIGN: dracula
    depends_on:
      postgres: { condition: service_healthy }

  mongo-express:
    image: mongo-express:latest
    ports: ["127.0.0.1:8092:8081"]
    environment:
      ME_CONFIG_MONGODB_URL: mongodb://mongo:27017
      ME_CONFIG_BASICAUTH: "false"            # local only; the image otherwise asks for admin/pass
    depends_on:
      mongo: { condition: service_healthy }

  redisinsight:
    image: redis/redisinsight:latest
    ports: ["127.0.0.1:8093:5540"]
    environment:                              # auto-adds the connection on first start (else add it by hand, Step 4)
      RI_REDIS_HOST: redis
      RI_REDIS_PORT: "6379"
      RI_REDIS_ALIAS: quickbite
    volumes: [riredis:/data]
    depends_on:
      redis: { condition: service_healthy }

volumes:
  riredis:
```

If your `docker-compose.yml` from file 03 already declares `kafka-ui` under the `tools` profile, delete that block so it isn't defined twice.

`scripts/consoles.sh`

```bash
#!/usr/bin/env bash
# Start/stop the browser consoles for the local stack (file 21).
#   scripts/consoles.sh up | down | list
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DC=(docker compose -f "$ROOT/deploy/compose/docker-compose.yml" -f "$ROOT/deploy/compose/docker-compose.tools.yml")
CONSOLES=(kafka-ui adminer mongo-express redisinsight)

case "${1:-up}" in
  up)
    "${DC[@]}" up -d "${CONSOLES[@]}" || exit 1
    cat <<EOF

  Kafka UI      http://localhost:8090
  Adminer       http://localhost:8091   (PostgreSQL / postgres / quickbite / quickbite / orders)
  mongo-express http://localhost:8092
  RedisInsight  http://localhost:8093
  Keycloak      http://localhost:8180   (admin / admin)
EOF
    ;;
  down) "${DC[@]}" stop "${CONSOLES[@]}" ;;
  list) "${DC[@]}" ps "${CONSOLES[@]}" ;;
  *) sed -n '2,5p' "${BASH_SOURCE[0]}"; exit 2 ;;
esac
```

```bash
chmod +x scripts/consoles.sh && scripts/consoles.sh up
```

---

## Step 2: Kafka UI (`:8090`)

The single most useful console in this project.

| Look at | Where | What it tells you |
|---|---|---|
| `orders.events`, `payments.events`, `rider.location` | **Topics → Messages** | The event payload **and its headers** (`eventId`, `eventType`, `sessionId`, `correlationId`): this is where chapter 19's empty ids were visible |
| `orders.events.dlt`, `payments.events.dlt` | **Topics → Messages** | Poison messages, with `kafka_dlt-exception-*` headers decoded properly (the console consumer prints them as raw bytes) |
| `payment-svc`, `order-svc`, `location-svc`, `realtime-*` | **Consumers** | **Lag** per partition: the honest measure of whether a consumer is keeping up (file 12 scales on it) |
| Partition count and assignment | **Topics → Partitions** | Why a 4th consumer sits idle on a 3-partition topic |

Try it: place an order, then filter `orders.events` by key (the order id) to see `order.created` followed by each `order.status-changed`, in order, in one partition.

## Step 3: Adminer (`:8091`) for PostgreSQL

Login form: **System** PostgreSQL, **Server** `postgres`, **Username/Password** `quickbite`, **Database** `orders` (or `payments`, or `keycloak` after chapter 17).

| Look at | Why |
|---|---|
| `outbox` | The outbox pattern in action: rows appear with `published_at` NULL, then get marked. Stop Kafka and watch them pile up |
| `orders` + `order_lines` | Server-side pricing; `version` incrementing under optimistic locking |
| `processed_events` | Idempotency: one row per consumed event id |
| `flyway_schema_history` | Which migrations ran, with their checksums |
| `payments` (payments DB) | `status`, `failure_reason`, `card_brand`/`card_last4`, and **no card numbers anywhere** |

Adminer's **SQL command** box runs anything, e.g. the dispatcher's hot query:

```sql
select id, status, created_at from orders where status = 'PAID' order by created_at limit 20;
```

## Step 4: mongo-express (`:8092`) and RedisInsight (`:8093`)

**mongo-express** → database `catalog` → collection `restaurants`: 22 documents (16 `blr`, 6 `mum`), each with its embedded `menu`. Useful for seeing why a menu item is "sold out" (`available: false`) without writing a query.

**RedisInsight** → *Browser* to walk keys, *Workbench* to run commands, *Analysis* to see what eats memory. If the connection wasn't added automatically, click **Add Redis database** → host `redis`, port `6379`.

Keys worth knowing, all created by earlier chapters:

| Pattern | From | Notes |
|---|---|---|
| `session:*`, `revoked:sid:*` | Gateway (04) | Live sessions and the logout denylist |
| `cache:restaurants:city:*`, `cache:restaurant:*` | catalog-svc (05) | Watch the TTL count down, with its jitter |
| `riders:blr` | location-svc (08) | A **sorted set**: in Workbench, `GEOSEARCH riders:blr FROMLONLAT 77.6271 12.9279 BYRADIUS 5 km ASC WITHDIST` |
| `rider:alive:*`, `rider:busy:*` | location-svc (08) | Liveness TTL and the exclusive claim |
| `track:order:*` | location-svc (20) | The rider trail: `LRANGE track:order:<id> 0 -1` |
| `surge:*` | Spark job (13) | Multiplier per grid cell, 120 s TTL |

RedisInsight's **Profiler** (a `MONITOR` stream) shows every command live. Run it while `rider-sim.sh` is moving and you'll see the `GEOADD` / `SETEX` / `EVALSHA` pattern per GPS update, which is exactly the op-count that drives the cache sizing in chapter 18.

## Step 5: The consoles you already have

**Keycloak admin** (`http://localhost:8180`, `admin` / `admin`) → realm **quickbite**:

- *Users* → alice → **Sessions**: every active login, and a per-session logout button.
- *Clients* → `android-app` → *Advanced*: PKCE setting; → `order-svc` → *Credentials*: the client secret.
- *Realm settings* → *Sessions* / *Tokens*: the lifespans your tokens inherit.
- *Users* → alice → *Attack detection*: the brute-force lockout you hit earlier, with a **Reset** button.

**Spark UI** (`http://localhost:4040`) while the surge job runs (file 13): *Structured Streaming* shows batch duration and input rows per trigger. If batch time exceeds the trigger interval, the job is falling behind — the streaming equivalent of consumer lag.

**Service actuators:** `/actuator/health` is open, the rest requires a token (by design, file 02). For a browser, temporarily widen it in one service's `application.yml`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,beans,env,configprops,mappings   # local debugging only
```

then visit `http://localhost:8082/actuator/health` directly. `mappings` answers "why is my new endpoint 404?" and `configprops` answers "did that YAML value actually apply?" — the question behind several problems earlier in this guide. Revert before committing.

## Step 6: On Kubernetes (file 12)

There are no published ports, so forward what you need:

```bash
kubectl -n quickbite port-forward svc/postgres 5432:5432 &     # then point Adminer at host.docker.internal
kubectl -n kafka   port-forward svc/my-cluster-kafka-bootstrap 9092:9092 &
kubectl -n quickbite port-forward svc/redis 6379:6379 &
```

For the cluster itself, **k9s** (a terminal UI: `brew install k9s`) is faster than any web dashboard for pods, logs and events. If you want a browser, **Headlamp** or the Kubernetes Dashboard both work; install per their docs and port-forward.

---

## Security notes (these matter)

1. **Every console here is unauthenticated** and has full read/write access to its datastore. That's acceptable only because each port is bound to `127.0.0.1`. Never change those bindings to `0.0.0.0` on a shared machine, and never deploy this overlay.
2. **The tools are a separate Compose file** on purpose: `scripts/up.sh` and the CI path never start them.
3. **RedisInsight and Adminer can write**, not just read. A stray `FLUSHALL` or `DROP TABLE` in the wrong tab is indistinguishable from a bug you'll then spend an hour chasing. Prefer the read-only equivalents (`docker exec ... redis-cli --scan`, `psql -c "select ..."`) when you're only looking.
4. In production, the equivalents are a bastion host with audited access, read replicas for queries, and a managed Kafka console behind SSO. Local convenience is not the production pattern.

## Verify

```bash
scripts/consoles.sh up
for p in 8090 8091 8092 8093 8180; do
  printf "%s -> %s\n" "$p" "$(curl -s -o /dev/null -w '%{http_code}' localhost:$p)"
done
```

All five should answer (`200`, or `302`/`30x` for consoles that redirect to a login page). Then:

| Check | Pass condition |
|---|---|
| Kafka UI → Topics | `orders.events` with messages and headers |
| Adminer → `orders` → `outbox` | Rows visible while Kafka is stopped |
| mongo-express → `catalog.restaurants` | 22 documents |
| RedisInsight → Browser | `cache:*` and `session:*` keys |
| Keycloak → Users → alice → Sessions | Your current session listed |

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Console loads but "cannot connect" | It was started without the infra file, so it isn't on the `quickbite` network | Always pass both `-f` files (the script does) |
| Adminer: "server not found" | Wrong Server field | Use `postgres` (the container name), not `localhost` |
| Kafka UI: cluster offline | Wrong bootstrap address | `kafka:19092` (internal listener), not `localhost:9092` |
| RedisInsight asks for a database | The `RI_*` env vars aren't supported by your image version | Add it by hand: host `redis`, port `6379` |
| mongo-express asks for a password | Basic auth enabled by default in that version | `ME_CONFIG_BASICAUTH: "false"`, or log in with `admin` / `pass` |
| Port already in use | Another local tool owns 8090–8093 | Change the left-hand side of the port mapping |
