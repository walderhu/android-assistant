package com.assistant.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Парсит ответ нейросети и управляет дневником питания. */
object NutritionFoodLogger {

    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    val LOG_INSTRUCTIONS = """
Дополнительно: веди дневник питания пользователя. Оцени порции и КБЖУ, ответь кратко по-русски.
В конце КАЖДОГО ответа — одна строка (скрытый блок для приложения):
LOG: {"ops":[...]}

Даты: поле date / from_date / to_date — yyyy-MM-dd, или today|сегодня, yesterday|вчера (от выбранного дня в контексте).

Операции ops (можно несколько за раз):
- {"op":"add","meal":"Завтрак","name":"...","grams":100,"kcal":0,"protein":0,"fat":0,"carbs":0}
- {"op":"remove","meal":"Ужин","match":"рис"} — убрать запись (match = часть названия)
- {"op":"update","meal":"Ужин","match":"рис","name":"...","grams":...,"kcal":...,"protein":...,"fat":...,"carbs":...}
- {"op":"move","from_meal":"Ужин","to_meal":"Завтрак","match":"курица"} — перенести между приёмами (тот же день, если date не указан)
- {"op":"move","from_date":"today","from_meal":"Ужин","to_date":"today","to_meal":"Завтрак","match":"..."}
- {"op":"copy_day","from_date":"yesterday","to_date":"today"} — скопировать ВСЕ приёмы вчера → сегодня (merge)
- {"op":"copy_day","from_date":"yesterday","to_date":"today","merge":false} — заменить сегодняшние приёмы вчерашними
- {"op":"copy_meal","from_date":"yesterday","from_meal":"Завтрак","to_meal":"Завтрак"} — один приём вчера → сегодня
- {"op":"clear","meal":"Ужин"} — очистить приём
- {"op":"create_dish","name":"Салат","ingredients":[{"name":"огурец","grams":100,"protein":0.7,"fat":0.1,"carbs":2.5,"kcal":15},...],"meal":"Обед","dish_grams":250}

Правила:
- meal: Завтрак|Обед|Ужин|Перекус или кастомный приём из дневника
- Если пользователь явно указал приём («в завтрак», «на обед», [Обед]) — используй его
- «перенеси X из ужина в завтрак» → move с match=X (смотри дневник выше)
- «как вчера» / «повтори вчерашнее» / «то же что ел вчера» → copy_day from_date=yesterday to_date=today
- «вчера на завтрак было X, добавь сегодня» → copy_meal или add по вчерашнему дневнику
- Исправления: «не X а Y» → update; «убери/не ел X» → remove
- Если еды/изменений нет — LOG: {"ops":[]}
Не пиши ничего после строки LOG.
""".trimIndent()

    private val LOG_RE = Regex("""LOG:\s*(\{.*\})\s*$""", RegexOption.DOT_MATCHES_ALL)

    data class ParsedItem(
        val meal: String,
        val name: String,
        val grams: Double,
        val kcal: Int,
        val protein: Double,
        val fat: Double,
        val carbs: Double
    )

    fun diaryContext(ctx: Context, dateKey: String): String =
        NutritionController.formatDiaryContext(ctx, dateKey)

    fun stripLogBlock(reply: String): Pair<String, JSONObject?> {
        val m = LOG_RE.find(reply.trim()) ?: return reply.trim() to null
        val visible = reply.replace(m.value, "").trim()
        val json = runCatching { JSONObject(m.groupValues[1]) }.getOrNull()
        return visible to json
    }

