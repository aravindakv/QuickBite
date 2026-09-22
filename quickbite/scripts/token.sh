#!/usr/bin/env bash
# usage: scripts/token.sh alice alice   -> prints an access token on stdout
# On failure it prints Keycloak's actual error to stderr and exits 1 (so TOKEN=$(...) never silently ends up empty).
set -uo pipefail
[ $# -eq 2 ] || { echo "usage: $0 <username> <password>" >&2; exit 2; }
command -v jq >/dev/null || { echo "token.sh: jq is not installed (sudo apt install jq / brew install jq)" >&2; exit 1; }
KC=${KC_URL:-http://localhost:8180}

RESP=$(curl -sS -w '\n%{http_code}' -X POST "$KC/realms/quickbite/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=cli-test \
  --data-urlencode "username=$1" --data-urlencode "password=$2" -d scope=openid 2>&1) || {
  echo "token.sh: cannot reach Keycloak at $KC ($RESP). Is it running and healthy? docker compose ps keycloak" >&2; exit 1; }

CODE=$(tail -n1 <<<"$RESP"); BODY=$(sed '$d' <<<"$RESP")
TOKEN=$(jq -r '.access_token // empty' <<<"$BODY" 2>/dev/null)
if [ "$CODE" != "200" ] || [ -z "$TOKEN" ]; then
  echo "token.sh: HTTP $CODE from Keycloak: $BODY" >&2
  exit 1
fi
echo "$TOKEN"