#!/usr/bin/env bash
# local_stats2.0.sh — build + install + launch для android-test/ ассистента
# Использование:
#   ./local_stats2.0.sh                # билд + установка + запуск
#   ./local_stats2.0.sh --no-build     # только переустановка + запуск (если APK уже собран)
#   ./local_stats2.0.sh --push "msg"   # после билда коммит + пуш с указанным сообщением
#   ./local_stats2.0.sh --logs         # показать свежий logcat после запуска
#   ./local_stats2.0.sh --reboot-adb   # перезапустить adb-server (лечит зависший install)
set -euo pipefail



# if ! lsusb | grep -q Xiaomi; then
#     BUSID=$(usbipd list | awk '/Redmi Note 8 Pro, ADB Interface/ {print $1}')

#     if [ -n "$BUSID" ]; then
#         echo "Подключение..."
#         usbipd attach --wsl --busid "$BUSID"
#     else
#         echo "Телефон не найден в usbipd list"
#     fi
# else 
# 	echo "Телефон уже подключен"
# fi


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

for arg in "$@"; do
  case "$arg" in
    --no-build)    DO_BUILD=0 ;;
    --logs)        DO_LOGS=1 ;;
    --push)        DO_PUSH=1; PUSH_MSG="${2:-}"; shift ;;
    --reboot-adb)  REBOOT_ADB=1 ;;
    --push=*)      DO_PUSH=1; PUSH_MSG="${arg#--push=}" ;;
    -h|--help)
      sed -n '2,11p' "$0"; exit 0 ;;
    *) echo "${C_RED}неизвестный аргумент:${C_RESET} $arg" >&2; exit 1 ;;
  esac
done

# ── Утилиты ──────────────────────────────────────────────────────────────────
step() { printf "\n${C_BOLD}${C_CYAN}▸ %s${C_RESET}\n" "$*"; }
ok()   { printf "  ${C_GREEN}✓${C_RESET} %s\n" "$*"; }
warn() { printf "  ${C_YELLOW}!${C_RESET} %s\n" "$*"; }
fail() { printf "\n${C_BOLD}${C_RED}✗ %s${C_RESET}\n" "$*" >&2; exit 1; }
hr()   { printf "${C_DIM}%s${C_RESET}\n" "───────────────────────────────────────────────────────────────"; }

# Таймер
START=$(date +%s)
elapsed() { printf "${C_DIM}(%ss)${C_RESET}" "$(( $(date +%s) - START ))"; }

# ── Заголовок ─────────────────────────────────────────────────────────────────
printf "${C_BOLD}${C_RESET}" #!!!
# cat <<'BANNER'
#    _      __    __       ___            __  __
#   | | /| / /__ / /  ___ / _ \___ ___ __/ /_/ /  ___ ____
#   | |/ |/ / -_) _ \/ _ \/ // / -_) _ `/ __/ _ \/ _ `(_-<
#   |__/|__/\__/_.__/\___/____/\__/\_,_/\__/_//_/\_,_/___/
# BANNER
cat <<BANNER
${C_RESET}
     _      _____   __   ___  _______  __ ____  __
    | | /| / / _ | / /  / _ \/ __/ _ \/ // / / / /
    | |/ |/ / __ |/ /__/ // / _// , _/ _  / /_/ /
    |__/|__/_/ |_/____/____/___/_/|_/_//_/\____/
${C_RESET}
BANNER
printf "${C_RESET}\n"
printf "  ${C_DIM}прошивка android-test ассистента${C_RESET}\n"
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

# ── Устройство ──────────────────────────────────────────────────────────────
step "проверяю adb и устройство"
if [ "$REBOOT_ADB" -eq 1 ]; then
  adb kill-server >/dev/null 2>&1 || true
  sleep 1
  adb start-server >/dev/null
  ok "adb-server перезапущен"
fi

DEVICE_LIST=$(adb devices 2>/dev/null | tail -n +2)
if ! printf '%s\n' "$DEVICE_LIST" | grep -qE 'device$'; then
  fail "нет подключённых устройств. Подключи телефон и попробуй снова"
fi
DEVICE=$(printf '%s\n' "$DEVICE_LIST" | awk 'NF && $2=="device"{print $1; exit}')
MODEL=$(adb -s "$DEVICE" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
ANDROID=$(adb -s "$DEVICE" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
ok "устройство: ${C_BOLD}$DEVICE${C_RESET} ($MODEL, Android $ANDROID)"

# ── Сборка ───────────────────────────────────────────────────────────────────
if [ "$DO_BUILD" -eq 1 ]; then
  step "собираю APK (gradle assembleDebug)"

  GRADLE_PROPS=()
  [ -n "${OPENROUTER_API_KEY:-}" ] && GRADLE_PROPS+=(-POPENROUTER_API_KEY="$OPENROUTER_API_KEY")
  [ -n "${GROQ_API_KEY:-}" ]        && GRADLE_PROPS+=(-PGROQ_API_KEY="$GROQ_API_KEY")

  if ./gradlew assembleDebug --no-daemon --console=plain "${GRADLE_PROPS[@]}" 2>&1 | tail -20; then
    ok "сборка прошла $(elapsed)"
  else
    fail "сборка упала"
  fi
else
  ok "пропускаю билд (--no-build)"
fi

APK=app/build/outputs/apk/debug/app-debug.apk
[ -f "$APK" ] || fail "APK не найден: $APK"

# ── Установка ────────────────────────────────────────────────────────────────
step "ставлю APK"
APK_SIZE=$(du -h "$APK" | cut -f1)
ok "apk: $APK ($APK_SIZE)"

# Если процесс install висит — adb kill-server чинит
if ! timeout 90 adb -s "$DEVICE" install -r "$APK" 2>&1 | tail -5; then
  warn "install завис, перезапускаю adb-server"
  adb kill-server >/dev/null 2>&1 || true
  sleep 1
  adb start-server >/dev/null
  timeout 60 adb -s "$DEVICE" install -r "$APK" | tail -3
fi
ok "установлено"

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
printf "${C_DIM}следующий запуск: ./local_stats2.0.sh [опции]${C_RESET}\n\n"
