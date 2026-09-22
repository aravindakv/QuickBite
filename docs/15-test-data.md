# 15 — Test Data Reference

Everything you need to drive manual, scripted and mobile tests: who to log in as, what to order, which card to pay with, and what should happen.

---

## 1. Users (Keycloak realm `quickbite`)

| Username / password | Fixed user id (`sub`) | Roles | Seeded cards | Use for |
|---|---|---|---|---|
| `alice` / `alice` | `11111111-1111-1111-1111-111111111111` | customer | Visa 4242 ★, 0002, 9995, 0341 | Main customer: every payment scenario |
| `bob` / `bob` | `22222222-2222-2222-2222-222222222222` | rider | – | Rider app / `rider-sim.sh` |
| `carol` / `carol` | `33333333-3333-3333-3333-333333333333` | rider | – | Second rider (dispatch contention) |
| `admin` / `admin` | `44444444-4444-4444-4444-444444444444` | admin, customer | Mastercard 4444 ★, Amex 0005 | Chaos endpoints; brand tests |
| `load1`…`loadN` / same as username | random | customer | Visa 4242 ★ | k6 and surge (created by `scripts/create-load-users.sh`) |

★ = default card (used when an order has no `paymentMethodId`).

Keycloak admin console: `http://localhost:8180`, login `admin` / `admin` (master realm).

---

### Demo Mode accounts and data (file 16)

| Username / password | Fixed id | Role | Data from the demo pack |
|---|---|---|---|
| `demo` / `demo` | `55555555-5555-5555-5555-555555555555` | customer | 3 cards (Visa 4242 ★, Mastercard 4444, Visa 0002 decline), 5 historical orders, Home/Office addresses |
| `demo-rider-1` / `demo-rider` | `66666666-6666-6666-6666-000000000001` | rider | Ravi, Motorbike: virtual (demo-svc) |
| `demo-rider-2` / `demo-rider` | `…000000000002` | rider | Meena, Scooter: virtual |
| `demo-rider-3` / `demo-rider` | `…000000000003` | rider | Arjun, E-bike: virtual |

Demo restaurants `d1`–`d8` (all items available, prep compressed to 6–45 s) appear only while `DEMO_MODE=true`, and always in the app's Offline demo. Their source is `demo-data/restaurants.json`.

---

## 2. Restaurants (22 restaurants, 132 items)

Source: `services/catalog-svc/src/main/resources/seed/restaurants.json`. Every restaurant has 6 items with ids `rN-i1` … `rN-i6`, and **`rN-i6` is always sold out**.

