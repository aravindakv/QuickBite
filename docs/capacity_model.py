# QuickBite 10-year capacity model. All numbers derived here; no hand arithmetic.
import math
def ceil(x): return int(math.ceil(x - 1e-9))
KB, MB, GB, TB = 1e3, 1e6, 1e9, 1e12

# ---------------- inputs (given constraints + business assumptions) ----------------
SERVER_RPS      = 30_000      # given: nominal request capacity of one app server (lightweight op)
UTIL            = 0.60        # design utilisation: 3 AZs, losing one -> 0.60*1.5 = 90%
CACHE_GB_SERVER = 4           # given
CACHE_USABLE    = 0.75        # usable fraction of cache RAM (overhead/fragmentation/failover headroom)
STORE_TB_SERVER = 20          # given
STORE_FILL      = 0.70        # usable fraction of disk
YEARS           = 10
GROWTH          = 0.15        # 15%/yr compound
REPL            = 3           # replication factor for hot stores
NIC_GBPS, NIC_UTIL = 25, 0.40

DAU0, ORDERS_DAY0 = 10e6, 2e6
CALLS_PER_DAU, PEAK_FACTOR = 30, 5
ORDER_PEAK = 10               # meal rushes are spikier than browsing
DELIVERY_MIN, GPS_EVERY_S = 40, 4

g = (1 + GROWTH) ** (YEARS - 1)                  # year-10 multiplier
DAU, ORDERS_DAY = DAU0 * g, ORDERS_DAY0 * g
peak_api = DAU * CALLS_PER_DAU / 86400 * PEAK_FACTOR
peak_orders = ORDERS_DAY / 86400 * ORDER_PEAK
inflight = peak_orders * DELIVERY_MIN * 60                      # Little's Law
riders_online = inflight * 0.55                                 # riders per in-flight order
gps_peak = riders_online / GPS_EVERY_S
ws_conns = riders_online + inflight + 50_000
sessions = DAU * 0.20

# traffic mix at the edge
mix = {"gateway":1.00, "catalog":0.65, "order":0.20, "payment":0.05, "location-rest":0.05, "auth":0.05}
CDN_OFFLOAD = 0.50                                              # catalog reads served by CDN

# cost factor = "work units" per request; effective rps = SERVER_RPS / factor.
# 30k rps/server is realistic for proxying or cache hits, not for a DB write or an external payment call.
cost = {"gateway":1, "catalog":2, "order":15, "payment":25, "location-ingest":3,
        "realtime":1, "location-rest":3, "auth":6}

def servers(load, factor, minimum=3):
    return max(minimum, ceil(load / (SERVER_RPS / factor * UTIL)))

load = {
 "gateway":        peak_api,
 "auth":           sessions / 900 + peak_api * 0.01,            # token refresh every 15 min + logins
 "catalog":        peak_api * mix["catalog"] * (1 - CDN_OFFLOAD),
 "order":          peak_api * mix["order"],
 "payment":        peak_orders * 3,                             # authorize + capture + reads
 "location-ingest":gps_peak,
 "location-rest":  peak_api * mix["location-rest"],
 "realtime":       gps_peak + peak_orders * 6,                  # fan-out messages/s
}
app = {s: servers(load[s], cost[s]) for s in load}
# realtime is memory/connection bound too: 250k sockets per 64 GB server
app["realtime"] = max(app["realtime"], ceil(ws_conns / 250_000 / 0.8))

# ---------------- cache ----------------
cache_items = {
 "sessions (gateway/auth)":      sessions * 1 * KB,
 "menu cache (catalog)":         200_000 * 50 * KB,
 "order status (order/realtime)":inflight * 1 * KB,
 "rider geo index (location)":   riders_online * 150,
 "rate-limit buckets (gateway)": sessions * 100,
 "ws routing registry (realtime)": ws_conns * 100,
 "idempotency keys (payment)":   ORDERS_DAY * 200,
}
cache_bytes = sum(cache_items.values()) * 1.5                   # 1.5x overhead
cache_ops = gps_peak + peak_api * 2 + load["catalog"] * 0.9 + peak_orders * 4
shards_mem = ceil(cache_bytes / (CACHE_GB_SERVER * GB * CACHE_USABLE))
shards_ops = ceil(cache_ops / (100_000 * 0.5))                  # 100k ops/s per node, 50% target
cache_shards = max(shards_mem, shards_ops, 3)
cache_servers = cache_shards * 2                                # + 1 replica each

