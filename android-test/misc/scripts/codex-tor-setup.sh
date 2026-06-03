#!/bin/bash
# Codex через Tor (более надёжный способ обхода российских блокировок)

set -e

echo "🔧 Настройка Codex через Tor..."

# 1. Проверяем, установлен ли Tor
if ! command -v tor &> /dev/null; then
    echo "📦 Устанавливаю Tor..."
    sudo apt-get update
    sudo apt-get install -y tor
fi
echo "✅ Tor установлен"

# 2. Останавливаем текущий Tor сервис
sudo systemctl stop tor 2>/dev/null || true

# 3. Настраиваем Tor для прозрачного прокси
cat <<EOF | sudo tee /etc/tor/torrc
SocksPort 9050
SocksPolicy 9050 Accept 127.0.0.1
HTTPSProxy false
HTTPProxy false
EOF

# 4. Запускаем Tor
echo "🔄 Запускаю Tor..."
sudo systemctl start tor
sleep 5

# Проверяем статус
if ! systemctl is-active --quiet tor; then
    echo "❌ Tor не запустился"
    sudo journalctl -u tor --no-pager -n 20
    exit 1
fi
echo "✅ Tor запущен на порту 9050"

# 5. Устанавливаем torsocks для SOCKS5 → HTTP конвертации
if ! command -v torsocks &> /dev/null; then
    echo "📦 Устанавливаю torsocks..."
    sudo apt-get install -y torsocks
fi

# 6. Запускаем HTTP прокси через torsocks
echo "🔄 Запускаю HTTP прокси через Tor..."
nohup torsocks -i python3 -m http.server 9051 > /tmp/codex-tor-http.log 2>&1 &
TOR_HTTP_PID=$!
sleep 3
echo "   HTTP прокси запущен на 127.0.0.1:9051 (PID: $TOR_HTTP_PID)"

# 7. Настраиваем Codex
export HTTP_PROXY=http://127.0.0.1:9051
export HTTPS_PROXY=http://127.0.0.1:9051
export NO_PROXY=localhost,127.0.0.1

echo ""
echo "🌐 Прокси настроены:"
echo "   HTTP_PROXY=$HTTP_PROXY"
echo "   HTTPS_PROXY=$HTTPS_PROXY"

# 8. Тестируем подключение
echo ""
echo "🧪 Тестирую подключение к OpenAI через Tor..."
if timeout 10 curl -s --proxy http://127.0.0.1:9051 https://api.openai.com/server/v1/models >/dev/null 2>&1; then
    echo "✅ Подключение к OpenAI через Tor работает!"
else
    echo "⚠️  Подключение к OpenAI не ответило в течение 10 секунд"
    echo "   Это нормально - Tor может быть медленным"
fi

# 9. Очищаем и переустанавливаем Codex
echo ""
echo "🧹 Очищаю настройки Codex..."
rm -rf "$HOME/.codex" "$HOME/.local/share/codex" 2>/dev/null || true

echo "📦 Переустанавливаю Codex..."
npm uninstall -g @openai/codex 2>/dev/null || true
npm install -g @openai/codex

echo ""
echo "🚀 ГОТОВО! Выполните:"
echo "   codex login"
echo ""
echo "   После авторизации Codex будет использовать Tor для всех запросов."
echo ""
echo "💡 Советы:"
echo "   - Tor может быть медленным (30-60 сек на запрос)"
echo "   - Чтобы остановить Tor: sudo systemctl stop tor"
echo "   - Чтобы проверить IP: curl --socks5-hostname 127.0.0.1:9050 https://api.ipify.org"