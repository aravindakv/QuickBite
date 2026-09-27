#!/usr/bin/env bash
# Re-run whenever a device/emulator (re)connects.
for d in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do
  adb -s "$d" reverse tcp:8000 tcp:8000     # API + WebSockets (NGINX)
  adb -s "$d" reverse tcp:8180 tcp:8180     # Keycloak login page + token endpoint
  echo "✔ $d: $(adb -s "$d" reverse --list | wc -l | tr -d ' ') reverse rules"
done