    fun apply(ctx: Context, dateKey: String, log: JSONObject?): Int {
        if (log == null) return 0
        var n = 0
        log.optJSONArray("ops")?.let { arr ->
            for (i in 0 until arr.length()) {
                n += applyOp(ctx, dateKey, arr.optJSONObject(i) ?: continue)
            }
        }
        log.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                n += applyOp(ctx, dateKey, o.put("op", "add"))
            }
        }
        return n
    }

    private fun applyOp(ctx: Context, baseDateKey: String, o: JSONObject): Int {
        return when (o.optString("op", "add")) {
            "add" -> applyAdd(ctx, baseDateKey, o)
            "remove" -> if (applyRemove(ctx, baseDateKey, o)) 1 else 0
            "update" -> if (applyUpdate(ctx, baseDateKey, o)) 1 else 0
            "move" -> if (applyMove(ctx, baseDateKey, o)) 1 else 0
            "copy_day" -> applyCopyDay(ctx, baseDateKey, o)
            "copy_meal" -> applyCopyMeal(ctx, baseDateKey, o)
            "clear" -> applyClear(ctx, baseDateKey, o)
            "create_dish" -> applyCreateDish(ctx, baseDateKey, o)
            else -> 0
        }
    }

    private fun resolveDateKey(baseDateKey: String, raw: String, fallback: String = baseDateKey): String {
        val t = raw.trim().lowercase()
        if (t.isBlank()) return fallback
        val base = runCatching { LocalDate.parse(baseDateKey) }.getOrDefault(LocalDate.now())
        return when (t) {
            "today", "сегодня" -> baseDateKey
            "yesterday", "вчера" -> base.minusDays(1).format(DATE_FMT)
            else -> raw.trim()
        }
    }

    private fun applyAdd(ctx: Context, baseDateKey: String, o: JSONObject): Int {
        val dateKey = resolveDateKey(baseDateKey, o.optString("date"))
        val item = parseItem(ctx, o) ?: return 0
        NutritionController.addMealItem(ctx, dateKey, item.meal, toMealItem(item))
        return 1
    }

    private fun applyRemove(ctx: Context, baseDateKey: String, o: JSONObject): Boolean {
        val dateKey = resolveDateKey(baseDateKey, o.optString("date"))
        val match = o.optString("match").ifBlank { o.optString("name") }
        if (match.isBlank()) return false
        val meal = o.optString("meal").trim()
        return if (meal.isNotBlank()) {
            val m = mealOf(ctx, meal, allowDefault = false) ?: return false
            NutritionController.removeMealItemByMatch(ctx, dateKey, m, match)
        } else {
            NutritionController.removeMealItemAnywhere(ctx, dateKey, match)
        }
    }

    private fun applyUpdate(ctx: Context, baseDateKey: String, o: JSONObject): Boolean {
        val dateKey = resolveDateKey(baseDateKey, o.optString("date"))
        val meal = mealOf(ctx, o.optString("meal"), allowDefault = false) ?: return false
        val match = o.optString("match").ifBlank { o.optString("name") }
        val item = parseItem(ctx, o, meal) ?: return false
        return NutritionController.updateMealItemByMatch(ctx, dateKey, meal, match, toMealItem(item))
    }

    private fun applyMove(ctx: Context, baseDateKey: String, o: JSONObject): Boolean {
        val fromDate = resolveDateKey(baseDateKey, o.optString("from_date", o.optString("date")))
        val toDate = resolveDateKey(baseDateKey, o.optString("to_date", o.optString("date")))
        val from = mealOf(ctx, o.optString("from_meal"), allowDefault = false) ?: return false
        val to = mealOf(ctx, o.optString("to_meal"), allowDefault = false) ?: return false
        val match = o.optString("match").ifBlank { o.optString("name") }
        if (match.isBlank()) return false
        return NutritionController.moveMealItemByMatch(ctx, fromDate, from, toDate, to, match)
    }

    private fun applyCopyDay(ctx: Context, baseDateKey: String, o: JSONObject): Int {
        val from = resolveDateKey(baseDateKey, o.optString("from_date", "yesterday"))
        val to = resolveDateKey(baseDateKey, o.optString("to_date", "today"))
        val merge = o.optBoolean("merge", true)
        return NutritionController.copyDayMeals(ctx, from, to, merge)
    }

    private fun applyCopyMeal(ctx: Context, baseDateKey: String, o: JSONObject): Int {
        val fromDate = resolveDateKey(baseDateKey, o.optString("from_date", "yesterday"))
        val toDate = resolveDateKey(baseDateKey, o.optString("to_date", "today"))
        val fromMeal = mealOf(ctx, o.optString("from_meal"), allowDefault = false) ?: return 0
        val toMeal = mealOf(ctx, o.optString("to_meal", o.optString("from_meal")), allowDefault = false)
            ?: return 0
        val merge = o.optBoolean("merge", true)
        return NutritionController.copyMealItems(ctx, fromDate, fromMeal, toDate, toMeal, merge)
    }

    private fun applyClear(ctx: Context, baseDateKey: String, o: JSONObject): Int {
        val dateKey = resolveDateKey(baseDateKey, o.optString("date"))
        val meal = mealOf(ctx, o.optString("meal"), allowDefault = false) ?: return 0
        NutritionController.clearMeal(ctx, dateKey, meal)
        return 1
    }

    private fun applyCreateDish(ctx: Context, baseDateKey: String, o: JSONObject): Int {
        val dateKey = resolveDateKey(baseDateKey, o.optString("date"))
        val dishName = o.optString("name").trim()
        if (dishName.isBlank()) return 0
        val arr = o.optJSONArray("ingredients") ?: return 0
        if (arr.length() == 0) return 0
        val db = NutritionDatabase(ctx)
        val ingredients = mutableListOf<NutritionDatabase.Ingredient>()
        for (i in 0 until arr.length()) {
            val ing = arr.optJSONObject(i) ?: continue
            resolveIngredient(ctx, db, ing)?.let { ingredients += it }
        }
        if (ingredients.isEmpty()) return 0
        val dishId = UUID.randomUUID().toString()
        val dish = NutritionDatabase.Dish(
            id = dishId, name = dishName, servingG = 100.0, ingredients = ingredients
        )
        db.upsertDish(dish)
        val meal = mealOf(ctx, o.optString("meal"), allowDefault = true) ?: return 1
        val dishGrams = o.optDouble("dish_grams", 100.0).coerceAtLeast(1.0)
        val macros = db.dishMacrosPer100(dish)
        val kcal = (dishGrams * macros.kcal / 100.0).toInt()
        if (kcal <= 0) return 1
        NutritionController.addMealItem(
            ctx, dateKey, meal,
            NutritionController.MealItem(
                name = dishName,
                grams = dishGrams,
                kcal = kcal,
                protein = dishGrams * macros.protein / 100.0,
                fat = dishGrams * macros.fat / 100.0,
                carbs = dishGrams * macros.carbs / 100.0
            )
        )
        return 1
    }

    private fun resolveIngredient(
        ctx: Context,
        db: NutritionDatabase,
        ing: JSONObject
    ): NutritionDatabase.Ingredient? {
        val name = ing.optString("name").trim()
        if (name.isBlank()) return null
        val grams = ing.optDouble("grams", 100.0).coerceAtLeast(1.0)
        db.listProducts().firstOrNull { fuzzy(name, it.name) }?.let {
            return NutritionDatabase.Ingredient(NutritionDatabase.Kind.PRODUCT, it.id, grams)
        }
        db.listCustomItems().firstOrNull { fuzzy(name, it.name) }?.let {
            return NutritionDatabase.Ingredient(NutritionDatabase.Kind.CUSTOM, it.id, grams)
        }
        val p = ing.optDouble("protein", 0.0)
        val f = ing.optDouble("fat", 0.0)
        val c = ing.optDouble("carbs", 0.0)
        val id = UUID.randomUUID().toString()
        db.upsertCustomItem(
            NutritionDatabase.CustomItem(id, name, p, f, c, 100.0)
        )
        return NutritionDatabase.Ingredient(NutritionDatabase.Kind.CUSTOM, id, grams)
    }

    private fun fuzzy(a: String, b: String): Boolean {
        val x = a.lowercase().trim()
        val y = b.lowercase().trim()
        return x == y || x.contains(y) || y.contains(x)
    }

    private fun parseItem(ctx: Context, o: JSONObject, mealOverride: String? = null): ParsedItem? {
        val name = o.optString("name").trim()
        if (name.isBlank()) return null
        val meal = mealOverride ?: mealOf(ctx, o.optString("meal"), allowDefault = true) ?: return null
        val grams = o.optDouble("grams", 100.0).coerceAtLeast(1.0)
        val kcal = o.optInt("kcal", 0)
        if (kcal <= 0) return null
        return ParsedItem(
            meal = meal,
            name = name,
            grams = grams,
            kcal = kcal,
            protein = o.optDouble("protein", 0.0),
            fat = o.optDouble("fat", 0.0),
            carbs = o.optDouble("carbs", 0.0)
        )
    }

    private fun toMealItem(p: ParsedItem) = NutritionController.MealItem(
        name = p.name, grams = p.grams, kcal = p.kcal,
        protein = p.protein, fat = p.fat, carbs = p.carbs
    )

    private fun mealOf(ctx: Context, raw: String, allowDefault: Boolean): String? {
        val meals = NutritionController.allMealNames(ctx)
        val t = raw.trim()
        if (t.isNotBlank()) {
            if (t in meals) return t
            val aliases = mapOf(
                "завтрак" to "Завтрак", "обед" to "Обед", "ужин" to "Ужин", "перекус" to "Перекус"
            )
            aliases[t.lowercase()]?.let { return it }
            meals.firstOrNull { fuzzy(t, it) }?.let { return it }
        }
        return if (allowDefault) {
            NutritionController.mealForHour(java.time.LocalTime.now().hour)
        } else null
    }

    fun dateKeyFrom(state: ChatRepository.State): String =
        state.selectedDate?.takeIf { it.isNotBlank() }
            ?: LocalDate.now().format(DATE_FMT)
}
