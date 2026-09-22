# 09 — Containerize and Run the Whole Stack

**Goal:** all six services run as containers next to the infrastructure. They have:

- health-gated startup
- memory limits
- non-root users
- a proper PID 1

You will also run the **watchdog and crash drills**: hang a loop, kill a JVM, and watch the platform react.

---

## Step 1: One Dockerfile for every service

`deploy/docker/Dockerfile`

```dockerfile
# Runtime-only image: the jar is built by Gradle on the host (fast incremental builds, shared Gradle cache).
FROM eclipse-temurin:25-jre

# tini = tiny init as PID 1: forwards SIGTERM to the JVM (graceful shutdown) and reaps zombie processes.
# curl = used by the container healthcheck.
RUN apt-get update \
 && apt-get install -y --no-install-recommends tini curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home app

WORKDIR /app
ARG JAR_FILE
COPY ${JAR_FILE} /app/app.jar

USER 10001

# Container-aware JVM:
#  MaxRAMPercentage=75   -> heap = 75% of the container limit (the rest is metaspace, threads, direct buffers)
#  UseZGC                -> generational ZGC: sub-millisecond pauses (low tail latency)
#  ExitOnOutOfMemoryError-> a JVM that hit OOM is in an undefined state; die and get restarted instead
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"

ENTRYPOINT ["tini", "--", "java", "-jar", "/app/app.jar"]
```

`.dockerignore` (repo root): keeps the build context small.

```
.git
.gradle
**/build/tmp
**/build/classes
**/build/resources
clients/
analytics/
*.md
```

**Why build the jar outside Docker?** For learning, it makes image builds take seconds. A production CI pipeline usually does a **multi-stage build** (a Gradle stage, then a JRE stage) or uses `./gradlew bootBuildImage` (Cloud Native Buildpacks), which also splits dependencies into cached layers. Try `bootBuildImage` as an exercise and compare the image sizes.

---

## Step 2: Compose file for the applications

`deploy/compose/docker-compose.apps.yml`: layered on top of the infrastructure file from 03.

```yaml
x-app-env: &app-env
  KAFKA_BOOTSTRAP: kafka:19092
  REDIS_HOST: redis
  DB_HOST: postgres
  MONGO_URI: mongodb://mongo:27017/catalog
  KC_ISSUER: http://localhost:8180/realms/quickbite                                  # PUBLIC url (token "iss")
  KC_JWKS: http://keycloak:8180/realms/quickbite/protocol/openid-connect/certs       # INTERNAL url (keys)
  KC_TOKEN_URI: http://keycloak:8180/realms/quickbite/protocol/openid-connect/token
  CATALOG_URL: http://catalog-svc:8081
  LOCATION_URL: http://location-svc:8084

x-app: &app
  restart: unless-stopped
  init: false                    # tini is already PID 1 inside the image
  deploy:
    resources:
      limits:
        memory: 512m             # heap ~384m via MaxRAMPercentage
        cpus: "1.0"
  depends_on:
    postgres: { condition: service_healthy }
    mongo:    { condition: service_healthy }
    redis:    { condition: service_healthy }
    kafka:    { condition: service_healthy }
    keycloak: { condition: service_healthy }

services:
  gateway:
    <<: *app
    build: { context: ../.., dockerfile: deploy/docker/Dockerfile, args: { JAR_FILE: services/gateway/build/libs/app.jar } }
    image: quickbite/gateway:dev
    ports: ["8080:8080"]
    environment:
      <<: *app-env
      CATALOG_URL: http://catalog-svc:8081
      ORDER_URL: http://order-svc:8082
      PAYMENT_URL: http://payment-svc:8083
      LOCATION_URL: http://location-svc:8084
      LOCATION_WS_URL: ws://location-svc:8084
      REALTIME_WS_URL: ws://realtime-svc:8085
    healthcheck: { test: ["CMD", "curl", "-fs", "http://localhost:8080/actuator/health/readiness"], interval: 10s, retries: 12, start_period: 20s }

  catalog-svc:
    <<: *app
    build: { context: ../.., dockerfile: deploy/docker/Dockerfile, args: { JAR_FILE: services/catalog-svc/build/libs/app.jar } }
    image: quickbite/catalog-svc:dev
    environment: { <<: *app-env, NODE_ID: "1" }
    healthcheck: { test: ["CMD", "curl", "-fs", "http://localhost:8081/actuator/health/readiness"], interval: 10s, retries: 12, start_period: 20s }

  order-svc:
    <<: *app
    build: { context: ../.., dockerfile: deploy/docker/Dockerfile, args: { JAR_FILE: services/order-svc/build/libs/app.jar } }
    image: quickbite/order-svc:dev
    environment: { <<: *app-env, NODE_ID: "2" }
    healthcheck: { test: ["CMD", "curl", "-fs", "http://localhost:8082/actuator/health/readiness"], interval: 10s, retries: 12, start_period: 20s }

  payment-svc:
    <<: *app
    build: { context: ../.., dockerfile: deploy/docker/Dockerfile, args: { JAR_FILE: services/payment-svc/build/libs/app.jar } }
    image: quickbite/payment-svc:dev
    environment: { <<: *app-env, NODE_ID: "3" }
    healthcheck: { test: ["CMD", "curl", "-fs", "http://localhost:8083/actuator/health/readiness"], interval: 10s, retries: 12, start_period: 20s }

  location-svc:
    <<: *app
    build: { context: ../.., dockerfile: deploy/docker/Dockerfile, args: { JAR_FILE: services/location-svc/build/libs/app.jar } }
    image: quickbite/location-svc:dev
    environment: { <<: *app-env, NODE_ID: "4" }
    healthcheck: { test: ["CMD", "curl", "-fs", "http://localhost:8084/actuator/health/readiness"], interval: 10s, retries: 12, start_period: 20s }

  realtime-svc:
    <<: *app
    build: { context: ../.., dockerfile: deploy/docker/Dockerfile, args: { JAR_FILE: services/realtime-svc/build/libs/app.jar } }
    image: quickbite/realtime-svc:dev
    environment: { <<: *app-env, NODE_ID: "5" }
    healthcheck: { test: ["CMD", "curl", "-fs", "http://localhost:8085/actuator/health/readiness"], interval: 10s, retries: 12, start_period: 20s }

  nginx:
    environment:
      GATEWAY_UPSTREAM: gateway:8080          # now the gateway is a container, not your IDE
    depends_on:
      gateway: { condition: service_healthy }
```

