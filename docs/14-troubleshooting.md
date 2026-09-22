# 14 — Troubleshooting

Find your symptom, check the cause, and apply the fix. Most issues fall into five buckets: **issuer/URLs, ports/networking, Boot 4 renames, Kafka listeners, and memory**.

---

## Getting a token (`scripts/token.sh` prints nothing, `null`, or an error)

Run the request by hand to see Keycloak's real answer:

```bash
curl -sS -X POST http://localhost:8180/realms/quickbite/protocol/openid-connect/token \
  -d grant_type=password -d client_id=cli-test -d username=alice -d password=alice -d scope=openid; echo
```

| What you see | Cause | Fix |
|---|---|---|
| `bash: scripts/token.sh: No such file or directory` / `Permission denied` | Script not created, or not executable | Create it from file 03 (or re-run `extract_code.py` on chapter 03); `chmod +x scripts/*.sh`; run from the **repo root** |
| `jq: command not found` | jq missing | `sudo apt install jq` |
| `curl: (7) Failed to connect to localhost port 8180` | Keycloak not running, still starting, or its port isn't mapped | `docker compose ps keycloak` → wait for `healthy` (30–90 s); `docker compose logs keycloak \| tail -30` |
| `{"error":"Realm does not exist"}` (HTTP 404) | Realm JSON not imported (wrong mount path, or invalid JSON) | `docker compose logs keycloak \| grep -i -E "import\|realm"`; check `deploy/keycloak/quickbite-realm.json` exists and is valid (`jq . deploy/keycloak/quickbite-realm.json > /dev/null`); then recreate: `docker compose rm -sf keycloak && docker compose up -d keycloak` |
| `"error":"invalid_client"` / `"unauthorized_client"` / `Client not allowed for direct access grants` | The `cli-test` client is missing or has password grant disabled | Same as above: the realm file didn't import completely; recreate the container |
| `"error":"invalid_grant","error_description":"Invalid user credentials"` | User missing, or wrong password | Users are `alice/alice`, `bob/bob`, `admin/admin`, `demo/demo`; check in the admin console (`http://localhost:8180`, admin/admin → realm **quickbite** → Users) |
| `"Account is not fully set up"` | The user has a pending required action (e.g. missing email/first/last name after a hand-edited realm) | Admin console → Users → alice → remove *Required user actions*, and fill in email/first/last name |
| `"Account disabled"` / `"Account temporarily disabled"` | Brute-force protection locked the user after failed attempts | Wait ~1 min, or admin console → Users → alice → *Enabled*; or recreate the container |
| HTTP 200, but `aud`/`sid` missing when decoded | Realm imported from an older JSON | Recreate the Keycloak container so the current realm file is imported |

> **Why recreating works:** in `start-dev` mode Keycloak keeps its database *inside the container*, and `--import-realm` only imports a realm that doesn't exist yet. Removing the container discards the old realm, and the next start re-imports the JSON.

## Authentication and tokens

| Symptom | Likely cause | Fix |
|---|---|---|
| Every call is `401`, and the gateway log says `The iss claim is not valid` | The token's `iss` ≠ the expected issuer. Usually Keycloak was reached via a different host (`10.0.2.2`, a LAN IP), or `KC_HOSTNAME` isn't applied. | Use `localhost` everywhere via `adb reverse`/port-forward. Check `scripts/jwt-decode.sh` → `iss` must be `http://localhost:8180/realms/quickbite`. Recreate the Keycloak container. |
| `401` with `The aud claim is not valid` | The audience mapper is missing on the client that issued the token | Check the `protocolMappers` of `android-app`/`cli-test` in the realm JSON; re-import the realm (`docker compose rm -sf keycloak && docker compose up -d keycloak`). |
| Gateway `401 token has no session id` | Your Keycloak version doesn't put `sid` in access tokens | In the admin console → Client scopes → **basic**, check for a session-id mapper, or add a "User Session Note" mapper. The gateway already falls back to `session_state`. |
| Services fail at first request: `Couldn't retrieve remote JWK set` | The JWKS URL is unreachable from the service | In containers, use `http://keycloak:8180/...` (`KC_JWKS`), never `localhost`. |
| `403` although you're logged in | Missing role: realm roles aren't mapped | Decode the token and check `realm_access.roles`; confirm `realmRoles` in the realm JSON. Remember `hasRole('rider')` expects `ROLE_rider`. |
| Realm changes ignored | The import runs only if the realm doesn't exist | `docker compose rm -sf keycloak && docker compose up -d keycloak` (dev mode keeps data in the container). |
| Order-svc → location-svc `401`/`403` | The service-account token lacks the `service` role, or the secret is wrong | Test the client credentials with the curl in file 03; check `ORDER_SVC_SECRET`. |

