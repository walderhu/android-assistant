package com.assistant.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object OpenRouterClient {
    private const val URL_API = "https://openrouter.ai/api/v1/chat/completions"

    suspend fun send(
        messages: List<Pair<String, String>>,
        model: String,
        systemPrompt: String? = null
    ): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.OPENROUTER_API_KEY
        if (apiKey.isBlank() || apiKey == "sk-or-v1-DUMMY") {
            throw IllegalStateException("API ключ не задан (BuildConfig.OPENROUTER_API_KEY пустой). Пересоберите с API_KEY=...")
        }

        val payload = JSONObject().apply {
            put("model", model)
            val arr = JSONArray()
            if (!systemPrompt.isNullOrBlank()) {
                arr.put(JSONObject().put("role", "system").put("content", systemPrompt))
            }
            messages.forEach { (role, content) ->
                arr.put(JSONObject().put("role", role).put("content", content))
            }
            put("messages", arr)
        }

        val conn = (URL(URL_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
        }

        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }
                .orEmpty()

            if (code !in 200..299) {
                // Попробуем вытащить человеческое сообщение из JSON-ответа OpenRouter
                val humanMessage = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                throw RuntimeException(
                    "HTTP $code: " + (if (humanMessage.isNotEmpty()) humanMessage else body.ifBlank { conn.responseMessage })
                )
            }

            JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            throw RuntimeException("Сеть/JSON: ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
        } finally {
            conn.disconnect()
        }
    }

    suspend fun describeImage(text: String, imageBase64: String, mime: String = "image/jpeg", model: String = "openai/gpt-4o-mini"): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.OPENROUTER_API_KEY
        if (apiKey.isBlank() || apiKey == "sk-or-v1-DUMMY") {
            throw IllegalStateException("API ключ не задан")
        }

        val dataUrl = "data:$mime;base64,$imageBase64"
        val userContent = JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", text))
            put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUrl)))
        }
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", userContent)
            ))
        }

        val conn = (URL(URL_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
        }

        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val humanMessage = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                throw RuntimeException("HTTP $code: " + (if (humanMessage.isNotEmpty()) humanMessage else body))
            }
            JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            throw RuntimeException("Vision: ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
        } finally {
            conn.disconnect()
        }
    }
}
