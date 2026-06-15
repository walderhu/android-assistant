#!/usr/bin/env python3
"""Telegram-бот питания + HTTP API для синка с Android."""
import json
import os
import re
import sqlite3
import threading
from datetime import date, datetime, timedelta
from pathlib import Path

import requests
from flask import Flask, jsonify, request
from telegram import Update
from telegram.ext import Application, CommandHandler, ContextTypes, MessageHandler, filters

try:
    from dotenv import load_dotenv
    load_dotenv(Path(__file__).with_name(".env"))
except ImportError:
    pass

DB = Path(__file__).with_name("bridge.db")
DAY_HINT = re.compile(r"завтрак|обед|ужин|перекус|за день|весь день|с утра|на завтрак|на обед|на ужин", re.I)

LOG_INSTRUCTIONS = """Дополнительно: веди дневник питания пользователя. Оцени порции и КБЖУ, ответь кратко по-русски.
В конце КАЖДОГО ответа — одна строка (скрытый блок для приложения):
LOG: {"ops":[...]}

Даты: поле date / from_date / to_date — yyyy-MM-dd, или today|сегодня, yesterday|вчера (от выбранного дня в контексте).

Операции ops (можно несколько за раз):
- {"op":"add","meal":"Завтрак","name":"...","grams":100,"kcal":250,"protein":10,"fat":5,"carbs":30}
- {"op":"remove","meal":"Ужин","match":"рис"}
- {"op":"update","meal":"Ужин","match":"рис","name":"...","grams":...,"kcal":...,"protein":...,"fat":...,"carbs":...}
- {"op":"move","from_meal":"Ужин","to_meal":"Завтрак","match":"курица"}
- {"op":"clear","meal":"Ужин"}
- Если еды/изменений нет — LOG: {"ops":[]}

Правила:
- КАЖДЫЙ продукт/блюдо — отдельный op в массиве ops
- kcal/protein/fat/carbs — ОБЯЗАТЕЛЬНО оцени реально, НИКОГДА не ставь 0
- Если пользователь перечислил весь день — отдельные ops с правильным meal (Завтрак/Обед/Ужин/Перекус) для каждого приёма
- Если перечислено несколько продуктов в одном приёме — отдельный add на каждый
Не пиши ничего после строки LOG."""

SYSTEM_PROMPT = """Ты — ассистент по питанию. Помогаешь вести дневник: добавлять, исправлять, переносить между днями и приёмами, удалять записи.
Отвечай кратко по-русски. Учитывай текущий дневник и явные указания пользователя."""


def env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


TOKEN = env("TELEGRAM_TOKEN")
OWNER_ID = int(env("TELEGRAM_ID") or "0")
SYNC_SECRET = env("SYNC_SECRET", "walderhu-sync")
OR_KEY = env("OPENROUTER_API_KEY")
TEXT_MODEL = env("TEXT_MODEL", "openai/gpt-4o-mini")
VOICE_MODEL = env("VOICE_MODEL", "openai/whisper-1")
HTTP_PORT = int(env("HTTP_PORT", "8787"))


def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    with db() as c:
        c.executescript("""
            CREATE TABLE IF NOT EXISTS pending (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date_key TEXT NOT NULL,
                ops_json TEXT NOT NULL,
                created_at TEXT NOT NULL,
                acked INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS kv (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
        """)


def kv_get(key: str, default: str = "{}") -> str:
    with db() as c:
        row = c.execute("SELECT value FROM kv WHERE key=?", (key,)).fetchone()
        return row["value"] if row else default


def kv_set(key: str, value: str) -> None:
    with db() as c:
        c.execute(
            "INSERT INTO kv(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            (key, value),
        )


def meal_for_hour(h: int) -> str:
    if 5 <= h < 11:
        return "Завтрак"
    if 11 <= h < 16:
        return "Обед"
    if 16 <= h < 22:
        return "Ужин"
    return "Перекус"


def format_day(meal_data: dict, day: str, title: str | None = None) -> str:
    meals = meal_data.get(day) or {}
    head = title or day
    if not meals:
        return f"{head}: пусто"
    lines = [head + ":"]
    for meal, md in meals.items():
        items = md.get("items") or []
        if not items:
            continue
        parts = []
        for it in items:
            g = f" {int(it.get('g', 0))}г" if it.get("g") else ""
            parts.append(f"{it.get('name', '')}{g} {int(it.get('kcal', 0))}ккал")
        if parts:
            lines.append(f"  {meal}: " + "; ".join(parts))
    return "\n".join(lines)