## Gateway input validation (file 04, step 5)

| Symptom | Likely cause | Fix |
|---|---|---|
| `400 Request validation failed`, `errors` mention a field you just added | The route's JSON Schema doesn't know the new field (`additionalProperties: false`) | Add the field to `validation/schemas/<name>.schema.json` **in the same change** as the service |
| `404` for an endpoint that exists in the service | The hardened routes are an allow-list; the new path/method isn't listed, or the id doesn't match the typed pattern | Add a route with exact `Path` + `Method` (+ validation filters) |
| `415 Content-Type must be application/json` | A client sends a body without `Content-Type: application/json` (e.g. `curl -d` without `-H`) | Add the header |
| `400 Request body not allowed for GET` / `This endpoint takes no request body` | A client sends a body on GET or on action endpoints (pickup/deliver/pause) | Send no body |
| `401 Malformed Authorization header` before any token check | Not `Bearer <a>.<b>.<c>`: an extra space, quotes, `Basic`, or a truncated token | Fix the header; decode it with `scripts/jwt-decode.sh` |
| `400 Repeated query parameter` | `?ids=a&ids=b` style | Use a comma list: `?ids=a,b` |
| Correlation ids from a client never show up in logs | The id didn't match `[A-Za-z0-9_-]{1,64}`, so the gateway dropped it (and generated a new one) | Send UUIDs |
| WebSocket from a browser dashboard gets `403` | `Origin` isn't allow-listed | Add it to `quickbite.validation.ws-allowed-origins` |
| Gateway fails to build routes: `Unknown validation schema 'x'` | Typo in `application.yml` | Fix the name; `JsonSchemaRegistryTest.allReferencedSchemasExist` catches this in CI |

## Payments and test data

