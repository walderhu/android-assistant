package com.assistant.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Локальный crash-лог: пишет в cacheDir/nutrition_crash.log.
 * Файл можно скопировать из приложения или прочитать через adb.
 */
object CrashLog {
    private const val FILE_NAME = "nutrition_crash.log"
    private const val MAX_BYTES = 200_000

    fun log(ctx: Context, t: Throwable, tag: String = "") {
        runCatching {
            val dir = ctx.cacheDir
            val f = File(dir, FILE_NAME)
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val entry = buildString {
                append("---- $ts ")
                if (tag.isNotBlank()) append("[$tag] ")
                append("----\n")
                append(sw.toString())
                append('\n')
            }
            f.appendText(entry)
            // обрезаем хвост если файл распух
            if (f.length() > MAX_BYTES) {
                val keep = f.readText().takeLast(MAX_BYTES / 2)
                f.writeText(keep)
            }
        }
    }

    fun read(ctx: Context): String {
        val f = File(ctx.cacheDir, FILE_NAME)
        return if (f.exists()) f.readText() else ""
    }

    fun clear(ctx: Context) {
        runCatching { File(ctx.cacheDir, FILE_NAME).delete() }
    }
}