def diary_context(date_key: str) -> str:
    raw = kv_get("meal_data", "{}")
    try:
        meal_data = json.loads(raw)
    except json.JSONDecodeError:
        meal_data = {}
    day = datetime.strptime(date_key, "%Y-%m-%d").date()
    yesterday = (day - timedelta(days=1)).strftime("%Y-%m-%d")
    hour = datetime.now().hour
    parts = [
        f"Выбранный день (date по умолчанию для ops): {date_key}",
        format_day(meal_data, date_key),
        "",
        format_day(meal_data, yesterday, f"Вчера ({yesterday})"),
        f"Подсказка по времени (если приём не указан): {meal_for_hour(hour)}",
    ]
    return "\n".join(parts)


def extract_log_json(reply: str) -> dict | None:
    idx = reply.rfind("LOG:")
    if idx < 0:
        return None
    rest = reply[idx + 4:].strip()
    start = rest.find("{")
    if start < 0:
        return None
    depth = 0
    for i in range(start, len(rest)):
        ch = rest[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                try:
                    return json.loads(rest[start : i + 1])
                except json.JSONDecodeError:
                    return None
    return None


def strip_log(reply: str) -> tuple[str, dict | None]:
    log = extract_log_json(reply)
    if log is None:
        return reply.strip(), None
    idx = reply.rfind("LOG:")
    visible = reply[:idx].strip()
    return visible, log


def apply_ops_to_meal_data(meal_data: dict, date_key: str, log: dict) -> int:
    ops = log.get("ops") or []
    n = 0
    day = meal_data.setdefault(date_key, {})
    for op in ops:
        if op.get("op", "add") != "add":
            continue
        meal = op.get("meal") or meal_for_hour(datetime.now().hour)
        name = (op.get("name") or "").strip()
        if not name:
            continue
        item = {
            "name": name,
            "g": op.get("grams", 100),
            "kcal": op.get("kcal") or 0,
            "p": op.get("protein", 0),
            "f": op.get("fat", 0),
            "c": op.get("carbs", 0),
        }
        if item["kcal"] <= 0:
            item["kcal"] = int(item["p"] * 4 + item["f"] * 9 + item["c"] * 4)
        if item["kcal"] <= 0:
            item["kcal"] = max(30, int(item["g"] * 1.5))
        md = day.setdefault(meal, {"items": []})
        md["items"].append(item)
        n += 1
    return n


def wrap_user_message(text: str) -> str:
    t = text.strip()
    if t.startswith("["):
        return t
    if DAY_HINT.search(t) or t.count(",") >= 2 or len(t) > 120:
        return t
    return f"[{meal_for_hour(datetime.now().hour)}] {t}"


def openrouter_chat(system: str, user: str) -> str:
    if not OR_KEY:
        raise RuntimeError("OPENROUTER_API_KEY не задан")
    r = requests.post(
        "https://openrouter.ai/api/v1/chat/completions",
        headers={"Authorization": f"Bearer {OR_KEY}", "Content-Type": "application/json"},
        json={
            "model": TEXT_MODEL,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
        },
        timeout=90,
    )
    r.raise_for_status()
    return r.json()["choices"][0]["message"]["content"]


def transcribe_ogg(path: Path) -> str:
    if not OR_KEY:
        raise RuntimeError("OPENROUTER_API_KEY не задан")
    with path.open("rb") as f:
        r = requests.post(
            "https://openrouter.ai/api/v1/audio/transcriptions",
            headers={"Authorization": f"Bearer {OR_KEY}"},
            files={"file": (path.name, f, "audio/ogg")},
            data={"model": VOICE_MODEL},
            timeout=180,
        )
    r.raise_for_status()
    data = r.json()
    return (data.get("text") or "").strip()


def enqueue_ops(date_key: str, log: dict | None) -> int:
    if not log:
        return 0
    ops = log.get("ops") or log.get("items")
    if not ops:
        return 0
    payload = log if "ops" in log else {"ops": [{"op": "add", **x} for x in ops]}
    with db() as c:
        c.execute(
            "INSERT INTO pending(date_key, ops_json, created_at) VALUES(?,?,?)",
            (date_key, json.dumps(payload, ensure_ascii=False), datetime.utcnow().isoformat()),
        )
    try:
        meal_data = json.loads(kv_get("meal_data", "{}"))
    except json.JSONDecodeError:
        meal_data = {}
    apply_ops_to_meal_data(meal_data, date_key, payload)
    kv_set("meal_data", json.dumps(meal_data, ensure_ascii=False))
    return len(payload.get("ops", []))


def process_food_text(text: str, date_key: str | None = None) -> str:
    dk = date_key or date.today().strftime("%Y-%m-%d")
    msg = wrap_user_message(text)
    sys_prompt = SYSTEM_PROMPT + "\n\n" + diary_context(dk) + "\n\n" + LOG_INSTRUCTIONS
    reply = openrouter_chat(sys_prompt, msg)
    visible, log = strip_log(reply)
    n = enqueue_ops(dk, log)
    if not visible:
        visible = f"Записал ({n})." if n else "Не смог разобрать еду — попробуй перечислить по приёмам."
    elif n:
        visible += f"\n↳ записей: {n}"
    elif log is None:
        visible += "\n⚠️ не распознал LOG — повтори списком: завтрак ..., обед ..."
    return visible


def auth_ok() -> bool:
    auth = request.headers.get("Authorization", "")
    return auth == f"Bearer {SYNC_SECRET}"


app = Flask(__name__)


@app.get("/health")
def health():
    return jsonify({"ok": True})


@app.put("/api/diary")
def put_diary():
    if not auth_ok():
        return jsonify({"error": "unauthorized"}), 401
    body = request.get_json(force=True, silent=True) or {}
    kv_set("meal_data", json.dumps(body.get("meal_data") or {}, ensure_ascii=False))
    if body.get("date_key"):
        kv_set("date_key", str(body["date_key"]))
    return jsonify({"ok": True})


@app.get("/api/pending")
def get_pending():
    if not auth_ok():
        return jsonify({"error": "unauthorized"}), 401
    with db() as c:
        rows = c.execute(
            "SELECT id, date_key, ops_json, created_at FROM pending WHERE acked=0 ORDER BY id"
        ).fetchall()
    out = [
        {
            "id": r["id"],
            "date_key": r["date_key"],
            "ops": json.loads(r["ops_json"]),
            "created_at": r["created_at"],
        }
        for r in rows
    ]
    return jsonify({"pending": out})


@app.post("/api/ack")
def post_ack():
    if not auth_ok():
        return jsonify({"error": "unauthorized"}), 401
    body = request.get_json(force=True, silent=True) or {}
    ids = body.get("ids") or []
    if not ids:
        return jsonify({"ok": True, "acked": 0})
    q = ",".join("?" * len(ids))
    with db() as c:
        c.execute(f"UPDATE pending SET acked=1 WHERE id IN ({q})", ids)
    return jsonify({"ok": True, "acked": len(ids)})


async def cmd_start(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    if update.effective_user and update.effective_user.id != OWNER_ID:
        return
    await update.message.reply_text(
        "Пришли текст или голосовое — запишу в дневник питания.\n"
        "Синк с телефоном: включи «Telegram бот» в боковом меню приложения."
    )


async def on_text(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    if not update.message or not update.effective_user:
        return
    if update.effective_user.id != OWNER_ID:
        return
    text = (update.message.text or "").strip()
    if not text:
        return
    await update.message.reply_chat_action("typing")
    try:
        dk = kv_get("date_key", date.today().strftime("%Y-%m-%d"))
        if dk == "{}":
            dk = date.today().strftime("%Y-%m-%d")
        reply = process_food_text(text, dk)
        await update.message.reply_text(reply)
    except Exception as e:
        await update.message.reply_text(f"Ошибка: {e}")


async def on_voice(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    if not update.message or not update.effective_user:
        return
    if update.effective_user.id != OWNER_ID:
        return
    voice = update.message.voice or update.message.audio
    if not voice:
        return
    await update.message.reply_chat_action("typing")
    tmp = Path("/tmp") / f"tg_voice_{update.update_id}.ogg"
    try:
        f = await voice.get_file()
        await f.download_to_drive(custom_path=str(tmp))
        text = transcribe_ogg(tmp)
        if not text:
            await update.message.reply_text("Не расслышал.")
            return
        dk = kv_get("date_key", date.today().strftime("%Y-%m-%d"))
        if dk == "{}":
            dk = date.today().strftime("%Y-%m-%d")
        reply = process_food_text(text, dk)
        await update.message.reply_text(f"🎤 {text}\n\n{reply}")
    except Exception as e:
        await update.message.reply_text(f"Ошибка голоса: {e}")
    finally:
        tmp.unlink(missing_ok=True)


def run_http() -> None:
    app.run(host="0.0.0.0", port=HTTP_PORT, threaded=True, use_reloader=False)


def main() -> None:
    if not TOKEN or not OWNER_ID:
        raise SystemExit("Задай TELEGRAM_TOKEN и TELEGRAM_ID в .env")
    init_db()
    threading.Thread(target=run_http, daemon=True).start()
    tg = Application.builder().token(TOKEN).build()
    tg.add_handler(CommandHandler("start", cmd_start))
    tg.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, on_text))
    tg.add_handler(MessageHandler(filters.VOICE | filters.AUDIO, on_voice))
    print(f"HTTP :{HTTP_PORT}, TG owner={OWNER_ID}")
    tg.run_polling(drop_pending_updates=True)


if __name__ == "__main__":
    main()
