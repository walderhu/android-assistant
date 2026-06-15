#!/usr/bin/env bash
# dev.sh — инкрементальная сборка через долгоживущий Docker-контейнер.
#
# Первый запуск: ~3-5 мин (собирается образ assistant-dev).
# Дальше: 5-30 сек на инкрементальную пересборку.
#
# Использование:
#   ./dev.sh                 # собрать APK с реальным API_KEY
#   ./dev.sh assembleDebug   # любая gradle-цель
#   ./dev.sh clean           # очистить build/
#
# После сборки APK лежит в app/build/outputs/apk/debug/app-debug.apk (на хосте).

set -euo pipefail
cd "$(dirname "$0")"

if [ -z "${OPENROUTER_API_KEY:-}" ]; then
  echo "OPENROUTER_API_KEY is empty (set it in your shell)" >&2
  exit 1
fi

# собрать образ, если его нет
if ! docker image inspect assistant-dev >/dev/null 2>&1; then
  echo ">>> Building assistant-dev image (one-time, ~3-5 min)…"
  docker build -f Dockerfile.dev -t assistant-dev . >&2
fi

# Persistent gradle cache (between runs). Use a separate dir to avoid
# fighting with the host's gradle daemon over /root/.gradle/journal-1.
GRADLE_CACHE="$HOME/.gradle-assistant"
mkdir -p "$GRADLE_CACHE"

# Persistent Android SDK (so we don't re-download platforms/build-tools)
# (уже внутри образа — переиспользуем)

# Mount: project source + gradle cache. SDK — внутри образа.
docker run --rm \
  -v "$(pwd)":/project \
  -v "$GRADLE_CACHE":/root/.gradle \
  -w /project \
  assistant-dev \
  gradle "${@:-assembleDebug}" \
    -POPENROUTER_API_KEY="$OPENROUTER_API_KEY" \
    --console=plain
