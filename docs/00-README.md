# QuickBite — Local Implementation Guide

This guide takes you from an empty folder to a working microservices platform running on your laptop, which you then test end to end from an Android phone or emulator.

You will build **6 Spring Boot services**, run them on **Docker Compose** and then on **Kubernetes (kind)**, secure them with **Keycloak (OAuth2 + JWT)**, connect them with **Kafka**, and drive the whole flow from a **Kotlin/Compose Android app**. Every file ends with a **"Verify"** section. Do not move on until it passes.

---

## What you will have at the end

```
 Android app (customer: alice)          Android app / script (rider: bob)
     │  HTTP + WebSocket                          │ WebSocket GPS
     ▼                                            ▼
 adb reverse ──► NGINX :8000 ──► Spring Cloud Gateway :8080
                                   │  JWT check · session-id · rate limit
     ┌──────────────┬──────────────┼──────────────┬──────────────┐
     ▼              ▼              ▼              ▼              ▼
 catalog-svc    order-svc     payment-svc    location-svc   realtime-svc
  :8081          :8082          :8083          :8084          :8085
 Mongo+Redis   Postgres        Postgres       Redis GEO      WebSocket fan-out
                  │  outbox        │  outbox       │               ▲
                  └──────► Kafka (KRaft) ◄────────┴───────────────┘
 Keycloak :8180 issues tokens for everything
```

**The happy path you will test from the phone:**

1. Alice logs in (OAuth2 authorization code + PKCE).
2. She browses restaurants (served from a Redis cache).
3. She places an order. order-svc prices it server-side and writes the order plus an outbox event.
4. payment-svc consumes `order.created` and authorizes through a flaky fake PSP guarded by a circuit breaker. It publishes `payment.authorized`.
5. order-svc marks the order PAID. Its dispatcher finds the nearest free rider via Redis `GEOSEARCH` and claims them with an atomic `SET NX`.
6. Alice's phone receives live status and rider GPS over WebSocket.
7. Bob marks the order picked up, then delivered. payment-svc captures the payment.

Payment uses alice's saved card. Pick a **decline test card** in the app and step 4 instead ends in `payment.failed` → order `CANCELLED`, with the decline reason shown on the phone.

---

## Files (do them in order)

| # | File | What you build | Topics covered |
|---|---|---|---|
| 00 | `00-README.md` | This roadmap | – |
| 01 | `01-prerequisites-and-setup.md` | Tools, versions, machine sizing | – |
| 02 | `02-monorepo-gradle-and-common-lib.md` | Gradle monorepo, convention plugins, shared library (session-id, security, watchdog, outbox, IDs) | 1, 3, 9 |
| 03 | `03-local-infrastructure-compose.md` | Postgres, Mongo, Redis, Kafka (KRaft), Keycloak realm, NGINX | 4, 5, 7 |
| 04 | `04-api-gateway-oauth-jwt-session.md` | Spring Cloud Gateway, JWT validation, session revocation, rate limiting | 1, 3, 7 |
| 05 | `05-catalog-service.md` | MongoDB, cache-aside with Redis, ETag/CDN headers | 7, 8 |
| 06 | `06-order-service.md` | Order state machine, outbox, saga, dispatcher, service-to-service auth | 1, 5, 7 |
| 07 | `07-payment-service.md` | Circuit breaker, retry, idempotency, dead-letter topics | 5, 7, 8 |
| 08 | `08-location-and-realtime-services.md` | WebSockets, Redis GEO, distributed claim lock, fan-out | 2, 7, 8 |
| 09 | `09-containerize-and-run-everything.md` | Dockerfiles, full stack in Compose, watchdog drills | 4, 8, 9 |
| 10 | `10-android-app.md` | Kotlin/Compose app: AppAuth PKCE, Retrofit, WebSockets, map | 2, 3 |
| 11 | `11-testing-guide.md` | Unit, integration (Testcontainers), security, E2E script, k6 load, chaos, mobile test plan | 8, 10 |
| 12 | `12-kubernetes-local-kind.md` | kind cluster, Strimzi, Helm chart, Gateway API, HPA, scaling experiments | 4, 6, 11 |
| 13 | `13-spark-surge-pricing.md` | Spark Structured Streaming on Kafka, Parquet lake, surge into Redis | 4, 5 |
| 14 | `14-troubleshooting.md` | Symptom → cause → fix table | – |
| 15 | `15-test-data.md` | **Test data reference:** users, 22 restaurants, test cards, scenario matrix, helper scripts | 10 |
| 16 | `16-demo-mode.md` | **Demo Mode:** JSON demo pack, `DEMO_MODE` server setting, virtual riders (demo-svc), Android Settings screen with Server/Offline demo | 1, 2, 5, 9, 10 |