**Things to notice:**

- `x-app-env` / `x-app` are YAML **anchors**: one definition, reused six times.
- `NODE_ID` differs per service, so Snowflake IDs never collide. When you scale a service to several replicas (below), each replica needs its own id. File 12 derives it from the Kubernetes pod ordinal.
- `KC_ISSUER` (public) vs `KC_JWKS` / `KC_TOKEN_URI` (internal): the frontend/backchannel split from file 01.

---

## Step 3: Build and start

`scripts/up.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
./gradlew bootJar -x test --parallel
cd deploy/compose
docker compose -f docker-compose.yml -f docker-compose.apps.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.apps.yml ps
```

`scripts/down.sh`

```bash
#!/usr/bin/env bash
cd deploy/compose && docker compose -f docker-compose.yml -f docker-compose.apps.yml down "$@"   # add -v to wipe data
```

```bash
chmod +x scripts/up.sh scripts/down.sh
# Stop any bootRun processes first (ports 8080-8085 must be free)
scripts/up.sh
```

Wait until every service shows `healthy` (about 1–2 minutes the first time).

---

## Step 4: Smoke test the containerized stack

`scripts/smoke.sh`

```bash
#!/usr/bin/env bash
# Full happy path through NGINX. Run scripts/rider-sim.sh in another terminal first.
set -euo pipefail
API=http://localhost:8000
ALICE=$(scripts/token.sh alice alice)
echo "Restaurants: $(curl -s $API/api/restaurants | jq length)"
ID=$(curl -s -X POST $API/api/orders -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: $(uuidgen)" \
  -H 'Content-Type: application/json' \
  -d '{"restaurantId":"r1","items":[{"menuItemId":"r1-i1","quantity":2}],"deliveryLat":12.9279,"deliveryLon":77.6271}' | jq -r .id)
echo "Order $ID placed"
for i in $(seq 1 45); do
  S=$(curl -s -H "Authorization: Bearer $ALICE" $API/api/orders/$ID | jq -r .status)
  printf "\r[%02ds] %-16s" "$((i*2))" "$S"
  [ "$S" = "DELIVERED" ] || [ "$S" = "CANCELLED" ] && break
  sleep 2
done
echo; echo "Payment: $(curl -s -H "Authorization: Bearer $ALICE" $API/api/payments/orders/$ID | jq -r .status)"
```

```bash
chmod +x scripts/smoke.sh
scripts/rider-sim.sh &          # bob online
scripts/smoke.sh                # PENDING_PAYMENT -> PAID -> RIDER_ASSIGNED -> PICKED_UP -> DELIVERED; Payment: CAPTURED
kill %1
```

### Follow one user journey across all services by session-id

```bash
SID=$(scripts/jwt-decode.sh "$(scripts/token.sh alice alice)" | jq -r .sid)
cd deploy/compose
docker compose -f docker-compose.yml -f docker-compose.apps.yml logs --no-log-prefix order-svc payment-svc | grep "sid=" | tail -20
```

Each log line carries `[sid=... cid=...]`. payment-svc's lines (from a **Kafka consumer**) carry the same `sid` as order-svc's HTTP lines, because the outbox copied it into the Kafka headers and the `RecordInterceptor` restored it.

---

## Step 5: Watchdog and resilience drills

### Drill 1: Hung loop → liveness DOWN (the process-level watchdog)

