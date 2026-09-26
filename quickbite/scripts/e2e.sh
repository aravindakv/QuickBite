CID="probe-$RANDOM"
curl -sS -o /dev/null -X POST $API/api/orders -H "Authorization: Bearer $ALICE" -H "X-Correlation-Id: $CID" \
  -H 'Content-Type: application/json' \
  -d '{"restaurantId":"r1","items":[{"menuItemId":"r1-i1","quantity":1}],"deliveryLat":12.93,"deliveryLon":77.62}'
sleep 1
check "correlation id reaches the outbox" \
  "$(docker exec quickbite-postgres-1 psql -U quickbite -d orders -tAc \
     "select correlation_id from outbox order by created_at desc limit 1")" "$CID"