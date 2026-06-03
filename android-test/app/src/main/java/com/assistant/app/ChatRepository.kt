package com.assistant.app

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Хранилище диалогов в filesDir/chats.json.
 * Один JSON-файл на все чаты, чтобы не плодить файлы и атомарно перезаписывать.
 */
class ChatRepository(context: Context) {
    private val file: File = File(context.filesDir, "chats.json")
    private val legacyPrefs: SharedPreferences =
        context.getSharedPreferences("chat", Context.MODE_PRIVATE)

    data class Chat(
        val id: String,
        var title: String,
        val createdAt: Long,
        var updatedAt: Long,
        var pinned: Boolean = false,
        val mode: String? = null,
        val messages: MutableList<Message>
    )

    data class State(
        val chats: MutableList<Chat>,
        var currentId: String
    )

    @Synchronized
    fun load(): State {
        if (!file.exists()) {
            // миграция со старого одиночного history в SharedPreferences
            val legacy = legacyPrefs.getString("history", null)
            if (!legacy.isNullOrBlank()) {
                val msgs = parseMessages(legacy)
                val now = System.currentTimeMillis()
                val first = Chat(
                    id = newId(),
                    title = titleFor(msgs),
                    createdAt = now,
                    updatedAt = now,
                    messages = msgs
                )
                val state = State(mutableListOf(first), first.id)
                write(state)
                legacyPrefs.edit().remove("history").apply()
                return state
            }
            return createFreshState()
        }
        return try {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("chats") ?: JSONArray()
            val chats = mutableListOf<Chat>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                chats += Chat(
                    id = o.getString("id"),
                    title = o.optString("title", "Чат"),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                    pinned = o.optBoolean("pinned", false),
                    mode = o.opt("mode")?.takeIf { it != JSONObject.NULL }?.toString(),
                    messages = parseMessages(o.optJSONArray("messages"))
                )
            }
            val currentId = root.optString("currentId", chats.firstOrNull()?.id ?: "")
            if (chats.isEmpty()) createFreshState() else State(chats, currentId)
        } catch (e: Exception) {
            createFreshState()
        }
    }

    @Synchronized
    fun save(state: State) = write(state)

    @Synchronized
    fun createChat(state: State, modeId: String? = null, title: String? = null): Chat {
        val now = System.currentTimeMillis()
        val chat = Chat(
            id = newId(),
            title = title ?: (modeId?.let { Modes.byId(it)?.name } ?: "Новый чат"),
            createdAt = now,
            updatedAt = now,
            mode = modeId,
            messages = mutableListOf()
        )
        state.chats.add(0, chat)
        state.currentId = chat.id
        write(state)
        return chat
    }

    /** Найти существующий чат для мода, или null. */
    fun findModeChat(state: State, modeId: String): Chat? =
        state.chats.firstOrNull { it.mode == modeId }

    /** Список обычных чатов (без модов). */
    fun regularChats(state: State): List<Chat> =
        state.chats.filter { it.mode == null }

    @Synchronized
    fun deleteChat(state: State, id: String) {
        state.chats.removeAll { it.id == id }
        if (state.currentId == id) {
            state.currentId = state.chats.firstOrNull()?.id ?: createChat(state).id
        }
        write(state)
    }

    @Synchronized
    fun appendMessage(state: State, chatId: String, role: String, text: String) {
        val chat = state.chats.firstOrNull { it.id == chatId } ?: return
        chat.messages += Message(text, isUser = role == "user")
        chat.updatedAt = System.currentTimeMillis()
        if (chat.title == "Новый чат" && role == "user") {
            chat.title = text.take(40).ifBlank { "Чат" }
        }
        write(state)
    }

    @Synchronized
    fun replaceLastAssistant(state: State, chatId: String, text: String) {
        val chat = state.chats.firstOrNull { it.id == chatId } ?: return
        // ищем последнее сообщение ассистента без isLoading — у нас его нет в Message,
        // поэтому находим по тексту-плейсхолдеру "●" / "…" — проще всего: последний assistant
        for (i in chat.messages.indices.reversed()) {
            if (!chat.messages[i].isUser) {
                chat.messages[i] = Message(text, isUser = false)
                break
            }
        }
        chat.updatedAt = System.currentTimeMillis()
        write(state)
    }

    @Synchronized
    fun updateTitle(state: State, chatId: String, title: String) {
        val chat = state.chats.firstOrNull { it.id == chatId } ?: return
        chat.title = title.ifBlank { "Чат" }
        write(state)
    }

    @Synchronized
    fun togglePin(state: State, chatId: String) {
        val chat = state.chats.firstOrNull { it.id == chatId } ?: return
        chat.pinned = !chat.pinned
        chat.updatedAt = System.currentTimeMillis()
        write(state)
    }

    /** Возвращает отсортированную копию: закреплённые сверху, далее по свежести. */
    fun sortedChats(state: State, base: List<Chat> = state.chats): List<Chat> =
        base.sortedWith(
            compareBy<Chat> { if (it.pinned) 0 else 1 }
                .thenByDescending { it.updatedAt }
        )

    private fun write(state: State) {
        val arr = JSONArray()
        for (c in state.chats) {
            val msgs = JSONArray()
            for (m in c.messages) {
                msgs.put(JSONObject()
                    .put("r", if (m.isUser) "user" else "assistant")
                    .put("t", m.text)
                    .put("ts", m.timestamp)
                    .put("voice", m.isVoice)
                    .put("img", m.imageUri ?: JSONObject.NULL))
            }
            arr.put(JSONObject()
                .put("id", c.id)
                .put("title", c.title)
                .put("createdAt", c.createdAt)
                .put("updatedAt", c.updatedAt)
                .put("pinned", c.pinned)
                .put("mode", c.mode ?: JSONObject.NULL)
                .put("messages", msgs))
        }
        val root = JSONObject().put("chats", arr).put("currentId", state.currentId)
        file.writeText(root.toString())
    }

    private fun parseMessages(json: String?): MutableList<Message> {
        if (json.isNullOrBlank()) return mutableListOf()
        return try {
            val arr = JSONArray(json)
            val out = mutableListOf<Message>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val isUser = o.optString("r", "user") == "user"
                val text = o.optString("t", "")
                val ts = o.optLong("ts", System.currentTimeMillis())
                val isVoice = o.optBoolean("voice", false)
                val img = o.opt("img")?.takeIf { it != JSONObject.NULL }?.toString()
                out += Message(text, isUser = isUser, timestamp = ts, isVoice = isVoice, imageUri = img)
            }
            out
        } catch (e: Exception) { mutableListOf() }
    }

    private fun parseMessages(arr: JSONArray?): MutableList<Message> {
        if (arr == null) return mutableListOf()
        val out = mutableListOf<Message>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val isUser = o.optString("r", "user") == "user"
            val text = o.optString("t", "")
            val ts = o.optLong("ts", System.currentTimeMillis())
            val isVoice = o.optBoolean("voice", false)
            val img = o.opt("img")?.takeIf { it != JSONObject.NULL }?.toString()
            out += Message(text, isUser = isUser, timestamp = ts, isVoice = isVoice, imageUri = img)
        }
        return out
    }

    private fun titleFor(msgs: List<Message>): String {
        return msgs.firstOrNull { it.isUser }?.text?.take(40)?.ifBlank { null }
            ?: msgs.firstOrNull()?.text?.take(40)
            ?: "Чат"
    }

    private fun createFreshState(): State {
        val now = System.currentTimeMillis()
        val first = Chat(
            id = newId(),
            title = "Новый чат",
            createdAt = now,
            updatedAt = now,
            messages = mutableListOf()
        )
        val state = State(mutableListOf(first), first.id)
        write(state)
        return state
    }

    private fun newId() = UUID.randomUUID().toString()
}
