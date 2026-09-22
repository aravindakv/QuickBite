# 16 — Demo Mode: Server + Android, Driven by a JSON Demo Pack

**Goal:** a **Demo Mode** setting on both sides that lets you show a complete order, from browsing to live tracking to delivered, **without anyone playing the rider**.

| Mode (Android setting) | Needs a backend? | Who fulfils orders | Use it for |
|---|---|---|---|
| **Off** | Yes (normal stack) | Real riders (`rider-sim.sh`, rider app) | Development, testing |
| **Server demo** | Yes, started with `DEMO_MODE=true` | **demo-svc**: 3 virtual riders using the real APIs | Showing the real distributed system end to end |
| **Offline demo** | **No**: runs entirely on the phone | On-device simulation | Pitches and UI reviews with no laptop or network |

Both modes are driven by **one JSON demo pack** (`demo-data/`), the single source of truth for demo restaurants, the demo customer, virtual riders, order history, test cards and fulfilment timing.

---

## Concepts first

| Concept | How it shows up here |
|---|---|
| **Configuration, not code branches** | One flag (`DEMO_MODE` on the server, a setting in the app) switches behaviour. Demo logic lives in *separate, conditional* components (`@ConditionalOnProperty`) instead of `if (demo)` scattered through business code. |
| **Data as versioned, validated JSON** | The pack has a `manifest.json` with a version, and loaders **validate referential integrity at startup** (history → restaurants → items, riders exist, cards pass Luhn). Bad data fails fast with a precise message, and a unit test runs the same validation in CI. |
| **Simulator as a black-box client** | demo-svc changes nothing in order/location/payment logic. It logs in as rider accounts and calls the **same public APIs** a rider phone calls. If demo mode works, the real rider path works too. |
| **Fake backend at the HTTP boundary** | Offline demo replaces the network with an OkHttp `Interceptor` that answers requests from JSON. Retrofit, the ViewModel and every screen run **unchanged**. This is the same technique used for UI tests and screenshots. |
| **Safety guard** | Demo mode exposes demo passwords and auto-creates cards. The server **refuses to start** with `DEMO_MODE=true` unless `APP_ENV` is in an allow-list (`local,dev,staging`). Release builds of the app can hide demo mode entirely. |

```
                    demo-data/*.json  (single source of truth)
                  ┌──────────┴───────────────────────────────┐
     packaged into libs/demo-data jar                copied into Android assets/demo/
   (or mounted at /demo-data for live edits)                  │
                  │                                            ▼
  gateway ── GET /api/settings (demo info)          OFFLINE: DemoInterceptor + DemoFulfillment
  catalog ── loads demo restaurants d1–d8                    (no network at all)
  payment ── seeds demo cards, auto-provisions a card
  order   ── imports order history                  SERVER: normal app → NGINX → gateway ...
  demo-svc ─ 3 virtual riders ──(REST as riders)──► gateway → location/order services
```

---

# Part A: The demo pack (JSON)

## A1. Files

Create the folder `demo-data/` at the repo root. Demo restaurant ids **must start with `d`** (the loaders use this prefix to add and remove demo data safely).

`demo-data/manifest.json`

```json
{
  "name": "QuickBite Demo Pack",
  "version": 1,
  "city": "blr",
  "description": "Self-contained demo dataset: 8 restaurants, 1 customer, 3 virtual riders, order history, test cards and fulfillment timings."
}
```

`demo-data/restaurants.json`: same schema as the catalog seed (file 05), all items available, short prep times.

```json
[
  {"id": "d1", "name": "Idli Express", "cuisine": "South Indian", "cityId": "blr", "area": "Koramangala", "lat": 12.934, "lon": 77.626, "rating": 4.6, "avgPrepMinutes": 6, "menu": [
    {"id": "d1-i1", "name": "Idli (2 pcs)", "price": 60, "veg": true, "available": true},
    {"id": "d1-i2", "name": "Ghee Podi Idli", "price": 90, "veg": true, "available": true},
    {"id": "d1-i3", "name": "Masala Dosa", "price": 110, "veg": true, "available": true},
    {"id": "d1-i4", "name": "Onion Uttapam", "price": 120, "veg": true, "available": true},
    {"id": "d1-i5", "name": "Medu Vada", "price": 50, "veg": true, "available": true},
    {"id": "d1-i6", "name": "Filter Coffee", "price": 35, "veg": true, "available": true}
  ]},
  {"id": "d2", "name": "Biryani Theory", "cuisine": "Hyderabadi", "cityId": "blr", "area": "Koramangala", "lat": 12.931, "lon": 77.622, "rating": 4.4, "avgPrepMinutes": 12, "menu": [
    {"id": "d2-i1", "name": "Chicken Biryani", "price": 299, "veg": false, "available": true},
    {"id": "d2-i2", "name": "Paneer Biryani", "price": 249, "veg": true, "available": true},
    {"id": "d2-i3", "name": "Egg Biryani", "price": 219, "veg": false, "available": true},
    {"id": "d2-i4", "name": "Raita", "price": 40, "veg": true, "available": true},
    {"id": "d2-i5", "name": "Mirchi Salan", "price": 60, "veg": true, "available": true},
    {"id": "d2-i6", "name": "Qubani Ka Meetha", "price": 110, "veg": true, "available": true}
  ]},
  {"id": "d3", "name": "Slice Society", "cuisine": "Italian", "cityId": "blr", "area": "HSR Layout", "lat": 12.915, "lon": 77.64, "rating": 4.3, "avgPrepMinutes": 10, "menu": [
    {"id": "d3-i1", "name": "Margherita", "price": 279, "veg": true, "available": true},
    {"id": "d3-i2", "name": "Farmhouse Pizza", "price": 349, "veg": true, "available": true},
    {"id": "d3-i3", "name": "Pepperoni Pizza", "price": 399, "veg": false, "available": true},
    {"id": "d3-i4", "name": "Pesto Pasta", "price": 299, "veg": true, "available": true},
    {"id": "d3-i5", "name": "Garlic Knots", "price": 129, "veg": true, "available": true},
    {"id": "d3-i6", "name": "Panna Cotta", "price": 169, "veg": true, "available": true}
  ]},
  {"id": "d4", "name": "Bowl & Co", "cuisine": "Salads", "cityId": "blr", "area": "Koramangala", "lat": 12.929, "lon": 77.628, "rating": 4.7, "avgPrepMinutes": 5, "menu": [
    {"id": "d4-i1", "name": "Caesar Salad", "price": 249, "veg": true, "available": true},
    {"id": "d4-i2", "name": "Burrito Bowl", "price": 279, "veg": true, "available": true},
    {"id": "d4-i3", "name": "Chicken Protein Bowl", "price": 329, "veg": false, "available": true},
    {"id": "d4-i4", "name": "Fruit Bowl", "price": 179, "veg": true, "available": true},
    {"id": "d4-i5", "name": "Cold Coffee", "price": 149, "veg": true, "available": true},
    {"id": "d4-i6", "name": "Kombucha", "price": 189, "veg": true, "available": true}
  ]},
  {"id": "d5", "name": "Kebab Street", "cuisine": "Mughlai", "cityId": "blr", "area": "BTM Layout", "lat": 12.917, "lon": 77.61, "rating": 4.2, "avgPrepMinutes": 11, "menu": [
    {"id": "d5-i1", "name": "Seekh Kebab", "price": 259, "veg": false, "available": true},
    {"id": "d5-i2", "name": "Paneer Tikka", "price": 239, "veg": true, "available": true},
    {"id": "d5-i3", "name": "Chicken Tikka Roll", "price": 189, "veg": false, "available": true},
    {"id": "d5-i4", "name": "Rumali Roti", "price": 40, "veg": true, "available": true},
    {"id": "d5-i5", "name": "Mint Chutney", "price": 20, "veg": true, "available": true},
    {"id": "d5-i6", "name": "Kulfi", "price": 90, "veg": true, "available": true}
  ]},
  {"id": "d6", "name": "Noodle Lab", "cuisine": "Chinese", "cityId": "blr", "area": "HSR Layout", "lat": 12.912, "lon": 77.645, "rating": 4.1, "avgPrepMinutes": 8, "menu": [
    {"id": "d6-i1", "name": "Veg Hakka Noodles", "price": 199, "veg": true, "available": true},
    {"id": "d6-i2", "name": "Chicken Chow Mein", "price": 239, "veg": false, "available": true},
    {"id": "d6-i3", "name": "Chilli Paneer", "price": 229, "veg": true, "available": true},
    {"id": "d6-i4", "name": "Spring Rolls", "price": 149, "veg": true, "available": true},
    {"id": "d6-i5", "name": "Fried Rice", "price": 189, "veg": true, "available": true},
    {"id": "d6-i6", "name": "Lemon Iced Tea", "price": 99, "veg": true, "available": true}
  ]},
  {"id": "d7", "name": "Brew & Bake", "cuisine": "Cafe", "cityId": "blr", "area": "Koramangala", "lat": 12.936, "lon": 77.618, "rating": 4.8, "avgPrepMinutes": 5, "menu": [
    {"id": "d7-i1", "name": "Cappuccino", "price": 159, "veg": true, "available": true},
    {"id": "d7-i2", "name": "Croissant", "price": 129, "veg": true, "available": true},
    {"id": "d7-i3", "name": "Blueberry Muffin", "price": 119, "veg": true, "available": true},
    {"id": "d7-i4", "name": "Chicken Sandwich", "price": 219, "veg": false, "available": true},
    {"id": "d7-i5", "name": "Brownie", "price": 99, "veg": true, "available": true},
    {"id": "d7-i6", "name": "Iced Latte", "price": 179, "veg": true, "available": true}
  ]},
  {"id": "d8", "name": "Thali House", "cuisine": "North Indian", "cityId": "blr", "area": "Indiranagar", "lat": 12.97, "lon": 77.64, "rating": 4.5, "avgPrepMinutes": 9, "menu": [
    {"id": "d8-i1", "name": "Veg Thali", "price": 249, "veg": true, "available": true},
    {"id": "d8-i2", "name": "Chicken Thali", "price": 319, "veg": false, "available": true},
    {"id": "d8-i3", "name": "Dal Makhani", "price": 199, "veg": true, "available": true},
    {"id": "d8-i4", "name": "Butter Naan", "price": 45, "veg": true, "available": true},
    {"id": "d8-i5", "name": "Jeera Rice", "price": 129, "veg": true, "available": true},
    {"id": "d8-i6", "name": "Gulab Jamun", "price": 79, "veg": true, "available": true}
  ]}
]
```

`demo-data/customers.json`: the demo customer (id matches the Keycloak realm, file 03).

```json
[
  {
    "userId": "55555555-5555-5555-5555-555555555555",
    "username": "demo",
    "password": "demo",
    "displayName": "Demo Customer",
    "addresses": [
      {
        "label": "Home",
        "lat": 12.9279,
        "lon": 77.6271
      },
      {
        "label": "Office",
        "lat": 12.9352,
        "lon": 77.6245
      }
    ],
    "cards": [
      {
        "number": "4242424242424242",
        "expMonth": 12,
        "expYear": 2030,
        "cvc": "123",
        "isDefault": true
      },
      {
        "number": "5555555555554444",
        "expMonth": 6,
        "expYear": 2031,
        "cvc": "321",
        "isDefault": false
      },
      {
        "number": "4000000000000002",
        "expMonth": 1,
        "expYear": 2032,
        "cvc": "999",
        "isDefault": false
      }
    ]
  }
]
```

`demo-data/riders.json`: virtual riders (accounts exist in the realm; the passwords are demo-only).

```json
[
  {"userId": "66666666-6666-6666-6666-000000000001", "username": "demo-rider-1", "password": "demo-rider", "name": "Ravi", "vehicle": "Motorbike", "start": {"lat": 12.9335, "lon": 77.6245}},
  {"userId": "66666666-6666-6666-6666-000000000002", "username": "demo-rider-2", "password": "demo-rider", "name": "Meena", "vehicle": "Scooter", "start": {"lat": 12.92, "lon": 77.635}},
  {"userId": "66666666-6666-6666-6666-000000000003", "username": "demo-rider-3", "password": "demo-rider", "name": "Arjun", "vehicle": "E-bike", "start": {"lat": 12.93, "lon": 77.615}}
]
```

`demo-data/orders-history.json`: past orders, so "My orders" isn't empty.

