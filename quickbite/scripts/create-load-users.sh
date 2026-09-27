#!/usr/bin/env bash
set -euo pipefail
KC=http://localhost:8180; N=${1:-50}
ADM=$(curl -s -X POST $KC/realms/master/protocol/openid-connect/token \
  -d grant_type=password -d client_id=admin-cli -d username=admin -d password=admin | jq -r .access_token)
ROLE=$(curl -s -H "Authorization: Bearer $ADM" $KC/admin/realms/quickbite/roles/customer)
for i in $(seq 1 $N); do
  U="load$i"
  curl -s -o /dev/null -X POST $KC/admin/realms/quickbite/users -H "Authorization: Bearer $ADM" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$U\",\"enabled\":true,\"email\":\"$U@quickbite.dev\",\"emailVerified\":true,\"firstName\":\"Load\",\"lastName\":\"$i\",
         \"credentials\":[{\"type\":\"password\",\"value\":\"$U\",\"temporary\":false}]}"
  ID=$(curl -s -H "Authorization: Bearer $ADM" "$KC/admin/realms/quickbite/users?username=$U&exact=true" | jq -r '.[0].id')
  curl -s -o /dev/null -X POST $KC/admin/realms/quickbite/users/$ID/role-mappings/realm -H "Authorization: Bearer $ADM" \
    -H 'Content-Type: application/json' -d "[$ROLE]"
  scripts/add-card.sh "$U" "$U" 4242424242424242 > /dev/null     # default card -> their orders can be paid
done
echo "created $N users (password = username), each with a default Visa 4242"