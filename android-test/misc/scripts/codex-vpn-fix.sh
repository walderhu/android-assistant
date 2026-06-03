#!/bin/bash
# Автоматическая настройка Codex для работы через SOCKS5 VPN в России
# Требуется: работающий SOCKS5 прокси на 127.0.0.1:10808 (ComfyVPN)

set -e

echo "🔧 Настройка Codex для работы через SOCKS5 VPN..."

# 1. Проверяем, что SOCKS5 прокси доступен
if ! nc -z 127.0.0.1 10808 2>/dev/null; then
    echo "❌ Ошибка: SOCKS5 прокси не доступен на 127.0.0.1:10808"
    echo "   Убедитесь, что ComfyVPN запущен и прокси активирован"
    exit 1
fi
echo "✅ SOCKS5 прокси обнаружен на 127.0.0.1:10808"

# 2. Устанавливаем socat для конвертации SOCKS5 → HTTP
if ! command -v socat &> /dev/null; then
    echo "📦 Устанавливаю socat..."
    sudo apt-get update && sudo apt-get install -y socat
fi
echo "✅ socat установлен"

# 3. Останавливаем любые предыдущие конвертеры
pkill -f "socat.*SOCKS5:127.0.0.1:10808" 2>/dev/null || true

# 4. Запускаем конвертер SOCKS5 → HTTP на порту 8088
echo "🔄 Запускаю конвертер SOCKS5 → HTTP (127.0.0.1:10808 → 127.0.0.1:8088)..."
socat TCP-LISTEN:8088,fork SOCKS5:127.0.0.1:10808 &
SOCAT_PID=$!
echo "   Конвертер запущен с PID: $SOCAT_PID"

# Даем время на запуск
sleep 2

# 5. Проверяем, что конвертер работает
if ! nc -z 127.0.0.1 8088 2>/dev/null; then
    echo "❌ Ошибка: конвертер не запустился"
    kill $SOCAT_PID 2>/dev/null || true
    exit 1
fi
echo "✅ Конвертер активен на 127.0.0.1:8088"

# 6. Настраиваем переменные окружения для Codex
export HTTP_PROXY=http://127.0.0.1:8088
export HTTPS_PROXY=http://127.0.0.1:8088
export NO_PROXY=localhost,127.0.0.1
echo "🌐 Настроены прокси переменные:"
echo "   HTTP_PROXY=$HTTP_PROXY"
echo "   HTTPS_PROXY=$HTTPS_PROXY"

# 7. Очищаем старые настройки Codex (опционально, но рекомендуется)
echo "🧹 Очистка старых настроек Codex..."
rm -rf "$HOME/.codex" "$HOME/.local/share/codex" 2>/dev/null || true

# 8. Переустанавливаем Codex CLI
echo "📦 Переустанавливаю Codex CLI..."
npm uninstall -g @openai/codex 2>/dev/null || true
npm install -g @openai/codex
echo "✅ Codex установлен: $(codex --version 2>/dev/null || echo 'версия неизвестна')"

# 9. Инструкция по следующему шагу
echo ""
echo "🚀 ВСЁ ГОТОВО! Теперь выполните:"
echo "   codex login"
echo ""
echo "   После этого откроется браузер для авторизации через OpenAI."
echo "   Войдите в ваш аккаунт OpenAI Plus и разрешите доступ."
echo ""
echo "⚠️  ВАЖНО: Не закрывайте этот терминал во время использования Codex!"
echo "   Конвертер будет работать в фоне (PID: $SOCAT_PID)"
echo "   Чтобы остановить конвертер позже: kill $SOCAT_PID"
echo ""
echo "💡 Если что-то не работает, проверьте:"
echo "   - Что ComfyVPN всё ещё активен"
echo "   - Что порт 10808 слушает SOCKS5 (netstat -tuln | grep 10808)"
echo "   - Что конвертер запущен (ps aux | grep socat)"