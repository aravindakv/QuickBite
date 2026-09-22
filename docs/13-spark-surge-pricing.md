# 13 — Spark: Streaming Surge Pricing on Kafka + a Parquet Data Lake

**Goal:** a Spark Structured Streaming job that reads `rider.location` and `orders.events` from Kafka and does two things:

1. Every 10 s, compute **demand vs. supply per ~1 km grid cell** over 1-minute windows, and write a **surge multiplier** into Redis. order-svc then uses it to price the delivery fee.
2. Continuously land all raw events as **Parquet**, partitioned by date and topic, in a data lake (a local volume, or HDFS if you want the Hadoop piece), then run a **batch** report on it.

---

## Concepts first

| Concept | Meaning here |
|---|---|
| **Structured Streaming** | You write a normal DataFrame query; Spark runs it incrementally as micro-batches on new Kafka data. |
| **Event time vs processing time** | Group by *when the event happened* (the Kafka timestamp), not when Spark saw it. A phone that reconnects late still counts in the right minute. |
| **Watermark** | "Accept events up to 2 minutes late, then finalize the window." It bounds state size; without it, Spark would keep every window forever. |
| **Checkpoint** | Kafka offsets and aggregation state persisted to disk. The job restarts exactly where it stopped. |
| **Delivery guarantee** | Reading Kafka with checkpoints is exactly-once *into Spark state*. Writing out is **at-least-once**, so sinks must be idempotent. A Redis `SET surge:cell value` is idempotent by nature (last write wins). |
| **approx_count_distinct** | HyperLogLog: count distinct riders per cell in a few KB of memory instead of storing every rider id. |
| **Batch vs streaming on the same data** | The Parquet lake allows cheap historical analysis (e.g. "p90 prep time by hour") without touching the operational databases. This is the Lambda/Kappa idea in miniature. |

---

## Step 1: The job project

The job builds as its own small Gradle project, because Spark 4.0 targets Java 17/21, not 25.

`analytics/surge-job/settings.gradle.kts`

```kotlin
rootProject.name = "surge-job"
```

`analytics/surge-job/build.gradle.kts`

```kotlin
plugins { java }

repositories { mavenCentral() }

java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }

dependencies {
    // "provided" at runtime by the Spark image; Kafka connector and Jedis are added with --packages
    compileOnly("org.apache.spark:spark-sql_2.13:4.0.0")
    compileOnly("redis.clients:jedis:5.2.0")
}

tasks.jar { archiveFileName.set("surge-job.jar") }
```

`analytics/surge-job/src/main/java/com/quickbite/analytics/SurgeJob.java`

