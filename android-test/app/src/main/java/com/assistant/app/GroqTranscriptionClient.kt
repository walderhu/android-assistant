package com.assistant.app

import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object GroqTranscriptionClient {

    private const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val MODEL = "whisper-large-v3-turbo"

    fun transcribe(apiKey: String, audio: File, language: String = "ru"): String {
        require(audio.exists() && audio.length() > 0) { "audio пустой: ${audio.absolutePath}" }
        val boundary = "----WAVFormBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val url = URL(ENDPOINT)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000

        conn.outputStream.use { out ->
            writeField(out, boundary, "model", MODEL)
            writeField(out, boundary, "language", language)
            writeField(out, boundary, "response_format", "json")
            writeFile(out, boundary, audio)
            out.write("--$boundary--\r\n".toByteArray())
            out.flush()
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: "<no body>"
        if (code !in 200..299) error("Groq $code: $body")
        val text = JSONObject(body).optString("text", "").trim()
        if (text.isEmpty()) error("Groq 200, но text пустой. body=$body")
        return text
    }

    private fun writeField(out: OutputStream, boundary: String, name: String, value: String) {
        out.write("--$boundary\r\n".toByteArray())
        out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
        out.write(value.toByteArray())
        out.write("\r\n".toByteArray())
    }

    private fun writeFile(out: OutputStream, boundary: String, file: File) {
        out.write("--$boundary\r\n".toByteArray())
        out.write(
            "Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n".toByteArray()
        )
        out.write("Content-Type: audio/mp4\r\n\r\n".toByteArray())
        file.inputStream().use { it.copyTo(out) }
        out.write("\r\n".toByteArray())
    }
}
