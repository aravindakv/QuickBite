#!/usr/bin/env bash
# usage: scripts/add-card.sh <user> <password> <card-number>   -> prints the new pm_ id
set -euo pipefail
T=$(scripts/token.sh "$1" "$2")
N=$(echo "$3" | tr -d ' -')
CVC=123; [[ "$N" =~ ^3[47] ]] && CVC=1234
curl -s -X POST localhost:8000/api/payments/methods -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d "{\"number\":\"$N\",\"expMonth\":12,\"expYear\":2030,\"cvc\":\"$CVC\",\"holderName\":\"$1\"}" | jq -r '.id // .detail'
