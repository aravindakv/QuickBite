# 18 — Capacity Sizing: Servers per Service for a 10-Year Horizon

Every number below is produced by the model in `scripts/capacity_model.py`; re-run it after changing any assumption.

## 1. Given constraints

| Constraint | Value | Used as |
|---|---|---|
| App server throughput | **30,000 rps** | Nominal capacity of one server for a *lightweight* request |
| Design utilisation | 60% | 3 AZs: losing one pushes survivors to 60% × 1.5 = 90% |
| Cache server | **4 GB** (75% usable = 3 GB) | Redis/Valkey shard size |
| Storage server | **20 TB** (70% usable = 14 TB) | One DB/object-store node |
| Horizon | **10 years**, 15%/yr growth | Cumulative data, year-10 peak traffic |
| Replication | 3× hot stores, 1.5× erasure-coded cold | Durability |

## 2. Derived load (year 10, peak)

Starting point: 10,000,000 DAU and 2,000,000 orders/day in year 1, compounding 15%/yr.

| Metric | Year 10 | How |
|---|---|---|
| Daily active users | 35,178,763 | 10M × 1.15⁹ |
| Orders/day | 7,035,753 | 2M × 1.15⁹ |
| Peak API rate | **61,074 rps** | DAU × 30 calls ÷ 86,400 × 5 |
| Peak order rate | 814 /s | orders/day ÷ 86,400 × 10 (meal rush) |
| Orders in flight | 1,954,376 | Little's Law: λ × 40 min |
| Riders online | 1,074,907 | 0.55 per in-flight order |
| GPS events | **268,727 /s** | riders ÷ 4 s |
| WebSocket connections | **3,079,282** | riders + tracking customers + dashboards |
| Concurrent sessions | 7,035,753 | 20% of DAU |

## 3. CPU / app servers

`servers = ceil( load ÷ (30,000 ÷ cost_factor × 0.60) )`, minimum 3 for AZ spread.

**The cost factor is the honest part.** 30,000 rps/server holds for proxying or a cache hit. A request that opens a DB transaction, calls another service, or waits on a payment provider costs far more, so its effective capacity is 30,000 ÷ factor.

| Service | Work | Peak load (/s) | Cost factor | Effective rps/server | Servers |
|---|---|---|---|---|---|
| `gateway` | Edge: JWT check, session lookup, routing | 61,074 | 1× (proxy + 2 Redis ops) | 30,000 | **4** |
| `auth` | Keycloak: token issue + refresh | 8,428 | 6× (RSA sign + DB) | 5,000 | **3** |
| `catalog` | Restaurant/menu reads (after CDN) | 19,849 | 2× (cache hit + serialize) | 15,000 | **3** |
| `order` | Place order, state machine, outbox | 12,215 | 15× (catalog call + DB tx) | 2,000 | **11** |
| `payment` | Authorize/capture via PSP | 2,443 | 25× (external call, mostly waiting) | 1,200 | **4** |
| `location-ingest` | Rider GPS over WebSocket | 268,727 | 3× (Redis GEOADD + Kafka produce) | 10,000 | **45** |
| `location-rest` | Rider REST (status, nearest) | 3,054 | 3× (Redis GEOSEARCH) | 10,000 | **3** |
| `realtime` | WebSocket fan-out to phones | 273,613 | 1× (in-memory push) | 30,000 | **16** |

**Total app servers: 89** (before multi-region, see §7).

The two heavyweights are not the ones you'd guess from user-facing traffic: `location-ingest` (268,727 GPS events/s) and `realtime` (3,079,282 sockets). Ordering itself needs 11 servers.

## 4. Memory

Sizing rule: 32 GB per app server; 64 GB for `realtime`, which holds ~250k sockets per server (~40 KB each, buffers included).

| Service | Servers | RAM/server | RAM total |
|---|---|---|---|
| `gateway` | 4 | 32 GB | 128 GB |
| `auth` | 3 | 32 GB | 96 GB |
| `catalog` | 3 | 32 GB | 96 GB |
| `order` | 11 | 32 GB | 352 GB |
| `payment` | 4 | 32 GB | 128 GB |
| `location-ingest` | 45 | 32 GB | 1440 GB |
| `location-rest` | 3 | 32 GB | 96 GB |
| `realtime` | 16 | 64 GB | 1024 GB |

**Total app-tier RAM: 3,360 GB** (~3.4 TB). Cache and storage RAM are counted separately below.

## 5. Cache servers (4 GB each)

