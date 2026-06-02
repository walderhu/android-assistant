#!/usr/bin/env bash
# attach-phone.sh — проброс Xiaomi/Redmi из Windows в WSL через usbipd.
#
# Использует ваш алиас `usbipd` (Windows .exe, прокинут в WSL).
# Запускать из WSL — без sudo и без отдельного PowerShell.
#
#   ./attach-phone.sh                # найти "Redmi"
#   ./attach-phone.sh "Xiaomi"       # другая подстрока

set -euo pipefail

SEARCH="${1:-Redmi}"

# ---------- цвета ----------
RED='\033[0;31m'; GRN='\033[0;32m'; YEL='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
say()  { printf "${CYAN}>>>${NC} %s\n" "$*"; }
ok()   { printf "${GRN}✓${NC} %s\n" "$*"; }
warn() { printf "${YEL}!${NC} %s\n" "$*"; }
die()  { printf "${RED}✗ %s${NC}\n" "$*" >&2; exit 1; }

# ---------- проверки ----------
command -v usbipd >/dev/null || die "usbipd не найден. Проверьте alias в ~/.bashrc (должен указывать на Windows .exe)"

# usbipd требует админ-прав на Windows-стороне. WSL-проверка: запускаем с -V.
usbipd -V >/dev/null 2>&1 || die "usbipd не отвечает. Возможно, нужно запустить PowerShell от админа хотя бы раз, чтобы драйвер зарегистрировался"

# ---------- получить список ----------
say "usbipd list…"
RAW=$(usbipd list 2>&1) || die "usbipd list упал: $RAW"
printf '%s\n' "$RAW" | sed 's/^/    /'

# ---------- распарсить Connected-секцию ----------
# формат: "  BUSID  VID:PID    DEVICE...                       STATE"
# STATE ∈ {Not shared, Shared, Attached}
mapfile -t DEVICES < <(
  printf '%s\n' "$RAW" | awk -v search="$SEARCH" '
    /^[A-Za-zА-Яа-я].*:/      { section=$0; next }
    /^\s*BUSID\s/             { next }   # заголовок
    section ~ /Connected:/ && match($0, /^[[:space:]]*([0-9]+-[0-9]+)[[:space:]]+([0-9a-fA-F]{4}:[0-9a-fA-F]{4})[[:space:]]+(.+)[[:space:]]+(Not shared|Shared|Attached)[[:space:]]*$/, m) {
      busid=m[1]; vidpid=m[2]; name=m[3]; state=m[4]
      gsub(/[[:space:]]+$/, "", name)
      if (name ~ search) print busid "|" vidpid "|" name "|" state
    }
  '
)

[ "${#DEVICES[@]}" -gt 0 ] || die "устройство с именем содержащим '$SEARCH' не найдено"

# ---------- выбрать ADB-интерфейс, если их несколько ----------
CHOSEN=""
ADB_HIT=""
for d in "${DEVICES[@]}"; do
  IFS='|' read -r busid vidpid name state <<< "$d"
  if [[ "$name" == *ADB* ]]; then
    ADB_HIT="$d"
    break
  fi
done
CHOSEN="${ADB_HIT:-${DEVICES[0]}}"
IFS='|' read -r BUSID VIDPID NAME STATE <<< "$CHOSEN"

say "Найдено: $NAME"
echo "    BUSID  = $BUSID"
echo "    VID:PID= $VIDPID"
echo "    STATE  = $STATE"

# ---------- bind/attach ----------
case "$STATE" in
  "Not shared")
    say "usbipd bind --busid $BUSID"
    usbipd bind --busid "$BUSID" || die "bind упал"
    ok "bind выполнен"
    ;&  # fallthrough: после bind нужно ещё и attach
  "Shared")
    say "usbipd attach --wsl --busid $BUSID"
    usbipd attach --wsl --busid "$BUSID" || die "attach упал"
    ok "проброшено в WSL"
    ;;
  "Attached")
    ok "уже в WSL, ничего делать не нужно"
    exit 0
    ;;
  *)
    die "неизвестное состояние: $STATE"
    ;;
esac

ok "готово. Дальше: ./debug.sh"