```json
[
  {"customerUserId": "55555555-5555-5555-5555-555555555555", "restaurantId": "d1", "items": [{"menuItemId": "d1-i3", "quantity": 2}, {"menuItemId": "d1-i6", "quantity": 2}], "daysAgo": 1, "riderUsername": "demo-rider-1"},
  {"customerUserId": "55555555-5555-5555-5555-555555555555", "restaurantId": "d2", "items": [{"menuItemId": "d2-i1", "quantity": 1}, {"menuItemId": "d2-i4", "quantity": 1}], "daysAgo": 3, "riderUsername": "demo-rider-2"},
  {"customerUserId": "55555555-5555-5555-5555-555555555555", "restaurantId": "d7", "items": [{"menuItemId": "d7-i1", "quantity": 2}, {"menuItemId": "d7-i2", "quantity": 1}], "daysAgo": 6, "riderUsername": "demo-rider-3"},
  {"customerUserId": "55555555-5555-5555-5555-555555555555", "restaurantId": "d4", "items": [{"menuItemId": "d4-i2", "quantity": 1}], "daysAgo": 10, "riderUsername": "demo-rider-1"},
  {"customerUserId": "55555555-5555-5555-5555-555555555555", "restaurantId": "d8", "items": [{"menuItemId": "d8-i1", "quantity": 2}, {"menuItemId": "d8-i6", "quantity": 2}], "daysAgo": 14, "riderUsername": "demo-rider-2"}
]
```

`demo-data/scenario.json`: fulfilment timing. `secondsPerPrepMinute: 4` compresses time, so a 12-minute biryani is ready in about 45 s (capped by `maxPrepSeconds`).

```json
{
  "speedFactor": 1.0,
  "secondsPerPrepMinute": 4,
  "minPrepSeconds": 6,
  "maxPrepSeconds": 45,
  "travelSteps": 15,
  "stepIntervalMs": 2000,
  "idleReportIntervalMs": 4000,
  "returnToStart": true,
  "offline": {
    "paymentDelayMs": 1200,
    "assignDelayMs": 2500
  }
}
```

`demo-data/cards.json`: the test-card catalogue (must match `FakePsp.TEST_CARDS`; a test enforces it).

```json
[
  {"number": "4242424242424242", "brand": "VISA", "behavior": "APPROVE", "declineCode": null, "description": "Approved"},
  {"number": "5555555555554444", "brand": "MASTERCARD", "behavior": "APPROVE", "declineCode": null, "description": "Approved"},
  {"number": "378282246310005", "brand": "AMEX", "behavior": "APPROVE", "declineCode": null, "description": "Approved (4-digit CVC)"},
  {"number": "4000000000000002", "brand": "VISA", "behavior": "DECLINE", "declineCode": "card_declined", "description": "Declined"},
  {"number": "4000000000009995", "brand": "VISA", "behavior": "DECLINE", "declineCode": "insufficient_funds", "description": "Declined: insufficient funds"},
  {"number": "4000000000000069", "brand": "VISA", "behavior": "DECLINE", "declineCode": "expired_card", "description": "Declined: expired card"},
  {"number": "4000000000000127", "brand": "VISA", "behavior": "DECLINE", "declineCode": "incorrect_cvc", "description": "Declined: incorrect CVC"},
  {"number": "4000000000000119", "brand": "VISA", "behavior": "PROCESSING_ERROR", "declineCode": null, "description": "Transient processing error (every attempt)"},
  {"number": "4000000000001976", "brand": "VISA", "behavior": "SLOW", "declineCode": null, "description": "Approved after 2.5 s"},
  {"number": "4000000000000341", "brand": "VISA", "behavior": "CAPTURE_FAIL", "declineCode": null, "description": "Authorizes, capture fails"}
]
```

---

## A2. Shared server library `libs/demo-data`

Add it and the new service to the root `settings.gradle.kts`:

```kotlin
include(
    "libs:common",
    "libs:demo-data",          // NEW
    "services:gateway",
    "services:catalog-svc",
    "services:order-svc",
    "services:payment-svc",
    "services:location-svc",
    "services:realtime-svc",
    "services:demo-svc",       // NEW
)
```

`libs/demo-data/build.gradle.kts`

```kotlin
plugins { id("quickbite.library") }

dependencies {
    api("tools.jackson.core:jackson-databind")                         // Jackson 3 (version from the Boot BOM)
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// Ship the repo-level demo-data/*.json inside the jar under /demo/ -> works with zero configuration
tasks.processResources {
    from(rootProject.file("demo-data")) { into("demo") }
}
```

`libs/demo-data/src/main/java/com/quickbite/demo/DemoModel.java`

```java
package com.quickbite.demo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Records mirroring the JSON files one-to-one. */
public final class DemoModel {
    private DemoModel() {}

    public record Manifest(String name, int version, String city, String description) {}

    public record Item(String id, String name, BigDecimal price, boolean veg, boolean available) {}

    public record Restaurant(String id, String name, String cuisine, String cityId, String area,
                             double lat, double lon, double rating, int avgPrepMinutes, List<Item> menu) {
        public Optional<Item> item(String itemId) { return menu.stream().filter(i -> i.id().equals(itemId)).findFirst(); }
    }

    public record Point(double lat, double lon) {}
    public record Address(String label, double lat, double lon) {}
    public record Card(String number, int expMonth, int expYear, String cvc, boolean isDefault) {}
    public record Customer(String userId, String username, String password, String displayName,
                           List<Address> addresses, List<Card> cards) {}
    public record Rider(String userId, String username, String password, String name, String vehicle, Point start) {}

    public record OfflineTiming(long paymentDelayMs, long assignDelayMs) {}
    public record Scenario(double speedFactor, int secondsPerPrepMinute, int minPrepSeconds, int maxPrepSeconds,
                           int travelSteps, long stepIntervalMs, long idleReportIntervalMs, boolean returnToStart,
                           OfflineTiming offline) {
        /** Demo-time prep duration, compressed and clamped, then divided by the live speed factor. */
        public long prepMillis(int avgPrepMinutes, double speed) {
            int secs = Math.max(minPrepSeconds, Math.min(maxPrepSeconds, avgPrepMinutes * secondsPerPrepMinute));
            return (long) (secs * 1000 / speed);
        }
    }

    public record HistoryItem(String menuItemId, int quantity) {}
    public record HistoricalOrder(String customerUserId, String restaurantId, List<HistoryItem> items,
                                  int daysAgo, String riderUsername) {}

    public record TestCard(String number, String brand, String behavior, String declineCode, String description) {}
}
```

`libs/demo-data/src/main/java/com/quickbite/demo/DemoPack.java`

```java
package com.quickbite.demo;

import com.quickbite.demo.DemoModel.*;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/** Loads and VALIDATES the demo pack from a directory (live-editable) or from the classpath (/demo/). */
public final class DemoPack {
    public static final int SUPPORTED_VERSION = 1;
    public static final List<String> FILES = List.of("manifest.json", "restaurants.json", "customers.json",
            "riders.json", "orders-history.json", "scenario.json", "cards.json");
    private static final Pattern UUID_RE = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final Manifest manifest;
    private final List<Restaurant> restaurants;
    private final List<Customer> customers;
    private final List<Rider> riders;
    private final List<HistoricalOrder> history;
    private final Scenario scenario;
    private final List<TestCard> testCards;

    private DemoPack(Manifest m, List<Restaurant> r, List<Customer> c, List<Rider> rd,
                     List<HistoricalOrder> h, Scenario s, List<TestCard> t) {
        manifest = m; restaurants = List.copyOf(r); customers = List.copyOf(c); riders = List.copyOf(rd);
        history = List.copyOf(h); scenario = s; testCards = List.copyOf(t);
    }

    /** dir == null -> classpath:/demo/ */
    public static DemoPack load(Path dir) {
        JsonMapper json = JsonMapper.builder().build();
        DemoPack pack = new DemoPack(
                read(json, dir, "manifest.json", Manifest.class),
                List.of(read(json, dir, "restaurants.json", Restaurant[].class)),
                List.of(read(json, dir, "customers.json", Customer[].class)),
                List.of(read(json, dir, "riders.json", Rider[].class)),
                List.of(read(json, dir, "orders-history.json", HistoricalOrder[].class)),
                read(json, dir, "scenario.json", Scenario.class),
                List.of(read(json, dir, "cards.json", TestCard[].class)));
        pack.validate();
        return pack;
    }

    private static <T> T read(JsonMapper json, Path dir, String file, Class<T> type) {
        try (InputStream in = dir != null ? Files.newInputStream(dir.resolve(file))
                                          : DemoPack.class.getResourceAsStream("/demo/" + file)) {
            if (in == null) throw new IllegalStateException("Demo pack file missing: " + file);
            return json.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read demo pack file " + file, e);
        }
    }

    /** Collect ALL problems, then fail once with a readable list: data files are code, test them. */
    private void validate() {
        List<String> errors = new ArrayList<>();
        if (manifest.version() != SUPPORTED_VERSION)
            errors.add("manifest.version " + manifest.version() + " unsupported (expected " + SUPPORTED_VERSION + ")");

        Set<String> ids = new HashSet<>();
        for (Restaurant r : restaurants) {
            if (!r.id().startsWith("d")) errors.add("restaurant id '" + r.id() + "' must start with 'd'");
            if (!ids.add(r.id())) errors.add("duplicate restaurant id " + r.id());
            if (r.menu().isEmpty()) errors.add(r.id() + " has an empty menu");
            for (Item i : r.menu()) {
                if (!i.id().startsWith(r.id() + "-")) errors.add("item " + i.id() + " must start with '" + r.id() + "-'");
                if (i.price().signum() <= 0) errors.add("item " + i.id() + " has non-positive price");
            }
        }
        for (Customer c : customers) {
            if (!UUID_RE.matcher(c.userId()).matches()) errors.add("customer " + c.username() + " userId is not a UUID");
            if (c.addresses().isEmpty()) errors.add("customer " + c.username() + " needs at least one address");
            if (c.cards().stream().filter(Card::isDefault).count() > 1) errors.add("customer " + c.username() + " has >1 default card");
            c.cards().forEach(k -> { if (!luhn(k.number())) errors.add("card ****" + last4(k.number()) + " fails Luhn"); });
        }
        Set<String> riderNames = new HashSet<>();
        for (Rider r : riders) if (!riderNames.add(r.username())) errors.add("duplicate rider " + r.username());
        for (HistoricalOrder h : history) {
            var rest = restaurant(h.restaurantId());
            if (rest.isEmpty()) { errors.add("history references unknown restaurant " + h.restaurantId()); continue; }
            h.items().forEach(i -> { if (rest.get().item(i.menuItemId()).isEmpty()) errors.add("history references unknown item " + i.menuItemId()); });
            if (rider(h.riderUsername()).isEmpty()) errors.add("history references unknown rider " + h.riderUsername());
            if (customer(h.customerUserId()).isEmpty()) errors.add("history references unknown customer " + h.customerUserId());
        }
        if (scenario.speedFactor() <= 0 || scenario.speedFactor() > 20) errors.add("scenario.speedFactor must be in (0, 20]");
        if (scenario.travelSteps() < 2) errors.add("scenario.travelSteps must be >= 2");
        testCards.forEach(t -> { if (!luhn(t.number())) errors.add("test card ****" + last4(t.number()) + " fails Luhn"); });

        if (!errors.isEmpty()) throw new IllegalStateException("Invalid demo pack:\n - " + String.join("\n - ", errors));
    }

    static boolean luhn(String pan) {
        if (pan == null || !pan.matches("\\d{13,19}")) return false;
        int sum = 0;
        for (int i = 0; i < pan.length(); i++) {
            int d = pan.charAt(pan.length() - 1 - i) - '0';
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9; }
            sum += d;
        }
        return sum % 10 == 0;
    }
    private static String last4(String s) { return s == null || s.length() < 4 ? "?" : s.substring(s.length() - 4); }

    public Manifest manifest() { return manifest; }
    public List<Restaurant> restaurants() { return restaurants; }
    public List<Customer> customers() { return customers; }
    public List<Rider> riders() { return riders; }
    public List<HistoricalOrder> history() { return history; }
    public Scenario scenario() { return scenario; }
    public List<TestCard> testCards() { return testCards; }
    public Optional<Restaurant> restaurant(String id) { return restaurants.stream().filter(r -> r.id().equals(id)).findFirst(); }
    public Optional<Rider> rider(String username) { return riders.stream().filter(r -> r.username().equals(username)).findFirst(); }
    public Optional<Customer> customer(String userId) { return customers.stream().filter(c -> c.userId().equals(userId)).findFirst(); }
}
```

`libs/demo-data/src/main/java/com/quickbite/demo/DemoAutoConfiguration.java`

