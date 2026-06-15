package com.assistant.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object TelegramBridge {
    private const val PREFS = "telegram_bridge"
    private const val K_URL = "server_url"
    private const val K_SECRET = "sync_secret"
    private const val K_ENABLED = "enabled"
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun serverUrl(ctx: Context): String = prefs(ctx).getString(K_URL, "") ?: ""
    fun syncSecret(ctx: Context): String = prefs(ctx).getString(K_SECRET, "walderhu-sync") ?: "walderhu-sync"
    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(K_ENABLED, false)

    fun setServerUrl(ctx: Context, v: String) = prefs(ctx).edit().putString(K_URL, v.trim()).apply()
    fun setSyncSecret(ctx: Context, v: String) = prefs(ctx).edit().putString(K_SECRET, v.trim()).apply()
    fun setEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(K_ENABLED, v).apply()

    data class SyncResult(val applied: Int, val message: String)

    suspend fun sync(ctx: Context, dateKey: String = LocalDate.now().format(DATE_FMT)): SyncResult =
        withContext(Dispatchers.IO) {
            if (!isEnabled(ctx)) return@withContext SyncResult(0, "выключено")
            val base = serverUrl(ctx).trim().trimEnd('/')
            if (base.isBlank()) return@withContext SyncResult(0, "нет URL сервера")

            uploadDiary(ctx, base, dateKey)
            val pending = fetchPending(ctx, base)
            if (pending.isEmpty()) return@withContext SyncResult(0, "нет новых записей")

            var n = 0
            val ackIds = JSONArray()
            for (p in pending) {
                val applied = NutritionFoodLogger.apply(ctx, p.dateKey, p.ops)
                n += applied
                if (applied > 0) ackIds.put(p.id)
            }
            if (ackIds.length() > 0) ack(ctx, base, ackIds)
            if (n > 0) {
                NutritionWidgetHelper.updateAll(ctx)
                NutritionController.onOverlayChanged?.invoke()
            }
            SyncResult(n, if (n > 0) "+$n" else "ок")
        }

    private data class Pending(val id: Long, val dateKey: String, val ops: JSONObject)

    private fun uploadDiary(ctx: Context, base: String, dateKey: String) {
        val all = NutritionController.loadMealData(ctx)
        val subset = JSONObject()
        val day = runCatching { LocalDate.parse(dateKey) }.getOrDefault(LocalDate.now())
        listOf(day, day.minusDays(1)).forEach { d ->
            val k = d.format(DATE_FMT)
            val meals = all[k] ?: return@forEach
            val dayObj = JSONObject()
            meals.forEach { (meal, md) ->
                val arr = JSONArray()
                md.items.forEach { it ->
                    arr.put(JSONObject().apply {
                        put("name", it.name)
                        put("g", it.grams)
                        put("kcal", it.kcal)
                        put("p", it.protein)
                        put("f", it.fat)
                        put("c", it.carbs)
                    })
                }
                dayObj.put(meal, JSONObject().apply { put("items", arr) })
            }
            subset.put(k, dayObj)
        }
        httpJson(ctx, "$base/api/diary", "PUT", JSONObject().apply {
            put("date_key", dateKey)
            put("meal_data", subset)
        })
    }

    private fun fetchPending(ctx: Context, base: String): List<Pending> {
        val resp = httpJson(ctx, "$base/api/pending", "GET", null) ?: return emptyList()
        val arr = resp.optJSONArray("pending") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Pending(
                id = o.optLong("id"),
                dateKey = o.optString("date_key"),
                ops = o.optJSONObject("ops") ?: JSONObject()
            )
        }
    }

    private fun ack(ctx: Context, base: String, ids: JSONArray) {
        httpJson(ctx, "$base/api/ack", "POST", JSONObject().put("ids", ids))
    }

    private fun httpJson(ctx: Context, url: String, method: String, body: JSONObject?): JSONObject? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer ${syncSecret(ctx)}")
            setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toString().toByteArray()) }
            }
        }
        return try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code: $text")
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
