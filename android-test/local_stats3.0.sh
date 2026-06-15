#!/usr/bin/env bash
# local_stats3.0.sh — параллельная сборка + install + launch android-test ассистента
#
# Оптимизации относительно 2.0 (главное, почему 3.0 быстрее):
#   ✓ --no-daemon убран       daemon живёт ~3ч между билдами → 10-30s JVM startup saved
#   ✓ --parallel              Gradle запускает aapt2/kotlinc/javac параллельно
#   ✓ --build-cache           артефакты кешируются между билдами (org.gradle.caching)
#   ✓ --configuration-cache   фаза конфигурации кешируется, не парсим .gradle заново
#   ✓ adb-проверка устройства идёт в фоне параллельно со сборкой
#   ✓ push + pm install       быстрее, чем adb install, на больших APK и Xiaomi-устройствах
#   ✓ :app:assembleDebug      прямой вызов задачи, без root-project overhead
#   ✓ --status + tee          живой прогресс в консоли + полный лог для диагностики
#
# Использование (флаги совместимы с 2.0):
#   ./local_stats3.0.sh                # билд + установка + запуск
#   ./local_stats3.0.sh --no-build     # только переустановка + запуск
#   ./local_stats3.0.sh --push "msg"   # после билда коммит + пуш с сообщением
#   ./local_stats3.0.sh --logs         # показать свежий logcat после запуска
#   ./local_stats3.0.sh --reboot-adb   # перезапустить adb-server
#   ./local_stats3.0.sh --profile      # + build-scan HTML-отчёт (для диагностики)

#      _      _____   __   ___  _______  __ ____  __
#     | | /| / / _ | / /  / _ \/ __/ _ \/ // / / / /
#     | |/ |/ / __ |/ /__/ // / _// , _/ _  / /_/ / 
#     |__/|__/_/ |_/____/____/___/_/|_/_//_/\____/  
#                                                   



set -euo pipefail

cd "$(dirname "$0")"

# ── Цвета ────────────────────────────────────────────────────────────────────
if [ -t 1 ]; then
  C_RESET=$'\033[0m'
  C_BOLD=$'\033[1m'
  C_DIM=$'\033[2m'
  C_RED=$'\033[31m'
  C_GREEN=$'\033[32m'
  C_YELLOW=$'\033[33m'
  C_BLUE=$'\033[34m'
  C_MAGENTA=$'\033[35m'
  C_CYAN=$'\033[36m'
else
  C_RESET=""; C_BOLD=""; C_DIM=""; C_RED=""; C_GREEN=""; C_YELLOW=""
  C_BLUE=""; C_MAGENTA=""; C_CYAN=""
fi

# ── Аргументы ────────────────────────────────────────────────────────────────
DO_BUILD=1
DO_LOGS=0
DO_PUSH=0
PUSH_MSG=""
REBOOT_ADB=0
DO_PROFILE=0

for arg in "$@"; do
  case "$arg" in
    --no-build)    DO_BUILD=0 ;;
    --logs)        DO_LOGS=1 ;;
    --push)        DO_PUSH=1; PUSH_MSG="${2:-}"; shift ;;
    --reboot-adb)  REBOOT_ADB=1 ;;
    --profile)     DO_PROFILE=1 ;;
    --push=*)      DO_PUSH=1; PUSH_MSG="${arg#--push=}" ;;
    -h|--help)
      sed -n '2,14p' "$0"; exit 0 ;;
    *) printf "${C_RED}неизвестный аргумент:${C_RESET} %s\n" "$arg" >&2; exit 1 ;;
  esac
done

# ── Утилиты ──────────────────────────────────────────────────────────────────
step() { printf "\n${C_BOLD}${C_CYAN}▸ %s${C_RESET}\n" "$*"; }
ok()   { printf "  ${C_GREEN}✓${C_RESET} %s\n" "$*"; }
warn() { printf "  ${C_YELLOW}!${C_RESET} %s\n" "$*"; }
fail() { printf "\n${C_BOLD}${C_RED}✗ %s${C_RESET}\n" "$*" >&2; exit 1; }
hr()   { printf "${C_DIM}%s${C_RESET}\n" "───────────────────────────────────────────────────────────────"; }

START=$(date +%s)
elapsed() { printf "${C_DIM}(%ss)${C_RESET}" "$(( $(date +%s) - START ))"; }

# ── TMPDIR + cleanup ─────────────────────────────────────────────────────────
TMPDIR=$(mktemp -d)
ADB_PID=""

