#!/usr/bin/env bash
# usage: scripts/rider-sim.sh [bob] [bob]
set -euo pipefail
U=${1:-bob}; P=${2:-bob}; API=http://localhost:8000
LAT=12.9345; LON=77.6230          # start near restaurant r1
tok() { scripts/token.sh "$U" "$P"; }
T=$(tok); started=$(date +%s)

post_loc() { curl -s -o /dev/null -X POST "$API/api/riders/me/location" -H "Authorization: Bearer $T" \
  -H 'Content-Type: application/json' -d "{\"lat\":$1,\"lon\":$2}"; }

echo "Rider $U online at $LAT,$LON: waiting for an assignment..."
while true; do
  (( $(date +%s) - started > 240 )) && { T=$(tok); started=$(date +%s); }   # refresh the 5-min token
  post_loc $LAT $LON
  ORDER=$(curl -s "$API/api/orders/rider/active" -H "Authorization: Bearer $T")
  if [ -n "$ORDER" ]; then
    ID=$(echo "$ORDER" | jq -r .id); DLAT=$(echo "$ORDER" | jq -r .deliveryLat); DLON=$(echo "$ORDER" | jq -r .deliveryLon)
    echo "Assigned order $ID -> picking up"
    sleep 3; curl -s -o /dev/null -X POST "$API/api/orders/$ID/pickup" -H "Authorization: Bearer $T"
    for i in $(seq 1 20); do                                  # 20 steps x 2 s = 40 s ride
      CLAT=$(awk -v a=$LAT -v b=$DLAT -v i=$i 'BEGIN{printf "%.6f", a+(b-a)*i/20}')
      CLON=$(awk -v a=$LON -v b=$DLON -v i=$i 'BEGIN{printf "%.6f", a+(b-a)*i/20}')
      post_loc $CLAT $CLON; echo "  step $i/20 at $CLAT,$CLON"; sleep 2
    done
    curl -s -X POST "$API/api/orders/$ID/deliver" -H "Authorization: Bearer $T" | jq -r .status
    LAT=12.9345; LON=77.6230                                  # ride back to the start
  fi
  sleep 4
done