```java
package com.quickbite.analytics;

import org.apache.spark.api.java.function.VoidFunction2;
import org.apache.spark.sql.*;
import org.apache.spark.sql.streaming.Trigger;
import org.apache.spark.sql.types.StructType;
import redis.clients.jedis.JedisPooled;

import static org.apache.spark.sql.functions.*;

public class SurgeJob {

    public static void main(String[] args) throws Exception {
        String bootstrap = env("KAFKA_BOOTSTRAP", "kafka:19092");
        String redisHost = env("REDIS_HOST", "redis");
        String lake = env("LAKE_PATH", "/data/lake");
        String checkpoints = env("CHECKPOINT_PATH", "/data/checkpoints");

        SparkSession spark = SparkSession.builder().appName("quickbite-surge").getOrCreate();
        spark.sparkContext().setLogLevel("WARN");

        // 1) One Kafka source, two topics
        Dataset<Row> raw = spark.readStream().format("kafka")
                .option("kafka.bootstrap.servers", bootstrap)
                .option("subscribe", "rider.location,orders.events")
                .option("startingOffsets", "latest")
                .option("includeHeaders", "true")
                .load();

        Dataset<Row> events = raw.selectExpr(
                "topic",
                "CAST(value AS STRING) AS json",
                "timestamp",                                                            // Kafka record timestamp = event time
                "CAST(filter(headers, h -> h.key = 'eventType')[0].value AS STRING) AS eventType");

        // 2) Raw events -> Parquet lake (partitioned for cheap date/topic pruning later)
        events.withColumn("date", to_date(col("timestamp")))
              .writeStream().format("parquet")
              .option("path", lake + "/events")
              .option("checkpointLocation", checkpoints + "/lake")
              .partitionBy("date", "topic")
              .trigger(Trigger.ProcessingTime("1 minute"))
              .start();

        // 3) Supply (riders) and demand (new orders) as one stream of (lat, lon, kind, riderId)
        StructType loc = new StructType().add("riderId", "string").add("lat", "double").add("lon", "double");
        StructType created = new StructType().add("deliveryLat", "double").add("deliveryLon", "double");

        Dataset<Row> supply = events.filter("topic = 'rider.location'")
                .select(from_json(col("json"), loc).as("e"), col("timestamp"))
                .select(col("e.riderId").as("riderId"), col("e.lat").as("lat"), col("e.lon").as("lon"),
                        col("timestamp"), lit("supply").as("kind"));

        Dataset<Row> demand = events.filter("topic = 'orders.events' AND eventType = 'order.created'")
                .select(from_json(col("json"), created).as("e"), col("timestamp"))
                .select(lit(null).cast("string").as("riderId"), col("e.deliveryLat").as("lat"), col("e.deliveryLon").as("lon"),
                        col("timestamp"), lit("demand").as("kind"));

        // ~1.1 km grid cell: floor(lat*100):floor(lon*100). order-svc computes the SAME key.
        Column cell = concat_ws(":", floor(col("lat").multiply(100)), floor(col("lon").multiply(100)));

        Dataset<Row> perCell = supply.unionByName(demand)
                .withColumn("cell", cell)
                .withWatermark("timestamp", "2 minutes")
                .groupBy(window(col("timestamp"), "1 minute"), col("cell"))
                .agg(sum(when(col("kind").equalTo("demand"), 1).otherwise(0)).as("orders"),
                     approx_count_distinct(col("riderId")).as("riders"));   // nulls (demand rows) are ignored

        // 4) Surge multiplier -> Redis (idempotent SET with TTL: stale surge expires by itself)
        perCell.writeStream()
               .outputMode("update")
               .option("checkpointLocation", checkpoints + "/surge")
               .trigger(Trigger.ProcessingTime("10 seconds"))
               .foreachBatch((VoidFunction2<Dataset<Row>, Long>) (batch, batchId) -> {
                   var rows = batch.collectAsList();              // small: one row per active cell
                   try (var redis = new JedisPooled(redisHost, 6379)) {
                       for (Row r : rows) {
                           long orders = r.getAs("orders");
                           long riders = r.getAs("riders");
                           double ratio = (double) orders / Math.max(riders, 1);
                           double multiplier = ratio <= 1 ? 1.0 : Math.min(2.0, 1.0 + 0.25 * (ratio - 1));
                           String key = "surge:" + r.getAs("cell");
                           redis.setex(key, 120, String.format("%.2f", multiplier));
                           System.out.printf("batch %d cell %s orders=%d riders=%d -> x%.2f%n",
                                   batchId, r.getAs("cell"), orders, riders, multiplier);
                       }
                   }
               })
               .start();

        spark.streams().awaitAnyTermination();
    }

    private static String env(String k, String d) { String v = System.getenv(k); return v == null ? d : v; }
}
```

`analytics/surge-job/src/main/java/com/quickbite/analytics/DailyReport.java`: the batch half.

```java
package com.quickbite.analytics;

import org.apache.spark.sql.*;
import static org.apache.spark.sql.functions.*;

/** Reads the Parquet lake (not the operational DBs!) and prints orders per hour and busiest cells. */
public class DailyReport {
    public static void main(String[] args) {
        String lake = System.getenv().getOrDefault("LAKE_PATH", "/data/lake");
        SparkSession spark = SparkSession.builder().appName("quickbite-daily").getOrCreate();
        spark.sparkContext().setLogLevel("WARN");

        Dataset<Row> orders = spark.read().parquet(lake + "/events")
                .filter("topic = 'orders.events' AND eventType = 'order.created'")   // partition pruning on topic
                .select(col("timestamp"), from_json(col("json"),
                        "orderId STRING, amount DOUBLE, deliveryLat DOUBLE, deliveryLon DOUBLE").as("o"));

        orders.groupBy(date_format(col("timestamp"), "yyyy-MM-dd HH:00").as("hour"))
              .agg(count("*").as("orders"), round(sum("o.amount"), 2).as("gmv"))
              .orderBy("hour").show(48, false);

        orders.groupBy(concat_ws(":", floor(col("o.deliveryLat").multiply(100)), floor(col("o.deliveryLon").multiply(100))).as("cell"))
              .count().orderBy(desc("count")).show(10, false);
    }
}
```

