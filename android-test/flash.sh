#!/usr/bin/env bash
# flash.sh — быстрая прошивка android-test
#
#   ./flash.sh                 # билд + установка + запуск
#   ./flash.sh -n               # только установка
#   ./flash.sh -a               # usbipd attach
#   ./flash.sh -v               # подробный вывод
#   ./flash.sh --logs           # + logcat
#   ./flash.sh --push "msg"     # + git commit/push

set -euo pipefail
cd "$(dirname "$0")"

DO_BUILD=1 DO_LOGS=0 DO_PUSH=0 DO_ATTACH=0 VERBOSE=0
PUSH_MSG=""
PKG=com.assistant.app
APK=app/build/outputs/apk/debug/app-debug.apk
LOG_DIR="$(pwd)/.flash"
mkdir -p "$LOG_DIR"
BUILD_LOG="$LOG_DIR/build.log"
INST_LOG="$LOG_DIR/install.log"

while [ $# -gt 0 ]; do
  case "$1" in
    -n|--no-build) DO_BUILD=0 ;;
    -a|--attach)   DO_ATTACH=1 ;;
    -v|--verbose)  VERBOSE=1 ;;
    --logs)        DO_LOGS=1 ;;
    --push)        DO_PUSH=1; PUSH_MSG="${2:-}"; shift ;;
    --push=*)      DO_PUSH=1; PUSH_MSG="${1#--push=}" ;;
    -h|--help)     sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "неизвестный аргумент: $1" >&2; exit 1 ;;
  esac
  shift
done

t0=$(date +%s)
say() { [ "$VERBOSE" -eq 1 ] && printf '▸ %s\n' "$*" || true; }
ok()  { printf '✓ %s (%ss)\n' "$*" "$(( $(date +%s) - t0 ))"; }
die() { printf '✗ %s\n' "$*" >&2; [ -f "$BUILD_LOG" ] && printf '  лог сборки: %s\n' "$BUILD_LOG" >&2; [ -f "$INST_LOG" ] && printf '  лог установки: %s\n' "$INST_LOG" >&2; exit 1; }

[ -f ../.env ] && { set -a; # shellcheck disable=SC1091
  . ../.env; set +a; }

# ── телефон ──────────────────────────────────────────────────────────────────
pick_device() {
  if [ "$DO_ATTACH" -eq 1 ] && command -v usbipd >/dev/null; then
    if ! adb devices 2>/dev/null | tail -n +2 | grep -qE 'device$'; then
      BUSID=$(usbipd list 2>/dev/null | awk '/ADB Interface/ {print $1; exit}')
      [ -n "$BUSID" ] && usbipd attach --wsl --busid "$BUSID" >/dev/null 2>&1 || true
      sleep 1
    fi
  fi
  adb devices 2>/dev/null | awk 'NF && $2=="device" {print $1; exit}'
}

DEVICE=$(pick_device)
[ -n "$DEVICE" ] || die "нет устройства — подключи телефон или ./flash.sh -a"

# ── сборка ───────────────────────────────────────────────────────────────────
if [ "$DO_BUILD" -eq 1 ]; then
  say "сборка"
  PROPS=()
  [ -n "${OPENROUTER_API_KEY:-}" ] && PROPS+=(-POPENROUTER_API_KEY="$OPENROUTER_API_KEY")
  [ -n "${GROQ_API_KEY:-}" ]        && PROPS+=(-PGROQ_API_KEY="$GROQ_API_KEY")
  FLAGS=(:app:assembleDebug --parallel --build-cache --configuration-cache "${PROPS[@]}")
  if [ "$VERBOSE" -eq 1 ]; then
    ./gradlew "${FLAGS[@]}" --console=plain 2>&1 | tee "$BUILD_LOG" || { tail -40 "$BUILD_LOG"; die "сборка упала"; }
  else
    ./gradlew "${FLAGS[@]}" -q >"$BUILD_LOG" 2>&1 || { tail -40 "$BUILD_LOG"; die "сборка упала"; }
  fi
  ok "собрано"
else
  say "билд пропущен"
fi

[ -f "$APK" ] || die "APK не найден: $APK"
ok "устройство $DEVICE"

# ── установка ────────────────────────────────────────────────────────────────
say "установка"
INSTALLED=0
: >"$INST_LOG"

if adb -s "$DEVICE" install -r -t -d --fastdeploy "$APK" >>"$INST_LOG" 2>&1; then
  INSTALLED=1
  ok "fastdeploy"
fi

if [ "$INSTALLED" -eq 0 ]; then
  PUSH=/data/local/tmp/app-debug.apk
  if adb -s "$DEVICE" push "$APK" "$PUSH" >>"$INST_LOG" 2>&1 \
     && adb -s "$DEVICE" shell pm install -r -t "$PUSH" >>"$INST_LOG" 2>&1; then
    INSTALLED=1
    ok "push+pm"
  fi
  adb -s "$DEVICE" shell rm -f "$PUSH" 2>/dev/null || true
fi

if [ "$INSTALLED" -eq 0 ]; then
  adb -s "$DEVICE" install -r -t "$APK" >>"$INST_LOG" 2>&1 \
    || { tail -10 "$INST_LOG"; die "установка упала"; }
  ok "adb install"
fi

# ── запуск ───────────────────────────────────────────────────────────────────
adb -s "$DEVICE" shell am force-stop "$PKG" >/dev/null 2>&1 || true
if ! adb -s "$DEVICE" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1; then
  adb -s "$DEVICE" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 \
    || { tail -5 "$INST_LOG"; die "не удалось запустить"; }
fi
ok "запущено"

if [ "$DO_LOGS" -eq 1 ]; then
  PID=$(adb -s "$DEVICE" shell pidof "$PKG" 2>/dev/null | tr -d '\r')
  adb -s "$DEVICE" logcat -d ${PID:+--pid="$PID"} 2>/dev/null | tail -30
fi

if [ "$DO_PUSH" -eq 1 ]; then
  REPO=$(cd .. && pwd)
  if git -C "$REPO" status --porcelain android-test/ | grep -q .; then
    git -C "$REPO" add android-test/
    git -C "$REPO" commit -m "${PUSH_MSG:-wip: flash $(date +%H:%M)}"
    git -C "$REPO" push origin master
    ok "запушено"
  fi
fi

printf 'готово за %ss\n' "$(( $(date +%s) - t0 ))"
