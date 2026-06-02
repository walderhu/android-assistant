package com.assistant.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

object TranscriptionClient {

    private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    private const val MODEL = "openai/gpt-audio"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun transcribe(apiKey: String, audio: File, language: String = "ru"): String {
        require(audio.exists() && audio.length() > 0) { "audio пустой: ${audio.absolutePath}" }
        val bytes = audio.readBytes()
        val b64 = Base64.getEncoder().encodeToString(bytes)
        val format = when (audio.extension.lowercase()) {
            "wav" -> "wav"
            "mp3" -> "mp3"
            "ogg", "oga" -> "ogg"
            else -> "m4a"
        }

        val content = JSONArray().apply {
            put(JSONObject().put("type", "text")
                .put("text", "Transcribe this audio. Output ONLY the spoken text, no commentary. Language: $language."))
            put(JSONObject().put("type", "input_audio")
                .put("input_audio", JSONObject().put("data", b64).put("format", format)))
        }
        val messages = JSONArray().put(
            JSONObject().put("role", "user").put("content", content)
        )
        val payload = JSONObject()
            .put("model", MODEL)
            .put("messages", messages)

        val req = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("OpenRouter ${resp.code}: $body")
            val text = JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
            if (text.isEmpty()) error("OpenRouter 200, но text пустой. body=$body")
            return text
        }
    }
}