| Id | Name | City | Area | Cuisine | ★ | Prep (min) | First item (always available) | Sold-out item | Notes |
|---|---|---|---|---|---|---|---|---|---|
| `r1` | Dosa Junction | `blr` | Koramangala | South Indian | 4.5 | 15 | `r1-i1` Masala Dosa ₹120 | `r1-i6` Ghee Pongal | used by scripts |
| `r2` | Biryani Bros | `blr` | BTM Layout | Hyderabadi | 4.3 | 25 | `r2-i1` Chicken Dum Biryani ₹320 | `r2-i6` Haleem | used by scripts |
| `r3` | Pasta Stop | `blr` | Indiranagar | Italian | 4.1 | 20 | `r3-i1` Margherita Pizza ₹350 | `r3-i6` Truffle Risotto |  |
| `r4` | Green Bowl | `blr` | Koramangala | Salads | 4.6 | 10 | `r4-i1` Greek Salad ₹280 | `r4-i6` Acai Bowl | on default delivery point, used by scripts |
| `r5` | Tandoor Tales | `blr` | Marathahalli | North Indian | 4.2 | 25 | `r5-i1` Paneer Butter Masala ₹290 | `r5-i6` Gulab Jamun |  |
| `r6` | Wok This Way | `blr` | HSR Layout | Chinese | 4.0 | 18 | `r6-i1` Veg Hakka Noodles ₹220 | `r6-i6` Dim Sum Basket |  |
| `r7` | Burger Barn | `blr` | HSR Layout | Burgers | 4.1 | 15 | `r7-i1` Classic Veg Burger ₹180 | `r7-i6` Onion Rings |  |
| `r8` | Sugar Rush | `blr` | Indiranagar | Desserts | 4.7 | 10 | `r8-i1` Death by Chocolate ₹280 | `r8-i6` Brownie Sundae |  |
| `r9` | Malabar Kitchen | `blr` | Bellandur | Kerala | 4.4 | 22 | `r9-i1` Appam & Stew ₹220 | `r9-i6` Payasam |  |
| `r10` | Nawab's Table | `blr` | MG Road | Mughlai | 4.3 | 30 | `r10-i1` Galouti Kebab ₹380 | `r10-i6` Phirni |  |
| `r11` | Tokyo Box | `blr` | Whitefield | Japanese | 4.2 | 25 | `r11-i1` Veg Sushi Platter ₹480 | `r11-i6` Matcha Ice Cream |  |
| `r12` | Chaat Corner | `blr` | Jayanagar | Street Food | 4.5 | 8 | `r12-i1` Pani Puri ₹60 | `r12-i6` Kathi Roll |  |
| `r13` | Udupi Upahara | `blr` | Malleshwaram | South Indian | 4.6 | 12 | `r13-i1` Masala Dosa ₹120 | `r13-i6` Ghee Pongal |  |
| `r14` | Mangalore Pearl | `blr` | JP Nagar | Coastal | 4.4 | 25 | `r14-i1` Prawn Gassi ₹450 | `r14-i6` Mangalore Buns |  |
| `r15` | Late Night Wok | `blr` | Electronic City | Chinese | 3.8 | 20 | `r15-i1` Veg Hakka Noodles ₹220 | `r15-i6` Dim Sum Basket |  |
| `r16` | Premium Pizza Co | `blr` | Koramangala | Italian | 4.8 | 28 | `r16-i1` Margherita Pizza ₹630 | `r16-i6` Truffle Risotto | 1.8× prices |
| `r17` | Bandra Bites | `mum` | Bandra | Burgers | 4.3 | 15 | `r17-i1` Classic Veg Burger ₹180 | `r17-i6` Onion Rings |  |
| `r18` | Colaba Coastal | `mum` | Colaba | Coastal | 4.6 | 25 | `r18-i1` Prawn Gassi ₹450 | `r18-i6` Mangalore Buns |  |
| `r19` | Andheri Chaat House | `mum` | Andheri | Street Food | 4.4 | 8 | `r19-i1` Pani Puri ₹60 | `r19-i6` Kathi Roll |  |
| `r20` | Powai Pasta Bar | `mum` | Powai | Italian | 4.1 | 20 | `r20-i1` Margherita Pizza ₹350 | `r20-i6` Truffle Risotto |  |
| `r21` | Juhu Biryani House | `mum` | Juhu | Hyderabadi | 4.2 | 25 | `r21-i1` Chicken Dum Biryani ₹320 | `r21-i6` Haleem |  |
| `r22` | Marine Drive Desserts | `mum` | Churchgate | Desserts | 4.7 | 10 | `r22-i1` Death by Chocolate ₹280 | `r22-i6` Brownie Sundae |  |

**Useful combinations:**

| Goal | Order |
|---|---|
| Cheapest order | `r12-i5` Masala Chai ₹30 |
| Big total (larger payment) | `r16-i3` Chicken Alfredo ₹756 × 3 = ₹2,268 |
| Sold-out rejection (`409`) | Any `rN-i6`, e.g. `r1-i6` Ghee Pongal |
| Unknown item (`400`) | `r1-i99` |
| Unknown restaurant (`404`) | `r99` |
| Non-veg vs veg display | `r2` (mostly non-veg), `r8` (all veg) |
| Mumbai city list | `GET /api/restaurants?city=mum` |

---

## 3. Delivery locations

