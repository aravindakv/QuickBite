#!/usr/bin/env bash
cd deploy/compose && docker compose -f docker-compose.yml -f docker-compose.apps.yml down "$@"   # add -v to wipe data