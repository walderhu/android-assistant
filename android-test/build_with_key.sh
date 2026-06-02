#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# Подтягиваем ключи из ../.env, если есть
if [ -f ../.env ]; then
  set -a
  # shellcheck disable=SC1091
  . ../.env
  set +a
fi
if [ -z "${OPENROUTER_API_KEY:-}" ]; then
  echo "OPENROUTER_API_KEY is empty (проверь ../.env)" >&2
  exit 1
fi
docker build --build-arg "API_KEY=$OPENROUTER_API_KEY" -t assistant-debug . 2>&1