cleanup() {
  if [ -n "$ADB_PID" ] && kill -0 "$ADB_PID" 2>/dev/null; then
    kill "$ADB_PID" 2>/dev/null || true
    wait "$ADB_PID" 2>/dev/null || true
  fi
  rm -rf "$TMPDIR" 2>/dev/null || true
}
trap cleanup EXIT

# ── Заголовок ────────────────────────────────────────────────────────────────
# cat <<'BANNER'
#  ██╗    ██╗ █████╗ ██╗     ██████╗ ███████╗██████╗ ██╗  ██╗██╗   ██╗
#  ██║    ██║██╔══██╗██║     ██╔══██╗██╔════╝██╔══██╗██║  ██║██║   ██║
#  ██║ █╗ ██║███████║██║     ██║  ██║█████╗  ██████╔╝███████║██║   ██║
#  ██║███╗██║██╔══██║██║     ██║  ██║██╔══╝  ██╔══██╗██╔══██║██║   ██║
#  ╚███╔███╔╝██║  ██║███████╗██████╔╝███████╗██║  ██║██║  ██║╚██████╔╝
#   ╚══╝╚══╝ ╚═╝  ╚═╝╚══════╝╚═════╝ ╚══════╝╚═╝  ╚═╝╚═╝  ╚═╝ ╚═════╝
# BANNER
# cat <<'BANNER'
#      _      _____   __   ___  _______  __ ____  __
#     | | /| / / _ | / /  / _ \/ __/ _ \/ // / / / /
#     | |/ |/ / __ |/ /__/ // / _// , _/ _  / /_/ / 
#     |__/|__/_/ |_/____/____/___/_/|_/_//_/\____/  
                                                  
# BANNER

cat <<BANNER
${C_RESET}
     _      _____   __   ___  _______  __ ____  __
    | | /| / / _ | / /  / _ \/ __/ _ \/ // / / / /
    | |/ |/ / __ |/ /__/ // / _// , _/ _  / /_/ /
    |__/|__/_/ |_/____/____/___/_/|_/_//_/\____/
${C_RESET}
BANNER
printf "  ${C_DIM}прошивка android-test ассистента · v3.0 (parallel)${C_RESET}\n"
hr

# ── .env с ключами ───────────────────────────────────────────────────────────
if [ -f ../.env ]; then
  set -a
  # shellcheck disable=SC1091
  . ../.env
  set +a
  ok "ключи из ../.env подгружены"
else
  warn "../.env не найден — OPENROUTER/GROQ будут пустыми"
fi

