#!/usr/bin/env bash
# debug.sh — собрать APK, установить на телефон, запустить, повесить logcat.
#
# Использование:
#   ./debug.sh                # пересобрать (если надо), переустановить, запустить, показать logcat
#   ./debug.sh --no-build     # пропустить сборку, использовать существующий out/app-debug.apk
#   ./debug.sh --no-launch    # только установить, не запускать и не показывать logcat
#   ./debug.sh --rebuild      # форс-пересборка (docker build --no-cache)
#   API_KEY=sk-or-v1-... ./debug.sh   # передать ключ OpenRouter
#
# Требования: docker, adb, телефон по USB с включённой отладкой.

set -euo pipefail

cd "$(dirname "$0")"

PKG="com.assistant.app"
ACTIVITY=".MainActivity"
APK_PATH="out/app-debug.apk"
DOCKER_IMAGE="assistant-debug"

DO_BUILD=1
DO_LAUNCH=1
REBUILD=0

for arg in "$@"; do
  case "$arg" in
    --no-build)  DO_BUILD=0 ;;
    --no-launch) DO_LAUNCH=0 ;;
    --rebuild)   REBUILD=1 ;;
    -h|--help)   sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown flag: $arg" >&2; exit 2 ;;
  esac
done

# ---------- цвета ----------
RED='\033[0;31m'; GRN='\033[0;32m'; YEL='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
say()  { printf "${CYAN}>>>${NC} %s\n" "$*"; }
ok()   { printf "${GRN}✓${NC} %s\n" "$*"; }
warn() { printf "${YEL}!${NC} %s\n" "$*"; }
die()  { printf "${RED}✗ %s${NC}\n" "$*" >&2; exit 1; }

# ---------- 1. проверить устройство ----------
say "Проверяю подключение по USB…"
if ! command -v adb >/dev/null; then
  die "adb не найден (apt install adb)"
fi

adb get-state >/dev/null 2>&1 || die "устройство не авторизовано. Откройте экран телефона, тапните «Разрешить отладку по USB»"
adb get-state | grep -q "^device$" || die "устройство в состоянии $(adb get-state 2>/dev/null). Переткните кабель"
ok "телефон подключён: $(adb devices -l | awk 'NR==2 {print $5}')"

# ---------- 2. сборка (через Docker) ----------
if [ "$DO_BUILD" -eq 1 ]; then
  if [ "$REBUILD" -eq 1 ]; then
    say "Полная пересборка Docker-образа (--no-cache)…"
    docker build --no-cache --build-arg "API_KEY=${API_KEY:-sk-or-v1-DUMMY}" -t "$DOCKER_IMAGE" . >/dev/null
  else
    say "Сборка APK (Docker)…"
    docker build --build-arg "API_KEY=${API_KEY:-sk-or-v1-DUMMY}" -t "$DOCKER_IMAGE" . >/dev/null \
      || die "docker build упал"
  fi
  mkdir -p out
  docker run --rm -v "$(pwd)/out":/out "$DOCKER_IMAGE" \
    bash -c "cp /project/app/build/outputs/apk/debug/app-debug.apk /out/" >/dev/null \
    || die "не удалось извлечь APK из контейнера"
  ok "APK: $(ls -lh "$APK_PATH" | awk '{print $5, $9}')"
else
  [ -f "$APK_PATH" ] || die "--no-build, но $APK_PATH не существует"
  warn "пропускаю сборку, использую $APK_PATH"
fi

# ---------- 3. установка (push + pm install, не зависает на Xiaomi) ----------
say "Ставлю APK на телефон (push + pm install)…"
adb push "$APK_PATH" /data/local/tmp/a.apk >/dev/null \
  || die "adb push упал — проверьте USB-соединение"
adb shell pm install -r -t /data/local/tmp/a.apk \
  || die "pm install упал"
adb shell rm /data/local/tmp/a.apk
ok "установлено: $PKG"

# ---------- 4. запуск + logcat ----------
if [ "$DO_LAUNCH" -eq 1 ]; then
  say "Запускаю $PKG/$ACTIVITY…"
  adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
  adb logcat -c
  adb shell am start -n "$PKG/$ACTIVITY" >/dev/null \
    || die "am start упал"

  PID=$(adb shell ps -A | awk -v p="$PKG" '$NF==p {print $2; exit}')
  if [ -z "$PID" ]; then
    warn "не удалось получить PID — покажу весь logcat"
    adb logcat
  else
    ok "PID=$PID, logcat (Ctrl+C чтобы выйти)"
    adb logcat --pid="$PID"
  fi
else
  warn "пропускаю запуск и logcat (--no-launch)"
  echo "    запустить вручную:  adb shell am start -n $PKG/$ACTIVITY"
  echo "    логи:               adb logcat --pid=\$(adb shell ps -A | awk '/$PKG/ {print \$2}')"
fi