# ---------------- storage, cumulative over 10 years ----------------
years_sum = sum((1 + GROWTH) ** n for n in range(YEARS))        # cumulative growth multiplier
orders_total = ORDERS_DAY0 * 365 * years_sum
gps_avg_day0 = riders_online / g * 0.40 * 86400 / GPS_EVERY_S   # year-1 average events/day
def store(raw, repl=REPL, index=1.0):
    total = raw * repl * index
    return total, ceil(total / (STORE_TB_SERVER * TB * STORE_FILL))

stores = {}
stores["orders + lines + outbox (Postgres)"] = store(orders_total * 6 * KB, index=1.3)
stores["payments ledger (Postgres)"]         = store(orders_total * 1.5 * KB, index=1.3)
stores["catalog + menus (MongoDB)"]          = store(1e6 * 100 * KB * 3)     # 1M restaurants, growth folded in
stores["Kafka (7-day retention)"]            = store(gps_peak * 120 * 86400 * 7)
stores["event lake hot: Parquet, 90 days"]   = store(gps_avg_day0 * g * 120 / 10 * 90)
cold_raw = gps_avg_day0 * 120 / 10 * 365 * years_sum
stores["event lake cold: object store, 10 y (EC 1.5x)"] = store(cold_raw, repl=1.5)
storage_servers = sum(v[1] for v in stores.values())

# ---------------- network (peak, per direction, global) ----------------
net = {
 "gateway API in":      peak_api * 1 * KB,
 "gateway API out":     peak_api * 5 * KB,
 "CDN origin fill":     peak_api * mix["catalog"] * CDN_OFFLOAD * 0.05 * 30 * KB,
 "WebSocket in (GPS)":  gps_peak * 200,
 "WebSocket out":       (gps_peak + peak_orders * 6) * 200,
 "Kafka replication":   gps_peak * 120 * REPL,
 "east-west (services)":peak_api * 3 * KB,
}
net_total = sum(net.values())
nics = ceil(net_total * 8 / (NIC_GBPS * 1e9 * NIC_UTIL))

RAM_PER_SERVER = 64
app_total = sum(app.values())
out = dict(DAU=DAU, ORDERS_DAY=ORDERS_DAY, peak_api=peak_api, peak_orders=peak_orders,
           inflight=inflight, riders=riders_online, gps=gps_peak, ws=ws_conns, sessions=sessions,
           app=app, load=load, cost=cost, cache_items=cache_items, cache_bytes=cache_bytes,
           cache_ops=cache_ops, shards_mem=shards_mem, shards_ops=shards_ops,
           cache_shards=cache_shards, cache_servers=cache_servers, stores=stores,
           storage_servers=storage_servers, net=net, net_total=net_total, nics=nics,
           orders_total=orders_total, app_total=app_total, ram=app_total*RAM_PER_SERVER,
           years_sum=years_sum, g=g)
import json, pickle
pickle.dump(out, open("/tmp/cap/out.pkl","wb"))
for k in ("DAU","ORDERS_DAY","peak_api","peak_orders","inflight","riders","gps","ws","sessions"):
    print(f"{k:12} {out[k]:,.0f}")
print("app servers:", app, "=", app_total)
print(f"cache: {cache_bytes/GB:.1f} GB, ops {cache_ops:,.0f}/s, shards mem={shards_mem} ops={shards_ops} -> {cache_shards} x2 = {cache_servers}")
print("orders over 10y:", f"{orders_total/1e9:.1f}B")
for k,(b,s) in stores.items(): print(f"  {k:52} {b/TB:8.1f} TB -> {s:3} servers")
print("storage servers:", storage_servers)
print(f"network {net_total*8/1e9:.1f} Gbps -> {nics} NICs")
