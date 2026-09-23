#!/usr/bin/env bash
# Infrastructure control for QuickBite (file 03): Postgres, Mongo, Redis, Kafka, Keycloak, NGINX.
#
#   scripts/infra.sh up        start everything and wait until healthy
#   scripts/infra.sh status    container + health overview
#   scripts/infra.sh logs [s]  follow logs (all, or one service)
#   scripts/infra.sh tools     also start Kafka UI on http://localhost:8090
#   scripts/infra.sh restart s recreate one service (e.g. keycloak, after editing the realm file)
#   scripts/infra.sh down      stop, KEEP data
#   scripts/infra.sh reset     stop and WIPE data (fresh Postgres/Mongo/Kafka, realm re-imported)
#
# Runs from anywhere: paths are resolved relative to this script.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE_PATH="$ROOT/deploy/compose/docker-compose.yml"
DC=(docker compose -f "$COMPOSE_FILE_PATH")
SERVICES=(postgres mongo redis kafka keycloak nginx)
WAIT_SECONDS=${WAIT_SECONDS:-180}

die() { echo "ERROR: $*" >&2; exit 1; }

preflight() {
  command -v docker >/dev/null || die "docker is not installed / not on PATH"
  docker compose version >/dev/null 2>&1 || die "Docker Compose v2 is required (try: docker compose version)"
  docker info >/dev/null 2>&1 || die "cannot talk to the Docker daemon (is Docker Desktop / the docker service running?)"
  [ -f "$COMPOSE_FILE_PATH" ] || die "missing $COMPOSE_FILE_PATH (create it from file 03)"
  [ -f "$ROOT/deploy/keycloak/quickbite-realm.json" ] || die "missing deploy/keycloak/quickbite-realm.json (file 03, step 2)"
  command -v jq >/dev/null && jq . "$ROOT/deploy/keycloak/quickbite-realm.json" >/dev/null \
    || echo "note: install jq to validate the realm JSON and to use scripts/token.sh" >&2
}

# Warn about ports held by something else (another kind cluster, an IDE-run service, ...)
check_ports() {
  local busy=0 p pid
  for p in 5432 27017 6379 9092 8180 8000; do
    if command -v ss >/dev/null && ss -ltn "( sport = :$p )" 2>/dev/null | grep -q ":$p"; then
      pid=$(docker ps --filter "publish=$p" --format '{{.Names}}' | paste -sd, -)
      echo "port $p is already in use${pid:+ by container(s): $pid}" >&2
      busy=1
    fi
  done
  [ $busy -eq 0 ] || echo "-> stop whatever holds those ports, or Compose will fail to bind them" >&2
}

health_of() {  # health_of <service> -> healthy | starting | unhealthy | running | missing
  local cid; cid=$("${DC[@]}" ps -q "$1" 2>/dev/null)
  [ -n "$cid" ] || { echo missing; return; }
  docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{if .State.Running}}running{{else}}stopped{{end}}{{end}}' "$cid" 2>/dev/null || echo missing
}

wait_healthy() {
  local deadline=$(( SECONDS + WAIT_SECONDS )) all s st
  echo "waiting for containers to become healthy (up to ${WAIT_SECONDS}s; Keycloak takes the longest)..."
  while [ $SECONDS -lt $deadline ]; do
    all=1
    for s in "${SERVICES[@]}"; do
      st=$(health_of "$s")
      case "$st" in
        healthy|running) ;;
        unhealthy) echo; echo "$s is UNHEALTHY. Last logs:" >&2; "${DC[@]}" logs --tail 30 "$s" >&2; return 1 ;;
        *) all=0 ;;
      esac
    done
    [ $all -eq 1 ] && { echo; return 0; }
    printf '.'; sleep 3
  done
  echo; echo "timed out after ${WAIT_SECONDS}s. Current state:" >&2; status; return 1
}

status() {
  printf '%-10s %s\n' SERVICE STATE
  local s; for s in "${SERVICES[@]}"; do printf '%-10s %s\n' "$s" "$(health_of "$s")"; done
}

summary() {
  cat <<EOF

Infrastructure is up:
  Keycloak     http://localhost:8180        (admin console: admin / admin)
  NGINX edge   http://localhost:8000        (502 until the gateway runs: expected at file 03)
  Postgres     localhost:5432               (user/password: quickbite)
  MongoDB      localhost:27017
  Redis        localhost:6379
  Kafka        localhost:9092               (containers use kafka:19092)

Next (file 03, step 6):
  chmod +x scripts/*.sh
  TOKEN=\$(scripts/token.sh alice alice) && scripts/jwt-decode.sh "\$TOKEN" | jq '{iss, aud, sub, sid, realm_access}'
EOF
}

preflight
case "${1:-up}" in
  up)
    check_ports
    "${DC[@]}" up -d || die "compose up failed"
    wait_healthy && summary
    ;;
  tools)
    "${DC[@]}" --profile tools up -d kafka-ui && echo "Kafka UI: http://localhost:8090"
    ;;
  status)  status ;;
  logs)    shift; "${DC[@]}" logs -f --tail 100 "$@" ;;
  restart)
    [ $# -ge 2 ] || die "usage: scripts/infra.sh restart <service>"
    # rm -sf + up re-creates the container: for Keycloak this also re-imports the realm (dev mode keeps its DB inside the container)
    "${DC[@]}" rm -sf "$2" && "${DC[@]}" up -d "$2" && wait_healthy && status
    ;;
  down)    "${DC[@]}" down --remove-orphans ;;
  reset)
    read -r -p "This DELETES all local data (orders, payments, catalog, Kafka, Keycloak realm state). Continue? [y/N] " a
    [ "$a" = y ] || exit 0
    "${DC[@]}" down -v --remove-orphans
    ;;
  *) sed -n '2,20p' "${BASH_SOURCE[0]}"; exit 2 ;;
esac
