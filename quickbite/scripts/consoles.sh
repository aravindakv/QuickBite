#!/usr/bin/env bash
# Start/stop the browser consoles for the local stack (file 21).
#   scripts/consoles.sh up | down | list
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DC=(docker compose -f "$ROOT/deploy/compose/docker-compose.yml" -f "$ROOT/deploy/compose/docker-compose.tools.yml")
CONSOLES=(kafka-ui adminer mongo-express redisinsight)

case "${1:-up}" in
  up)
    "${DC[@]}" up -d "${CONSOLES[@]}" || exit 1
    cat <<EOF

  Kafka UI      http://localhost:8090
  Adminer       http://localhost:8091   (PostgreSQL / postgres / quickbite / quickbite / orders)
  mongo-express http://localhost:8092
  RedisInsight  http://localhost:8093
  Keycloak      http://localhost:8180   (admin / admin)
EOF
    ;;
  down) "${DC[@]}" stop "${CONSOLES[@]}" ;;
  list) "${DC[@]}" ps "${CONSOLES[@]}" ;;
  *) sed -n '2,5p' "${BASH_SOURCE[0]}"; exit 2 ;;
esac