# ── Параллельная фоновая проверка устройства ─────────────────────────────────
# adb-команды стартуют сразу, не ждём завершения сборки.
(
  if [ "$REBOOT_ADB" -eq 1 ]; then
    adb kill-server >/dev/null 2>&1 || true
    sleep 1
    adb start-server >/dev/null
  fi

  DEVICE_LIST=$(adb devices 2>/dev/null | tail -n +2)
  if ! printf '%s\n' "$DEVICE_LIST" | grep -qE 'device$'; then
    echo "FAIL:нет устройств" >&2
    exit 1
  fi

  DEVICE=$(printf '%s\n' "$DEVICE_LIST" | awk 'NF && $2=="device"{print $1; exit}')
  echo "$DEVICE" > "$TMPDIR/device"

  MODEL=$(adb -s "$DEVICE" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
  ANDROID=$(adb -s "$DEVICE" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
  echo "$MODEL|$ANDROID" > "$TMPDIR/info"
) &
ADB_PID=$!

# ── Сборка ───────────────────────────────────────────────────────────────────
BUILD_LOG="$TMPDIR/build.log"
if [ "$DO_BUILD" -eq 1 ]; then
  step "собираю APK (gradle :app:assembleDebug + --parallel + --build-cache)"

  GRADLE_PROPS=()
  [ -n "${OPENROUTER_API_KEY:-}" ] && GRADLE_PROPS+=(-POPENROUTER_API_KEY="$OPENROUTER_API_KEY")
  [ -n "${GROQ_API_KEY:-}" ]        && GRADLE_PROPS+=(-PGROQ_API_KEY="$GROQ_API_KEY")

  GRADLE_FLAGS=(
    :app:assembleDebug
    --parallel
    --build-cache
    --configuration-cache
    --console=plain
    --status
  )
  [ "$DO_PROFILE" -eq 1 ] && GRADLE_FLAGS+=(--profile)

  # pipefail (включён через set -euo pipefail) → exit code берётся от gradle, не от tee
  if ./gradlew "${GRADLE_FLAGS[@]}" "${GRADLE_PROPS[@]}" 2>&1 | tee "$BUILD_LOG"; then
    ok "сборка прошла $(elapsed)"
  else
    printf "${C_DIM}последние 60 строк лога сборки:${C_RESET}\n"
    tail -60 "$BUILD_LOG" || true
    [ "$DO_PROFILE" -eq 1 ] && warn "build-scan: app/build/reports/profile/profile-*.html"
    fail "сборка упала"
  fi
else
  ok "пропускаю билд (--no-build)"
fi

APK=app/build/outputs/apk/debug/app-debug.apk
[ -f "$APK" ] || fail "APK не найден: $APK"

# ── Ждём adb-проверку ────────────────────────────────────────────────────────
if ! wait "$ADB_PID"; then
  fail "нет подключённых устройств. Подключи телефон и попробуй снова"
fi
DEVICE=$(cat "$TMPDIR/device")
INFO=$(cat "$TMPDIR/info")
MODEL=${INFO%|*}
ANDROID=${INFO#*|}
ok "устройство: ${C_BOLD}$DEVICE${C_RESET} ($MODEL, Android $ANDROID)"

# ── Установка ────────────────────────────────────────────────────────────────
step "ставлю APK"
APK_SIZE=$(du -h "$APK" | cut -f1)
ok "apk: $APK ($APK_SIZE)"

# push + pm install обычно быстрее, чем adb install, на Xiaomi и больших APK:
#   adb install стримит APK через протокол install, а push+pm — сырой push + локальный install.
PUSH_PATH=/data/local/tmp/app-debug.apk
INSTALLED=0
if adb -s "$DEVICE" push "$APK" "$PUSH_PATH" 2>&1 | tail -1; then
  if adb -s "$DEVICE" shell pm install -r -t "$PUSH_PATH" 2>&1 | tail -2; then
    ok "установлено (push+pm install)"
    INSTALLED=1
  fi
fi
adb -s "$DEVICE" shell rm "$PUSH_PATH" 2>/dev/null || true

if [ "$INSTALLED" -eq 0 ]; then
  warn "push+pm install не сработал, fallback на adb install"
  if ! timeout 90 adb -s "$DEVICE" install -r "$APK" 2>&1 | tail -3; then
    warn "install завис, перезапускаю adb-server"
    adb kill-server >/dev/null 2>&1 || true
    sleep 1
    adb start-server >/dev/null
    timeout 60 adb -s "$DEVICE" install -r "$APK" | tail -3
  fi
  ok "установлено"
fi

# ── Запуск ────────────────────────────────────────────────────────────────────
step "запускаю"
adb -s "$DEVICE" shell am force-stop com.assistant.app 2>/dev/null || true
adb -s "$DEVICE" shell am start -n com.assistant.app/.MainActivity 2>&1 | tail -2
sleep 1
PID=$(adb -s "$DEVICE" shell pidof com.assistant.app 2>/dev/null | tr -d '\r')
[ -n "$PID" ] && ok "запущено, pid=$PID $(elapsed)" || warn "приложение не стартовало"

# ── Logcat (опционально) ─────────────────────────────────────────────────────
if [ "$DO_LOGS" -eq 1 ]; then
  step "свежий logcat com.assistant.app"
  adb -s "$DEVICE" logcat -d --pid="$PID" 2>&1 | tail -40
fi

# ── Git push (опционально) ────────────────────────────────────────────────────
if [ "$DO_PUSH" -eq 1 ]; then
  step "git commit + push"
  REPO_ROOT="$(cd .. && pwd)"
  cd "$REPO_ROOT"

  if git status --porcelain android-test/ | grep -q .; then
    git add android-test/
    if [ -n "$PUSH_MSG" ]; then
      git commit -m "$PUSH_MSG"
    else
      git commit -m "wip: local build $(date +%H:%M)"
    fi
    git push origin master
    ok "запушено"
  else
    warn "нечего коммитить — нет изменений в android-test/"
  fi
fi

# ── Итог ─────────────────────────────────────────────────────────────────────
hr
printf "${C_BOLD}${C_GREEN}✓ готово${C_RESET}  ${C_DIM}общее время: %ss${C_RESET}\n" \
  "$(( $(date +%s) - START ))"
printf "${C_DIM}следующий запуск: ./local_stats3.0.sh [опции]${C_RESET}\n"
printf "${C_DIM}tip: флаги --parallel/--build-cache/--configuration-cache можно закрепить в gradle.properties:${C_RESET}\n"
printf "${C_DIM}     org.gradle.parallel=true${C_RESET}\n"
printf "${C_DIM}     org.gradle.caching=true${C_RESET}\n"
printf "${C_DIM}     org.gradle.configuration-cache=true${C_RESET}\n\n"
