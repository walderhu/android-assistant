package com.assistant.app

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

object TranscriptionClient {

    private const val TAG = "Transcription"
    private const val OR_CHAT_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    private const val OR_TRANSCRIPT_ENDPOINT = "https://openrouter.ai/api/v1/audio/transcriptions"
    private const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .build()

    /**
     * Транскрибирует аудио с авто-фоллбэком по цепочке моделей.
     * Сначала пробует указанную модель, потом цепочку запасных.
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
                Log.i(TAG, "пробую: $label")
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

    /** Цепочка: сначала запрошенная модель, потом фоллбэки. */
    private fun buildChain(
        model: String,
        orKey: String,
        groqKey: String,
        audio: File,
        language: String
    ): List<Pair<String, () -> String>> {
        val chain = mutableListOf<Pair<String, () -> String>>()
        when (model) {
            "openai/whisper-1" -> {
                chain += "openai/whisper-1 (OR)" to { transcribeOrWhisper(orKey, audio, "whisper-1", language) }
                chain += "openai/gpt-4o (OR chat)" to { transcribeOrChat(orKey, audio, "openai/gpt-4o", language) }
                chain += "groq/whisper-large-v3" to { transcribeGroq(groqKey, audio, "whisper-large-v3", language) }
            }
            "groq/whisper-large-v3" -> {
                chain += "groq/whisper-large-v3" to { transcribeGroq(groqKey, audio, "whisper-large-v3", language) }
                chain += "openai/whisper-1 (OR)" to { transcribeOrWhisper(orKey, audio, "whisper-1", language) }
                chain += "openai/gpt-4o (OR chat)" to { transcribeOrChat(orKey, audio, "openai/gpt-4o", language) }
            }
            else -> {
                chain += "chat: $model" to { transcribeOrChat(orKey, audio, model, language) }
                chain += "openai/whisper-1 (OR)" to { transcribeOrWhisper(orKey, audio, "whisper-1", language) }
                chain += "openai/gpt-4o (OR chat)" to { transcribeOrChat(orKey, audio, "openai/gpt-4o", language) }
                chain += "groq/whisper-large-v3" to { transcribeGroq(groqKey, audio, "whisper-large-v3", language) }
            }
        }
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
        val req = Request.Builder()
            .url(OR_CHAT_ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("HTTP-Referer", "https://walderhu.app")
            .addHeader("X-Title", "walderhu-assistant")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) walderhu-assistant/1.1")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("OpenRouter ${resp.code}: $body")
            val text = JSONObject(body)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
            if (text.isEmpty()) error("OpenRouter 200, но text пустой. body=$body")
            return text
        }
    }

    private fun transcribeOrWhisper(apiKey: String, audio: File, model: String, language: String): String {
        val mime = when (audio.extension.lowercase()) {
            "wav" -> "audio/wav"; "mp3" -> "audio/mpeg"; "ogg", "oga" -> "audio/ogg"; else -> "audio/mp4"
        }.toMediaType()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("language", language)
            .addFormDataPart("file", audio.name, audio.asRequestBody(mime))
            .build()
        val req = Request.Builder()
            .url(OR_TRANSCRIPT_ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("HTTP-Referer", "https://walderhu.app")
            .addHeader("X-Title", "walderhu-assistant")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) walderhu-assistant/1.1")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val respBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("OpenRouter ${resp.code}: $respBody")
            val text = JSONObject(respBody).optString("text", "").trim()
            if (text.isEmpty()) error("OpenRouter 200, но text пустой. body=$respBody")
            return text
        }
    }

    private fun transcribeGroq(apiKey: String, audio: File, model: String, language: String): String {
        val mime = when (audio.extension.lowercase()) {
            "wav" -> "audio/wav"; "mp3" -> "audio/mpeg"; "ogg", "oga" -> "audio/ogg"; else -> "audio/mp4"
        }.toMediaType()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("language", language)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", audio.name, audio.asRequestBody(mime))
            .build()
        val req = Request.Builder()
            .url(GROQ_ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) walderhu-assistant/1.1")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val respBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Groq ${resp.code}: $respBody")
            val text = JSONObject(respBody).optString("text", "").trim()
            if (text.isEmpty()) error("Groq 200, но text пустой. body=$respBody")
            return text
        }
    }
}
