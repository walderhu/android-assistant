package com.assistant.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.io.File
import java.util.UUID

/**
 * SQLite-хранилище «Базы данных питания». Три таблицы:
 *  - products     — внешние продукты (по штрихкоду или вручную);
 *  - custom_items — свои записи, созданные с нуля;
 *  - dishes       — блюда, состоящие из ингредиентов (products|custom_items) с граммовкой.
 *
 * При первом запуске мигрирует данные из устаревшего nutrition_products.json.
 */
class NutritionDatabase(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, NAME, null, VERSION) {

    enum class Kind { PRODUCT, CUSTOM, DISH }

    data class Product(
        val id: String,
        val name: String,
        val brand: String = "",
        val barcode: String? = null,
        val protein: Double = 0.0,
        val fat: Double = 0.0,
        val carbs: Double = 0.0,
        val servingG: Double = 0.0,
        val photoPath: String? = null,
        val source: String = "manual",
        val favorite: Boolean = false
    ) {
        val kcal: Int get() = (protein * 4 + fat * 9 + carbs * 4).toInt()
    }

    data class CustomItem(
        val id: String,
        val name: String,
        val protein: Double = 0.0,
        val fat: Double = 0.0,
        val carbs: Double = 0.0,
        val servingG: Double = 0.0,
        val photoPath: String? = null,
        val favorite: Boolean = false
    ) {
        val kcal: Int get() = (protein * 4 + fat * 9 + carbs * 4).toInt()
    }

    data class Ingredient(
        val kind: Kind,
        val refId: String,
        val grams: Double
    ) {
        val name: String? = null  // заполняется в join
    }

