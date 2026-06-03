package com.assistant.app

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

    private const val OR_CHAT_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    private const val OR_TRANSCRIPT_ENDPOINT = "https://openrouter.ai/api/v1/audio/transcriptions"
    private const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * @param model одна из: "openai/gpt-audio", "openai/whisper-1", "groq/whisper-large-v3"
     * @param orKey OpenRouter API key (для первых двух)
     * @param groqKey Groq API key (для groq/whisper-large-v3)
     */
    fun transcribe(
        orKey: String,
        groqKey: String,
        audio: File,
        model: String,
        language: String = "ru"
    ): String {
        require(audio.exists() && audio.length() > 0) { "audio пустой: ${audio.absolutePath}" }
        return when (model) {
            "openai/gpt-audio" -> transcribeOrChat(orKey, audio, model, language)
            "openai/whisper-1" -> transcribeOrWhisper(orKey, audio, "whisper-1", language)
            "groq/whisper-large-v3" -> transcribeGroq(groqKey, audio, "whisper-large-v3", language)
            else -> transcribeOrChat(orKey, audio, "openai/gpt-audio", language)
        }
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
