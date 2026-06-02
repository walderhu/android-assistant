#!/bin/bash
set -e

export API_KEY=""

cd "$(dirname "$0")"

echo ">>> Building image..."
docker build --build-arg API_KEY="$API_KEY" -t assistant . 2>&1 | tail -80

echo ">>> Extracting APK..."
mkdir -p out
docker run --rm -v "$(pwd)/out":/out assistant bash -c "cp /project/app/build/outputs/apk/debug/app-debug.apk /out/ 2>/dev/null || cp app/build/outputs/apk/debug/app-debug.apk /out/"

echo ">>> Done. APK: $(pwd)/out/app-debug.apk"
