package com.assistant.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

object TranscriptionClient {

    private const val TAG = "Transcription"
    private const val OR_CHAT_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    private const val OR_TRANSCRIPT_ENDPOINT = "https://openrouter.ai/api/v1/audio/transcriptions"
    private const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"

    private fun openConn(url: String, apiKey: String, useAuth: Boolean = true): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) walderhu-assistant/1.1")
            setRequestProperty("HTTP-Referer", "https://walderhu.app")
            setRequestProperty("X-Title", "walderhu-assistant")
            if (useAuth) setRequestProperty("Authorization", "Bearer $apiKey")
        }
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } }.orEmpty()
    }

    /**
     * Транскрибирует аудио с авто-фоллбэком по цепочке моделей.
     */
    fun transcribe(
        orKey: String,
        groqKey: String,
        audio: File,
        model: String,
        language: String = "ru"
    ): String {
        require(audio.exists() && audio.length() > 0) { "audio пустой: ${audio.absolutePath}" }

        val chain = buildChain(model, orKey, groqKey, audio, language)
        var lastError: String? = null
        for ((label, block) in chain) {
            try {
                Log.i(TAG, "пробую: $label (audio=${audio.length()}B)")
                val text = block()
                if (text.isNotBlank()) {
                    Log.i(TAG, "успех: $label (${text.length} символов)")
                    return text
                }
                lastError = "$label: пустой ответ"
            } catch (e: Exception) {
                lastError = "$label → ${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                Log.w(TAG, "фейл: $lastError")
            }
        }
        error("Все модели не сработали. Последняя ошибка: $lastError")
    }

    private fun buildChain(
        model: String,
        orKey: String,
        groqKey: String,
        audio: File,
        language: String
    ): List<Pair<String, () -> String>> {
        val chain = mutableListOf<Pair<String, () -> String>>()
        // Рабочие модели впереди. Google Gemini поддерживает audio в OpenRouter.
        chain += "google/gemini-2.0-flash-001 (chat)" to { transcribeOrChat(orKey, audio, "google/gemini-2.0-flash-001", language) }
        chain += "google/gemini-flash-1.5 (chat)" to { transcribeOrChat(orKey, audio, "google/gemini-flash-1.5", language) }
        chain += "openai/whisper-1 (OR)" to { transcribeOrWhisper(orKey, audio, "whisper-1", language) }
        chain += "groq/whisper-large-v3" to { transcribeGroq(groqKey, audio, "whisper-large-v3", language) }
        return chain
    }

    private fun transcribeOrChat(apiKey: String, audio: File, model: String, language: String): String {
        val bytes = audio.readBytes()
        val b64 = Base64.getEncoder().encodeToString(bytes)
        val format = when (audio.extension.lowercase()) {
            "wav" -> "wav"; "mp3" -> "mp3"; "ogg", "oga" -> "ogg"; else -> "m4a"
        }
        val content = JSONArray().apply {
            put(JSONObject().put("type", "text")
                .put("text", "Transcribe this audio. Output ONLY the spoken text, no commentary. Language: $language."))
            put(JSONObject().put("type", "input_audio")
                .put("input_audio", JSONObject().put("data", b64).put("format", format)))
        }
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        val payload = JSONObject().put("model", model).put("messages", messages)
        val conn = openConn(OR_CHAT_ENDPOINT, apiKey).apply {
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val body = readBody(conn)
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}: $body")
            val text = JSONObject(body)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
            if (text.isEmpty()) error("HTTP 200, но text пустой. body=$body")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun transcribeOrWhisper(apiKey: String, audio: File, model: String, language: String): String {
        val mime = when (audio.extension.lowercase()) {
            "wav" -> "audio/wav"; "mp3" -> "audio/mpeg"; "ogg", "oga" -> "audio/ogg"; else -> "audio/mp4"
        }
        val boundary = "----WalderhuBoundary${System.currentTimeMillis()}"
        val CRLF = "\r\n"
        val sb = StringBuilder()
        fun addField(name: String, value: String) {
            sb.append("--").append(boundary).append(CRLF)
            sb.append("Content-Disposition: form-data; name=\"").append(name).append("\"").append(CRLF)
            sb.append(CRLF).append(value).append(CRLF)
        }
        addField("model", model)
        addField("language", language)
        sb.append("--").append(boundary).append(CRLF)
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(audio.name).append("\"").append(CRLF)
        sb.append("Content-Type: ").append(mime).append(CRLF).append(CRLF)
        val headBytes = sb.toString().toByteArray()
        val tailBytes = (CRLF + "--" + boundary + "--" + CRLF).toByteArray()
        val totalSize = headBytes.size + audio.length() + tailBytes.size
        val conn = openConn(OR_TRANSCRIPT_ENDPOINT, apiKey).apply {
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setFixedLengthStreamingMode(totalSize)
        }
        try {
            conn.outputStream.use { out: OutputStream ->
                out.write(headBytes)
                audio.inputStream().use { it.copyTo(out) }
                out.write(tailBytes)
            }
            val body = readBody(conn)
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}: $body")
            val text = JSONObject(body).optString("text", "").trim()
            if (text.isEmpty()) error("HTTP 200, но text пустой. body=$body")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun transcribeGroq(apiKey: String, audio: File, model: String, language: String): String {
        val mime = when (audio.extension.lowercase()) {
            "wav" -> "audio/wav"; "mp3" -> "audio/mpeg"; "ogg", "oga" -> "audio/ogg"; else -> "audio/mp4"
        }
        val boundary = "----GroqBoundary${System.currentTimeMillis()}"
        val CRLF = "\r\n"
        val sb = StringBuilder()
        fun addField(name: String, value: String) {
            sb.append("--").append(boundary).append(CRLF)
            sb.append("Content-Disposition: form-data; name=\"").append(name).append("\"").append(CRLF)
            sb.append(CRLF).append(value).append(CRLF)
        }
        addField("model", model)
        addField("language", language)
        addField("response_format", "json")
        sb.append("--").append(boundary).append(CRLF)
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(audio.name).append("\"").append(CRLF)
        sb.append("Content-Type: ").append(mime).append(CRLF).append(CRLF)
        val headBytes = sb.toString().toByteArray()
        val tailBytes = (CRLF + "--" + boundary + "--" + CRLF).toByteArray()
        val totalSize = headBytes.size + audio.length() + tailBytes.size
        val conn = openConn(GROQ_ENDPOINT, apiKey).apply {
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setFixedLengthStreamingMode(totalSize)
        }
        try {
            conn.outputStream.use { out: OutputStream ->
                out.write(headBytes)
                audio.inputStream().use { it.copyTo(out) }
                out.write(tailBytes)
            }
            val body = readBody(conn)
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}: $body")
            val text = JSONObject(body).optString("text", "").trim()
            if (text.isEmpty()) error("HTTP 200, но text пустой. body=$body")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
