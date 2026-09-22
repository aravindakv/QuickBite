#!/usr/bin/env bash
# usage: scripts/jwt-decode.sh <token>   -> pretty-prints the payload (NO signature check: debugging only)
p=$(echo -n "$1" | cut -d. -f2 | tr '_-' '/+')
while [ $(( ${#p} % 4 )) -ne 0 ]; do p="$p="; done
echo "$p" | base64 -d 2>/dev/null | jq .