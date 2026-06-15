#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [ -z "${OPENROUTER_API_KEY:-}" ]; then
  echo "OPENROUTER_API_KEY is empty" >&2
  exit 1
fi
docker build --build-arg "API_KEY=$OPENROUTER_API_KEY" -t assistant-debug . 2>&1