    data class Dish(
        val id: String,
        val name: String,
        val servingG: Double = 100.0,
        val photoPath: String? = null,
        val favorite: Boolean = false,
        val ingredients: List<Ingredient> = emptyList()
    ) {
        val totalGrams: Double get() = ingredients.sumOf { it.grams }.coerceAtLeast(1.0)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE products (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, brand TEXT NOT NULL DEFAULT '',
                barcode TEXT, protein REAL NOT NULL DEFAULT 0, fat REAL NOT NULL DEFAULT 0,
                carbs REAL NOT NULL DEFAULT 0, serving_g REAL NOT NULL DEFAULT 0,
                photo_path TEXT, source TEXT NOT NULL DEFAULT 'manual',
                favorite INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE custom_items (
                id TEXT PRIMARY KEY, name TEXT NOT NULL,
                protein REAL NOT NULL DEFAULT 0, fat REAL NOT NULL DEFAULT 0,
                carbs REAL NOT NULL DEFAULT 0, serving_g REAL NOT NULL DEFAULT 0,
                photo_path TEXT,
                favorite INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE dishes (
                id TEXT PRIMARY KEY, name TEXT NOT NULL,
                serving_g REAL NOT NULL DEFAULT 100, photo_path TEXT,
                favorite INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE dish_ingredients (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                dish_id TEXT NOT NULL,
                ingredient_kind TEXT NOT NULL,
                ingredient_id TEXT NOT NULL,
                grams REAL NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_ingredients_dish ON dish_ingredients(dish_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v2: избранное для продуктов и своих записей (для сортировки)
            db.execSQL("ALTER TABLE products ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE custom_items ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            // v3: избранное для блюд
            db.execSQL("ALTER TABLE dishes ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
        }
    }

    // ────────── Миграция из устаревшего JSON-стора ──────────
    fun migrateFromLegacyJson(ctx: Context) {
        val legacy = File(ctx.filesDir, "nutrition_products.json")
        if (!legacy.exists()) return
        val wdb = writableDatabase
        val cur = wdb.rawQuery("SELECT COUNT(*) FROM products", null)
        cur.moveToFirst()
        val count = cur.getInt(0)
        cur.close()
        if (count > 0) {
            legacy.delete()
            return
        }
        runCatching {
            val arr = JSONArray(legacy.readText())
            val now = System.currentTimeMillis()
            wdb.beginTransaction()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val nm = o.optString("name")
                if (nm.isBlank()) continue
                val cv = ContentValues().apply {
                    put("id", o.optString("id", UUID.randomUUID().toString()))
                    put("name", nm)
                    put("brand", o.optString("brand"))
                    put("protein", o.optInt("protein").toDouble())
                    put("fat", o.optInt("fat").toDouble())
                    put("carbs", o.optInt("carbs").toDouble())
                    put("serving_g", 0.0)
                    put("photo_path", o.optString("photoPath").ifBlank { null })
                    put("source", "legacy")
                    put("created_at", now - (arr.length() - i) * 1000L)
                }
                wdb.insert("products", null, cv)
            }
            wdb.setTransactionSuccessful()
        }
        wdb.endTransaction()
        legacy.delete()
    }

    // ────────── Products ──────────
    fun listProducts(): MutableList<Product> {
        val r = readableDatabase.rawQuery(
            "SELECT id,name,brand,barcode,protein,fat,carbs,serving_g,photo_path,source,favorite FROM products ORDER BY favorite DESC, created_at DESC",
            null
        )
        val out = mutableListOf<Product>()
        r.use { c -> while (c.moveToNext()) out += Product(
            id = c.getString(0), name = c.getString(1), brand = c.getString(2) ?: "",
            barcode = c.getString(3), protein = c.getDouble(4), fat = c.getDouble(5),
            carbs = c.getDouble(6), servingG = c.getDouble(7),
            photoPath = c.getString(8), source = c.getString(9) ?: "manual",
            favorite = c.getInt(10) != 0
        ) }
        return out
    }

    fun upsertProduct(p: Product) {
        val cv = ContentValues().apply {
            put("id", p.id)
            put("name", p.name)
            put("brand", p.brand)
            put("barcode", p.barcode)
            put("protein", p.protein)
            put("fat", p.fat)
            put("carbs", p.carbs)
            put("serving_g", p.servingG)
            put("photo_path", p.photoPath)
            put("source", p.source)
            put("favorite", if (p.favorite) 1 else 0)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("products", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteProduct(id: String) {
        writableDatabase.delete("products", "id=?", arrayOf(id))
        // удалить зависимости из блюд
        writableDatabase.delete("dish_ingredients", "ingredient_kind=? AND ingredient_id=?",
            arrayOf("product", id))
    }

    fun setProductFavorite(id: String, favorite: Boolean) {
        val cv = ContentValues().apply { put("favorite", if (favorite) 1 else 0) }
        writableDatabase.update("products", cv, "id=?", arrayOf(id))
    }

    fun findProductByBarcode(barcode: String): Product? {
        val r = readableDatabase.rawQuery(
            "SELECT id,name,brand,barcode,protein,fat,carbs,serving_g,photo_path,source,favorite FROM products WHERE barcode=? LIMIT 1",
            arrayOf(barcode)
        )
        r.use { c -> if (c.moveToFirst()) return Product(
            id = c.getString(0), name = c.getString(1), brand = c.getString(2) ?: "",
            barcode = c.getString(3), protein = c.getDouble(4), fat = c.getDouble(5),
            carbs = c.getDouble(6), servingG = c.getDouble(7),
            photoPath = c.getString(8), source = c.getString(9) ?: "manual",
            favorite = c.getInt(10) != 0
        ) }
        return null
    }

    // ────────── CustomItems ──────────
    fun listCustomItems(): MutableList<CustomItem> {
        val r = readableDatabase.rawQuery(
            "SELECT id,name,protein,fat,carbs,serving_g,photo_path,favorite FROM custom_items ORDER BY favorite DESC, created_at DESC",
            null
        )
        val out = mutableListOf<CustomItem>()
        r.use { c -> while (c.moveToNext()) out += CustomItem(
            id = c.getString(0), name = c.getString(1), protein = c.getDouble(2),
            fat = c.getDouble(3), carbs = c.getDouble(4), servingG = c.getDouble(5),
            photoPath = c.getString(6), favorite = c.getInt(7) != 0
        ) }
        return out
    }

    fun upsertCustomItem(it: CustomItem) {
        val cv = ContentValues().apply {
            put("id", it.id); put("name", it.name)
            put("protein", it.protein); put("fat", it.fat); put("carbs", it.carbs)
            put("serving_g", it.servingG); put("photo_path", it.photoPath)
            put("favorite", if (it.favorite) 1 else 0)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("custom_items", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteCustomItem(id: String) {
        writableDatabase.delete("custom_items", "id=?", arrayOf(id))
        writableDatabase.delete("dish_ingredients", "ingredient_kind=? AND ingredient_id=?",
            arrayOf("custom", id))
    }

    fun setCustomItemFavorite(id: String, favorite: Boolean) {
        val cv = ContentValues().apply { put("favorite", if (favorite) 1 else 0) }
        writableDatabase.update("custom_items", cv, "id=?", arrayOf(id))
    }

    // ────────── Dishes ──────────
    fun listDishes(): MutableList<Dish> {
        val r = readableDatabase.rawQuery(
            "SELECT id,name,serving_g,photo_path,favorite FROM dishes ORDER BY favorite DESC, created_at DESC",
            null
        )
        val out = mutableListOf<Dish>()
        r.use { c -> while (c.moveToNext()) {
            val id = c.getString(0)
            out += Dish(
                id = id, name = c.getString(1), servingG = c.getDouble(2),
                photoPath = c.getString(3), favorite = c.getInt(4) != 0,
                ingredients = listIngredients(id)
            )
        } }
        return out
    }

    fun upsertDish(d: Dish) {
        val cv = ContentValues().apply {
            put("id", d.id); put("name", d.name)
            put("serving_g", d.servingG); put("photo_path", d.photoPath)
            put("favorite", if (d.favorite) 1 else 0)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("dishes", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        writableDatabase.delete("dish_ingredients", "dish_id=?", arrayOf(d.id))
        for (i in d.ingredients) {
            val icv = ContentValues().apply {
                put("dish_id", d.id)
                put("ingredient_kind", i.kind.name.lowercase())
                put("ingredient_id", i.refId)
                put("grams", i.grams)
            }
            writableDatabase.insert("dish_ingredients", null, icv)
        }
    }

    fun deleteDish(id: String) {
        writableDatabase.delete("dishes", "id=?", arrayOf(id))
        writableDatabase.delete("dish_ingredients", "dish_id=?", arrayOf(id))
    }

    fun setDishFavorite(id: String, favorite: Boolean) {
        val cv = ContentValues().apply { put("favorite", if (favorite) 1 else 0) }
        writableDatabase.update("dishes", cv, "id=?", arrayOf(id))
    }

    fun listIngredients(dishId: String): List<Ingredient> {
        val r = readableDatabase.rawQuery(
            "SELECT ingredient_kind, ingredient_id, grams FROM dish_ingredients WHERE dish_id=?",
            arrayOf(dishId)
        )
        val out = mutableListOf<Ingredient>()
        r.use { c -> while (c.moveToNext()) {
            out += Ingredient(
                kind = if (c.getString(0) == "product") Kind.PRODUCT else Kind.CUSTOM,
                refId = c.getString(1), grams = c.getDouble(2)
            )
        } }
        return out
    }

    /** Возвращает (protein, fat, carbs) на 100 г продукта/кастомной записи или null. */
    fun macrosFor(kind: Kind, refId: String): Triple<Double, Double, Double>? {
        val table = if (kind == Kind.PRODUCT) "products" else "custom_items"
        val r = readableDatabase.rawQuery(
            "SELECT protein,fat,carbs FROM $table WHERE id=?", arrayOf(refId))
        r.use { c -> if (c.moveToFirst()) return Triple(c.getDouble(0), c.getDouble(1), c.getDouble(2)) }
        return null
    }

    /** БЖУ на 100 г готового блюда. */
    fun dishMacrosPer100(dish: Dish): MacrosPer100 {
        val totalG = dish.totalGrams
        var p = 0.0; var f = 0.0; var c = 0.0
        for (i in dish.ingredients) {
            val m = macrosFor(i.kind, i.refId) ?: continue
            p += m.first * i.grams / 100.0
            f += m.second * i.grams / 100.0
            c += m.third * i.grams / 100.0
        }
        val p100 = p * 100.0 / totalG
        val f100 = f * 100.0 / totalG
        val c100 = c * 100.0 / totalG
        return MacrosPer100(p100, f100, c100, (p100 * 4 + f100 * 9 + c100 * 4).toInt())
    }

    data class MacrosPer100(val protein: Double, val fat: Double, val carbs: Double, val kcal: Int)

    /** Подгружает имя ингредиента по (kind, refId). */
    fun nameFor(kind: Kind, refId: String): String {
        val table = if (kind == Kind.PRODUCT) "products" else "custom_items"
        val r = readableDatabase.rawQuery("SELECT name FROM $table WHERE id=?", arrayOf(refId))
        r.use { c -> if (c.moveToFirst()) return c.getString(0) ?: "?" }
        return "?"
    }

    /** Подгружает путь к фото ингредиента по (kind, refId). */
    fun photoFor(kind: Kind, refId: String): String? {
        val table = if (kind == Kind.PRODUCT) "products" else "custom_items"
        val r = readableDatabase.rawQuery("SELECT photo_path FROM $table WHERE id=?", arrayOf(refId))
        r.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }

    companion object {
        private const val NAME = "nutrition.db"
        private const val VERSION = 3
    }
}