```bash
ADMIN=$(scripts/token.sh admin admin)
curl -s -X POST -H "Authorization: Bearer $ADMIN" "localhost:8000/api/orders/admin/debug/hang?on=true"

# Poll liveness directly on the container (health endpoints aren't exposed through the gateway)
for i in $(seq 1 8); do
  docker exec quickbite-order-svc-1 curl -s localhost:8082/actuator/health/liveness | jq -c '{status, stuck: .components.loopWatchdog.details.stuckLoops}'
  sleep 5
done
# After ~30 s: {"status":"DOWN","stuck":["dispatcher"]}

curl -s -X POST -H "Authorization: Bearer $ADMIN" "localhost:8000/api/orders/admin/debug/hang?on=false"
```

**What you learned:**

- The process was **alive**: HTTP still answered and the port was open. But a critical loop was dead, so orders would sit in PAID forever. Only an internal heartbeat detects this.
- **Docker Compose does not restart unhealthy containers.** It restarts only containers that *exit*. **Kubernetes does**: a failing liveness probe restarts the container. You will see that in file 12.
- For plain Docker hosts, an "autoheal" sidecar or systemd (below) closes the gap.

### Drill 2: JVM crash → automatic restart

```bash
# Kill every process the app user owns inside the container (the JVM). tini (PID 1) then exits with the child's code.
docker exec quickbite-order-svc-1 sh -c 'kill -9 -1' || true
docker ps --filter name=order-svc --format '{{.Names}} {{.Status}}'   # "Up 3 seconds (health: starting)"
docker inspect -f '{{.RestartCount}}' quickbite-order-svc-1            # incremented
```

Meanwhile, place an order. The saga just waits: events sit in the outbox/Kafka until order-svc is back. **No data is lost, only delayed.** That is the payoff of asynchronous messaging.

### Drill 3: Graceful shutdown

```bash
docker compose -f deploy/compose/docker-compose.yml -f deploy/compose/docker-compose.apps.yml stop -t 30 payment-svc
docker logs quickbite-payment-svc-1 2>&1 | grep -i -E "graceful|shutdown" | tail -5
```

You should see "Commencing graceful shutdown": in-flight requests finish, Kafka offsets are committed, and the process exits. This works because tini forwarded SIGTERM to the JVM. Without an init process, a JVM running as PID 1 can miss signals and be SIGKILLed after the timeout.

### Drill 4: Horizontal scaling in Compose (preview of file 12)

```bash
cd deploy/compose
docker compose -f docker-compose.yml -f docker-compose.apps.yml up -d --scale payment-svc=3 --no-recreate
docker exec -it quickbite-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group payment-svc
```

The 3 partitions of `orders.events` are now spread over 3 consumers, one each. Scale to 4 and one consumer sits **idle**:

> **Partitions cap consumer parallelism.** This is why partition counts are sized for *future* peak (the capacity sheet chose 48 for `rider.location`).

(Scaling order-svc this way would give two replicas the same `NODE_ID`. Kubernetes handles that properly in file 12.)

---

## Optional: systemd watchdog on a plain Linux VM

If you deploy a jar on a VM without containers, systemd can act as the watchdog:

`/etc/systemd/system/order-svc.service`

```ini
[Unit]
Description=QuickBite order-svc
After=network-online.target

[Service]
User=quickbite
ExecStart=/usr/bin/java -XX:+ExitOnOutOfMemoryError -jar /opt/quickbite/order-svc.jar
Type=notify
NotifyAccess=all
WatchdogSec=45
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Then add a small component that pings systemd **only while the `LoopWatchdog` is healthy**:

```java
@Component
@ConditionalOnProperty("quickbite.systemd.enabled")
class SystemdNotifier {
    private final LoopWatchdog watchdog;
    SystemdNotifier(LoopWatchdog watchdog) { this.watchdog = watchdog; }

    @EventListener(ApplicationReadyEvent.class)
    void ready() { notify("READY=1"); }

    @Scheduled(fixedRate = 15_000)
    void ping() { if (watchdog.health().getStatus().equals(Status.UP)) notify("WATCHDOG=1"); }

    private void notify(String state) {
        try { new ProcessBuilder("systemd-notify", state).inheritIO().start().waitFor(); } catch (Exception ignored) {}
    }
}
```

If the dispatcher hangs, the pings stop. systemd waits `WatchdogSec`, then kills and restarts the process. This is the same idea as Android's watchdog and Kubernetes' liveness probe, at the OS level.

---

## Verify

| Check | Pass condition |
|---|---|
| `docker compose ps` | 12+ containers, all `healthy` |
| `scripts/smoke.sh` | Ends `DELIVERED`, payment `CAPTURED` |
| Session-id grep | The same `sid` in order-svc and payment-svc logs |
| Drill 1 | Liveness `DOWN` with `stuckLoops: [dispatcher]`, `UP` again after un-hanging |
| Drill 2 | Container restarted; an order placed during the outage completes afterwards |
| Drill 4 | Consumer group shows 3 members with 1 partition each |

You now have a complete, containerized backend. **Next: the Android app (file 10).**
