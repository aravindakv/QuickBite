#!/usr/bin/env bash
set -euo pipefail
./gradlew bootJar -x test --parallel
cd deploy/compose
docker compose -f docker-compose.yml -f docker-compose.apps.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.apps.yml ps