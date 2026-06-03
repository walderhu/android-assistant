package com.assistant.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object NutritionProductStore {
    data class Product(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val brand: String = "",
        val protein: Int = 0,
        val fat: Int = 0,
        val carbs: Int = 0,
        val kcal: Int = protein * 4 + fat * 9 + carbs * 4,
        val photoPath: String? = null
    )

    private fun file(ctx: Context) = File(ctx.filesDir, "nutrition_products.json")

    @Synchronized
    fun all(ctx: Context): MutableList<Product> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(f.readText())
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Product(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    name = o.optString("name"),
                    brand = o.optString("brand"),
                    protein = o.optInt("protein"),
                    fat = o.optInt("fat"),
                    carbs = o.optInt("carbs"),
                    kcal = o.optInt("kcal"),
                    photoPath = o.optString("photoPath").ifBlank { null }
                )
            }.filter { it.name.isNotBlank() }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    @Synchronized
    fun add(ctx: Context, product: Product) {
        val items = all(ctx)
        items.add(0, product)
        write(ctx, items)
    }

    @Synchronized
    fun delete(ctx: Context, id: String) {
        val items = all(ctx)
        val removed = items.firstOrNull { it.id == id }
        items.removeAll { it.id == id }
        removed?.photoPath?.let { runCatching { File(it).delete() } }
        write(ctx, items)
    }

    private fun write(ctx: Context, items: List<Product>) {
        val arr = JSONArray()
        items.forEach { p ->
            arr.put(JSONObject()
                .put("id", p.id)
                .put("name", p.name)
                .put("brand", p.brand)
                .put("protein", p.protein)
                .put("fat", p.fat)
                .put("carbs", p.carbs)
                .put("kcal", p.kcal)
                .put("photoPath", p.photoPath ?: ""))
        }
        file(ctx).writeText(arr.toString())
    }
}
