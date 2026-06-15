package com.assistant.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** OpenAI-совместимый API: Ollama, llama.cpp server, vLLM на ноуте/VPS. */
object LocalLlmClient {

    private fun endpoint(baseUrl: String): String {
        val b = baseUrl.trim().removeSuffix("/")
        return when {
            b.endsWith("/chat/completions") -> b
            b.endsWith("/v1") -> "$b/chat/completions"
            else -> "$b/v1/chat/completions"
        }
    }

    suspend fun send(
        baseUrl: String,
        model: String,
        messages: List<Pair<String, String>>,
        systemPrompt: String? = null,
        apiKey: String = ""
    ): String = withContext(Dispatchers.IO) {
        require(baseUrl.isNotBlank()) { "URL сервера не задан (Настройки → Текст)" }
        require(model.isNotBlank()) { "Имя модели не задано (Настройки → Текст)" }

        val payload = JSONObject().apply {
            put("model", model)
            put("stream", false)
            val arr = JSONArray()
            if (!systemPrompt.isNullOrBlank()) {
                arr.put(JSONObject().put("role", "system").put("content", systemPrompt))
            }
            messages.forEach { (role, content) ->
                arr.put(JSONObject().put("role", role).put("content", content))
            }
            put("messages", arr)
        }

        val conn = (URL(endpoint(baseUrl)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            connectTimeout = 15_000
            readTimeout = 300_000
            doOutput = true
        }

        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                throw RuntimeException("HTTP $code: ${msg.ifBlank { body.ifBlank { conn.responseMessage } }}")
            }
            JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } finally {
            conn.disconnect()
        }
    }
}