```bash
cd analytics/surge-job && ../../gradlew jar && cd ../..
```

---

## Step 2: Run it next to the Compose stack

```bash
docker volume create quickbite_lake

docker run --rm -d --name surge-job --user root --network quickbite_default \
  -v "$PWD/analytics/surge-job/build/libs:/job:ro" -v quickbite_lake:/data \
  -e KAFKA_BOOTSTRAP=kafka:19092 -e REDIS_HOST=redis \
  apache/spark:4.0.0 /opt/spark/bin/spark-submit --master 'local[2]' \
    --conf spark.jars.ivy=/tmp/.ivy2 \
    --packages org.apache.spark:spark-sql-kafka-0-10_2.13:4.0.0,redis.clients:jedis:5.2.0 \
    --class com.quickbite.analytics.SurgeJob /job/surge-job.jar

docker logs -f surge-job      # first start downloads packages (~1 min)
```

**Notes:**

- `--network quickbite_default` works because the Compose file pins `name: quickbite` (file 03).
- `local[2]` means Spark runs in one JVM with 2 cores. On a real cluster you'd submit to Kubernetes (the Spark Operator) or YARN with executors on many nodes; the code is unchanged.
- `--user root` is a local shortcut so Spark can write to the named volume. Don't do this in production.

---

## Step 3: Use surge in order-svc

Add Redis to `services/order-svc/build.gradle.kts`:

```kotlin
implementation("org.springframework.boot:spring-boot-starter-data-redis")
```

and to its `application.yml`:

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
```

`services/order-svc/src/main/java/com/quickbite/order/app/SurgeService.java`

```java
package com.quickbite.order.app;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class SurgeService {
    static final BigDecimal BASE_DELIVERY_FEE = new BigDecimal("30.00");
    private final StringRedisTemplate redis;

    public SurgeService(StringRedisTemplate redis) { this.redis = redis; }

    /** Same grid as the Spark job. Missing key / Redis down -> no surge (fail safe for the customer). */
    public BigDecimal multiplier(double lat, double lon) {
        String cell = (long) Math.floor(lat * 100) + ":" + (long) Math.floor(lon * 100);
        try {
            String v = redis.opsForValue().get("surge:" + cell);
            return v == null ? BigDecimal.ONE : new BigDecimal(v);
        } catch (Exception e) {
            return BigDecimal.ONE;
        }
    }

    public BigDecimal deliveryFee(double lat, double lon) {
        return BASE_DELIVERY_FEE.multiply(multiplier(lat, lon)).setScale(2, RoundingMode.HALF_UP);
    }
}
```

In `OrderService.place(...)`, inject `SurgeService surge` and add the fee as an order line, just before the transaction:

```java
BigDecimal m = surge.multiplier(req.deliveryLat(), req.deliveryLon());
String label = m.compareTo(BigDecimal.ONE) > 0 ? "Delivery fee (surge x" + m + ")" : "Delivery fee";
lines.add(new OrderLine("delivery-fee", label, 1, surge.deliveryFee(req.deliveryLat(), req.deliveryLon())));
```

**Design note:** surge is **AP data**. It's computed from sampled streams, it may be a few seconds stale, and it degrades to x1.00. We never block an order on it.

Rebuild and restart order-svc (`scripts/up.sh`).

> **Your tests just changed:** `OrderFlowIT` now sees an extra ₹30 delivery-fee line, so the expected total becomes 330. Either update the assertion, or add `@MockitoBean SurgeService surge;` with `when(surge.deliveryFee(anyDouble(), anyDouble())).thenReturn(new BigDecimal("30.00"))` and `when(surge.multiplier(anyDouble(), anyDouble())).thenReturn(BigDecimal.ONE)`. Mocking keeps the test deterministic and independent of Redis.

---

## Step 4: Simulate a rain storm

One rider is online, and 25 orders arrive in the same cell within a minute. The `loadN` users from file 11 each have a default Visa 4242, so their orders get paid. If you skipped file 11, run `scripts/create-load-users.sh 25` first; without a card, every order ends `CANCELLED: NO_PAYMENT_METHOD` (it still counts as demand for surge, but nothing gets delivered).

```bash
scripts/rider-sim.sh > /dev/null 2>&1 &     # 1 rider

