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