```java
package com.quickbite.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.List;

@AutoConfiguration
@ConditionalOnProperty(name = "quickbite.demo.enabled", havingValue = "true")
public class DemoAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(DemoAutoConfiguration.class);

    @Bean
    DemoPack demoPack(@Value("${quickbite.demo.data-dir:}") String dataDir,
                      @Value("${quickbite.environment:local}") String environment,
                      @Value("${quickbite.demo.allowed-environments:local,dev,staging}") List<String> allowed) {
        // SAFETY GUARD: demo mode leaks demo passwords and auto-creates cards. Never in production.
        if (!allowed.contains(environment)) {
            throw new IllegalStateException("DEMO_MODE=true is not allowed in environment '" + environment
                    + "' (allowed: " + allowed + ")");
        }
        DemoPack pack = DemoPack.load(dataDir.isBlank() ? null : Path.of(dataDir));
        log.warn("*** DEMO MODE ON *** pack '{}' v{} from {} ({} restaurants, {} riders)",
                pack.manifest().name(), pack.manifest().version(), dataDir.isBlank() ? "classpath" : dataDir,
                pack.restaurants().size(), pack.riders().size());
        return pack;
    }
}
```

`libs/demo-data/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

```
com.quickbite.demo.DemoAutoConfiguration
```

**Shared settings.** Add this block under the existing `quickbite:` key in `libs/common/src/main/resources/quickbite-defaults.yml`:

```yaml
quickbite:
  environment: ${APP_ENV:local}          # local | dev | staging | prod
  demo:
    enabled: ${DEMO_MODE:false}
    data-dir: ${DEMO_DATA_DIR:}          # empty = use the pack inside the jar
    allowed-environments: local,dev,staging