Topic numbers match your original list (1 = services + session-id … 11 = horizontal/vertical scaling).

**Suggested pace (part-time):** files 01–04 in week 1–2, 05–08 in weeks 3–5, 09–10 in week 6, 11 in week 7, and 12–13 in weeks 8–9.

---

## Demo Mode in 30 seconds (file 16)

Everything demo-related is driven by one JSON **demo pack** in `demo-data/` (8 demo restaurants, a demo customer, 3 virtual riders, order history, test cards, timings).

- **Server demo:** `scripts/up-demo.sh` starts the stack with `DEMO_MODE=true`. The **demo-svc** virtual riders then fulfil every order automatically. In the app: ⚙ → *Server demo* → log in `demo` / `demo`.
- **Offline demo:** in the app, ⚙ → *Offline demo* → *Start demo*. It needs no server or network; the phone serves the same JSON and simulates delivery.
- **Safety:** the server refuses `DEMO_MODE=true` unless `APP_ENV` is `local`, `dev` or `staging`, and release builds of the app hide demo mode.

## Ports used on your machine

| Port | What | Notes |
|---|---|---|
| 8000 | NGINX (edge) | **The phone talks only to this** (via `adb reverse`) |
| 8080 | API gateway | Direct access for curl debugging |
| 8081–8085 | catalog, order, payment, location, realtime | Only while running services from the IDE |
| 8180 | Keycloak | Also reversed to the phone, for the login page |
| 5432 / 27017 / 6379 / 9092 | Postgres / Mongo / Redis / Kafka | |
| 8090 | Kafka UI (optional) | Browse topics and messages |
| 8086 | demo-svc (Demo Mode only) | Virtual riders; reached via `/api/demo/**` on the gateway |

## Test users (created by the realm import in file 03)

| User / password | Role | Used for |
|---|---|---|
| `alice` / `alice` | customer | Places orders |
| `bob` / `bob` | rider | Delivers orders |
| `carol` / `carol` | rider | Second rider, to test dispatch choice |
| `admin` / `admin` | admin + customer | Chaos/debug endpoints; has a Mastercard and an Amex |
| `demo` / `demo` | customer | Demo Mode customer (cards and order history come from the demo pack) |
| `demo-rider-1..3` / `demo-rider` | rider | Virtual riders, logged in by demo-svc (don't use them manually) |

User ids are **fixed** in the realm import (alice = `1111…`, bob = `2222…`, carol = `3333…`, admin = `4444…`), so services can seed data per user.

## Test data at a glance (details in `15-test-data.md`)

- **22 restaurants / 132 menu items** in two cities (`blr` 16, `mum` 6), loaded from `seed/restaurants.json`. The last item of every menu is **sold out**, and `r16` is a 1.8× price outlier.
- **Fake cards** (Stripe-style magic numbers, all Luhn-valid). Any future expiry and any CVC (4 digits for Amex):

| Card | Result |
|---|---|
| `4242 4242 4242 4242` | Approved (alice's default) |
| `4000 0000 0000 0002` | Declined `card_declined` |
| `4000 0000 0000 9995` | Declined `insufficient_funds` |
| `4000 0000 0000 0119` | Transient PSP error → retries → `PSP_UNAVAILABLE` |
| `4000 0000 0000 1976` | Approved after 2.5 s (slow call) |
| `4000 0000 0000 0341` | Authorized; capture fails → dead-letter topic |

Card numbers never leave payment-svc's fake PSP: services and databases hold only `pm_…` / `tok_…` tokens plus brand and last 4 digits.

## Version baseline (Sept 2026)

Java 25 LTS · Spring Boot 4.0.x · Spring Cloud 2025.1.x · Kafka 4.1 (KRaft only) · Keycloak 26 · PostgreSQL 17 · MongoDB 8 · Redis 7.4 · Gradle 9.1+.

> **Spring Boot 4 note:** Boot 4 split auto-configuration into per-technology modules and renamed some starters (for example, `spring-boot-starter-web` → `spring-boot-starter-webmvc`, and the OAuth2 starters now live under `spring-boot-starter-security-*`). This guide uses the new names. The convention plugin also adds `spring-boot-properties-migrator`, which logs a warning if any property key used here was renamed in your exact patch version.

## How to read each file

Each step has three parts:

- **Do:** the commands or code.
- **Why:** the concept behind it (this is the part you are learning).
- **Verify:** a concrete check.

Code blocks are complete files unless marked `// ...`. Paths are relative to the repo root `quickbite/`.
