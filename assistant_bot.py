import base64
import tempfile
import os
import telebot
from openai import OpenAI

TOKEN = os.environ["TELEGRAM_BOT_TOKEN"]
OPENROUTER_KEY = os.environ["OPENROUTER_API_KEY"]
GROQ_KEY = os.environ["GROQ_API_KEY"]

CHAT_MODEL = "openai/gpt-4o-mini"
VISION_MODEL = "openai/gpt-4o-mini"
TRANSCRIBE_MODEL = "whisper-large-v3-turbo"

bot = telebot.TeleBot(TOKEN)
client = OpenAI(api_key=OPENROUTER_KEY, base_url="https://openrouter.ai/api/v1")
groq = OpenAI(api_key=GROQ_KEY, base_url="https://api.groq.com/openai/v1")

chat_history = [
    {
        "role": "system",
        "content": (
            "Ты умный персональный ассистент в Telegram. "
            "Пользователь общается голосом — его голосовые сообщения уже транскрибированы внешним сервисом и приходят тебе как текст. "
            "Отвечай на содержание сообщений естественно и по делу. "
            "Никогда не говори что не можешь обрабатывать аудио или голос. "
            "Отвечай на языке пользователя."
        ),
    }
]


def ai_chat(user_text: str) -> str:
    chat_history.append({"role": "user", "content": user_text})
    response = client.chat.completions.create(model=CHAT_MODEL, messages=chat_history)
    reply = response.choices[0].message.content
    chat_history.append({"role": "assistant", "content": reply})
    return reply


def ai_describe_image(image_bytes: bytes, prompt: str) -> str:
    b64 = base64.b64encode(image_bytes).decode("utf-8")
    response = client.chat.completions.create(
        model=VISION_MODEL,
        messages=[{
            "role": "user",
            "content": [
                {"type": "text", "text": prompt},
                {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}},
            ],
        }],
    )
    return response.choices[0].message.content


def ai_transcribe(audio_bytes: bytes) -> str:
    with tempfile.NamedTemporaryFile(suffix=".ogg", delete=False) as f:
        f.write(audio_bytes)
        tmp_path = f.name
    try:
        with open(tmp_path, "rb") as f:
            result = groq.audio.transcriptions.create(
                model=TRANSCRIBE_MODEL,
                file=("voice.ogg", f, "audio/ogg"),
            )
        return result.text
    finally:
        os.unlink(tmp_path)


# ─── Handlers ────────────────────────────────────────────────────────────────

@bot.message_handler(commands=["start", "help"])
def handle_start(message):
    bot.reply_to(message, (
        "Привет! Я AI-ассистент.\n\n"
        "📝 Текст → чат с памятью\n"
        "📷 Фото → описание изображения\n"
        "🎤 Голос → транскрипция + ответ\n\n"
        "/clear — очистить историю чата"
    ))


@bot.message_handler(commands=["clear"])
def handle_clear(message):
    chat_history[:] = [chat_history[0]]
    bot.reply_to(message, "История чата очищена.")


@bot.message_handler(content_types=["text"])
def handle_text(message):
    try:
        bot.send_chat_action(message.chat.id, "typing")
        reply = ai_chat(message.text)
        bot.reply_to(message, reply)
    except Exception as e:
        bot.reply_to(message, f"Ошибка: {e}")


@bot.message_handler(content_types=["photo"])
def handle_photo(message):
    try:
        bot.send_chat_action(message.chat.id, "typing")
        photo = message.photo[-1]
        file_info = bot.get_file(photo.file_id)
        image_bytes = bot.download_file(file_info.file_path)
        prompt = message.caption if message.caption else "Опиши это изображение подробно на русском языке."
        description = ai_describe_image(image_bytes, prompt)
        bot.reply_to(message, description)
    except Exception as e:
        bot.reply_to(message, f"Ошибка при описании фото: {e}")


@bot.message_handler(content_types=["voice"])
def handle_voice(message):
    try:
        bot.send_chat_action(message.chat.id, "typing")
        file_info = bot.get_file(message.voice.file_id)
        audio_bytes = bot.download_file(file_info.file_path)

        text = ai_transcribe(audio_bytes)
        bot.reply_to(message, f"🎤 *Транскрипция:*\n{text}", parse_mode="Markdown")

        bot.send_chat_action(message.chat.id, "typing")
        ai_reply = ai_chat(text)
        bot.send_message(message.chat.id, ai_reply)
    except Exception as e:
        bot.reply_to(message, f"Ошибка при обработке голоса: {e}")


# ─── Run ─────────────────────────────────────────────────────────────────────

print("Bot started...")
bot.polling(none_stop=True, interval=0)