| Name | Lat, Lon | Notes |
|---|---|---|
| Default (app + scripts) | 12.9279, 77.6271 | Same point as `r4 Green Bowl`; surge cell `1292:7762` |
| Rider start (`rider-sim.sh`, app rider mode) | 12.9345, 77.6230 | ~1 km from default, within the 5 km dispatch radius |
| Out of rider range | 13.0031, 77.5643 | ~11 km away: the order stays `PAID` (no rider within 5 km). Tests the dispatcher waiting |
| Mumbai | 19.0596, 72.8295 | No riders there: the order stays `PAID` |

---

## 4. Test cards

Any future expiry (`12/2030`), any holder name, CVC `123` (**`1234` for Amex**).

| Number | Brand | PSP behaviour | Order ends | Payment | `failureReason` |
|---|---|---|---|---|---|
| `4242 4242 4242 4242` | Visa | Approve | DELIVERED | CAPTURED | – |
| `5555 5555 5555 4444` | Mastercard | Approve | DELIVERED | CAPTURED | – |
| `3782 822463 10005` | Amex | Approve | DELIVERED | CAPTURED | – |
| `4000 0000 0000 0002` | Visa | Decline | CANCELLED | FAILED | `DECLINED: card_declined` |
| `4000 0000 0000 9995` | Visa | Decline | CANCELLED | FAILED | `DECLINED: insufficient_funds` |
| `4000 0000 0000 0069` | Visa | Decline | CANCELLED | FAILED | `DECLINED: expired_card` |
| `4000 0000 0000 0127` | Visa | Decline | CANCELLED | FAILED | `DECLINED: incorrect_cvc` |
| `4000 0000 0000 0119` | Visa | Transient error on every attempt | CANCELLED | FAILED | `PSP_UNAVAILABLE` (after 3 attempts) |
| `4000 0000 0000 1976` | Visa | Approve after 2.5 s | DELIVERED | CAPTURED | – (counts as a slow call) |
| `4000 0000 0000 0341` | Visa | Approve; capture fails | DELIVERED | stays AUTHORIZED | – (event in `orders.events.dlt`) |
| any other Luhn-valid number | detected | Approve | DELIVERED | CAPTURED | – |

**Rejected at "add card"** (`400`, nothing stored):

| Input | Message |
|---|---|
| `4242 4242 4242 4241` | Invalid card number (Luhn) |
| `1234` | Invalid card number (length) |
| expiry `01/2020` | Card has expired |
| month `13` | `400` validation error (`expMonth` must be ≤ 12) |
| Amex with CVC `123` | CVC must be 4 digits |
| 11th card for one user | `409` Maximum 10 cards |

**Special payment situations:**

| Situation | How | Result |
|---|---|---|
| No card at all | Order as a fresh `loadN` user created without the card step | `NO_PAYMENT_METHOD` |
| Someone else's card | alice orders with admin's `pm_…` id | `NO_PAYMENT_METHOD` (ownership check) |
| PSP outage (all cards) | `POST /api/payments/admin/psp {"latencyMs":150,"failureRate":1.0}` as admin | Breaker OPEN → fast `PSP_UNAVAILABLE` |
| Flaky PSP | `{"failureRate":0.3}` | Mostly approved thanks to retries |
| Slow PSP | `{"latencyMs":3000}` | Slow-call rate → breaker opens |

---

## 5. Scenario matrix (manual, E2E and mobile)

