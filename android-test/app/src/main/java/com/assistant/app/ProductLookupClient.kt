package com.assistant.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

object ProductLookupClient {
    private const val USER_AGENT = "AssistantNutrition/1.0"

    suspend fun getByBarcode(barcode: String): String? = withContext(Dispatchers.IO) {
        val fields = listOf(
            "code", "product_name", "generic_name", "brands", "quantity",
            "product_quantity", "product_quantity_unit", "serving_size",
            "nutriments", "categories"
        ).joinToString(",")
        val url = URL(
            "https://world.openfoodfacts.org/api/v2/product/" +
                URLEncoder.encode(barcode, "UTF-8") + ".json?fields=" + fields
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            if (root.optInt("status") != 1) return@withContext null
            format(barcode, root.optJSONObject("product") ?: JSONObject())
        } finally {
            conn.disconnect()
        }
    }

    private fun format(barcode: String, p: JSONObject): String {
        val n = p.optJSONObject("nutriments") ?: JSONObject()
        fun s(key: String): String = n.optDouble(key, Double.NaN)
            .takeUnless { it.isNaN() }?.let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) } ?: "?"
        val name = p.optString("product_name").ifBlank { p.optString("generic_name").ifBlank { "Без названия" } }
        val qty = p.optString("quantity").ifBlank {
            val v = p.optString("product_quantity")
            val u = p.optString("product_quantity_unit")
            listOf(v, u).filter { it.isNotBlank() }.joinToString(" ")
        }.ifBlank { "?" }
        return """
Продукт: $name
Бренд: ${p.optString("brands").ifBlank { "?" }}
Код: $barcode
Упаковка: $qty
Порция: ${p.optString("serving_size").ifBlank { "?" }}

На 100 г/мл:
КАЛОРИИ: ${s("energy-kcal_100g")} ккал
БЕЛКИ: ${s("proteins_100g")} г
ЖИРЫ: ${s("fat_100g")} г
УГЛЕВОДЫ: ${s("carbohydrates_100g")} г
САХАР: ${s("sugars_100g")} г
СОЛЬ: ${s("salt_100g")} г
""".trim()
    }
}
