#!/usr/bin/env bash
# Runs against the containerized stack (scripts/up.sh). Exit code 0 = all passed.
set -uo pipefail
API=${API:-http://localhost:8000}; PASS=0; FAIL=0; SKIP=0
check() { if [ "$2" = "$3" ]; then echo "  ✔ $1"; PASS=$((PASS+1)); else echo "  ✘ $1 (expected $3, got $2)"; FAIL=$((FAIL+1)); fi; }
skip()  { echo "  ~ $1 (skipped: $2)"; SKIP=$((SKIP+1)); }
code() { curl -s -o /dev/null -w "%{http_code}" "$@"; }

# Works against Compose (file 09) or Kubernetes (file 12); database checks are skipped if neither is reachable.
if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx quickbite-postgres-1; then
  PG=(docker exec quickbite-postgres-1)
elif kubectl get pod postgres-0 -n quickbite >/dev/null 2>&1; then
  PG=(kubectl exec -n quickbite postgres-0 --)
else
  PG=()
fi

ALICE=$(scripts/token.sh alice alice); BOB=$(scripts/token.sh bob bob); ADMIN=$(scripts/token.sh admin admin)
ORDER_BODY='{"restaurantId":"r1","items":[{"menuItemId":"r1-i1","quantity":1}],"deliveryLat":12.9279,"deliveryLon":77.6271}'

echo "== Security"
check "no token -> 401"            "$(code $API/api/orders)" 401
check "tampered token -> 401"      "$(code -H "Authorization: Bearer ${ALICE}x" $API/api/orders)" 401
check "rider cannot order -> 403"  "$(code -X POST -H "Authorization: Bearer $BOB" -H 'Content-Type: application/json' -d "$ORDER_BODY" $API/api/orders)" 403
check "internal api hidden"        "$(code -H "Authorization: Bearer $ALICE" "$API/internal/riders/nearest?lat=1&lon=1")" 403
check "public browse -> 200"       "$(code $API/api/restaurants)" 200

echo "== Happy path (rider simulator in background)"
scripts/rider-sim.sh > /tmp/rider.log 2>&1 & SIM=$!
sleep 5
ID=$(curl -s -X POST $API/api/orders -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: e2e-$RANDOM$RANDOM" \
     -H 'Content-Type: application/json' -d "$ORDER_BODY" | jq -r .id)
for i in $(seq 1 60); do S=$(curl -s -H "Authorization: Bearer $ALICE" $API/api/orders/$ID | jq -r .status); [ "$S" = "DELIVERED" ] && break; sleep 2; done
check "order delivered"            "$S" DELIVERED
check "payment captured"           "$(curl -s -H "Authorization: Bearer $ALICE" $API/api/payments/orders/$ID | jq -r .status)" CAPTURED
check "bob cannot read alice's payment" "$(code -H "Authorization: Bearer $BOB" $API/api/payments/orders/$ID)" 403
kill $SIM 2>/dev/null

echo "== Compensation"
curl -s -X POST $API/api/payments/admin/psp -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"latencyMs":50,"failureRate":1.0}' >/dev/null
ID2=$(curl -s -X POST $API/api/orders -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' -d "$ORDER_BODY" | jq -r .id)
sleep 4
check "PSP down -> order cancelled" "$(curl -s -H "Authorization: Bearer $ALICE" $API/api/orders/$ID2 | jq -r .status)" CANCELLED
curl -s -X POST $API/api/payments/admin/psp -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"latencyMs":150,"failureRate":0.0}' >/dev/null

echo "== Payments with test cards"
pmid() { curl -s -H "Authorization: Bearer $ALICE" $API/api/payments/methods | jq -r --arg l "$1" '.[] | select(.last4==$l) | .id'; }
order_with() { curl -s -X POST $API/api/orders -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d "{\"restaurantId\":\"r1\",\"items\":[{\"menuItemId\":\"r1-i1\",\"quantity\":1}],\"deliveryLat\":12.93,\"deliveryLon\":77.62,\"paymentMethodId\":\"$1\"}" | jq -r .id; }
check "alice has seeded cards"     "$(curl -s -H "Authorization: Bearer $ALICE" $API/api/payments/methods | jq '[.[] | select(.last4=="4242" and .isDefault)] | length')" 1
D=$(order_with "$(pmid 0002)"); F=$(order_with "$(pmid 9995)"); sleep 4
check "card_declined -> cancelled" "$(curl -s -H "Authorization: Bearer $ALICE" $API/api/orders/$D | jq -r .status)" CANCELLED
check "decline reason recorded"    "$(curl -s -H "Authorization: Bearer $ALICE" $API/api/payments/orders/$F | jq -r .failureReason)" "DECLINED: insufficient_funds"
check "Luhn-invalid card -> 400"   "$(code -X POST -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d '{"number":"4242424242424241","expMonth":12,"expYear":2030,"cvc":"123"}' $API/api/payments/methods)" 400
ADMIN_PM=$(curl -s -H "Authorization: Bearer $ADMIN" $API/api/payments/methods | jq -r '.[0].id')
X=$(order_with "$ADMIN_PM"); sleep 4
check "other user's card rejected" "$(curl -s -H "Authorization: Bearer $ALICE" $API/api/payments/orders/$X | jq -r .failureReason)" NO_PAYMENT_METHOD
# Dump the ENTIRE payments database and search for a full card number: must never appear
if [ ${#PG[@]} -gt 0 ]; then
  check "no PAN anywhere in DB"    "$("${PG[@]}" pg_dump -U quickbite payments | grep -c 4242424242424242)" 0
else
  skip  "no PAN anywhere in DB"    "no reachable Postgres (Compose down, no postgres-0 pod)"
fi
check "sold-out item -> 409"       "$(code -X POST -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d '{"restaurantId":"r1","items":[{"menuItemId":"r1-i6","quantity":1}],"deliveryLat":12.93,"deliveryLon":77.62}' $API/api/orders)" 409

echo "== Gateway input validation (file 04, step 5)"
check "path traversal -> 400"      "$(code --path-as-is -H "Authorization: Bearer $ALICE" "$API/api/orders/../internal/x")" 400
check "param pollution -> 400"     "$(code "$API/api/restaurants?city=blr&city=mum")" 400
check "unknown field -> 400"       "$(code -X POST -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d '{"restaurantId":"r1","items":[{"menuItemId":"r1-i1","quantity":1,"price":1}],"deliveryLat":12.9,"deliveryLon":77.6}' $API/api/orders)" 400
check "duplicate keys -> 400"      "$(code -X POST -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d '{"restaurantId":"r1","items":[{"menuItemId":"r1-i1","quantity":1,"quantity":999}],"deliveryLat":12.9,"deliveryLon":77.6}' $API/api/orders)" 400
check "wrong content type -> 415"  "$(code -X POST -H "Authorization: Bearer $ALICE" -H 'Content-Type: text/plain' -d x $API/api/orders)" 415
check "non-numeric id -> 404"      "$(code -H "Authorization: Bearer $ALICE" $API/api/orders/abc)" 404

echo "== Session revocation"
T=$(scripts/token.sh alice alice)
curl -s -X POST -H "Authorization: Bearer $T" $API/api/auth/logout >/dev/null
check "revoked token -> 401"       "$(code -H "Authorization: Bearer $T" $API/api/orders)" 401

echo; echo "PASSED: $PASS  FAILED: $FAIL  SKIPPED: $SKIP"; [ $FAIL -eq 0 ]