| Cached data | Size |
|---|---|
| sessions (gateway/auth) | 7.04 GB |
| menu cache (catalog) | 10.00 GB |
| order status (order/realtime) | 1.95 GB |
| rider geo index (location) | 0.16 GB |
| rate-limit buckets (gateway) | 0.70 GB |
| ws routing registry (realtime) | 0.31 GB |
| idempotency keys (payment) | 1.41 GB |

Sum × 1.5 (overhead, fragmentation, failover headroom) = **32.4 GB**.

- By memory: 32.4 GB ÷ 3 GB usable = **11 shards**
- By throughput: 411,997 ops/s ÷ 50k per node (100k at 50%) = **9 shards**
- Take the larger, add one replica each: **11 shards × 2 = 22 cache servers**

Memory binds here, not operations. That is typical: cache nodes run out of RAM long before CPU.

## 6. Storage servers (20 TB each, 10-year cumulative)

Cumulative orders over 10 years: **14.8 billion** (year-1 volume × 20.3).

| Store | 10-year size (incl. replication) | Servers |
|---|---|---|
| orders + lines + outbox (Postgres) | 346.8 TB | **25** |
| payments ledger (Postgres) | 86.7 TB | **7** |
| catalog + menus (MongoDB) | 0.9 TB | **1** |
| Kafka (7-day retention) | 58.5 TB | **5** |
| event lake hot: Parquet, 90 days | 30.1 TB | **3** |
| event lake cold: object store, 10 y (EC 1.5x) | 352.2 TB | **26** |

**Total storage servers: 67**

Two decisions do most of the work here:

- **Tiering the event lake.** GPS history dominates. Keeping 90 days hot (Parquet, 3×) and the rest in erasure-coded object storage costs 26 servers instead of roughly twice that at 3× replication.
- **Retention on orders.** 25 servers hold 10 years of orders online. Archiving anything older than 2 years to the lake would cut that by ~75%, at the cost of slower historical queries. Decide this with product and legal, not in isolation.

## 7. Network

| Flow | Peak |
|---|---|
| gateway API in | 0.49 Gbps |
| gateway API out | 2.44 Gbps |
| CDN origin fill | 0.24 Gbps |
| WebSocket in (GPS) | 0.43 Gbps |
| WebSocket out | 0.44 Gbps |
| Kafka replication | 0.77 Gbps |
| east-west (services) | 1.47 Gbps |

**Total ≈ 6.3 Gbps** at peak, which is ~71 Mbps per app server. 25 Gbps NICs are far from saturated; **packet rate**, not bandwidth, is the real constraint for the 268,727/s GPS stream (small messages). Size load balancers by connections (3,079,282 long-lived sockets), not by throughput.

## 8. Multi-region totals

With **R regions each able to absorb peak ÷ (R−1)**, three regions means each carries 50% of global peak:

| Tier | Single region | 3 regions (50% each) |
|---|---|---|
| App servers | 89 | 45 × 3 = **135** |
| Cache servers | 22 | 11 × 3 = **33** |
| Storage servers | 67 | data is **city-partitioned**, so it splits rather than duplicates: ~23 per region = **69** + cross-region async copies |

Storage behaves differently from compute: a Mumbai order lives in the Mumbai region, so three regions split the data instead of tripling it. Only the disaster-recovery copy adds volume.

## 9. Sanity checks and caveats

1. **30,000 rps/server is an input, not a measurement.** A Spring Boot service doing a DB transaction realistically serves 1,000–3,000 rps per 8-core node; that is what the cost factors encode. Replace both with your Phase 10 benchmarks (file 12, scaling ladder) before trusting any of this.
2. **Little's Law does the heavy lifting.** In-flight orders (1,954,376) follow from arrival rate × 40-minute delivery, and they drive sockets, cache and fan-out. Cut delivery time to 30 minutes and the realtime tier shrinks by 25%.
3. **Peak ≠ average.** Everything above is meal-rush peak. Average load is 5–10× lower, which is exactly why autoscaling pays for itself.
4. **Storage is a policy question.** Retention and tiering change the server count more than any code optimisation.
5. **What this excludes:** observability (Prometheus/Loki, typically 5–10% of the fleet), CI, Spark batch capacity, load balancers, and non-production environments. Add roughly 20%.

## 10. Re-running the model

```bash
python3 scripts/capacity_model.py          # prints every table's inputs and results
```

Change `DAU0`, `GROWTH`, `SERVER_RPS`, `cost`, retention, or `REPL` at the top and re-read the totals. Treat this file as the *output* of the model, never as hand-maintained numbers.
