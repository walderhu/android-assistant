#!/usr/bin/env bash
set -euo pipefail

# Ollama на ноуте/сервере. Телефон → http://IP:11434 (настройки приложения).
# WSL: ставь Ollama на Windows, телефон бьёт в IP Windows в локальной сети.

MODEL="${LLM_MODEL:-deepseek-r1:1.5b}"
HOST="${OLLAMA_HOST:-0.0.0.0:11434}"

if ! command -v ollama >/dev/null 2>&1; then
  echo "Установка Ollama…"
  if ! command -v zstd >/dev/null 2>&1; then
    echo "Нужен zstd…"
    if command -v apt-get >/dev/null 2>&1; then
      sudo apt-get update -qq && sudo apt-get install -y zstd
    elif command -v dnf >/dev/null 2>&1; then
      sudo dnf install -y zstd
    elif command -v pacman >/dev/null 2>&1; then
      sudo pacman -S --noconfirm zstd
    else
      echo "Установи zstd вручную и запусти снова" >&2
      exit 1
    fi
  fi
  curl -fsSL https://ollama.com/install.sh | sh
fi

echo "Скачивание модели $MODEL (~1 ГБ)…"
ollama pull "$MODEL"

echo ""
echo "Запуск (слушает $HOST):"
echo "  OLLAMA_HOST=$HOST ollama serve"
echo ""
echo "В приложении: Настройки → Текст → «Свой сервер»"
echo "  URL: http://<IP-ноута>:11434"
echo "  Модель: $MODEL"