for i in $(seq 1 25); do
  T=$(scripts/token.sh load$i load$i)        # load users from file 11 (per-user rate limits!)
  curl -s -o /dev/null -X POST localhost:8000/api/orders -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
    -d '{"restaurantId":"r4","items":[{"menuItemId":"r4-i1","quantity":1}],"deliveryLat":12.9279,"deliveryLon":77.6271}'
done

sleep 20
docker logs surge-job | tail -5                              # cell 1292:7762 orders=25 riders=1 -> x2.00
docker exec -it quickbite-redis-1 redis-cli get surge:1292:7762

# New order in that cell carries a surge delivery fee
curl -s -X POST localhost:8000/api/orders -H "Authorization: Bearer $(scripts/token.sh alice alice)" -H 'Content-Type: application/json' \
  -d '{"restaurantId":"r4","items":[{"menuItemId":"r4-i1","quantity":1}],"deliveryLat":12.9279,"deliveryLon":77.6271}' \
  | jq '.lines[] | select(.menuItemId=="delivery-fee")'
```

The phone shows the same thing: place an order from the app during the storm, and the tracking screen's order lines include the surge fee. After about 2 minutes without new demand, the Redis key expires and the fee returns to ₹30.

---

## Step 5: Batch report over the lake

Wait at least one minute after generating traffic (the lake trigger is 1 minute), then run:

```bash
docker run --rm --user root --network quickbite_default \
  -v "$PWD/analytics/surge-job/build/libs:/job:ro" -v quickbite_lake:/data \
  apache/spark:4.0.0 /opt/spark/bin/spark-submit --master 'local[2]' \
    --class com.quickbite.analytics.DailyReport /job/surge-job.jar

docker run --rm -v quickbite_lake:/data alpine find /data/lake/events -maxdepth 2 -type d   # date=.../topic=... partitions
```

---

## Optional: put the lake on HDFS (the Hadoop part)

To use real HDFS instead of a local volume:

1. Start a NameNode and a DataNode using the official `apache/hadoop` image. Hadoop's own docs include a small docker-compose example with `core-site.xml`/`hdfs-site.xml` passed as environment variables. Attach both containers to `quickbite_default`.
2. Create the directory: `docker exec namenode hdfs dfs -mkdir -p /lake /checkpoints && docker exec namenode hdfs dfs -chmod -R 777 /lake /checkpoints`.
3. Restart the job with `-e LAKE_PATH=hdfs://namenode:8020/lake -e CHECKPOINT_PATH=hdfs://namenode:8020/checkpoints`.

No code changes are needed: Spark's file sources speak `file://`, `hdfs://` and `s3a://` through the same Hadoop FileSystem API. In the cloud you'd typically point at object storage (`s3a://`) instead of running HDFS yourself.

---

## Verify

| Check | Pass condition |
|---|---|
| Job logs | `batch N cell ... -> xM` lines every 10 s while there's traffic |
| Storm | `surge:1292:7762` ≈ `2.00`; new order has a surge delivery-fee line |
| Decay | Key gone after ~2 min of no demand; fee back to 30.00 |
| Lake | `date=YYYY-MM-DD/topic=...` directories with `.parquet` files |
| Report | Orders per hour and the top cells printed |
| Restart | `docker restart surge-job` → resumes from the checkpoint (no reprocessing from zero) |

**Checkpoint:**

1. Why does the surge sink tolerate duplicates, but a sink that *incremented* a counter would not?
2. What happens to a GPS event that arrives 3 minutes late, and why is that acceptable for surge?
