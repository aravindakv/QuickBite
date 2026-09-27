#!/usr/bin/env bash
# Stop QuickBite: apps, infrastructure, consoles, Kubernetes releases and stray local processes.
#
#   scripts/stop.sh apps        stop the six services only (Compose + Helm), keep infra and data
#   scripts/stop.sh local       kill host-side strays: bootRun, port-forwards, rider-sim, k6
#   scripts/stop.sh consoles    stop the browser consoles (file 21)
#   scripts/stop.sh compose     stop the whole Compose stack, KEEP data
#   scripts/stop.sh k8s         uninstall the Helm releases, keep the cluster and stores
#   scripts/stop.sh cluster     stop the kind cluster containers (restart with: docker start ...)
#   scripts/stop.sh all         everything above except deleting data or the cluster
#   scripts/stop.sh wipe        all + DELETE Compose volumes (Postgres, Mongo, Kafka) -- asks first
#
# Safe to run when parts are already stopped: every step is best-effort.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_DIR="$ROOT/deploy/compose"
SERVICES=(gateway catalog-svc order-svc payment-svc location-svc realtime-svc demo-svc)
CONSOLES=(kafka-ui adminer mongo-express redisinsight)
KIND_CLUSTER=${KIND_CLUSTER:-quickbite}
NS=${NS:-quickbite}

have()  { command -v "$1" >/dev/null 2>&1; }
note()  { printf '  %s\n' "$*"; }
step()  { printf '\n== %s\n' "$*"; }

# Build the -f list from whichever compose files exist
compose() {
  local files=()
  for f in docker-compose.yml docker-compose.apps.yml docker-compose.demo.yml docker-compose.tools.yml \
           docker-compose.keycloak-prod.yml; do
    [ -f "$COMPOSE_DIR/$f" ] && files+=(-f "$COMPOSE_DIR/$f")
  done
  [ ${#files[@]} -gt 0 ] || return 0
  docker compose "${files[@]}" "$@"
}

stop_apps() {
  step "Stopping application containers (infra keeps running)"
  if have docker && docker info >/dev/null 2>&1; then
    compose stop "${SERVICES[@]}" 2>/dev/null | sed 's/^/  /'
  else
    note "docker not available, skipping"
  fi
  stop_k8s_releases
}

stop_k8s_releases() {
  have helm && have kubectl || { note "helm/kubectl not installed, skipping Kubernetes"; return 0; }
  kubectl get ns "$NS" >/dev/null 2>&1 || { note "namespace $NS not found, skipping Kubernetes"; return 0; }
  step "Uninstalling Helm releases in namespace $NS (stores and cluster stay)"
  local releases
  releases=$(helm -n "$NS" list -q 2>/dev/null)
  [ -n "$releases" ] || { note "no Helm releases"; return 0; }
  # shellcheck disable=SC2086
  helm -n "$NS" uninstall $releases 2>/dev/null | sed 's/^/  /'
}

stop_local() {
  step "Killing host-side processes"
  # Never use pkill -f on a pattern that matches this script's own command line.
  for pat in "bootRun" "kubectl.*port-forward" "rider-sim.sh" "grafana/k6" "fake.py"; do
    local pids
    pids=$(pgrep -f "$pat" | grep -v "^$$\$" || true)
    if [ -n "$pids" ]; then
      note "$pat -> $(echo "$pids" | tr '\n' ' ')"
      # shellcheck disable=SC2086
      kill $pids 2>/dev/null
    fi
  done
  note "done"
}

stop_consoles() {
  step "Stopping browser consoles"
  have docker || { note "docker not available"; return 0; }
  compose stop "${CONSOLES[@]}" 2>/dev/null | sed 's/^/  /'
}

stop_compose() {
  step "Stopping the whole Compose stack (data volumes kept)"
  have docker || { note "docker not available"; return 0; }
  compose down --remove-orphans 2>/dev/null | sed 's/^/  /'
}

stop_cluster() {
  have kind || { note "kind not installed, skipping"; return 0; }
  kind get clusters 2>/dev/null | grep -qx "$KIND_CLUSTER" || { note "cluster $KIND_CLUSTER not found"; return 0; }
  step "Stopping kind cluster '$KIND_CLUSTER' (kept on disk)"
  local nodes; nodes=$(kind get nodes --name "$KIND_CLUSTER" 2>/dev/null)
  # shellcheck disable=SC2086
  [ -n "$nodes" ] && docker stop $nodes >/dev/null 2>&1
  note "restart later with: docker start $(echo "$nodes" | tr '\n' ' ')"
  note "delete for good with: kind delete cluster --name $KIND_CLUSTER"
}

ports_report() {
  step "Ports still in use"
  local busy=0 p
  for p in 8000 8080 8081 8082 8083 8084 8085 8086 8090 8091 8092 8093 8180 5432 27017 6379 9092; do
    if have ss && ss -ltn 2>/dev/null | grep -q ":$p "; then
      note "$p held by: $(docker ps --filter "publish=$p" --format '{{.Names}}' | paste -sd, - || echo '?')"
      busy=1
    fi
  done
  [ $busy -eq 0 ] && note "none of the QuickBite ports are in use"
}

case "${1:-apps}" in
  apps)     stop_apps; ports_report ;;
  local)    stop_local; ports_report ;;
  consoles) stop_consoles ;;
  compose)  stop_compose; ports_report ;;
  k8s)      stop_k8s_releases ;;
  cluster)  stop_cluster ;;
  all)      stop_local; stop_consoles; stop_k8s_releases; stop_compose; stop_cluster; ports_report ;;
  wipe)
    read -r -p "This DELETES all local data (orders, payments, catalog, Kafka, Keycloak realm state). Continue? [y/N] " a
    [ "$a" = y ] || exit 0
    stop_local; stop_consoles; stop_k8s_releases
    step "Removing Compose volumes"
    compose down -v --remove-orphans 2>/dev/null | sed 's/^/  /'
    stop_cluster; ports_report ;;
  *) sed -n '2,14p' "${BASH_SOURCE[0]}"; exit 2 ;;
esac