```

(The gateway doesn't import the defaults file, so B1 adds the same block to its own `application.yml`.)

## A3. Pack validation test (runs in CI)

`libs/demo-data/src/test/java/com/quickbite/demo/DemoPackTest.java`

```java
package com.quickbite.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class DemoPackTest {
    @Test
    void bundledPackIsValid() {
        DemoPack pack = DemoPack.load(null);
        assertThat(pack.restaurants()).hasSize(8).allMatch(r -> r.id().startsWith("d"));
        assertThat(pack.riders()).hasSize(3);
        assertThat(pack.history()).hasSize(5);
    }

    @Test
    void brokenReferencesAreReportedPrecisely(@TempDir Path dir) throws Exception {
        for (String f : DemoPack.FILES) {
            try (var in = DemoPack.class.getResourceAsStream("/demo/" + f)) { Files.copy(in, dir.resolve(f)); }
        }
        Path history = dir.resolve("orders-history.json");
        Files.writeString(history, Files.readString(history).replace("d1-i3", "d1-i99"));

        assertThatThrownBy(() -> DemoPack.load(dir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown item d1-i99");
    }
}
```

```bash
./gradlew :libs:demo-data:test
```

---

# Part B: Server side

## B1. Gateway: `GET /api/settings`, demo route, safety settings

`services/gateway/build.gradle.kts`: add

```kotlin
implementation(project(":libs:demo-data"))
```

`services/gateway/src/main/resources/application.yml`: add the route and the demo block.

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            # ...existing routes...
            - id: demo
              uri: ${DEMO_URL:http://localhost:8086}
              predicates: ["Path=/api/demo/**"]

quickbite:
  environment: ${APP_ENV:local}
  demo:
    enabled: ${DEMO_MODE:false}
    data-dir: ${DEMO_DATA_DIR:}
    allowed-environments: local,dev,staging
```

`SecurityConfig.java`: permit the public settings endpoint (add next to the restaurants rule):

```java
.pathMatchers(HttpMethod.GET, "/api/settings").permitAll()
```

`services/gateway/src/main/java/com/quickbite/gateway/SettingsController.java`

```java
package com.quickbite.gateway;

import com.quickbite.demo.DemoPack;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public, unauthenticated "what kind of server is this?" endpoint. The app reads it to show the demo banner,
 * login hints and a warning if its Demo Mode doesn't match the server.
 */
@RestController
public class SettingsController {
    public record DemoUser(String username, String password, String role, String description) {}

    private final ObjectProvider<DemoPack> pack;
    private final boolean demo;
    private final String environment;

    public SettingsController(ObjectProvider<DemoPack> pack, @Value("${quickbite.demo.enabled:false}") boolean demo,
                              @Value("${quickbite.environment:local}") String environment) {
        this.pack = pack; this.demo = demo; this.environment = environment;
    }

    @GetMapping("/api/settings")
    public Map<String, Object> settings() {
        var m = new LinkedHashMap<String, Object>();
        m.put("demoMode", demo);
        m.put("environment", environment);
        DemoPack p = pack.getIfAvailable();
        if (demo && p != null) {
            m.put("packName", p.manifest().name());
            m.put("packVersion", p.manifest().version());
            m.put("city", p.manifest().city());
            List<DemoUser> users = new ArrayList<>();
            p.customers().forEach(c -> users.add(new DemoUser(c.username(), c.password(), "customer", c.displayName())));
            p.riders().forEach(r -> users.add(new DemoUser(r.username(), r.password(), "rider",
                    r.name() + " (virtual, driven by demo-svc)")));
            m.put("demoUsers", users);                 // ONLY ever exposed in demo mode
            m.put("fulfillment", "virtual riders (demo-svc)");
        }
        return m;
    }
}
```

## B2. catalog-svc: load and remove demo restaurants

`services/catalog-svc/build.gradle.kts`: add `implementation(project(":libs:demo-data"))`.

`services/catalog-svc/src/main/java/com/quickbite/catalog/config/DemoCatalogLoader.java`

```java
package com.quickbite.catalog.config;

import com.quickbite.catalog.domain.MenuItem;
import com.quickbite.catalog.domain.Restaurant;
import com.quickbite.catalog.domain.RestaurantRepository;
import com.quickbite.demo.DemoPack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Demo ON: upsert d* restaurants from the pack. Demo OFF: remove any d* restaurants left from a previous demo run. */
@Component
@Order(10)                                            // after the normal SeedData runner
public class DemoCatalogLoader implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoCatalogLoader.class);
    private final RestaurantRepository repo;
    private final StringRedisTemplate redis;
    private final ObjectProvider<DemoPack> pack;

    public DemoCatalogLoader(RestaurantRepository repo, StringRedisTemplate redis, ObjectProvider<DemoPack> pack) {
        this.repo = repo; this.redis = redis; this.pack = pack;
    }

    @Override
    public void run(String... args) {
        DemoPack p = pack.getIfAvailable();
        if (p == null) {
            var stale = repo.findAll().stream().filter(r -> r.id().startsWith("d")).toList();
            if (!stale.isEmpty()) { repo.deleteAll(stale); clearCache(); log.info("Demo mode off: removed {} demo restaurants", stale.size()); }
            return;
        }
        var docs = p.restaurants().stream().map(r -> new Restaurant(
                r.id(), r.name(), r.cuisine(), r.cityId(), r.area(), new GeoJsonPoint(r.lon(), r.lat()),
                r.rating(), r.avgPrepMinutes(), "https://picsum.photos/seed/" + r.id() + "/800/400",
                r.menu().stream().map(i -> new MenuItem(i.id(), i.name(), i.name() + " from " + r.name(),
                        i.price(), i.veg(), i.available(), "https://picsum.photos/seed/" + i.id() + "/400/300")).toList()))
            .toList();
        repo.saveAll(docs);                               // save = upsert by id -> re-running is idempotent
        clearCache();
        log.info("Demo mode: loaded {} demo restaurants", docs.size());
    }

    private void clearCache() {
        try { var keys = redis.keys("cache:*"); if (keys != null && !keys.isEmpty()) redis.delete(keys); } catch (Exception ignored) {}
    }
}
```

## B3. payment-svc: demo cards and a card for everyone

`services/payment-svc/build.gradle.kts`: add `implementation(project(":libs:demo-data"))`.

`services/payment-svc/src/main/java/com/quickbite/payment/demo/DemoCardSeeder.java`

```java
package com.quickbite.payment.demo;

import com.quickbite.demo.DemoPack;
import com.quickbite.payment.app.PaymentMethodService;
import com.quickbite.payment.app.PaymentMethodService.AddCard;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** @ConditionalOnProperty, not @ConditionalOnBean: user components are evaluated BEFORE auto-configured beans exist. */
@Component
@ConditionalOnProperty(name = "quickbite.demo.enabled", havingValue = "true")
public class DemoCardSeeder implements CommandLineRunner {
    private final DemoPack pack;
    private final PaymentMethodService methods;

    public DemoCardSeeder(DemoPack pack, PaymentMethodService methods) { this.pack = pack; this.methods = methods; }

    @Override
    public void run(String... args) {
        for (var c : pack.customers()) {
            if (!methods.list(c.userId()).isEmpty()) continue;
            for (var card : c.cards()) {
                var pm = methods.add(c.userId(), new AddCard(card.number(), card.expMonth(), card.expYear(), card.cvc(), c.displayName()));
                if (card.isDefault()) methods.setDefault(c.userId(), pm.getId());
            }
        }
    }
}
```

`services/payment-svc/src/main/java/com/quickbite/payment/demo/DemoCardProvisioner.java`

```java
package com.quickbite.payment.demo;

import com.quickbite.payment.app.PaymentMethodService;
import com.quickbite.payment.app.PaymentMethodService.AddCard;
import com.quickbite.payment.domain.PaymentMethod;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Demo convenience: a customer with NO card gets a Visa 4242, so every demo order can be fulfilled. */
@Component
@ConditionalOnProperty(name = "quickbite.demo.enabled", havingValue = "true")
public class DemoCardProvisioner {
    private final PaymentMethodService methods;
    public DemoCardProvisioner(PaymentMethodService methods) { this.methods = methods; }

    public PaymentMethod provision(String customerId) {
        return methods.add(customerId, new AddCard("4242424242424242", 12, 2030, "123", "Demo card"));
    }
}
```

In `PaymentService` (file 07), inject `ObjectProvider<DemoCardProvisioner> demoCards` through the constructor and change the card lookup in `onOrderCreated`:

```java
PaymentMethod pm = methods.resolve(e.customerId(), e.paymentMethodId()).orElse(null);
if (pm == null && e.paymentMethodId() == null) {            // only when NO card was chosen (never bypass ownership)
    DemoCardProvisioner demo = demoCards.getIfAvailable();   // null unless DEMO_MODE=true
    if (demo != null) pm = demo.provision(e.customerId());
}
```

**Catalogue consistency test**: `cards.json` (used by the Android offline demo) must match the Java list.

`services/payment-svc/src/test/java/com/quickbite/payment/psp/TestCardCatalogTest.java`

```java
package com.quickbite.payment.psp;

import com.quickbite.demo.DemoPack;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class TestCardCatalogTest {
    @Test
    void demoPackCardsMatchFakePsp() {
        var fromPack = DemoPack.load(null).testCards().stream()
                .collect(Collectors.toMap(c -> c.number(), c -> c.behavior() + ":" + c.declineCode()));
        var fromPsp = FakePsp.TEST_CARDS.stream()
                .collect(Collectors.toMap(FakePsp.TestCard::number, c -> c.behavior().name() + ":" + c.declineCode()));
        assertThat(fromPack).isEqualTo(fromPsp);   // two copies of the same data -> a test keeps them honest
    }
}
```

## B4. order-svc: import order history

`services/order-svc/build.gradle.kts`: add `implementation(project(":libs:demo-data"))`.

Add this method to `Order` (file 06):

```java
/** Data import / demo seeding ONLY: history rows are created already completed and back-dated. */
public void backdate(java.time.Instant when) { this.createdAt = when; this.updatedAt = when; }
```

`services/order-svc/src/main/java/com/quickbite/order/demo/DemoHistorySeeder.java`

```java
package com.quickbite.order.demo;

import com.quickbite.common.id.SnowflakeIdGenerator;
import com.quickbite.demo.DemoPack;
import com.quickbite.order.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

@Component
@ConditionalOnProperty(name = "quickbite.demo.enabled", havingValue = "true")
public class DemoHistorySeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoHistorySeeder.class);
    private final DemoPack pack;
    private final OrderRepository repo;
    private final SnowflakeIdGenerator ids;
    private final TransactionTemplate tx;

    public DemoHistorySeeder(DemoPack pack, OrderRepository repo, SnowflakeIdGenerator ids, TransactionTemplate tx) {
        this.pack = pack; this.repo = repo; this.ids = ids; this.tx = tx;
    }

    @Override
    public void run(String... args) {
        for (var customer : pack.customers()) {
            if (!repo.findTop20ByCustomerIdOrderByCreatedAtDesc(customer.userId()).isEmpty()) continue;   // import once
            var home = customer.addresses().getFirst();
            var imported = pack.history().stream().filter(h -> h.customerUserId().equals(customer.userId())).map(h -> {
                var r = pack.restaurant(h.restaurantId()).orElseThrow();
                var lines = h.items().stream().map(i -> {
                    var item = r.item(i.menuItemId()).orElseThrow();
                    return new OrderLine(item.id(), item.name(), i.quantity(), item.price());
                }).toList();
                var rider = pack.rider(h.riderUsername()).orElseThrow();
                Order o = Order.place(ids.nextId(), customer.userId(), r.id(), lines, home.lat(), home.lon(), null);
                o.transitionTo(OrderStatus.PAID);            // walk the REAL state machine: history obeys the same rules
                o.assignRider(rider.userId());
                o.transitionTo(OrderStatus.PICKED_UP);
                o.transitionTo(OrderStatus.DELIVERED);
                o.backdate(Instant.now().minus(Duration.ofDays(h.daysAgo())));
                return o;
            }).toList();
            // Deliberately NO outbox events: importing history must not trigger payments, riders or notifications.
            tx.executeWithoutResult(s -> repo.saveAll(imported));
            log.info("Demo mode: imported {} historical orders for {}", imported.size(), customer.username());
        }
    }
}
```

## B5. demo-svc: the fulfilment engine (virtual riders)

Each virtual rider runs a small state machine, advanced one **tick** at a time (every `stepIntervalMs ÷ speed`):

```
IDLE ──(GET /api/orders/rider/active returns RIDER_ASSIGNED)──► TO_RESTAURANT ──(arrived AND prep time elapsed: POST /pickup)──► DELIVERING
 ▲  keep-alive GPS every 4 s                                      GPS each tick                                   GPS each tick, N steps
 └──────────────────────────────────────────────(last step: POST /deliver)────────────────────────────────────────────┘
```

The rider **never gets an order pushed to it**. The real dispatcher (file 06) picks it because it's online and nearest, exactly as for a human rider.

`services/demo-svc/build.gradle.kts`

```kotlin
plugins { id("quickbite.spring-service") }

dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:demo-data"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

`services/demo-svc/src/main/resources/application.yml`

```yaml
spring:
  config:
    import: classpath:quickbite-defaults.yml
  application:
    name: demo-svc

server:
  port: 8086

management:
  endpoint:
    health:
      group:
        readiness:
          include: readinessState

quickbite:
  demo-svc:
    api-url: ${DEMO_API_URL:http://localhost:8080}   # the gateway: we act like any external client
```

All Java lives under `services/demo-svc/src/main/java/com/quickbite/demosvc/`.

`DemoSvcApplication.java`

```java
package com.quickbite.demosvc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoSvcApplication {
    public static void main(String[] args) { SpringApplication.run(DemoSvcApplication.class, args); }
}
```

`PasswordTokenProvider.java`: a rider "logs in" like the curl scripts do (`cli-test` client, password grant, dev only).

```java
package com.quickbite.demosvc;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;

class PasswordTokenProvider {
    private record TokenResponse(@JsonProperty("access_token") String accessToken, @JsonProperty("expires_in") long expiresIn) {}
    private final RestClient http = RestClient.create();
    private final String tokenUri, username, password;
    private String token;
    private Instant expiresAt = Instant.EPOCH;

    PasswordTokenProvider(String tokenUri, String username, String password) {
        this.tokenUri = tokenUri; this.username = username; this.password = password;
    }

    synchronized String token() {
        if (token == null || expiresAt.isBefore(Instant.now().plusSeconds(30))) {
            var form = new LinkedMultiValueMap<String, String>();
            form.add("grant_type", "password");
            form.add("client_id", "cli-test");
            form.add("username", username);
            form.add("password", password);
            TokenResponse r = http.post().uri(tokenUri).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(TokenResponse.class);
            token = r.accessToken();
            expiresAt = Instant.now().plusSeconds(r.expiresIn());
        }
        return token;
    }
}
```

`RiderAgent.java`

```java
package com.quickbite.demosvc;

import com.quickbite.demo.DemoModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One virtual rider. tick() is called by the engine; all state changes happen through the public APIs. */
class RiderAgent {
    private static final Logger log = LoggerFactory.getLogger(RiderAgent.class);
    enum Phase { IDLE, TO_RESTAURANT, DELIVERING }
    record Pos(double lat, double lon) {
        Pos toward(Pos t, double f) { return new Pos(lat + (t.lat - lat) * f, lon + (t.lon - lon) * f); }
        double km(Pos o) { double dx = (o.lon - lon) * 111.32 * Math.cos(Math.toRadians(lat)), dy = (o.lat - lat) * 110.57; return Math.hypot(dx, dy); }
    }
    record ActiveOrder(String id, String restaurantId, String status, double deliveryLat, double deliveryLon) {}
    record RestaurantInfo(Summary restaurant) { record Summary(double lat, double lon, int avgPrepMinutes) {} }
    record Snapshot(String username, String name, String vehicle, Phase phase, String orderId, double lat, double lon) {}

    private final DemoModel.Rider rider;
    private final DemoModel.Scenario scenario;
    private final RestClient api;
    private final PasswordTokenProvider tokens;
    private final Map<String, RestaurantInfo.Summary> restaurants = new ConcurrentHashMap<>();

    private volatile Phase phase = Phase.IDLE;
    private volatile Pos pos;
    private volatile String orderId;
    private Pos restaurantPos, destination;
    private long readyAtMillis, lastIdleReport;
    private int stepsLeft;

    RiderAgent(DemoModel.Rider rider, DemoModel.Scenario scenario, RestClient api, String tokenUri) {
        this.rider = rider; this.scenario = scenario; this.api = api;
        this.tokens = new PasswordTokenProvider(tokenUri, rider.username(), rider.password());
        this.pos = new Pos(rider.start().lat(), rider.start().lon());
    }

    void tick(double speed) {
        long now = System.currentTimeMillis();
        switch (phase) {
            case IDLE -> {
                if (scenario.returnToStart()) pos = pos.toward(new Pos(rider.start().lat(), rider.start().lon()), 0.1);
                if (now - lastIdleReport >= scenario.idleReportIntervalMs() / speed) { report(); lastIdleReport = now; }
                ActiveOrder a = activeOrder();
                if (a == null) return;
                orderId = a.id();
                destination = new Pos(a.deliveryLat(), a.deliveryLon());
                var r = restaurant(a.restaurantId());
                restaurantPos = new Pos(r.lat(), r.lon());
                if ("PICKED_UP".equals(a.status())) {           // e.g. demo-svc restarted mid-delivery: resume
                    phase = Phase.DELIVERING; stepsLeft = scenario.travelSteps();
                } else {
                    readyAtMillis = now + scenario.prepMillis(r.avgPrepMinutes(), speed);
                    phase = Phase.TO_RESTAURANT;
                }
                log.info("{} took order {} ({})", rider.name(), orderId, a.restaurantId());
            }
            case TO_RESTAURANT -> {
                pos = pos.toward(restaurantPos, 0.35);
                report();
                if (pos.km(restaurantPos) < 0.05 && now >= readyAtMillis) {
                    post("/api/orders/" + orderId + "/pickup");
                    phase = Phase.DELIVERING; stepsLeft = scenario.travelSteps();
                    log.info("{} picked up {}", rider.name(), orderId);
                }
            }
            case DELIVERING -> {
                pos = pos.toward(destination, 1.0 / Math.max(stepsLeft, 1));
                stepsLeft--;
                report();
                if (stepsLeft <= 0) {
                    post("/api/orders/" + orderId + "/deliver");
                    log.info("{} delivered {}", rider.name(), orderId);
                    orderId = null; phase = Phase.IDLE;
                }
            }
        }
    }

    Snapshot snapshot() { return new Snapshot(rider.username(), rider.name(), rider.vehicle(), phase, orderId, pos.lat, pos.lon); }

    // ---------- HTTP (exactly what a rider phone does) ----------
    private void report() {
        api.post().uri("/api/riders/me/location").header("Authorization", "Bearer " + tokens.token())
           .body(Map.of("lat", pos.lat, "lon", pos.lon)).retrieve().toBodilessEntity();
    }
    private ActiveOrder activeOrder() {
        var res = api.get().uri("/api/orders/rider/active").header("Authorization", "Bearer " + tokens.token())
                     .retrieve().toEntity(ActiveOrder.class);
        return res.getStatusCode() == HttpStatus.NO_CONTENT ? null : res.getBody();
    }
    private RestaurantInfo.Summary restaurant(String id) {
        return restaurants.computeIfAbsent(id, k -> api.get().uri("/api/restaurants/{id}", k).retrieve()
                .body(RestaurantInfo.class).restaurant());
    }
    private void post(String path) {
        api.post().uri(path).header("Authorization", "Bearer " + tokens.token()).retrieve().toBodilessEntity();
    }
}
```

`DemoEngine.java`

```java
package com.quickbite.demosvc;

import com.quickbite.common.health.LoopWatchdog;
import com.quickbite.demo.DemoPack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Component
@ConditionalOnProperty(name = "quickbite.demo.enabled", havingValue = "true")
public class DemoEngine implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(DemoEngine.class);
    public record Settings(boolean paused, double speedFactor) {}

    private final DemoPack pack;
    private final LoopWatchdog watchdog;
    private final List<RiderAgent> agents;
    private final AtomicReference<Settings> settings;
    private volatile boolean running;

    public DemoEngine(DemoPack pack, LoopWatchdog watchdog,
                      @Value("${quickbite.demo-svc.api-url}") String apiUrl,
                      @Value("${quickbite.service-auth.token-uri}") String tokenUri) {
        this.pack = pack; this.watchdog = watchdog;
        RestClient api = RestClient.builder().baseUrl(apiUrl).build();
        this.agents = pack.riders().stream().map(r -> new RiderAgent(r, pack.scenario(), api, tokenUri)).toList();
        this.settings = new AtomicReference<>(new Settings(false, pack.scenario().speedFactor()));
    }

    @Override public void start() {
        running = true;
        Thread.ofVirtual().name("demo-engine").start(this::loop);
        log.info("Demo engine started with {} virtual riders", agents.size());
    }
    @Override public void stop() { running = false; }
    @Override public boolean isRunning() { return running; }

    private void loop() {
        while (running) {
            watchdog.beat("demo-engine");                          // the engine is a critical loop, too
            Settings s = settings.get();
            if (!s.paused()) {
                try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {   // riders tick in parallel
                    agents.forEach(a -> pool.submit(() -> {
                        try { a.tick(s.speedFactor()); }
                        catch (Exception e) { log.warn("rider tick failed (will retry next tick): {}", e.toString()); }
                    }));
                }
            }
            sleep((long) (pack.scenario().stepIntervalMs() / s.speedFactor()));
        }
    }

    public List<RiderAgent.Snapshot> snapshot() { return agents.stream().map(RiderAgent::snapshot).toList(); }
    public Settings settings() { return settings.get(); }
    public void pause(boolean p) { settings.updateAndGet(s -> new Settings(p, s.speedFactor())); }
    public void speed(double f) {
        if (f < 0.25 || f > 20) throw new IllegalArgumentException("speed factor must be 0.25..20");
        settings.updateAndGet(s -> new Settings(s.paused(), f));
    }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
```

`DemoController.java`: runtime server-side settings for the demo.

```java
package com.quickbite.demosvc;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/demo")
@ConditionalOnProperty(name = "quickbite.demo.enabled", havingValue = "true")
public class DemoController {
    private final DemoEngine engine;
    public DemoController(DemoEngine engine) { this.engine = engine; }

    @GetMapping("/status")
    public Map<String, Object> status() {
        var s = engine.settings();
        return Map.of("paused", s.paused(), "speedFactor", s.speedFactor(), "riders", engine.snapshot());
    }

    @PostMapping("/pause")  @PreAuthorize("hasRole('admin')") public Map<String, Object> pause()  { engine.pause(true);  return status(); }
    @PostMapping("/resume") @PreAuthorize("hasRole('admin')") public Map<String, Object> resume() { engine.pause(false); return status(); }

    /** e.g. POST /api/demo/speed?factor=4 -> prep and travel 4x faster */
    @PostMapping("/speed") @PreAuthorize("hasRole('admin')")
    public Map<String, Object> speed(@RequestParam double factor) { engine.speed(factor); return status(); }
}
```

## B6. Run it: Compose overlay and scripts

`deploy/compose/docker-compose.demo.yml`: a third Compose file layered on top of the other two.

```yaml
x-demo-env: &demo-env
  DEMO_MODE: "true"
  APP_ENV: local
  DEMO_DATA_DIR: /demo-data           # live-editable pack: edit JSON on the host, restart the service

x-demo-volume: &demo-volume
  - ../../demo-data:/demo-data:ro

services:
  gateway:
    environment: { <<: *demo-env, DEMO_URL: http://demo-svc:8086 }
    volumes: *demo-volume
  catalog-svc:
    environment: *demo-env
    volumes: *demo-volume
  order-svc:
    environment: *demo-env
    volumes: *demo-volume
  payment-svc:
    environment: *demo-env
    volumes: *demo-volume

  demo-svc:
    build: { context: ../.., dockerfile: deploy/docker/Dockerfile, args: { JAR_FILE: services/demo-svc/build/libs/app.jar } }
    image: quickbite/demo-svc:dev
    restart: unless-stopped
    volumes: *demo-volume
    environment:
      <<: *demo-env
      NODE_ID: "6"
      DEMO_API_URL: http://gateway:8080
      KC_ISSUER: http://localhost:8180/realms/quickbite
      KC_JWKS: http://keycloak:8180/realms/quickbite/protocol/openid-connect/certs
      KC_TOKEN_URI: http://keycloak:8180/realms/quickbite/protocol/openid-connect/token
    depends_on:
      gateway: { condition: service_healthy }
    healthcheck: { test: ["CMD", "curl", "-fs", "http://localhost:8086/actuator/health/liveness"], interval: 10s, retries: 12, start_period: 20s }
```

`scripts/up-demo.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
./gradlew bootJar -x test --parallel
cd deploy/compose
docker compose -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.demo.yml up -d --build --remove-orphans
echo "Demo mode starting. Check: curl -s localhost:8000/api/settings | jq"
```

**Turning demo mode off:** run `scripts/up.sh` (the normal two files). Add `--remove-orphans` to its `up` command so demo-svc is removed too. The services restart without `DEMO_MODE`, so catalog-svc deletes the `d*` restaurants and nothing else demo-related runs.

**Kubernetes (file 12):** the pack is inside every jar, so no volume is needed. Add `DEMO_MODE: "true"` to `deploy/helm/values/common.yaml`'s `env`, create `deploy/helm/values/demo-svc.yaml` (`image: {repository: quickbite/demo-svc}`, `port: 8086`, `replicas: 1`, `env: {DEMO_API_URL: http://gateway:8080}`), add `DEMO_URL: http://demo-svc:8086` to the gateway values, and `helm upgrade --install demo-svc ...` like the others.

> **Why `replicas: 1` for demo-svc?** Two replicas would run *two* copies of each virtual rider, both logged in as `demo-rider-1`. For singleton workers you'd normally use leader election (e.g. a Kubernetes Lease), but for a demo tool, one replica is the honest answer.

## B7. Verify server demo mode

```bash
chmod +x scripts/up-demo.sh && scripts/up-demo.sh
docker compose -f deploy/compose/docker-compose.yml -f deploy/compose/docker-compose.apps.yml \
  -f deploy/compose/docker-compose.demo.yml logs demo-svc catalog-svc | grep -i demo | tail

# 1) Settings (public)
curl -s localhost:8000/api/settings | jq

# 2) Demo restaurants are in the catalog
curl -s localhost:8000/api/restaurants | jq '[.[] | select(.id | startswith("d"))] | length'      # 8

# 3) Demo customer: history + cards
DEMO=$(scripts/token.sh demo demo)
curl -s -H "Authorization: Bearer $DEMO" localhost:8000/api/orders | jq 'length'                 # 5
curl -s -H "Authorization: Bearer $DEMO" localhost:8000/api/payments/methods | jq -c '.[] | {brand,last4,isDefault}'

# 4) Order and watch it fulfil ITSELF (no rider-sim needed)
ID=$(curl -s -X POST localhost:8000/api/orders -H "Authorization: Bearer $DEMO" -H 'Content-Type: application/json' \
  -d '{"restaurantId":"d1","items":[{"menuItemId":"d1-i3","quantity":1}],"deliveryLat":12.9279,"deliveryLon":77.6271}' | jq -r .id)
for i in $(seq 1 60); do S=$(curl -s -H "Authorization: Bearer $DEMO" localhost:8000/api/orders/$ID | jq -r .status); printf "\r%-16s" $S; [ "$S" = DELIVERED ] && break; sleep 2; done; echo

# 5) Runtime controls (admin)
ADMIN=$(scripts/token.sh admin admin)
curl -s -H "Authorization: Bearer $ADMIN" localhost:8000/api/demo/status | jq '.riders[] | {name, phase, orderId}'
curl -s -X POST -H "Authorization: Bearer $ADMIN" "localhost:8000/api/demo/speed?factor=4" | jq .speedFactor
curl -s -X POST -H "Authorization: Bearer $ADMIN" localhost:8000/api/demo/pause | jq .paused

# 6) Any customer can order: a user WITHOUT cards gets a demo Visa 4242 automatically
scripts/create-load-users.sh 1 >/dev/null
L=$(scripts/token.sh load1 load1)
for pm in $(curl -s -H "Authorization: Bearer $L" localhost:8000/api/payments/methods | jq -r '.[].id'); do
  curl -s -X DELETE -H "Authorization: Bearer $L" localhost:8000/api/payments/methods/$pm; done      # now load1 has no cards
O=$(curl -s -X POST localhost:8000/api/orders -H "Authorization: Bearer $L" -H 'Content-Type: application/json' \
  -d '{"restaurantId":"d7","items":[{"menuItemId":"d7-i1","quantity":1}],"deliveryLat":12.9279,"deliveryLon":77.6271}' | jq -r .id)
sleep 4; curl -s -H "Authorization: Bearer $L" localhost:8000/api/payments/orders/$O | jq -c '{status, cardBrand, cardLast4}'

# 7) Safety guard: demo mode refuses to start in "prod"
docker compose -f deploy/compose/docker-compose.yml -f deploy/compose/docker-compose.apps.yml \
  -f deploy/compose/docker-compose.demo.yml run --rm -e APP_ENV=prod gateway 2>&1 | grep -m1 "not allowed"
```

| Check | Pass condition |
|---|---|
| 1 | `demoMode: true`, pack name/version, `demoUsers` (demo + 3 riders) |
| 2 | `8` |
| 3 | 5 historical orders; 3 cards, `VISA 4242` default |
| 4 | Status climbs to `DELIVERED` in ~1 minute on its own |
| 5 | Rider phases change (IDLE → TO_RESTAURANT → DELIVERING); speed and pause take effect immediately |
| 6 | `{"status":"AUTHORIZED","cardBrand":"VISA","cardLast4":"4242"}`: provisioned on the fly (with DEMO_MODE off: `FAILED`, `NO_PAYMENT_METHOD`) |
| 7 | "DEMO_MODE=true is not allowed in environment 'prod'" |

**Live-edit the pack:** change a price in `demo-data/restaurants.json`, run `docker compose ... restart catalog-svc`, and the new price is served. No rebuild is needed, thanks to the `DEMO_DATA_DIR` mount. Break a reference on purpose (e.g. `d1-i99` in `orders-history.json`), restart order-svc, and read the validation error in its log.

---

# Part C: Android: Settings screen, Server demo, Offline demo

## C1. Gradle: bundle the same JSON into the app

In `clients/android/app/build.gradle.kts`:

```kotlin
// Copy <repo>/demo-data/*.json into assets/demo/ at build time -> ONE source of truth for server and app
val copyDemoPack by tasks.registering(Copy::class) {
    from(rootProject.file("../../demo-data"))                       // clients/android -> repo root
    into(layout.buildDirectory.dir("generated/demo-assets/demo"))
}
tasks.named("preBuild") { dependsOn(copyDemoPack) }

android {
    defaultConfig {
        buildConfigField("boolean", "DEMO_ALLOWED", "true")
    }
    buildTypes {
        release {
            // Hide demo mode in store builds. Set to "true" for a dedicated demo/sales build variant.
            buildConfigField("boolean", "DEMO_ALLOWED", "false")
        }
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/demo-assets"))
}
```

## C2. App settings persisted as JSON

`settings/AppSettings.kt`

```kotlin
package com.quickbite.app.settings

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable enum class DemoMode { OFF, SERVER, OFFLINE }

@Serializable
data class AppSettings(
    val demoMode: DemoMode = DemoMode.OFF,
    val speedFactor: Double = 1.0,          // offline simulation speed
    val deliveryAddress: String = "Home",   // label from the demo pack's customer addresses
)

/** settings.json in the app's private files dir. Written atomically (temp file + rename). */
class SettingsStore(context: Context) {
    private val file = File(context.filesDir, "settings.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    var current: AppSettings = runCatching { json.decodeFromString<AppSettings>(file.readText()) }.getOrDefault(AppSettings())
        private set

    fun save(s: AppSettings) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(s))
        tmp.renameTo(file)                   // atomic on the same filesystem: never a half-written settings file
        current = s
    }
}
```

## C3. API additions

In `net/Models.kt`, add:

```kotlin
@Serializable data class DemoUser(val username: String, val password: String, val role: String, val description: String? = null)

@Serializable data class ServerSettings(
    val demoMode: Boolean = false, val environment: String? = null, val packName: String? = null,
    val packVersion: Int? = null, val city: String? = null, val demoUsers: List<DemoUser> = emptyList(),
    val fulfillment: String? = null)
```

Also add `val createdAt: String? = null` as the last field of `OrderDto` (the server already sends it).

In `QuickBiteApi` (`net/Api.kt`), add:

```kotlin
    @GET("api/settings") suspend fun settings(): ServerSettings
    @GET("api/orders") suspend fun myOrders(): List<OrderDto>
```

## C4. The demo pack and the offline state (JSON on the device)

`demo/DemoPack.kt`

```kotlin
package com.quickbite.app.demo

import android.content.Context
import com.quickbite.app.net.MenuItem
import com.quickbite.app.net.RestaurantDetail
import com.quickbite.app.net.RestaurantSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class DemoManifest(val name: String, val version: Int, val city: String, val description: String = "")
@Serializable data class DemoItem(val id: String, val name: String, val price: Double, val veg: Boolean, val available: Boolean)
@Serializable data class DemoRestaurant(
    val id: String, val name: String, val cuisine: String, val cityId: String, val area: String,
    val lat: Double, val lon: Double, val rating: Double, val avgPrepMinutes: Int, val menu: List<DemoItem>) {
    fun summary() = RestaurantSummary(id = id, name = name, cuisine = cuisine, area = area, rating = rating,
        avgPrepMinutes = avgPrepMinutes, imageUrl = null, lat = lat, lon = lon)
    fun detail() = RestaurantDetail(summary(), menu.map {
        MenuItem(id = it.id, name = it.name, description = "${it.name} from $name", price = it.price,
            veg = it.veg, available = it.available, imageUrl = null) })
}
@Serializable data class DemoPoint(val lat: Double, val lon: Double)
@Serializable data class DemoAddress(val label: String, val lat: Double, val lon: Double)
@Serializable data class DemoCard(val number: String, val expMonth: Int, val expYear: Int, val cvc: String, val isDefault: Boolean = false)
@Serializable data class DemoCustomer(val userId: String, val username: String, val password: String,
                                      val displayName: String, val addresses: List<DemoAddress>, val cards: List<DemoCard>)
@Serializable data class DemoRider(val userId: String, val username: String, val password: String,
                                   val name: String, val vehicle: String, val start: DemoPoint)
@Serializable data class OfflineTiming(val paymentDelayMs: Long = 1200, val assignDelayMs: Long = 2500)
@Serializable data class DemoScenario(
    val speedFactor: Double = 1.0, val secondsPerPrepMinute: Int = 4, val minPrepSeconds: Int = 6,
    val maxPrepSeconds: Int = 45, val travelSteps: Int = 15, val stepIntervalMs: Long = 2000,
    val idleReportIntervalMs: Long = 4000, val returnToStart: Boolean = true, val offline: OfflineTiming = OfflineTiming()) {
    fun prepMillis(avgPrepMinutes: Int, speed: Double): Long =
        ((avgPrepMinutes * secondsPerPrepMinute).coerceIn(minPrepSeconds, maxPrepSeconds) * 1000 / speed).toLong()
}
@Serializable data class DemoHistoryItem(val menuItemId: String, val quantity: Int)
@Serializable data class DemoHistoricalOrder(val customerUserId: String, val restaurantId: String,
                                             val items: List<DemoHistoryItem>, val daysAgo: Int, val riderUsername: String)
@Serializable data class DemoTestCard(val number: String, val brand: String, val behavior: String,
                                      val declineCode: String? = null, val description: String)

class DemoPack(
    val manifest: DemoManifest, val restaurants: List<DemoRestaurant>, val customers: List<DemoCustomer>,
    val riders: List<DemoRider>, val scenario: DemoScenario, val history: List<DemoHistoricalOrder>,
    val cards: List<DemoTestCard>) {

    fun restaurant(id: String) = restaurants.find { it.id == id }

    companion object {
        const val SUPPORTED_VERSION = 1
        private val json = Json { ignoreUnknownKeys = true }

        fun load(context: Context): DemoPack {
            fun text(f: String) = context.assets.open("demo/$f").bufferedReader().use { it.readText() }
            val pack = DemoPack(
                json.decodeFromString(text("manifest.json")), json.decodeFromString(text("restaurants.json")),
                json.decodeFromString(text("customers.json")), json.decodeFromString(text("riders.json")),
                json.decodeFromString(text("scenario.json")), json.decodeFromString(text("orders-history.json")),
                json.decodeFromString(text("cards.json")))
            require(pack.manifest.version == SUPPORTED_VERSION) {
                "Demo pack v${pack.manifest.version} is not supported by this app (expects v$SUPPORTED_VERSION)"
            }
            return pack
        }
    }
}
```

`demo/CardCheck.kt`

```kotlin
package com.quickbite.app.demo

object CardCheck {
    fun luhn(pan: String): Boolean {
        if (!pan.matches(Regex("\\d{13,19}"))) return false
        var sum = 0
        pan.reversed().forEachIndexed { i, ch -> var d = ch - '0'; if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }; sum += d }
        return sum % 10 == 0
    }
    fun brand(pan: String) = when {
        pan.startsWith("4") -> "VISA"
        pan.matches(Regex("^(5[1-5]|2(2[2-9]|[3-6]\\d|7[01]|720)).*")) -> "MASTERCARD"
        pan.matches(Regex("^3[47].*")) -> "AMEX"
        else -> "UNKNOWN"
    }
}
```

`demo/DemoStateStore.kt`: everything the offline "server" remembers, in `demo-state.json`.

```kotlin
package com.quickbite.app.demo

import android.content.Context
import com.quickbite.app.net.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration
import java.time.Instant

@Serializable data class CardBehavior(val behavior: String, val declineCode: String? = null)

@Serializable data class DemoState(
    val seq: Long = 10_000,
    val cards: List<PaymentMethod> = emptyList(),
    val behaviors: Map<String, CardBehavior> = emptyMap(),       // pm id -> how the fake PSP reacts
    val orders: List<OrderDto> = emptyList(),
    val payments: Map<String, PaymentView> = emptyMap(),         // order id -> payment
    val idempotency: Map<String, String> = emptyMap(),           // Idempotency-Key -> order id
)

class DemoStateStore(context: Context, private val pack: DemoPack) {
    private val file = File(context.filesDir, FILE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile var state: DemoState = runCatching { json.decodeFromString<DemoState>(file.readText()) }
        .getOrElse { initial().also(::persist) }
        private set

    @Synchronized fun update(f: (DemoState) -> DemoState): DemoState { state = f(state); persist(state); return state }

    fun nextOrderId(): String = "D" + update { it.copy(seq = it.seq + 1) }.seq

    fun behaviorOf(number: String) = pack.cards.find { it.number == number }
        ?.let { CardBehavior(it.behavior, it.declineCode) } ?: CardBehavior("APPROVE")

    private fun persist(s: DemoState) {
        val tmp = File(file.path + ".tmp"); tmp.writeText(json.encodeToString(s)); tmp.renameTo(file)
    }

    /** First run: cards and order history come from the pack. */
    private fun initial(): DemoState {
        val c = pack.customers.first()
        val cards = c.cards.mapIndexed { i, k ->
            PaymentMethod(id = "pm_demo_${i + 1}", brand = CardCheck.brand(k.number), last4 = k.number.takeLast(4),
                expMonth = k.expMonth, expYear = k.expYear, holderName = c.displayName, isDefault = k.isDefault)
        }
        val behaviors = c.cards.mapIndexed { i, k -> "pm_demo_${i + 1}" to behaviorOf(k.number) }.toMap()
        val home = c.addresses.first()
        val history = pack.history.filter { it.customerUserId == c.userId }.mapIndexed { i, h ->
            val r = pack.restaurant(h.restaurantId)!!
            val lines = h.items.map { hi -> val m = r.menu.first { it.id == hi.menuItemId }; OrderLine(m.id, m.name, hi.quantity, m.price) }
            OrderDto(id = "H${i + 1}", restaurantId = r.id, status = "DELIVERED",
                total = lines.sumOf { it.unitPrice * it.quantity }, currency = "INR",
                riderId = pack.riders.find { it.username == h.riderUsername }?.userId,
                deliveryLat = home.lat, deliveryLon = home.lon, lines = lines,
                createdAt = Instant.now().minus(Duration.ofDays(h.daysAgo.toLong())).toString())
        }
        val def = cards.firstOrNull { it.isDefault }
        val payments = history.associate { it.id to PaymentView(it.id, "CAPTURED", it.total, "INR", def?.brand, def?.last4) }
        return DemoState(cards = cards, behaviors = behaviors, orders = history, payments = payments)
    }

    companion object {
        const val FILE = "demo-state.json"
        fun wipe(context: Context) { File(context.filesDir, FILE).delete() }
    }
}
```

## C5. The offline "server": an OkHttp interceptor

`demo/DemoServer.kt`: implements the same endpoints and error shapes (`problem+json`) as the real services.

```kotlin
package com.quickbite.app.demo

import com.quickbite.app.net.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import java.time.Instant
import java.time.YearMonth

class DemoServer(private val pack: DemoPack, private val store: DemoStateStore, private val fulfillment: DemoFulfillment) {
    class Reply(val code: Int, val body: String)
    private class ApiError(val code: Int, override val message: String) : Exception(message)
    private val json = Network.json

    fun handle(method: String, url: HttpUrl, body: String?, idempotencyKey: String?): Reply = try {
        route(method, url.pathSegments.filter { it.isNotEmpty() }, url, body, idempotencyKey)
    } catch (e: ApiError) {
        Reply(e.code, """{"status":${e.code},"detail":${JsonPrimitive(e.message)}}""")
    } catch (e: Exception) {
        Reply(400, """{"status":400,"detail":${JsonPrimitive(e.message ?: "bad request")}}""")
    }

    private fun route(m: String, p: List<String>, url: HttpUrl, body: String?, key: String?): Reply = when {
        m == "GET" && p == listOf("api", "settings") -> ok(ServerSettings(demoMode = true, environment = "device",
            packName = pack.manifest.name, packVersion = pack.manifest.version, city = pack.manifest.city,
            fulfillment = "on-device simulation (offline)"))
        m == "GET" && p == listOf("api", "restaurants") ->
            ok(pack.restaurants.filter { it.cityId == (url.queryParameter("city") ?: "blr") }.map { it.summary() })
        m == "GET" && p.size == 3 && p[1] == "restaurants" ->
            ok(pack.restaurant(p[2])?.detail() ?: throw ApiError(404, "restaurant ${p[2]} not found"))
        m == "GET" && p == listOf("api", "payments", "methods") -> ok(store.state.cards)
        m == "POST" && p == listOf("api", "payments", "methods") ->
            created(addCard(json.decodeFromString<AddCardRequest>(body ?: "{}")))
        m == "POST" && p.size == 5 && p[2] == "methods" && p[4] == "default" -> { makeDefault(p[3]); noContent() }
        m == "GET" && p == listOf("api", "payments", "test-cards") ->
            ok(pack.cards.map { TestCard(it.number, it.brand, it.behavior, it.declineCode, it.description) })
        m == "GET" && p.size == 4 && p[1] == "payments" && p[2] == "orders" ->
            ok(store.state.payments[p[3]] ?: throw ApiError(404, "no payment for ${p[3]}"))
        m == "POST" && p == listOf("api", "orders") ->
            created(placeOrder(json.decodeFromString<PlaceOrderRequest>(body ?: "{}"), key))
        m == "GET" && p == listOf("api", "orders") -> ok(store.state.orders.sortedByDescending { it.createdAt })
        m == "GET" && p.size == 3 && p[1] == "orders" ->
            ok(store.state.orders.find { it.id == p[2] } ?: throw ApiError(404, "order ${p[2]} not found"))
        m == "POST" && p == listOf("api", "auth", "logout") -> noContent()
        else -> throw ApiError(404, "Not available in Offline demo: $m /${p.joinToString("/")} (rider mode needs Server demo)")
    }

    private fun placeOrder(req: PlaceOrderRequest, key: String?): OrderDto {
        key?.let { k -> store.state.idempotency[k]?.let { id -> return store.state.orders.first { it.id == id } } }  // same semantics as order-svc
        val r = pack.restaurant(req.restaurantId) ?: throw ApiError(404, "restaurant ${req.restaurantId} not found")
        if (req.items.isEmpty()) throw ApiError(400, "order must contain at least one item")
        val lines = req.items.map { i ->
            if (i.quantity !in 1..20) throw ApiError(400, "quantity must be 1..20")
            val item = r.menu.find { it.id == i.menuItemId } ?: throw ApiError(400, "unknown item ${i.menuItemId}")
            if (!item.available) throw ApiError(409, "${item.name} is sold out")
            OrderLine(item.id, item.name, i.quantity, item.price)             // price from the pack, never from the client
        }
        val order = OrderDto(id = store.nextOrderId(), restaurantId = r.id, status = "PENDING_PAYMENT",
            total = lines.sumOf { it.unitPrice * it.quantity }, currency = "INR", riderId = null,
            deliveryLat = req.deliveryLat, deliveryLon = req.deliveryLon, lines = lines, createdAt = Instant.now().toString())
        store.update { s -> s.copy(orders = s.orders + order,
            idempotency = if (key != null) s.idempotency + (key to order.id) else s.idempotency) }
        val pmId = req.paymentMethodId ?: store.state.cards.firstOrNull { it.isDefault }?.id
        fulfillment.start(order.id, pmId)
        return order
    }

    private fun addCard(c: AddCardRequest): PaymentMethod {
        val pan = c.number.filter(Char::isDigit)
        if (!CardCheck.luhn(pan)) throw ApiError(400, "Invalid card number")
        if (c.expMonth !in 1..12 || YearMonth.of(c.expYear, c.expMonth).isBefore(YearMonth.now())) throw ApiError(400, "Card has expired")
        val brand = CardCheck.brand(pan)
        val cvcLen = if (brand == "AMEX") 4 else 3
        if (!c.cvc.matches(Regex("\\d{$cvcLen}"))) throw ApiError(400, "CVC must be $cvcLen digits")
        if (store.state.cards.size >= 10) throw ApiError(409, "Maximum 10 cards")
        val pm = PaymentMethod(id = "pm_demo_${store.nextOrderId().drop(1)}", brand = brand, last4 = pan.takeLast(4),
            expMonth = c.expMonth, expYear = c.expYear, holderName = c.holderName, isDefault = store.state.cards.isEmpty())
        store.update { s -> s.copy(cards = s.cards + pm, behaviors = s.behaviors + (pm.id to store.behaviorOf(pan))) }
        return pm                                                           // the PAN is dropped here, like a real PSP token flow
    }

    private fun makeDefault(id: String) {
        if (store.state.cards.none { it.id == id }) throw ApiError(404, "card not found")
        store.update { s -> s.copy(cards = s.cards.map { it.copy(isDefault = it.id == id) }) }
    }

    private inline fun <reified T> ok(v: T) = Reply(200, json.encodeToString(v))
    private inline fun <reified T> created(v: T) = Reply(201, json.encodeToString(v))
    private fun noContent() = Reply(204, "")
}
```

`demo/DemoInterceptor.kt`

```kotlin
package com.quickbite.app.demo

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/** Answers every request locally. chain.proceed() is NEVER called, so nothing leaves the device. */
class DemoInterceptor(private val server: DemoServer, private val latencyMs: Long = 120) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val body = req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }
        Thread.sleep(latencyMs)                                   // feels like a network; exposes missing spinners
        val reply = server.handle(req.method, req.url, body, req.header("Idempotency-Key"))
        return Response.Builder()
            .request(req).protocol(Protocol.HTTP_1_1)
            .code(reply.code).message(if (reply.code < 400) "OK" else "Error")
            .body(reply.body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
```

## C6. On-device fulfilment simulation

`demo/DemoFulfillment.kt`: emits exactly the WebSocket messages realtime-svc would send.

```kotlin
package com.quickbite.app.demo

import com.quickbite.app.net.PaymentMethod
import com.quickbite.app.net.PaymentView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DemoFulfillment(private val scope: CoroutineScope, private val store: DemoStateStore,
                      private val pack: DemoPack, private val speed: Double) {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val events: SharedFlow<String> = _events
    private val sc = pack.scenario
    private fun scaled(ms: Long) = (ms / speed).toLong()

    /** App restarted mid-delivery? Fast-forward in-flight orders instead of leaving them stuck. */
    fun finishInterrupted() = store.update { s ->
        s.copy(orders = s.orders.map { if (it.status in setOf("PENDING_PAYMENT", "PAID", "RIDER_ASSIGNED", "PICKED_UP")) it.copy(status = "DELIVERED") else it })
    }

    fun start(orderId: String, paymentMethodId: String?) = scope.launch {
        delay(scaled(sc.offline.paymentDelayMs))
        val card = store.state.cards.find { it.id == paymentMethodId }
            ?: return@launch fail(orderId, null, "NO_PAYMENT_METHOD")
        val b = store.state.behaviors[card.id] ?: CardBehavior("APPROVE")
        when (b.behavior) {
            "DECLINE" -> return@launch fail(orderId, card, "DECLINED: ${b.declineCode}")
            "PROCESSING_ERROR" -> { delay(scaled(700)); return@launch fail(orderId, card, "PSP_UNAVAILABLE") }
            "SLOW" -> delay(scaled(2500))
        }
        pay(orderId, card, "AUTHORIZED", null)
        status(orderId, "PAID", null)

        delay(scaled(sc.offline.assignDelayMs))
        val order = store.state.orders.first { it.id == orderId }
        val restaurant = pack.restaurant(order.restaurantId)!!
        val rider = pack.riders.random()
        status(orderId, "RIDER_ASSIGNED", rider.userId)

        val step = scaled(sc.stepIntervalMs)
        var pos = rider.start.lat to rider.start.lon
        val prepSteps = maxOf(2, (sc.prepMillis(restaurant.avgPrepMinutes, speed) / step).toInt())
        repeat(prepSteps) { i ->                                          // ride to the restaurant while food is prepared
            pos = toward(pos, restaurant.lat to restaurant.lon, 1.0 / (prepSteps - i))
            location(orderId, pos); delay(step)
        }
        status(orderId, "PICKED_UP", rider.userId)
        repeat(sc.travelSteps) { i ->                                     // ride to the customer
            pos = toward(pos, order.deliveryLat to order.deliveryLon, 1.0 / (sc.travelSteps - i))
            location(orderId, pos); delay(step)
        }
        status(orderId, "DELIVERED", rider.userId)
        if (b.behavior != "CAPTURE_FAIL") pay(orderId, card, "CAPTURED", null)   // 0341: stays AUTHORIZED, like the server
    }

    private suspend fun fail(orderId: String, card: PaymentMethod?, reason: String) {
        pay(orderId, card, "FAILED", reason)
        status(orderId, "CANCELLED", null)
    }

    private fun pay(orderId: String, card: PaymentMethod?, status: String, reason: String?) = store.update { s ->
        val o = s.orders.first { it.id == orderId }
        s.copy(payments = s.payments + (orderId to PaymentView(orderId, status, o.total, o.currency, card?.brand, card?.last4, reason)))
    }

    private suspend fun status(orderId: String, status: String, riderId: String?) {
        store.update { s -> s.copy(orders = s.orders.map { if (it.id == orderId) it.copy(status = status, riderId = riderId ?: it.riderId) else it }) }
        _events.emit(buildJsonObject {
            put("type", "ORDER_STATUS"); put("orderId", orderId); put("status", status); put("riderId", riderId ?: "")
        }.toString())
    }

    private suspend fun location(orderId: String, p: Pair<Double, Double>) = _events.emit(buildJsonObject {
        put("type", "RIDER_LOCATION"); put("orderId", orderId); put("lat", p.first); put("lon", p.second)
        put("ts", System.currentTimeMillis())
    }.toString())

    private fun toward(a: Pair<Double, Double>, b: Pair<Double, Double>, f: Double) =
        (a.first + (b.first - a.first) * f) to (a.second + (b.second - a.second) * f)
}
```

## C7. One `Backend` interface, two implementations

`net/Backend.kt`

```kotlin
package com.quickbite.app.net

import android.content.Context
import com.quickbite.app.BuildConfig
import com.quickbite.app.auth.AuthManager
import com.quickbite.app.demo.*
import com.quickbite.app.settings.AppSettings
import com.quickbite.app.settings.DemoMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** What the ViewModel needs from "a server". The UI never knows which implementation it has. */
interface Backend {
    val api: QuickBiteApi
    val offline: Boolean
    fun updates(): Flow<String>
    fun openRiderSocket(): WebSocket?          // null = not supported (offline)
    fun close() {}
}

class RemoteBackend(auth: AuthManager) : Backend {
    private val http = Network.client(auth)
    private val realtime = Realtime(http)
    override val api = Network.api(http)
    override val offline = false
    override fun updates() = realtime.updates()
    override fun openRiderSocket() = realtime.openRiderSocket()
}

class DemoBackend(context: Context, settings: AppSettings) : Backend {
    private val pack = DemoPack.load(context)
    private val store = DemoStateStore(context, pack)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fulfillment = DemoFulfillment(scope, store, pack, settings.speedFactor).also { it.finishInterrupted() }
    override val api: QuickBiteApi = Retrofit.Builder()
        .baseUrl("http://demo.invalid/")                 // ".invalid" can never resolve: a safety net, not a real host
        .client(OkHttpClient.Builder().addInterceptor(DemoInterceptor(DemoServer(pack, store, fulfillment))).build())
        .addConverterFactory(Network.json.asConverterFactory("application/json".toMediaType()))
        .build().create(QuickBiteApi::class.java)
    override val offline = true
    override fun updates(): Flow<String> = fulfillment.events
    override fun openRiderSocket(): WebSocket? = null
    override fun close() = scope.cancel()
}

object Backends {
    fun create(context: Context, settings: AppSettings, auth: AuthManager): Backend =
        if (BuildConfig.DEMO_ALLOWED && settings.demoMode == DemoMode.OFFLINE) DemoBackend(context, settings)
        else RemoteBackend(auth)                         // OFF and SERVER both talk to the real stack
}
```

## C8. ViewModel: full replacement of `ui/AppViewModel.kt`

This version includes everything from file 10 (plus cards) and adds settings, demo modes and order history.

```kotlin
package com.quickbite.app.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.quickbite.app.BuildConfig
import com.quickbite.app.auth.AuthManager
import com.quickbite.app.demo.DemoPack
import com.quickbite.app.demo.DemoStateStore
import com.quickbite.app.net.*
import com.quickbite.app.settings.AppSettings
import com.quickbite.app.settings.DemoMode
import com.quickbite.app.settings.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import java.util.UUID

enum class Screen { Login, Restaurants, Menu, Tracking, Rider, Orders, Settings }

data class UiState(
    val screen: Screen = Screen.Login,
    val previousScreen: Screen = Screen.Login,
    val user: String = "",
    val roles: Set<String> = emptySet(),
    val busy: Boolean = false,
    val error: String? = null,
    val restaurants: List<RestaurantSummary> = emptyList(),
    val detail: RestaurantDetail? = null,
    val cart: Map<String, Int> = emptyMap(),
    val order: OrderDto? = null,
    val orders: List<OrderDto> = emptyList(),
    val payment: PaymentView? = null,
    val riderPos: Pair<Double, Double>? = null,
    val log: List<String> = emptyList(),
    val riderOnline: Boolean = false,
    val riderOrder: OrderDto? = null,
    val city: String = "blr",
    val cards: List<PaymentMethod> = emptyList(),
    val selectedCardId: String? = null,
    val testCards: List<TestCard> = emptyList(),
    val showAddCard: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val serverSettings: ServerSettings? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val auth = AuthManager(app)
    private val settingsStore = SettingsStore(app)
    val demoPack: DemoPack = DemoPack.load(app)                        // bundled JSON: addresses, hints, offline data
    private var backend: Backend = Backends.create(app, settingsStore.current, auth)
    private val api get() = backend.api
    private val publicApi by lazy { Network.api(OkHttpClient()) }       // unauthenticated, always the real server

    private val _state = MutableStateFlow(UiState(settings = settingsStore.current))
    val state: StateFlow<UiState> = _state

    private var updatesJob: Job? = null
    private var riderJob: Job? = null
    private var checkoutKey: String = UUID.randomUUID().toString()

    private val deliveryPoint: Pair<Double, Double>
        get() = demoPack.customers.first().addresses.find { it.label == _state.value.settings.deliveryAddress }
            ?.let { it.lat to it.lon } ?: (12.9279 to 77.6271)

    init {
        refreshServerSettings()
        when {
            backend.offline -> enterOfflineDemo()
            auth.isLoggedIn -> afterLogin(auth.username(), auth.roles())
        }
    }

    // ---------- auth ----------
    suspend fun loginIntent(): Intent = auth.loginIntent()

    fun onLoginResult(data: Intent?) = launchSafe {
        if (data == null) error("Login cancelled")
        auth.completeLogin(data)
        afterLogin(auth.username(), auth.roles())
    }

    /** Offline demo: no OAuth at all; the "user" is the pack's demo customer. */
    fun enterOfflineDemo() = afterLogin(demoPack.customers.first().displayName, setOf("customer"))

    private fun afterLogin(user: String, roles: Set<String>) {
        _state.update { it.copy(user = user, roles = roles, screen = if ("rider" in roles) Screen.Rider else Screen.Restaurants) }
        startUpdates()
        if ("rider" !in roles) { loadRestaurants(); loadCards() }
    }

    fun logout() = launchSafe {
        runCatching { api.logout() }
        updatesJob?.cancel(); riderJob?.cancel()
        if (!backend.offline) auth.clear()
        _state.value = UiState(settings = settingsStore.current, serverSettings = _state.value.serverSettings)
    }

    // ---------- settings ----------
    fun openSettings() { _state.update { it.copy(previousScreen = it.screen, screen = Screen.Settings, error = null) }; refreshServerSettings() }
    fun closeSettings() = _state.update { it.copy(screen = it.previousScreen) }

    fun refreshServerSettings() = viewModelScope.launch {
        _state.update { it.copy(serverSettings = runCatching { publicApi.settings() }.getOrNull()) }
    }

    fun applySettings(new: AppSettings) {
        val old = settingsStore.current
        settingsStore.save(new)
        val rebuild = new.demoMode != old.demoMode || (new.demoMode == DemoMode.OFFLINE && new.speedFactor != old.speedFactor)
        if (!rebuild) { _state.update { it.copy(settings = new, screen = it.previousScreen) }; return }
        restartBackend(new)
    }

    fun resetOfflineDemo() { DemoStateStore.wipe(getApplication()); restartBackend(settingsStore.current) }

    private fun restartBackend(s: AppSettings) {
        updatesJob?.cancel(); riderJob?.cancel(); backend.close()
        backend = Backends.create(getApplication(), s, auth)
        _state.value = UiState(settings = s, serverSettings = _state.value.serverSettings)
        when {
            backend.offline -> enterOfflineDemo()
            auth.isLoggedIn -> afterLogin(auth.username(), auth.roles())
        }
    }

    // ---------- customer ----------
    fun loadRestaurants(city: String = _state.value.city) = launchSafe {
        _state.update { it.copy(city = city, restaurants = api.restaurants(city)) }
    }

    fun openRestaurant(id: String) = launchSafe {
        _state.update { it.copy(detail = api.restaurant(id), cart = emptyMap(), screen = Screen.Menu) }
        checkoutKey = UUID.randomUUID().toString()
    }

    fun changeQty(itemId: String, delta: Int) = _state.update {
        val q = ((it.cart[itemId] ?: 0) + delta).coerceIn(0, 20)
        it.copy(cart = if (q == 0) it.cart - itemId else it.cart + (itemId to q))
    }

    fun placeOrder() = launchSafe {
        val s = _state.value
        val (lat, lon) = deliveryPoint
        val req = PlaceOrderRequest(s.detail!!.restaurant.id, s.cart.map { PlaceOrderRequest.Item(it.key, it.value) }, lat, lon, s.selectedCardId)
        val order = api.placeOrder(checkoutKey, req)
        _state.update { it.copy(order = order, payment = null, riderPos = null,
            log = listOf("Order ${order.id}: ${order.status}"), screen = Screen.Tracking) }
    }

    fun openOrders() = launchSafe { _state.update { it.copy(orders = api.myOrders(), screen = Screen.Orders) } }
    fun back() = _state.update { it.copy(screen = Screen.Restaurants, detail = null) }

    // ---------- cards ----------
    fun loadCards() = launchSafe {
        val cards = api.cards()
        val tests = if (BuildConfig.DEBUG || backend.offline) runCatching { api.testCards() }.getOrDefault(emptyList()) else emptyList()
        _state.update { it.copy(cards = cards, testCards = tests,
            selectedCardId = it.selectedCardId ?: cards.firstOrNull { c -> c.isDefault }?.id) }
    }
    fun selectCard(id: String) = _state.update { it.copy(selectedCardId = id) }
    fun showAddCard(show: Boolean) = _state.update { it.copy(showAddCard = show, error = null) }
    fun addCard(number: String, expMonth: Int, expYear: Int, cvc: String) = launchSafe {
        val card = api.addCard(AddCardRequest(number, expMonth, expYear, cvc, _state.value.user))
        _state.update { it.copy(cards = it.cards + card, selectedCardId = card.id, showAddCard = false) }
    }

    // ---------- realtime (both roles, both backends) ----------
    private fun startUpdates() {
        updatesJob?.cancel()
        updatesJob = viewModelScope.launch {
            backend.updates().collect { raw ->
                val msg = Network.json.parseToJsonElement(raw).jsonObject
                when (msg["type"]?.jsonPrimitive?.content) {
                    "ORDER_STATUS" -> {
                        val status = msg["status"]!!.jsonPrimitive.content
                        val orderId = msg["orderId"]!!.jsonPrimitive.content
                        _state.update { st ->
                            st.copy(order = st.order?.takeIf { it.id == orderId }?.copy(status = status) ?: st.order,
                                    log = (st.log + "Order $orderId → $status").takeLast(30))
                        }
                        if (status in setOf("PAID", "CANCELLED", "DELIVERED") && _state.value.order?.id == orderId) {
                            runCatching { api.payment(orderId) }.getOrNull()?.let { p -> _state.update { it.copy(payment = p) } }
                        }
                    }
                    "RIDER_LOCATION" -> _state.update {
                        it.copy(riderPos = msg["lat"]!!.jsonPrimitive.double to msg["lon"]!!.jsonPrimitive.double)
                    }
                }
            }
        }
    }

    // ---------- rider (Server demo / Off only) ----------
    fun toggleOnline() {
        if (_state.value.riderOnline) { riderJob?.cancel(); _state.update { it.copy(riderOnline = false) }; return }
        var socket = backend.openRiderSocket() ?: run {
            _state.update { it.copy(error = "Rider mode needs a server (switch Demo Mode to Off or Server)") }; return
        }
        _state.update { it.copy(riderOnline = true, riderPos = 12.9345 to 77.6230) }
        riderJob = viewModelScope.launch {
            var tick = 0
            while (isActive) {
                val pos = nextPosition(_state.value)
                _state.update { it.copy(riderPos = pos) }
                if (!socket.send("""{"lat":${pos.first},"lon":${pos.second}}""")) socket = backend.openRiderSocket() ?: break
                if (tick++ % 2 == 0) runCatching { api.riderActive() }.getOrNull()?.let { resp ->
                    _state.update { it.copy(riderOrder = if (resp.code() == 200) resp.body() else null) }
                }
                delay(2_000)
            }
            socket.close(1000, "offline")
        }
    }

    private fun nextPosition(s: UiState): Pair<Double, Double> {
        val cur = s.riderPos ?: (12.9345 to 77.6230)
        val o = s.riderOrder ?: return cur
        if (o.status != "PICKED_UP") return cur
        return (cur.first + (o.deliveryLat - cur.first) / 10) to (cur.second + (o.deliveryLon - cur.second) / 10)
    }

    fun pickup() = launchSafe { _state.value.riderOrder?.let { o -> _state.update { it.copy(riderOrder = api.pickup(o.id)) } } }
    fun deliver() = launchSafe { _state.value.riderOrder?.let { o -> api.deliver(o.id); _state.update { it.copy(riderOrder = null) } } }

    // ---------- helpers ----------
    private fun readableError(e: Exception): String =
        (e as? retrofit2.HttpException)?.response()?.errorBody()?.string()
            ?.let { runCatching { Network.json.parseToJsonElement(it).jsonObject["detail"]?.jsonPrimitive?.content }.getOrNull() }
            ?: e.message ?: e.toString()

    private fun launchSafe(block: suspend () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        try { block() } catch (e: Exception) { _state.update { it.copy(error = readableError(e)) } }
        finally { _state.update { it.copy(busy = false) } }
    }

    override fun onCleared() { backend.close() }
}
```

## C9. UI: Settings, My orders, banner, demo-aware login

`ui/SettingsScreen.kt`

```kotlin
package com.quickbite.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.quickbite.app.BuildConfig
import com.quickbite.app.settings.DemoMode

@Composable
fun SettingsScreen(s: UiState, vm: AppViewModel) {
    var draft by remember(s.settings) { mutableStateOf(s.settings) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        if (!BuildConfig.DEMO_ALLOWED) {
            Text("Demo mode is not available in this build.")
        } else {
            Text("Demo mode", style = MaterialTheme.typography.titleMedium)
            listOf(
                DemoMode.OFF to ("Off" to "Normal app against your backend."),
                DemoMode.SERVER to ("Server demo" to "Backend started with DEMO_MODE=true; virtual riders deliver automatically."),
                DemoMode.OFFLINE to ("Offline demo" to "No server needed. Data from the bundled demo pack; delivery simulated on this phone."),
            ).forEach { (mode, text) ->
                Row(Modifier.fillMaxWidth().clickable { draft = draft.copy(demoMode = mode) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = draft.demoMode == mode, onClick = { draft = draft.copy(demoMode = mode) })
                    Column { Text(text.first); Text(text.second, style = MaterialTheme.typography.bodySmall) }
                }
            }

            if (draft.demoMode == DemoMode.OFFLINE) {
                Text("Simulation speed: %.1f×".format(draft.speedFactor))
                Slider(value = draft.speedFactor.toFloat(), onValueChange = { draft = draft.copy(speedFactor = it.toDouble()) },
                    valueRange = 0.5f..8f, steps = 14)
                OutlinedButton(onClick = vm::resetOfflineDemo) { Text("Reset offline demo data") }
            }

            Spacer(Modifier.height(12.dp))
            Text("Delivery address (demo pack)", style = MaterialTheme.typography.titleMedium)
            vm.demoPack.customers.first().addresses.forEach { a ->
                Row(Modifier.fillMaxWidth().clickable { draft = draft.copy(deliveryAddress = a.label) },
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = draft.deliveryAddress == a.label, onClick = { draft = draft.copy(deliveryAddress = a.label) })
                    Text("${a.label} (%.4f, %.4f)".format(a.lat, a.lon))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Server", style = MaterialTheme.typography.titleMedium)
        val srv = s.serverSettings
        if (srv == null) Text("Not reachable at localhost:8000 (fine for Offline demo)")
        else {
            Text("Environment: ${srv.environment ?: "?"} · Demo mode: ${if (srv.demoMode) "ON" else "off"}")
            srv.packName?.let { Text("Pack: $it v${srv.packVersion}") }
            srv.demoUsers.forEach { Text("• ${it.username} / ${it.password} (${it.role})", style = MaterialTheme.typography.bodySmall) }
        }
        if (draft.demoMode == DemoMode.SERVER && srv?.demoMode != true) {
            Text("⚠ The server is not in demo mode: orders won't be fulfilled automatically. Start it with scripts/up-demo.sh.",
                color = MaterialTheme.colorScheme.error)
        }
        TextButton(onClick = vm::refreshServerSettings) { Text("Refresh server info") }

        Spacer(Modifier.height(16.dp))
        Row {
            Button(onClick = { vm.applySettings(draft) }) { Text("Save") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = vm::closeSettings) { Text("Cancel") }
        }
    }
}
```

`ui/OrdersScreen.kt`

```kotlin
package com.quickbite.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun OrdersScreen(s: UiState, vm: AppViewModel) {
    TextButton(onClick = vm::back) { Text("← Restaurants") }
    Text("My orders", style = MaterialTheme.typography.headlineSmall)
    if (s.orders.isEmpty()) Text("No orders yet")
    LazyColumn {
        items(s.orders, key = { it.id }) { o ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("${o.restaurantId} · ${o.status}", style = MaterialTheme.typography.titleSmall)
                    Text("₹%.0f · %s".format(o.total, o.createdAt?.take(10) ?: ""))
                    Text(o.lines.joinToString { "${it.quantity}× ${it.name}" }, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
```

In `ui/Screens.kt`, **replace** `AppScaffold` and `Login` with these versions (the other composables stay as they are):

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(s: UiState, vm: AppViewModel, onLogin: () -> Unit) {
    Scaffold(topBar = {
        Column {
            TopAppBar(title = { Text(if (s.user.isBlank()) "QuickBite" else "QuickBite · ${s.user}") },
                actions = {
                    if ("customer" in s.roles && s.screen != Screen.Settings) TextButton(onClick = vm::openOrders) { Text("Orders") }
                    TextButton(onClick = vm::openSettings) { Text("⚙") }
                    if (s.screen != Screen.Login && s.screen != Screen.Settings) TextButton(onClick = vm::logout) { Text("Logout") }
                })
            if (s.settings.demoMode != com.quickbite.app.settings.DemoMode.OFF) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(when (s.settings.demoMode) {
                            com.quickbite.app.settings.DemoMode.OFFLINE -> "DEMO MODE · Offline (on-device data)"
                            else -> "DEMO MODE · Server" + if (s.serverSettings?.demoMode == true) "" else " (server not in demo mode!)"
                        }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when (s.screen) {
                Screen.Login -> Login(s, vm, onLogin)
                Screen.Restaurants -> Restaurants(s, vm)
                Screen.Menu -> Menu(s, vm)
                Screen.Tracking -> Tracking(s, vm)
                Screen.Rider -> Rider(s, vm)
                Screen.Orders -> OrdersScreen(s, vm)
                Screen.Settings -> SettingsScreen(s, vm)
            }
        }
    }
}

@Composable private fun Login(s: UiState, vm: AppViewModel, onLogin: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        when (s.settings.demoMode) {
            com.quickbite.app.settings.DemoMode.OFFLINE -> {
                Text("Offline demo: no server needed")
                Spacer(Modifier.height(16.dp))
                Button(onClick = vm::enterOfflineDemo) { Text("Start demo") }
            }
            else -> {
                if (s.settings.demoMode == com.quickbite.app.settings.DemoMode.SERVER) {
                    val u = s.serverSettings?.demoUsers?.firstOrNull { it.role == "customer" }
                    Text("Demo login: ${u?.username ?: "demo"} / ${u?.password ?: "demo"}", style = MaterialTheme.typography.titleMedium)
                } else {
                    Text("Log in as alice/alice (customer) or bob/bob (rider)")
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onLogin) { Text("Log in with QuickBite ID") }
            }
        }
        TextButton(onClick = vm::openSettings) { Text("⚙ Settings / Demo mode") }
    }
}
```

## C10. Verify on the device

| # | Test | Steps | Expected |
|---|---|---|---|
| D1 | Settings persist | ⚙ → Offline demo → Save; force-stop the app; reopen | Still in Offline demo (`settings.json` survived) |
| D2 | Offline, no network | Airplane mode ON → Start demo → order from **Idli Express** | Full flow to DELIVERED; rider marker moves; payment `VISA ••4242 CAPTURED` |
| D3 | Offline history | Orders | 5 past orders from the pack + the new one |
| D4 | Offline decline | In "Pay with", pick **VISA ••0002** → order | CANCELLED, "Reason: DECLINED: card_declined" |
| D5 | Offline card validation | + Add card → `4242 4242 4242 4241` | "Invalid card number" |
| D6 | Offline speed | Settings → speed 6× → Save → order | Delivered in ~10–15 s |
| D7 | Offline persistence | Place an order, force-stop mid-delivery, reopen → Orders | The order shows DELIVERED (fast-forwarded), nothing stuck |
| D8 | Reset | Settings → Reset offline demo data | Back to 5 history orders and 3 cards |
| D9 | Server demo | `scripts/up-demo.sh` → app: ⚙ → Server demo → Save → log in `demo`/`demo` → order | A virtual rider (Ravi/Meena/Arjun) delivers; admin `GET /api/demo/status` shows it |
| D10 | Mode mismatch warning | Server demo in the app, backend started with `scripts/up.sh` | Banner: "(server not in demo mode!)"; settings warning |
| D11 | Release build | `./gradlew assembleRelease` → install | Settings shows "Demo mode is not available in this build" |

Inspect the JSON the app wrote:

```bash
adb shell run-as com.quickbite.app cat files/settings.json
adb shell run-as com.quickbite.app cat files/demo-state.json | head -c 600
```

---

## Summary: what switches where

| Setting | Where | Default | Effect |
|---|---|---|---|
| `DEMO_MODE` | Env var on gateway, catalog, order, payment, demo-svc | `false` | Loads the demo pack; enables the demo seeders, auto card, `/api/settings` details, and the virtual riders |
| `APP_ENV` | Env var, all services | `local` | Demo refuses to start outside `local,dev,staging` |
| `DEMO_DATA_DIR` | Env var | empty (use the pack in the jar) | Load the pack from a mounted folder (live edits) |
| `/api/demo/speed`, `/pause`, `/resume` | Runtime (admin) | from `scenario.json` | Control the virtual riders without a restart |
| App → Settings → Demo mode | `files/settings.json` | Off | Off / Server demo / Offline demo |
| App → speed, delivery address | `files/settings.json` | 1×, Home | Offline simulation speed; delivery point for all modes |
| `DEMO_ALLOWED` | Android `BuildConfig` | true (debug), false (release) | Hides demo mode from store builds |
