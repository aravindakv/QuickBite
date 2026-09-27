#!/usr/bin/env bash
# Same ports as Compose -> the app, scripts and adb reverse need no changes.
EDGE_SVC=$(kubectl -n quickbite get svc -o name | grep -m1 edge)
kubectl -n quickbite port-forward "$EDGE_SVC" 8000:80 &
kubectl -n quickbite port-forward svc/keycloak 8180:8180 &
wait