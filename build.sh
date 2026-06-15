#!/bin/bash
set -e

if [ -z "${OPENROUTER_API_KEY:-}" ] && [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi
if [ -z "${OPENROUTER_API_KEY:-}" ]; then
  echo "OPENROUTER_API_KEY is empty (export or add to .env)" >&2
  exit 1
fi

cd "$(dirname "$0")"

echo ">>> Building image..."
docker build --build-arg "API_KEY=$OPENROUTER_API_KEY" -t assistant . 2>&1 | tail -80

echo ">>> Extracting APK..."
mkdir -p out
docker run --rm -v "$(pwd)/out":/out assistant bash -c "cp /project/app/build/outputs/apk/debug/app-debug.apk /out/ 2>/dev/null || cp app/build/outputs/apk/debug/app-debug.apk /out/"

echo ">>> Done. APK: $(pwd)/out/app-debug.apk"
