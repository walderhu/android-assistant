#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# Подтягиваем ключ из ../.env, если есть
if [ -f ../.env ]; then
  set -a
  # shellcheck disable=SC1091
  . ../.env
  set +a
fi

if [ -z "${OPENROUTER_API_KEY:-}" ]; then
  echo "OPENROUTER_API_KEY пустой — впиши в ../.env" >&2
  exit 1
fi

./gradlew assembleDebug -POPENROUTER_API_KEY="$OPENROUTER_API_KEY"
adb uninstall com.assistant.app 2>/dev/null || true
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.assistant.app/.MainActivity