| # | Actor | Steps | Expected | Automated in |
|---|---|---|---|---|
| S1 | alice (default card) + bob | Order `r1-i1`; rider picks up and delivers | PENDING_PAYMENT → PAID → RIDER_ASSIGNED → PICKED_UP → DELIVERED; payment CAPTURED | `e2e.sh`, `smoke.sh`, mobile A/B |
| S2 | alice, card 0002 | Order | CANCELLED, `DECLINED: card_declined` | `e2e.sh`, mobile M12 |
| S3 | alice, card 9995 | Order | CANCELLED, `DECLINED: insufficient_funds` | `e2e.sh` |
| S4 | alice, card 0119 ×6 | Six orders | Breaker OPEN; a following 4242 order fails fast; recovers after 20 s | file 07 step C |
| S5 | alice, card 0341 + bob | Order, deliver | DELIVERED; payment AUTHORIZED; DLT entry | file 07 step G |
| S6 | alice | Add card `4242 4242 4242 4241` | `400 Invalid card number` | `e2e.sh`, mobile M14 |
| S7 | alice | Order `r1-i6` | `409 … is sold out` | `e2e.sh`, mobile M16 |
| S8 | alice | Order with admin's `pm_` id | `NO_PAYMENT_METHOD` | `e2e.sh` |
| S9 | alice | Same `Idempotency-Key` twice | One order | `OrderFlowIT`, mobile M5 |
| S10 | bob | `POST /api/orders` | `403` | `e2e.sh`, `OrderSecurityIT` |
| S11 | 25 × `loadN` + 1 rider | Orders to cell `1292:7762` | Surge ×2.00 on the delivery fee | file 13 |
| S12 | anyone | Grep `pg_dump` of payments for `4242424242424242` | 0 matches | `e2e.sh` |

---

## 6. Helper scripts

| Script | Purpose |
|---|---|
| `scripts/token.sh <user> <pass>` | Access token via the `cli-test` client |
| `scripts/jwt-decode.sh <token>` | Show claims (`iss`, `aud`, `sub`, `sid`, roles) |
| `scripts/add-card.sh <user> <pass> <number>` | Save a card, print its `pm_` id |
| `scripts/create-load-users.sh [N]` | N customers `loadN` with a default Visa 4242 |
| `scripts/rider-sim.sh [user] [pass]` | Simulated rider (online → pickup → drive → deliver) |
| `scripts/smoke.sh` | One happy-path order through the full stack |
| `scripts/e2e.sh` | Pass/fail suite: security, happy path, cards, compensation, revocation |
| `scripts/android-reverse.sh` | `adb reverse` 8000 and 8180 on every connected device |

Quick recipes:

```bash
# List alice's cards
curl -s -H "Authorization: Bearer $(scripts/token.sh alice alice)" localhost:8000/api/payments/methods | jq

# Order with a specific card (by last 4)
A=$(scripts/token.sh alice alice)
PM=$(curl -s -H "Authorization: Bearer $A" localhost:8000/api/payments/methods | jq -r '.[] | select(.last4=="9995") | .id')
curl -s -X POST localhost:8000/api/orders -H "Authorization: Bearer $A" -H 'Content-Type: application/json' \
  -d "{\"restaurantId\":\"r3\",\"items\":[{\"menuItemId\":\"r3-i1\",\"quantity\":1}],\"deliveryLat\":12.9279,\"deliveryLon\":77.6271,\"paymentMethodId\":\"$PM\"}" | jq

# All test cards as the app sees them (debug)
curl -s -H "Authorization: Bearer $A" localhost:8000/api/payments/test-cards | jq -r '.[] | "\(.number)  \(.description)"'
```

---

## 7. Changing and resetting test data

| Want to… | Do this |
|---|---|
| Add or edit restaurants | Edit `seed/restaurants.json` → restart catalog-svc with `SEED_RESET=true` → purge the NGINX cache (`docker exec quickbite-nginx-1 sh -c 'rm -rf /var/cache/nginx/edge/*'`) |
| Add a new magic card | Add a `TestCard` to `FakePsp.TEST_CARDS` (the number must pass Luhn: `CardRulesTest` will tell you) |
| Give another user seeded cards | Give them a fixed `id` in the realm JSON and add it to `CardSeeder.CARDS` |
| Turn test data off (production-like run) | `SEED_CATALOG=false`, `SEED_CARDS=false`, `TEST_CARDS_ENABLED=false` |
| Change demo data | Edit `demo-data/*.json`; server: restart the services (mounted via `DEMO_DATA_DIR`); app: rebuild, then ⚙ → Reset offline demo data |
| Start completely fresh | `scripts/down.sh -v && scripts/up.sh` (wipes Postgres, Mongo, Kafka; Keycloak re-imports the realm) |