| Symptom | Likely cause | Fix |
|---|---|---|
| Every order `CANCELLED`, reason `NO_PAYMENT_METHOD` | The user has no saved card, or the ids don't match the seeded ones | `GET /api/payments/methods`. If it's empty for alice, the realm was imported **before** the fixed ids were added: recreate the Keycloak container, then restart payment-svc (the seeder runs only for users with no cards). |
| alice has cards, but a specific order fails `NO_PAYMENT_METHOD` | The `paymentMethodId` belongs to another user (ownership check) or was deleted | Use an id from alice's own `GET /api/payments/methods`. |
| `400 Invalid card number` for a "real-looking" number | Luhn check failed (a typo) | Copy from the test-card table; all of them are Luhn-valid. |
| `400 CVC must be 4 digits` | Amex needs 4 digits | Use `1234` for `3782 822463 10005`. |
| `400 Card has expired` | Expiry in the past | Use `12/2030`. |
| Declines open the circuit breaker | Declines modelled as exceptions | Declines must be **returned** (`AuthResult.approved=false`); only `PspTransientException` counts as a failure (file 07). |
| `0341` order delivered but payment still `AUTHORIZED` | Expected: that card simulates capture failure | Look in `orders.events.dlt`; after "fixing" (e.g. restart payment-svc, which clears the fake PSP's memory), replay the DLT message. |
| Restaurant list shows the old 6 restaurants | Catalog seeded earlier, and the seeder only fills an empty collection | Run once with `SEED_RESET=true`. Also purge the NGINX edge cache: `docker exec quickbite-nginx-1 sh -c 'rm -rf /var/cache/nginx/edge/*'`. |
| `409 … is sold out` | You ordered the last menu item, which is sold out by design | Pick another item (or it's the test you meant to run). |
| Flyway `checksum mismatch` in payment-svc | You edited `V1__init.sql` after it had been applied | Never edit applied migrations; put changes in `V2__…`. Locally, `docker compose down -v` wipes the data. |

## Demo Mode (file 16)

| Symptom | Likely cause | Fix |
|---|---|---|
| Service fails at startup: `Invalid demo pack: - …` | A broken reference or bad value in `demo-data/*.json` | Read the listed problems; fix the JSON; run `./gradlew :libs:demo-data:test` |
| `DEMO_MODE=true is not allowed in environment 'prod'` | The safety guard | Intended. Use `APP_ENV=local/dev/staging` for demos. |
| Demo orders stay `PAID` forever | demo-svc not running, or its riders can't log in | `docker logs quickbite-demo-svc-1`: a 401 at login means the realm lacks the `demo-rider-*` users (recreate the Keycloak container); also check `GET /api/demo/status` isn't `paused` |
| Virtual riders never get assigned | The delivery point is more than 5 km from every rider start | Use the pack's Home/Office addresses, or add a rider near your point in `riders.json` |
| Two riders show the same name moving twice | demo-svc scaled to more than 1 replica | Keep `replicas: 1` (singleton worker) |
| `d*` restaurants still visible after turning demo off | Catalog restarted without the loader, or the NGINX edge cache | Restart catalog-svc (it deletes `d*` when demo is off); purge the NGINX cache |
| App banner says "(server not in demo mode!)" | App is in *Server demo*, backend started with `scripts/up.sh` | Start with `scripts/up-demo.sh`, or switch the app to *Offline demo* |
| Offline demo: "Not available in Offline demo: …" | The endpoint isn't simulated on the device (e.g. rider APIs) | Use *Server demo* for rider mode |
| App crash: "Demo pack v2 is not supported" | The JSON pack's `manifest.version` is newer than the app | Rebuild the app (the pack is copied into assets at build time) |
| App still shows old demo data after editing the JSON | Assets are copied at build time; `demo-state.json` persists | Rebuild/reinstall, then ⚙ → *Reset offline demo data* |

## Android

| Symptom | Likely cause | Fix |
|---|---|---|
| Login page won't load in the Custom Tab | Port 8180 not reversed | `scripts/android-reverse.sh`; re-run after every reconnect or emulator restart. |
| `only https connections are permitted` | AppAuth's default connection builder | Use a **debug** build (`DevConnectionBuilder` applies when `BuildConfig.DEBUG`). |
| `CLEARTEXT communication not permitted` | The network security config is missing or has the wrong domain | Check `android:networkSecurityConfig` in the manifest and `localhost` in the XML. |
| Redirect after login doesn't return to the app | Redirect URI mismatch | `manifestPlaceholders["appAuthRedirectScheme"] = "com.quickbite.app"` and Keycloak `redirectUris` = `com.quickbite.app:/oauth2redirect`, **exactly**. |
| `Invalid parameter: redirect_uri` on the Keycloak page | Same as above, on the Keycloak side | Fix the realm JSON and re-import. |
| App works, but there are no live updates | WebSocket blocked or not authorized | `adb logcat \| grep -i websocket`; test with `websocat` from the laptop; check the NGINX `/ws/` block. |
| Map is grey | OSM tiles blocked, or no user agent | Check internet on the device; `Configuration.getInstance().userAgentValue` is set in `QuickBiteApp`. |
| Chrome on the device can't reach `localhost:8000` | `adb reverse` lost, or NGINX down | `adb reverse --list`; `curl localhost:8000/nginx-health` on the laptop. |

## Spring Boot 4 / build

| Symptom | Likely cause | Fix |
|---|---|---|
| `Could not resolve org.springframework.boot:spring-boot-starter-xyz` | Boot 4 starter rename | See the migration guide's starter list, e.g. `-web` → `-webmvc`, `oauth2-resource-server` → `security-oauth2-resource-server`. For Kafka, fall back to `org.springframework.kafka:spring-kafka`. |
| `package org.springframework.boot.xxx does not exist` | Boot 4 moved classes into modules | Let the IDE re-import; search the class name in the Boot 4 API docs. |
| `Included health contributor 'mongo' does not exist` | The readiness group names a contributor that doesn't exist | Start with `include: readinessState`, open `/actuator/health`, and copy the real names. |
| Property seems ignored | The key was renamed in Boot 4, **or** it's also defined in `quickbite-defaults.yml` (the import wins) | Read the `spring-boot-properties-migrator` warnings at startup; keep the keys disjoint; override via env vars. |
| `@Retryable` attribute not found | The Spring Framework 7 attribute names differ in your patch | Open the annotation source in the IDE and adjust (`maxRetries`, `delay`, `jitter`, `multiplier`). |
| `ConnectionDetailsFactoryNotFoundException` in tests | The Testcontainers module doesn't match the technology starter | Make sure `spring-boot-starter-data-jpa` (which brings JDBC) is present for Postgres containers. Use Testcontainers 2 artifacts (`testcontainers-postgresql`). |
| Jackson `ObjectMapper` not found / wrong type | Boot 4 uses Jackson 3 (`tools.jackson.*`) | Inject `tools.jackson.databind.json.JsonMapper`. Annotations stay `com.fasterxml.jackson.annotation`. |

## Kafka

| Symptom | Likely cause | Fix |
|---|---|---|
| `Connection to node 1 (kafka/…:19092) could not be established` from an IDE-run service | The client uses the internal advertised listener | From the host, bootstrap with `localhost:9092` (the EXTERNAL listener). |
| `UNKNOWN_TOPIC_OR_PARTITION` at startup | The topic isn't created yet (auto-create is off) | Normal until the declaring service starts; each service declares its topics via `KafkaAdmin.NewTopics`. |
| DLT publish fails: `partition N does not exist` | The DLT has fewer partitions than its source | Create `X.dlt` with the same partition count (and re-alter after increasing partitions). |
| One consumer idle | More consumers than partitions | Expected. Partitions cap parallelism; increase partitions (file 12, rung 5). |
| Events not consumed after reset | The consumer group is active during `--reset-offsets` | Stop the service first, reset, then start it again. |
| Outbox rows stuck `published_at is null` | Kafka unreachable, or the relay loop hung | Check `/actuator/health/liveness` (`loopWatchdog` → `outbox-relay`) and the Kafka health. |

## Runtime / infrastructure

| Symptom | Likely cause | Fix |
|---|---|---|
| Containers restart randomly, exit code 137 | OOM kill (container limit or Docker VM) | `docker stats`; raise `memory` limits or the Docker VM RAM; the JVM heap is 75% of the limit. |
| NGINX `502` | The gateway isn't running or `GATEWAY_UPSTREAM` is wrong | IDE mode: `host.docker.internal:8080`; container mode: `gateway:8080` (`docker-compose.apps.yml`). |
| `host.docker.internal` unresolved on Linux | Missing host-gateway mapping | `extra_hosts: ["host.docker.internal:host-gateway"]` (already in file 03). |
| Many `429`s in load tests | The per-user gateway rate limit (20 rps) | Use many load users (file 11) or raise `replenishRate` for the test. |
| Orders stuck in `PAID` | No alive rider nearby, the location breaker is open, or the dispatcher is hung | Run `rider-sim.sh`; check `GEOSEARCH` in Redis; check liveness `stuckLoops`. |
| Orders `CANCELLED` immediately | PSP failure injection left on | `POST /api/payments/admin/psp {"latencyMs":150,"failureRate":0.0}`. |
| Duplicate primary key on orders when scaled | Two instances share a Snowflake node id | Unique `NODE_ID` per instance (Compose) or the sequence-based `IdConfig` (file 12). |

## Kubernetes (kind)

| Symptom | Likely cause | Fix |
|---|---|---|
| `ErrImagePull` / `ImagePullBackOff` | Image not loaded into kind | `kind load docker-image quickbite/<svc>:dev --name quickbite`; `imagePullPolicy: IfNotPresent`. |
| Pod `CrashLoopBackOff`: "Read-only file system" | `readOnlyRootFilesystem` and the app writes outside `/tmp` | Find the path in the logs and mount an `emptyDir` there. |
| Pods restart during startup | Liveness fires before the JVM is up | The startup probe must cover warm-up (`failureThreshold × periodSeconds` ≥ worst-case start). |
| `kubectl top` / HPA shows `<unknown>` | metrics-server isn't ready or lacks `--kubelet-insecure-tls` | Apply the patch from file 12, step 1; wait 60 s. |
| Gateway not `PROGRAMMED` | Gateway API CRDs/NGF version mismatch | Install the CRDs from the same NGF release you installed with Helm; check `kubectl describe gateway edge`. |
| Port-forward drops under load | `kubectl port-forward` is a dev tool, not a load balancer | For heavy load tests, run k6 **inside** the cluster (`kubectl run k6 --image=grafana/k6 ...`) against `gateway:8080`. |

## Spark

| Symptom | Likely cause | Fix |
|---|---|---|
| `Failed to find data source: kafka` | Connector not on the classpath | `--packages org.apache.spark:spark-sql-kafka-0-10_2.13:<spark version>`; the Scala and Spark versions must match the image. |
| `UnsupportedClassVersionError` | The job was compiled for a newer Java than the Spark image runs | Keep the job toolchain at 17 (file 13). |
| No surge output | No traffic in the last minute, or the wrong network | Generate orders and rider locations; check `--network quickbite_default`. |
| Permission denied writing `/data` | The Spark image runs as a non-root user | Use `--user root` locally, or `chown` the volume. |

---

## General debugging method

1. **Follow the request:** phone → NGINX (`docker logs quickbite-nginx-1`) → gateway (`401/403/429/5xx` reason) → service logs. Filter by `cid=` (correlation id) or `sid=`.
2. **Follow the event:** outbox row → Kafka topic (the console consumer with `print.headers`) → consumer logs → DLT.
3. **Check health:** `curl localhost:808x/actuator/health` shows each component's status and the watchdog details.
4. **Change one thing at a time**, and re-run `scripts/e2e.sh` after every fix.
