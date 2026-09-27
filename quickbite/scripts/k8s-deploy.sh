#!/usr/bin/env bash
set -euo pipefail
kubectl -n quickbite create secret generic order-svc-secret \
  --from-literal=ORDER_SVC_SECRET=order-svc-secret --dry-run=client -o yaml | kubectl apply -f -
for s in catalog-svc location-svc payment-svc order-svc realtime-svc gateway; do
  helm upgrade --install $s deploy/helm/quickbite-service -n quickbite \
    -f deploy/helm/values/common.yaml -f deploy/helm/values/$s.yaml
done
kubectl -n quickbite get pods -o wide