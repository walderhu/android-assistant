package com.assistant.app

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Button
import android.widget.EditText
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale

/**
 * Логика режима «Питание»: большая цифра остатка, компактные макросы,
 * приёмы пищи, форма параметров (4 колонки, граммы↔проценты с
 * автоконверсией). Хранилище в SharedPreferences("mode_params") → "nutrition".
 */
object NutritionController {

    /** Тег overlay-карточки в infoContainer (для системной кнопки «назад»). */
    const val CARD_TAG = "nutrition_card"

    data class Params(
        val kcalNorm: Int = 2000,
        val proteinPercent: Int = 18,
        val fatPercent: Int = 31,
        val carbsPercent: Int = 40,
        val waterMl: Int = 2000,
        val productLookupEnabled: Boolean = false,
        val shoppingListEnabled: Boolean = false
    ) {
        // Производные граммы из процентов и нормы
        val proteinG: Int get() = (kcalNorm * proteinPercent / 100.0 / 4).toInt()
        val fatG:     Int get() = (kcalNorm * fatPercent     / 100.0 / 9).toInt()
        val carbsG:   Int get() = (kcalNorm * carbsPercent   / 100.0 / 4).toInt()
    }

    private const val PREFS = "mode_params"
    private const val KEY = "nutrition"

    private const val CORAL = 0xFFFF5555.toInt()
    private const val TEXT_PRIMARY = 0xFFE6E6E6.toInt()
    private const val TEXT_HINT = 0xFF8A8A8A.toInt()
    private const val COLOR_PROTEIN = 0xFFF44336.toInt()   // red
    private const val COLOR_FAT = 0xFFFFC107.toInt()        // yellow
    private const val COLOR_CARBS = 0xFF2196F3.toInt()      // blue
    private const val COLOR_GREEN = 0xFF4CAF50.toInt()

    /** Продукт внутри приёма пищи (после добавления через picker). */
    data class MealItem(
        val name: String,
        val grams: Double,
        val kcal: Int,
        val protein: Double = 0.0,
        val fat: Double = 0.0,
        val carbs: Double = 0.0
    )

    /** Агрегированные данные приёма пищи за день. */
    data class MealData(
        val items: List<MealItem> = emptyList()
    ) {
        val kcal: Int get() = items.sumOf { it.kcal }
        val protein: Double get() = items.sumOf { it.protein }
        val fat: Double get() = items.sumOf { it.fat }
        val carbs: Double get() = items.sumOf { it.carbs }
    }

    fun load(ctx: Context): Params {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return Params()
        return runCatching {
            val o = JSONObject(raw)
            val kcal = o.optInt("kcalNorm", 2000)
            // бэк-совместимость: если есть старые *G, пересчитаем в проценты
            fun pctOrGrams(pctKey: String, gramsKey: String, default: Int, perGram: Int): Int {
                if (o.has(pctKey)) return o.optInt(pctKey, default)
                val grams = o.optInt(gramsKey, -1)
                if (grams < 0 || kcal <= 0) return default
                return (grams * perGram * 100.0 / kcal).toInt()
            }
            Params(
                kcalNorm = kcal,
                proteinPercent = pctOrGrams("proteinPercent", "proteinG", 18, 4),
                fatPercent     = pctOrGrams("fatPercent",     "fatG",     31, 9),
                carbsPercent   = pctOrGrams("carbsPercent",   "carbsG",   40, 4),
                waterMl        = o.optInt("waterMl", 2000),
                productLookupEnabled = o.optBoolean("productLookupEnabled", false),
                shoppingListEnabled = o.optBoolean("shoppingListEnabled", false)
            )
        }.getOrElse { Params() }
    }

    fun save(ctx: Context, p: Params) {
        val json = JSONObject().apply {
            put("kcalNorm", p.kcalNorm)
            put("proteinPercent", p.proteinPercent)
            put("fatPercent", p.fatPercent)
            put("carbsPercent", p.carbsPercent)
            put("waterMl", p.waterMl)
            put("productLookupEnabled", p.productLookupEnabled)
            put("shoppingListEnabled", p.shoppingListEnabled)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, json.toString())
            .apply()
    }

    /**
     * Заполняет контейнер содержимым информационного таба «Питание».
     * @param onOpenProducts тап по ссылке «База данных» → переход на вкладку База
     */
    fun renderInfo(
        ctx: Context,
        content: LinearLayout,
        selectedDate: LocalDate,
        activeKcal: Double,
        onMealClick: (String) -> Unit,
        onCaloriesClick: () -> Unit,
        onDateChange: (LocalDate) -> Unit,
        onOpenProducts: () -> Unit,
        onPickPhoto: (((Uri?) -> Unit) -> Unit)? = null
    ) {
        val p = load(ctx)
        val d = ctx.resources.displayMetrics.density
        content.removeAllViews()

        val dateKey = selectedDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val today = LocalDate.now()
        val minDate = LocalDate.of(2026, 1, 1)
        val isToday = selectedDate == today
        val isMin = !selectedDate.isAfter(minDate)

        // 0. Дата — кликабельная подпись; свайп влево/вправо на ней = ±1 день
        val dateLabel = TextView(ctx).apply {
            text = if (isToday) "сегодня · ${formatDateRu(selectedDate)}"
                else formatDateRu(selectedDate)
            setTextColor(TEXT_PRIMARY)
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            setBackgroundColor(0x00000000)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showDatePicker(ctx, selectedDate, minDate, today) { picked -> onDateChange(picked) }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        content.addView(dateLabel)

        // 1. Большая цифра «осталось» + зелёная подпись «ккал»
        val totals = dayTotals(ctx, dateKey)
        val consumed = totals.kcal
        val totalBudget = p.kcalNorm + activeKcal.toInt()
        val remaining = totalBudget - consumed
        val bigCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { onCaloriesClick() }
            val pad = (12 * d).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val bigText = TextView(ctx).apply {
            text = remaining.toString()
            textSize = 72f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (remaining < 0) 0xFFF44336.toInt() else 0xFF4CAF50.toInt())
            gravity = android.view.Gravity.CENTER
        }
        bigCard.addView(bigText)
        val kcalLabel = TextView(ctx).apply {
            text = "можно ещё съесть, ккал"
            setTextColor(0xFF4CAF50.toInt())
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, (4 * d).toInt())
        }
        bigCard.addView(kcalLabel)
        // Подпись: «норма · сожжено · съедено»
        val breakdown = TextView(ctx).apply {
            text = "норма ${p.kcalNorm}  ·  сожжено ${activeKcal.toInt()}  ·  съедено $consumed"
            setTextColor(TEXT_HINT)
            textSize = 11f
            gravity = android.view.Gravity.CENTER
        }
        bigCard.addView(breakdown)
        content.addView(bigCard)

        // 2. Крупные макросы: label + value, цвета red / yellow / blue
        val macrosRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * d).toInt() }
        }
        data class M(val label: String, val current: Double, val total: Int, val color: Int)
        listOf(
            M("Белки", totals.protein, p.proteinG, COLOR_PROTEIN),
            M("Жиры", totals.fat, p.fatG, COLOR_FAT),
            M("Углеводы", totals.carbs, p.carbsG, COLOR_CARBS)
        ).forEach { m ->
            val pct = if (m.total > 0) m.current / m.total * 100.0 else 0.0
            val over = pct > 100.0
            val pctRounded = (kotlin.math.round(pct.coerceAtLeast(0.0) * 10.0) / 10.0)
            val pctText = if (pctRounded % 1.0 == 0.0) pctRounded.toInt().toString()
                          else "%.1f".format(pctRounded)
            val valueColor = if (over) 0xFFF44336.toInt() else TEXT_PRIMARY
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(
                    (10 * d).toInt(), (10 * d).toInt(),
                    (10 * d).toInt(), (10 * d).toInt()
                )
                setBackgroundResource(R.drawable.card_bg)
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { setMargins((4 * d).toInt(), 0, (4 * d).toInt(), 0) }
            }
            val tvLabel = TextView(ctx).apply {
                text = m.label
                setTextColor(m.color)
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
            }
            val tvValue = TextView(ctx).apply {
                text = "${fmtNum(m.current)} / ${m.total} г ($pctText%)"
                setTextColor(valueColor)
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            card.addView(tvLabel)
            card.addView(tvValue)

            // Прогресс-бар: 4dp трек + цветной fill, scaleX от 0..1 (cap=100%).
            // Pivot слева, clipChildren=true у трека обрезает переполнение.
            val trackHeight = (4 * d).toInt()
            val track = android.widget.FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    trackHeight
                ).apply { topMargin = (6 * d).toInt() }
                setBackgroundColor(0xFF2B2B2B.toInt())
                clipChildren = true
            }
            val fillScale = (pct.coerceIn(0.0, 100.0) / 100.0).toFloat()
            val fill = View(ctx).apply {
                setBackgroundColor(m.color)
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                pivotX = 0f
                scaleX = fillScale
            }
            track.addView(fill)
            if (fillScale > 0f) {
                fill.scaleX = 0f
                fill.post {
                    fill.animate().scaleX(fillScale).setDuration(400)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
            }
            card.addView(track)
            macrosRow.addView(card)
        }
        content.addView(macrosRow)

        // 3. Зелёная кнопка «Добавить приём пищи» — открывает product picker
        //    с пред-выбранным приёмом по времени суток; приём пищи можно сменить
        //    чипами внутри showAddToMealSheet.
        val now = java.time.LocalTime.now().hour
        val suggestedMeal = mealForHour(now)
        val refreshFromTopBtn = { onDateChange(selectedDate) }
        val addMealBtn = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showProductPickerForMeal(ctx, suggestedMeal, dateKey) { refreshFromTopBtn() }
            }
            val pad = (14 * d).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (20 * d).toInt() }
        }
        addMealBtn.setOnClickListener {
            showProductPickerForMeal(ctx, suggestedMeal, dateKey) { refreshFromTopBtn() }
        }
        addMealBtn.addView(TextView(ctx).apply {
            text = "＋"
            setTextColor(0xFF4CAF50.toInt())
            textSize = 24f
            setPadding(0, 0, (12 * d).toInt(), 0)
        })
        addMealBtn.addView(TextView(ctx).apply {
            text = "Добавить приём пищи"
            setTextColor(0xFF4CAF50.toInt())
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            // без второго TextView с «· $suggestedMeal» — дубль. Юзер увидит
            // название приёма в поле ввода после тапа.
        })
        content.addView(addMealBtn)

        // 4. Заголовок «Приёмы пищи» + справа зелёная гиперссылка «База данных»
        val mealsHeader = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (16 * d).toInt(), 0, (8 * d).toInt())
        }
        mealsHeader.addView(TextView(ctx).apply {
            text = "ПРИЁМЫ ПИЩИ"
            setTextColor(TEXT_HINT)
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        mealsHeader.addView(TextView(ctx).apply {
            text = "База данных"
            setTextColor(0xFF4CAF50.toInt())
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding((12 * d).toInt(), 0, 0, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { onOpenProducts() }
        })
        content.addView(mealsHeader)

        // 4. Список приёмов пищи — раскрывающиеся карточки с продуктами внутри
        val inflater = android.view.LayoutInflater.from(ctx)
        val refresh = { onDateChange(selectedDate) }
        val allMeals = DEFAULT_MEALS + loadCustomMeals(ctx)
        allMeals.forEach { name ->
            val isCustom = name !in DEFAULT_MEALS
            renderMealCard(ctx, content, inflater, name, dateKey,
                showCircle = isCustom,
                onLongPressDelete = if (isCustom) {
                    { ctx -> removeCustomMeal(ctx, name) }
                } else null,
                refresh = refresh)
        }

        // 5. Прогресс за 30 дней — временно скрыт по запросу
        // renderComplianceGraph(ctx, content, p, dateKey)

        // 6. Crash-лог вынесен в боковое меню (см. activity_main.xml → btnCrashLog).
    }

    private val DEFAULT_MEALS = listOf("Завтрак", "Обед", "Ужин", "Перекус")

    /** Список кастомных приёмов пищи (добавляются юзером). */
    fun loadCustomMeals(ctx: Context): MutableList<String> {
        val raw = progressPrefs(ctx).getString("custom_meals", "[]") ?: "[]"
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it, "") }
                .filter { it.isNotBlank() }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun saveCustomMeals(ctx: Context, names: List<String>) {
        val arr = org.json.JSONArray()
        names.forEach { arr.put(it) }
        progressPrefs(ctx).edit().putString("custom_meals", arr.toString()).apply()
    }

    fun addCustomMeal(ctx: Context, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || DEFAULT_MEALS.contains(trimmed)) return
        val list = loadCustomMeals(ctx)
        if (!list.contains(trimmed)) {
            list.add(trimmed)
            saveCustomMeals(ctx, list)
        }
    }

    fun removeCustomMeal(ctx: Context, name: String) {
        val list = loadCustomMeals(ctx)
        if (list.remove(name)) saveCustomMeals(ctx, list)
    }

    private fun formatDateRu(d: LocalDate): String {
        val day = d.dayOfMonth
        // месяц в нижнем регистре («3 июня 2026»)
        val month = d.month.getDisplayName(TextStyle.FULL, Locale("ru"))
        return "$day $month ${d.year}"
    }

    /** Стандартный DatePickerDialog с min=2026-01-01, max=сегодня. */
    fun showDatePicker(
        ctx: Context,
        initial: LocalDate,
        minDate: LocalDate,
        maxDate: LocalDate,
        onPicked: (LocalDate) -> Unit
    ) {
        val dialog = android.app.DatePickerDialog(
            ctx,
            { _, year, month, dayOfMonth ->
                val picked = runCatching { LocalDate.of(year, month + 1, dayOfMonth) }.getOrNull() ?: return@DatePickerDialog
                onPicked(picked)
            },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth
        )
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = maxDate.toEpochDay() * 24L * 60 * 60 * 1000
        dialog.datePicker.maxDate = cal.timeInMillis
        cal.timeInMillis = minDate.toEpochDay() * 24L * 60 * 60 * 1000
        dialog.datePicker.minDate = cal.timeInMillis
        dialog.show()
    }

    /** Приём пищи по текущему часу (0..23):
     *  6-9 — Завтрак, 10-13 — Обед, 14-17 — Перекус, 18-5 — Ужин. */
    fun mealForHour(hour: Int): String = when (hour) {
        in 6..9 -> "Завтрак"
        in 10..13 -> "Обед"
        in 14..17 -> "Перекус"
        else -> "Ужин"
    }

    private fun progressPrefs(ctx: Context) =
        ctx.getSharedPreferences("nutrition_progress", Context.MODE_PRIVATE)

    private fun dayKey(calendar: Calendar = Calendar.getInstance()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)

    private fun loadDailyKcal(ctx: Context): MutableMap<String, Int> {
        val raw = progressPrefs(ctx).getString("daily_kcal", "{}") ?: "{}"
        return runCatching {
            val o = JSONObject(raw)
            val out = mutableMapOf<String, Int>()
            o.keys().forEach { key ->
                val value = o.optInt(key, -1)
                if (value >= 0) out[key] = value
            }
            out
        }.getOrDefault(mutableMapOf())
    }

    private fun saveDailyKcal(ctx: Context, values: Map<String, Int>) {
        val o = JSONObject()
        values.forEach { (key, value) -> o.put(key, value) }
        progressPrefs(ctx).edit().putString("daily_kcal", o.toString()).apply()
    }

    /** Map<dateKey, Map<mealName, kcal>> — съедено по приёмам пищи за день. */
    fun loadMealKcal(ctx: Context): MutableMap<String, MutableMap<String, Int>> {
        val raw = progressPrefs(ctx).getString("meal_kcal", "{}") ?: "{}"
        return runCatching {
            val o = JSONObject(raw)
            val out = mutableMapOf<String, MutableMap<String, Int>>()
            o.keys().forEach { day ->
                val m = o.optJSONObject(day) ?: return@forEach
                val inner = mutableMapOf<String, Int>()
                m.keys().forEach { meal ->
                    val v = m.optInt(meal, -1)
                    if (v >= 0) inner[meal] = v
                }
                if (inner.isNotEmpty()) out[day] = inner
            }
            out
        }.getOrDefault(mutableMapOf())
    }

    private fun saveMealKcal(ctx: Context, values: Map<String, Map<String, Int>>) {
        val o = JSONObject()
        values.forEach { (day, meals) ->
            val inner = JSONObject()
            meals.forEach { (k, v) -> inner.put(k, v) }
            o.put(day, inner)
        }
        progressPrefs(ctx).edit().putString("meal_kcal", o.toString()).apply()
    }

    fun addMealKcal(ctx: Context, dateKey: String, meal: String, kcal: Int) {
        val all = loadMealKcal(ctx)
        val day = all.getOrPut(dateKey) { mutableMapOf() }
        day[meal] = (day[meal] ?: 0) + kcal
        saveMealKcal(ctx, all)
    }

    /** Map<dateKey, Map<mealName, MealData>> — полные данные приёмов пищи со списком продуктов. */
    fun loadMealData(ctx: Context): MutableMap<String, MutableMap<String, MealData>> {
        val raw = progressPrefs(ctx).getString("meal_data", "{}") ?: "{}"
        return runCatching {
            val o = org.json.JSONObject(raw)
            val out = mutableMapOf<String, MutableMap<String, MealData>>()
            o.keys().forEach { day ->
                val m = o.optJSONObject(day) ?: return@forEach
                val inner = mutableMapOf<String, MealData>()
                m.keys().forEach { meal ->
                    val v = m.optJSONObject(meal) ?: return@forEach
                    val itemsArr = v.optJSONArray("items") ?: org.json.JSONArray()
                    val items = (0 until itemsArr.length()).map { i ->
                        val it = itemsArr.optJSONObject(i) ?: org.json.JSONObject()
                        MealItem(
                            name = it.optString("name", ""),
                            grams = it.optDouble("g", 0.0),
                            kcal = it.optInt("kcal", 0),
                            protein = it.optDouble("p", 0.0),
                            fat = it.optDouble("f", 0.0),
                            carbs = it.optDouble("c", 0.0)
                        )
                    }
                    inner[meal] = MealData(items = items)
                }
                if (inner.isNotEmpty()) out[day] = inner
            }
            out
        }.getOrDefault(mutableMapOf())
    }

    private fun saveMealData(ctx: Context, data: Map<String, Map<String, MealData>>) {
        val o = org.json.JSONObject()
        data.forEach { (day, meals) ->
            val inner = org.json.JSONObject()
            meals.forEach { (meal, md) ->
                val arr = org.json.JSONArray()
                md.items.forEach { it ->
                    arr.put(org.json.JSONObject().apply {
                        put("name", it.name)
                        put("g", it.grams)
                        put("kcal", it.kcal)
                        put("p", it.protein)
                        put("f", it.fat)
                        put("c", it.carbs)
                    })
                }
                inner.put(meal, org.json.JSONObject().apply { put("items", arr) })
            }
            o.put(day, inner)
        }
        progressPrefs(ctx).edit().putString("meal_data", o.toString()).apply()
    }

    /** Добавить продукт в приём пищи. */
    fun addMealItem(ctx: Context, dateKey: String, meal: String, item: MealItem) {
        val all = loadMealData(ctx)
        val day = all.getOrPut(dateKey) { mutableMapOf() }
        val current = day[meal] ?: MealData()
        day[meal] = current.copy(items = current.items + item)
        saveMealData(ctx, all)
    }

    /** Получить данные приёма пищи (или пустые). */
    fun getMealData(ctx: Context, dateKey: String, meal: String): MealData =
        loadMealData(ctx)[dateKey]?.get(meal) ?: MealData()

    /** Агрегированные суммы за день по всем приёмам пищи (с учётом items в meal_data). */
    fun dayTotals(ctx: Context, dateKey: String): DayTotals {
        val data = loadMealData(ctx)[dateKey]
        if (data != null && data.values.any { it.items.isNotEmpty() }) {
            return DayTotals(
                kcal = data.values.sumOf { it.kcal },
                protein = data.values.sumOf { it.protein },
                fat = data.values.sumOf { it.fat },
                carbs = data.values.sumOf { it.carbs }
            )
        }
        val kcal = loadMealKcal(ctx)[dateKey]?.values?.sum() ?: 0
        return DayTotals(kcal = kcal, protein = 0.0, fat = 0.0, carbs = 0.0)
    }

    data class DayTotals(val kcal: Int, val protein: Double, val fat: Double, val carbs: Double)

    fun setMealKcal(ctx: Context, dateKey: String, meal: String, kcal: Int) {
        val all = loadMealKcal(ctx)
        val day = all.getOrPut(dateKey) { mutableMapOf() }
        if (kcal <= 0) day.remove(meal) else day[meal] = kcal
        saveMealKcal(ctx, all)
    }

    fun clearDayKcal(ctx: Context, dateKey: String) {
        val all = loadMealKcal(ctx)
        all.remove(dateKey)
        saveMealKcal(ctx, all)
    }

    /** Диалог «Выбери вес порции и добавь к приёму пищи» (для продукта). */
    fun showAddProductToMeal(
        ctx: Context,
        product: NutritionDatabase.Product,
        suggestedMeal: String,
        dateKey: String = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")),
        onAdded: () -> Unit = {}
    ) {
        showAddToMealSheet(
            ctx = ctx,
            title = product.name + if (product.brand.isBlank()) "" else " · ${product.brand}",
            kcal100 = product.kcal.toDouble(),
            protein100 = product.protein,
            fat100 = product.fat,
            carbs100 = product.carbs,
            defaultG = if (product.servingG > 0) product.servingG else 100.0,
            suggestedMeal = suggestedMeal,
            dateKey = dateKey,
            onAdded = onAdded
        )
    }

    /** Диалог «Выбери вес порции и добавь к приёму пищи» (для блюда). */
    fun showAddDishToMeal(
        ctx: Context,
        dish: NutritionDatabase.Dish,
        macrosPer100: NutritionDatabase.MacrosPer100,
        suggestedMeal: String,
        dateKey: String = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")),
        onAdded: () -> Unit = {}
    ) {
        showAddToMealSheet(
            ctx = ctx,
            title = dish.name,
            kcal100 = macrosPer100.kcal.toDouble(),
            protein100 = macrosPer100.protein,
            fat100 = macrosPer100.fat,
            carbs100 = macrosPer100.carbs,
            defaultG = 100.0,
            suggestedMeal = suggestedMeal,
            dateKey = dateKey,
            onAdded = onAdded
        )
    }

    private fun showAddToMealSheet(
        ctx: Context,
        title: String,
        kcal100: Double,
        protein100: Double,
        fat100: Double,
        carbs100: Double,
        defaultG: Double,
        suggestedMeal: String,
        dateKey: String,
        onAdded: () -> Unit
    ) {
        val d = ctx.resources.displayMetrics.density
        val pad = (16 * d).toInt()

        val weightField = android.widget.EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (defaultG % 1.0 == 0.0) defaultG.toInt().toString() else "%.1f".format(defaultG))
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_HINT)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(pad / 2, pad, pad / 2, pad)
            setSelection(text.length)
        }
        val totalKcalView = TextView(ctx).apply {
            setTextColor(0xFF4CAF50.toInt())
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.END
        }
        val macrosView = TextView(ctx).apply {
            setTextColor(TEXT_HINT)
            textSize = 12f
            gravity = Gravity.END
        }
        fun recalc() {
            val g = weightField.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            val k = (g * kcal100 / 100.0).toInt()
            val p = g * protein100 / 100.0
            val f = g * fat100 / 100.0
            val c = g * carbs100 / 100.0
            totalKcalView.text = "$k ккал"
            macrosView.text = "Б ${fmtNum(p)} · Ж ${fmtNum(f)} · У ${fmtNum(c)}"
        }
        recalc()
        weightField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = recalc()
        })

        val chipsRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val mealChips = if (loadCustomMeals(ctx).isNotEmpty()) {
            android.widget.HorizontalScrollView(ctx).apply {
                addView(chipsRow)
                isHorizontalScrollBarEnabled = false
            }
        } else chipsRow
        fun chipBg(active: Boolean): android.graphics.drawable.GradientDrawable =
            android.graphics.drawable.GradientDrawable().apply {
                setColor(if (active) 0xFF4CAF50.toInt() else 0xFF1F1F1F.toInt())
                cornerRadius = 20f * d
            }
        val chipBgChecked = android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, (36 * d).toInt()
        ).apply { setMargins(0, 0, (8 * d).toInt(), 0) }
        val meals = listOf("Завтрак", "Обед", "Ужин", "Перекус") + loadCustomMeals(ctx)
        var selectedMeal = if (meals.contains(suggestedMeal)) suggestedMeal else meals.first()
        val chipViews = mutableListOf<TextView>()
        for (m in meals) {
            val isActive = m == selectedMeal
            val tv = TextView(ctx).apply {
                text = m
                setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
                gravity = Gravity.CENTER
                textSize = 13f
                background = chipBg(isActive)
                setTextColor(if (isActive) 0xFF0F0F0F.toInt() else TEXT_PRIMARY)
                setTypeface(null, android.graphics.Typeface.BOLD)
                isClickable = true
                isFocusable = true
                tag = m
            }
            tv.setOnClickListener {
                selectedMeal = m
                chipViews.forEach { c ->
                    val active = c.tag == m
                    c.background = chipBg(active)
                    c.setTextColor(if (active) 0xFF0F0F0F.toInt() else TEXT_PRIMARY)
                }
            }
            mealChips.addView(tv, chipBgChecked)
            chipViews.add(tv)
        }

        val titleView = TextView(ctx).apply {
            text = title
            setTextColor(TEXT_PRIMARY)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val perHundred = TextView(ctx).apply {
            text = "${kcal100.toInt()} ккал · Б ${fmtNum(protein100)} · Ж ${fmtNum(fat100)} · У ${fmtNum(carbs100)} (100 г)"
            setTextColor(TEXT_HINT)
            textSize = 11f
        }
        val weightLabel = TextView(ctx).apply {
            text = "Вес порции, г"
            setTextColor(TEXT_HINT)
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
        }
        container.addView(titleView)
        container.addView(perHundred)
        val sp1 = View(ctx); sp1.layoutParams = LinearLayout.LayoutParams(0, (12 * d).toInt()); container.addView(sp1)
        container.addView(weightLabel)
        val fieldBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF1A1A1A.toInt()); cornerRadius = 10f * d
        }
        val fieldWrap = LinearLayout(ctx).apply { background = fieldBg }
        fieldWrap.addView(weightField, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        container.addView(fieldWrap)
        val sp2 = View(ctx); sp2.layoutParams = LinearLayout.LayoutParams(0, (12 * d).toInt()); container.addView(sp2)
        container.addView(totalKcalView)
        container.addView(macrosView)
        val sp3 = View(ctx); sp3.layoutParams = LinearLayout.LayoutParams(0, (8 * d).toInt()); container.addView(sp3)
        container.addView(mealChips)

        val scroll = android.widget.ScrollView(ctx).apply {
            addView(container)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setView(scroll)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Добавить", null)
            .create()
        dialog.setOnShowListener {
            val addBtn = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            addBtn.setTextColor(0xFF4CAF50.toInt())
            addBtn.setOnClickListener {
                val g = weightField.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                if (g <= 0.0) {
                    weightField.error = "Введите вес"
                    return@setOnClickListener
                }
                val kcal = (g * kcal100 / 100.0).toInt()
                val p = g * protein100 / 100.0
                val f = g * fat100 / 100.0
                val c = g * carbs100 / 100.0
                // Сохраняем в новый формат meal_data с полным БЖУ.
                addMealItem(ctx, dateKey, selectedMeal, MealItem(
                    name = title, grams = g, kcal = kcal,
                    protein = p, fat = f, carbs = c
                ))
                // На всякий случай поддерживаем legacy-агрегатор (для старых экранов).
                addMealKcal(ctx, dateKey, selectedMeal, kcal)
                dialog.dismiss()
                onAdded()
            }
        }
        dialog.show()
        weightField.post { weightField.requestFocus() }
    }

    /** Диалог «Сколько ккал съели?» — обновляет prefs и зовёт onSaved(). */
    fun promptMealKcal(
        ctx: Context,
        meal: String,
        currentKcal: Int,
        dateKey: String,
        onSaved: () -> Unit
    ) {
        val input = android.widget.EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(currentKcal.toString())
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_HINT)
        }
        android.app.AlertDialog.Builder(ctx)
            .setTitle("$meal · ккал")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val v = input.text.toString().toIntOrNull() ?: 0
                setMealKcal(ctx, dateKey, meal, v)
                onSaved()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /* ─── ПРОГРЕСС 30 ДНЕЙ — временно скрыт по запросу ─────────────────
    private fun renderComplianceGraph(
        ctx: Context,
        content: LinearLayout,
        p: Params,
        dateKey: String
    ) {
        val d = ctx.resources.displayMetrics.density
        val values = loadDailyKcal(ctx)

        val header = TextView(ctx).apply {
            text = "ПРОГРЕСС 30 ДНЕЙ"
            setTextColor(TEXT_HINT)
            setPadding(0, (20 * d).toInt(), 0, (8 * d).toInt())
            textSize = 12f
            letterSpacing = 0.08f
        }
        content.addView(header)

        val input = EditText(ctx).apply {
            hint = "Съедено за выбранный день, ккал"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(values[dateKey]?.toString().orEmpty())
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_HINT)
            setBackgroundColor(0xFF1F1F1F.toInt())
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
        }
        content.addView(input)

        val graph = GridLayout(ctx).apply {
            columnCount = 10
            rowCount = 3
            setPadding(0, (12 * d).toInt(), 0, 0)
        }
        content.addView(graph)

        val summary = TextView(ctx).apply {
            setTextColor(TEXT_HINT)
            textSize = 12f
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        content.addView(summary)

        fun redraw() {
            graph.removeAllViews()
            val latest = loadDailyKcal(ctx)
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -29) }
            val recent = mutableListOf<Int>()
            repeat(30) {
                val key = dayKey(cal)
                val kcal = latest[key]
                if (kcal != null) recent += kcal
                val tile = View(ctx).apply {
                    setBackgroundColor(progressColor(kcal, p.kcalNorm))
                    contentDescription = "$key: ${kcal ?: 0} ккал"
                }
                graph.addView(tile, GridLayout.LayoutParams().apply {
                    width = (24 * d).toInt()
                    height = (24 * d).toInt()
                    setMargins(0, 0, (6 * d).toInt(), (6 * d).toInt())
                })
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            val avg = recent.takeIf { it.isNotEmpty() }?.average()?.toInt()
            summary.text = avg?.let {
                val diff = it - p.kcalNorm
                "Среднее: $it ккал (${if (diff > 0) "+" else ""}$diff)"
            } ?: "Нет данных"
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val next = loadDailyKcal(ctx)
                val value = s?.toString()?.toIntOrNull()
                if (value == null) next.remove(dateKey) else next[dateKey] = value.coerceAtLeast(0)
                saveDailyKcal(ctx, next)
                redraw()
            }
        })
        redraw()
    }

    private fun progressColor(kcal: Int?, norm: Int): Int {
        if (kcal == null || norm <= 0) return 0xFF2A2A2A.toInt()
        val diff = (kcal - norm).toFloat() / norm
        return when {
            diff < -0.25f -> 0xFF1565C0.toInt()
            diff < -0.10f -> 0xFF42A5F5.toInt()
            diff <= 0.10f -> 0xFF4CAF50.toInt()
            diff <= 0.25f -> 0xFFFFA726.toInt()
            else -> 0xFFE53935.toInt()
        }
    }
    ─────────────────────────────────────────────────────────────────── */

    // ═══════════════════════════════════════════════════════════════════
    //  База данных питания: 3 вкладки — Продукты / Свои записи / Блюда
    // ═══════════════════════════════════════════════════════════════════

    /** Стиль «как в чате» для поля ввода + фокус/показ клавиатуры. */
    private fun styleChatInput(ctx: Context, e: EditText, hint: String, number: Boolean = false) {
        val d = ctx.resources.displayMetrics.density
        e.hint = hint
        e.setBackgroundColor(0xFF2B2B2B.toInt())
        e.setTextColor(0xFFE6E6E6.toInt())
        e.setHintTextColor(0xFF8A8A8A.toInt())
        e.inputType = if (number)
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        else InputType.TYPE_CLASS_TEXT
        e.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        e.textSize = 16f
    }

    private fun showKeyboard(et: EditText) {
        et.requestFocus()
        et.post {
            val imm = et.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Таб «Продукты» — общая база: внешние (по штрихкоду) + свои записи. */
    fun renderProductsTab(
        ctx: Context,
        content: LinearLayout,
        container: ViewGroup,
        onMealClick: (String) -> Unit,
        onPickPhoto: (((Uri?) -> Unit) -> Unit)?,
        onTakePhoto: (((Uri?) -> Unit) -> Unit)? = null,
        onScanBarcode: ((String?) -> Unit) -> Unit,
        onAddToMeal: (NutritionDatabase.Product) -> Unit = { p -> onMealClick(formatProductMeal(p)) }
    ) {
        val d = ctx.resources.displayMetrics.density
        val db = NutritionDatabase(ctx)
        var refreshList: () -> Unit = {}

        val search = EditText(ctx).apply { styleChatInput(ctx, this, "Поиск") }
        search.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        content.addView(search)
        showKeyboard(search)

        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // запас снизу под FAB
            setPadding(0, (8 * d).toInt(), 0, (80 * d).toInt())
        }
        content.addView(list)

        fun matches(card: ItemCard, q: String): Boolean = when (card) {
            is ItemCard.Product -> q.isBlank() || card.p.name.lowercase().contains(q) || card.p.brand.lowercase().contains(q)
            is ItemCard.Custom -> q.isBlank() || card.c.name.lowercase().contains(q)
        }

        fun redraw() {
            val q = search.text.toString().trim().lowercase()
            val all = mutableListOf<ItemCard>()
            db.listProducts().forEach { all += ItemCard.Product(it) }
            db.listCustomItems().forEach { all += ItemCard.Custom(it) }
            val filtered = all.filter { matches(it, q) }
                .sortedWith(compareByDescending<ItemCard> { it.isFavorite }
                    .thenBy { it.name.lowercase() })
            renderItemCards(ctx, list, filtered, db, refreshList, onScanBarcode,
                onMealClick = { onAddToMeal(it) },
                onView = { card ->
                    // Тап на «+» = сразу добавить к приёму пищи
                    if (card is ItemCard.Product) onAddToMeal(card.p)
                },
                onEdit = { card ->
                    val (prod, cust) = when (card) {
                        is ItemCard.Product -> card.p to null
                        is ItemCard.Custom -> null to card.c
                    }
                    showProductView(
                        container, prod, cust,
                        onScanBarcode = onScanBarcode,
                        onPickPhoto = onPickPhoto,
                        onTakePhoto = onTakePhoto,
                        onPhotoChanged = { newPath ->
                            if (prod != null) {
                                db.upsertProduct(prod.copy(photoPath = newPath))
                            } else if (cust != null) {
                                db.upsertCustomItem(cust.copy(photoPath = newPath))
                            }
                            refreshList()
                        },
                        onSaved = { refreshList() },
                        onClose = {}
                    )
                },
                onDelete = { card ->
                    when (card) {
                        is ItemCard.Product -> { db.deleteProduct(card.p.id); refreshList() }
                        is ItemCard.Custom -> { db.deleteCustomItem(card.c.id); refreshList() }
                    }
                })
        }
        refreshList = ::redraw
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refreshList()
        })
        refreshList()
    }

    /** Создать новую карточку продукта через FAB — открывает ту же форму,
     *  что и просмотр/редактирование (showProductView), только пустую. */
    fun createProduct(
        container: ViewGroup,
        onScanBarcode: ((String?) -> Unit) -> Unit,
        onPickPhoto: (((Uri?) -> Unit) -> Unit)?,
        onTakePhoto: (((Uri?) -> Unit) -> Unit)? = null,
        onSaved: () -> Unit
    ) {
        showProductView(
            container = container,
            product = null,
            customItem = null,
            onScanBarcode = onScanBarcode,
            onPickPhoto = onPickPhoto,
            onTakePhoto = onTakePhoto,
            kindForNew = NutritionDatabase.Kind.PRODUCT,
            onSaved = onSaved,
            onClose = {}
        )
    }

    /** Создать новое блюдо (вызывается из FAB). */
    fun createDish(
        container: ViewGroup,
        onPickPhoto: (((Uri?) -> Unit) -> Unit)? = null,
        onTakePhoto: (((Uri?) -> Unit) -> Unit)? = null,
        onScanBarcode: ((String?) -> Unit) -> Unit,
        onSaved: () -> Unit
    ) {
        showDishCard(container, NutritionDatabase(container.context), null, onPickPhoto, onTakePhoto, onScanBarcode, onSaved)
    }

    /** Таб «Блюда» — композитные блюда с ингредиентами. */
    fun renderDishesTab(
        ctx: Context,
        content: LinearLayout,
        container: ViewGroup,
        onPickPhoto: (((Uri?) -> Unit) -> Unit)?,
        onTakePhoto: (((Uri?) -> Unit) -> Unit)? = null,
        onScanBarcode: ((String?) -> Unit) -> Unit,
        onAddToMeal: (NutritionDatabase.Dish) -> Unit = {}
    ) {
        val d = ctx.resources.displayMetrics.density
        val db = NutritionDatabase(ctx)
        var refreshList: () -> Unit = {}

        val search = EditText(ctx).apply { styleChatInput(ctx, this, "Поиск") }
        search.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * d).toInt() }
        content.addView(search)
        showKeyboard(search)

        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (12 * d).toInt(), 0, 0)
        }
        content.addView(list)

        fun redraw() {
            val q = search.text.toString().trim().lowercase()
            val items = db.listDishes().filter {
                q.isBlank() || it.name.lowercase().contains(q)
            }
            renderDishCards(ctx, list, db, items,
                onEdit = { dish -> showDishCard(container, db, dish, onPickPhoto, onTakePhoto, onScanBarcode) { refreshList() } },
                onAddToMeal = onAddToMeal,
                onRefresh = { refreshList() })
        }
        refreshList = ::redraw
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refreshList()
        })
        refreshList()
    }

    // ─── Универсальный диалог для продуктов и своих записей ───

    private fun showItemCard(
        container: ViewGroup,
        kind: NutritionDatabase.Kind,
        existing: Any?,
        onPickPhoto: (((Uri?) -> Unit) -> Unit)?,
        onScanBarcode: ((String?) -> Unit) -> Unit,
        onSaved: () -> Unit
    ) {
        val ctx = container.context
        val d = ctx.resources.displayMetrics.density
        val isProduct = kind == NutritionDatabase.Kind.PRODUCT
        val initial: Any = when {
            existing is NutritionDatabase.Product -> existing
            existing is NutritionDatabase.CustomItem -> existing
            isProduct -> NutritionDatabase.Product(
                id = java.util.UUID.randomUUID().toString(), name = ""
            )
            else -> NutritionDatabase.CustomItem(
                id = java.util.UUID.randomUUID().toString(), name = ""
            )
        }
        var photoPath: String? = when (initial) {
            is NutritionDatabase.Product -> initial.photoPath
            is NutritionDatabase.CustomItem -> initial.photoPath
            else -> null
        }
        val nameInit = when (initial) {
            is NutritionDatabase.Product -> initial.name
            is NutritionDatabase.CustomItem -> initial.name
            else -> ""
        }
        val brandInit = (initial as? NutritionDatabase.Product)?.brand ?: ""
        val proteinInit = when (initial) {
            is NutritionDatabase.Product -> initial.protein
            is NutritionDatabase.CustomItem -> initial.protein
            else -> 0.0
        }
        val fatInit = when (initial) {
            is NutritionDatabase.Product -> initial.fat
            is NutritionDatabase.CustomItem -> initial.fat
            else -> 0.0
        }
        val carbsInit = when (initial) {
            is NutritionDatabase.Product -> initial.carbs
            is NutritionDatabase.CustomItem -> initial.carbs
            else -> 0.0
        }
        val servingInit = when (initial) {
            is NutritionDatabase.Product -> initial.servingG
            is NutritionDatabase.CustomItem -> initial.servingG
            else -> 0.0
        }

        fun numberField(initial: Int): EditText = EditText(ctx).apply {
            setText(initial.toString())
            hint = "0"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(0xFF555555.toInt())
            setBackgroundColor(0xFF1F1F1F.toInt())
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            val pad = (12 * d).toInt()
            setPadding(pad, pad, pad, pad)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        fun decimalField(initial: Double): EditText = EditText(ctx).apply {
            setText(fmtNum(initial))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(0xFF555555.toInt())
            setBackgroundColor(0xFF1F1F1F.toInt())
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            val pad = (12 * d).toInt()
            setPadding(pad, pad, pad, pad)
            isFocusable = true
            isFocusableInTouchMode = true
            // При тапе — выделить всё, чтобы ввод новой цифры заменял прежнее значение
            setSelectAllOnFocus(true)
        }
        fun paramCard(label: String, field: EditText, extraBelow: TextView? = null): LinearLayout {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                val pad = (14 * d).toInt()
                setPadding(pad, (10 * d).toInt(), pad, (10 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * d).toInt(); bottomMargin = (4 * d).toInt() }
            }
            val top = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(TextView(ctx).apply {
                text = label
                setTextColor(TEXT_PRIMARY)
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(field, LinearLayout.LayoutParams(
                (140 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            card.addView(top)
            if (extraBelow != null) card.addView(extraBelow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * d).toInt() })
            return card
        }
        fun chatField(hint: String, initial: String): EditText = EditText(ctx).apply {
            styleChatInput(ctx, this, hint)
            setText(initial)
        }

        // Корневая карточка — overlay на весь infoContainer
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        card.tag = CARD_TAG
        // Скрываем верхние табы (Питание/Продукты/Блюда) — на их месте шапка карточки
        val modeTabs = (ctx as? android.app.Activity)
            ?.findViewById<View>(R.id.modeTabs)
        modeTabs?.visibility = View.GONE
        // Шапка: ✕ «Продукт» (заменяет верхние табы)
        val titleBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1B1B1B.toInt())
            // Высота — как у modeTabs (44dp), padding по бокам 16dp
            val vPad = (10 * d).toInt()
            val hPad = (16 * d).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            minimumHeight = (44 * d).toInt()
        }
        // Невидимый spacer слева, шириной как closeBtn — чтобы titleLabel был глобально по центру
        val leftSpacer = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                (40 * d).toInt(), ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val titleLabel = TextView(ctx).apply {
            text = if (isProduct) "Продукт" else "Своя запись"
            setTextColor(0xFFE6E6E6.toInt())
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(ctx).apply {
            text = "✕"
            setTextColor(0xFFE6E6E6.toInt())
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            isClickable = true
            isFocusable = true
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            // ripple при тапе
            setBackgroundResource(android.R.color.transparent)
            setOnClickListener {
                // Закрыть карточку БЕЗ сохранения (как системная кнопка «назад»)
                (card.parent as? ViewGroup)?.removeView(card)
                modeTabs?.visibility = View.VISIBLE
                hideKeyboard(ctx)
            }
        }
        titleBar.addView(leftSpacer)
        titleBar.addView(titleLabel)
        titleBar.addView(closeBtn)
        card.addView(titleBar)
        // При удалении карточки любым способом — вернуть mode tabs
        card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                modeTabs?.visibility = View.VISIBLE
                v.removeOnAttachStateChangeListener(this)
            }
        })

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * d).toInt()
            setPadding(pad, pad, pad, (24 * d).toInt())
        }
        scroll.addView(body)

        // Компактная карточка: лейбл + поле в одной строке (для КБЖУ 2×2 и «Название|Вес, г»)
        fun compactCard(label: String, field: EditText, green: Boolean = false): LinearLayout {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                val pad = (8 * d).toInt()
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins((2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt()) }
            }
            card.addView(TextView(ctx).apply {
                text = label
                setTextColor(if (green) 0xFF4CAF50.toInt() else 0xFF8A8A8A.toInt())
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            // Поле сливается с фоном карточки (прозрачный фон, без скруглений)
            field.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            field.setPadding(0, 0, 0, 0)
            card.addView(field, LinearLayout.LayoutParams(
                (60 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (4 * d).toInt() })
            return card
        }

        var name: EditText? = null
        try {
        // === Row 1: фото (50% ширины, квадрат) | колонка Наименование + «Вес, г» (50%) ===
        name = chatField("Название", nameInit)
        val photoThumb = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF2B2B2B.toInt())
            photoPath?.let {
                setImageURI(Uri.fromFile(File(it)))
                alpha = 1.0f
            } ?: run {
                setImageResource(R.drawable.food)
                alpha = 0.5f
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onPickPhoto?.invoke { uri ->
                    photoPath = uri?.let { copyPhoto(ctx, it) }
                    photoPath?.let {
                        setImageURI(Uri.fromFile(File(it)))
                        alpha = 1.0f
                    } ?: run {
                        setImageResource(R.drawable.food)
                        alpha = 0.5f
                    }
                }
            }
            setOnLongClickListener {
                val current = photoPath
                if (current.isNullOrBlank()) false
                else { showPhotoPreview(ctx, current); true }
            }
        }
        // === Row 1: фото на всю ширину (квадрат) ===
        photoThumb.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        body.addView(photoThumb, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, (4 * d).toInt(), 0, (4 * d).toInt()) })
        photoThumb.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int,
                                       ol: Int, ot: Int, orr: Int, ob: Int) {
                val lp = v.layoutParams
                if (lp.height != v.width) { lp.height = v.width; v.layoutParams = lp }
            }
        })

        // === Row 2: Наименование | «Вес, г» (две одинаковых compactCard) ===
        name.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        name.textSize = 18f
        name.setTypeface(null, android.graphics.Typeface.NORMAL)
        val amount: EditText = decimalField(100.0)
        amount.textSize = 18f
        amount.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        val nameAmountRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = (4 * d).toInt()
            setPadding(pad, 0, pad, 0)
        }
        nameAmountRow.addView(compactCard("Название", name))
        nameAmountRow.addView(compactCard("Вес, г", amount))
        body.addView(nameAmountRow)

        // Секция «Расчёт на X г»: поля (Ккал, Б, Ж, У) описывают X граммов.
        // Сохранение пересчитывает их на 100 г в БД.
        val protein = decimalField(proteinInit).apply { textSize = 18f }
        val fat = decimalField(fatInit).apply { textSize = 18f }
        val carbs = decimalField(carbsInit).apply { textSize = 18f }
        val kcal = numberField((proteinInit * 4 + fatInit * 9 + carbsInit * 4).toInt()).apply { textSize = 18f }
        // Цвета подсветки: дефолт/зелёный для суммы=100, красный — наименьшему при sum<100
        val COLOR_OK = 0xFFE6E6E6.toInt()
        val COLOR_BAD = 0xFFE57373.toInt()
        // Уникальный ключ для self-mutex тегов (защита от рекурсии при авто-коррекции)
        val MUTEX_KEY = 0x7F1A_B010
        var bjuValid = true
        fun updateBju() {
            var p = protein.text.toString().toDoubleOrNull() ?: 0.0
            var f = fat.text.toString().toDoubleOrNull() ?: 0.0
            var c = carbs.text.toString().toDoubleOrNull() ?: 0.0
            val sum = p + f + c
            // Проверяем какое из 3х полей изменилось (через «было»)
            val changed = listOf(protein to p, fat to f, carbs to c)
            if (sum > 100.0001) {
                // авто-корректировка: уменьшить последнее изменённое до 100 - остальные
                val changedField = changed.maxByOrNull { it.second }  // наибольшее = скорее всего то, что только что увеличили
                when (changedField?.first) {
                    protein -> p = (100.0 - f - c).coerceAtLeast(0.0)
                    fat -> f = (100.0 - p - c).coerceAtLeast(0.0)
                    carbs -> c = (100.0 - p - f).coerceAtLeast(0.0)
                    else -> {
                        // ничего не выбрали — уменьшаем наибольшее
                        val maxEntry = changed.maxByOrNull { it.second }
                        when (maxEntry?.first) {
                            protein -> p = (100.0 - f - c).coerceAtLeast(0.0)
                            fat -> f = (100.0 - p - c).coerceAtLeast(0.0)
                            carbs -> c = (100.0 - p - f).coerceAtLeast(0.0)
                            else -> {}
                        }
                    }
                }
                // защита от рекурсии: пишем в поле напрямую без триггера через tag
                if (protein.getTag(MUTEX_KEY) != true) {
                    protein.setTag(MUTEX_KEY, true)
                    protein.setText(fmtNum(p))
                    protein.setSelection(protein.text.length)
                    protein.setTag(MUTEX_KEY, false)
                }
                if (fat.getTag(MUTEX_KEY) != true) {
                    fat.setTag(MUTEX_KEY, true)
                    fat.setText(fmtNum(f))
                    fat.setSelection(fat.text.length)
                    fat.setTag(MUTEX_KEY, false)
                }
                if (carbs.getTag(MUTEX_KEY) != true) {
                    carbs.setTag(MUTEX_KEY, true)
                    carbs.setText(fmtNum(c))
                    carbs.setSelection(carbs.text.length)
                    carbs.setTag(MUTEX_KEY, false)
                }
            }
            // пересчитать ккал и подсветить наименьший, если sum < 100
            val newP = protein.text.toString().toDoubleOrNull() ?: 0.0
            val newF = fat.text.toString().toDoubleOrNull() ?: 0.0
            val newC = carbs.text.toString().toDoubleOrNull() ?: 0.0
            kcal.setText((newP * 4 + newF * 9 + newC * 4).toInt().toString())
            val newSum = newP + newF + newC
            if (newSum < 99.9999) {
                val mins = listOf(protein to newP, fat to newF, carbs to newC).minByOrNull { it.second }
                protein.setTextColor(if (mins?.first == protein) COLOR_BAD else COLOR_OK)
                fat.setTextColor(if (mins?.first == fat) COLOR_BAD else COLOR_OK)
                carbs.setTextColor(if (mins?.first == carbs) COLOR_BAD else COLOR_OK)
                bjuValid = false
            } else {
                protein.setTextColor(COLOR_OK)
                fat.setTextColor(COLOR_OK)
                carbs.setTextColor(COLOR_OK)
                bjuValid = true
            }
        }
        kcal.inputType = InputType.TYPE_CLASS_NUMBER
        kcal.isFocusable = false
        kcal.isClickable = false
        kcal.setTextColor(0xFF4CAF50.toInt())
        listOf(protein, fat, carbs).forEach { f ->
            f.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (f.getTag(MUTEX_KEY) == true) {
                        f.setTag(MUTEX_KEY, false)
                        return
                    }
                    updateBju()
                }
            })
            // При потере фокуса — пустое поле заменяем на «0»
            f.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus && f.text.toString().isBlank()) {
                    f.setTag(MUTEX_KEY, true)
                    f.setText("0")
                    f.setTag(MUTEX_KEY, false)
                    updateBju()
                }
            }
        }
        // «Граммовка» — то же самое (но без updateBju, только защита от пустоты)
        amount.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && amount.text.toString().isBlank()) {
                amount.setText("100")
            }
        }
        updateBju()
        // === КБЖУ 2×2: Б | Ж, У | Ккал ===
        // (compactCard объявлен ниже, но в Kotlin локальные функции видимы после parse)
        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (4 * d).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(compactCard("Белки", protein))
        row1.addView(compactCard("Жиры", fat))
        val row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(compactCard("Углеводы", carbs))
        row2.addView(compactCard("Ккал", kcal, green = true))
        grid.addView(row1)
        grid.addView(row2)
        body.addView(grid)

        // Нижний ряд: «Штрихкод» + «Сохранить» (для продукта) или только «Сохранить» (для блюда)
        val bottomRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val m = (16 * d).toInt()
            setPadding(0, m, 0, m)
        }
        val scanBtn = if (isProduct) Button(ctx).apply {
            text = "Штрихкод"
            setTextColor(TEXT_PRIMARY)
            setBackgroundColor(0xFF2B2B2B.toInt())
            setOnClickListener {
                onScanBarcode { scanned ->
                    if (scanned.isNullOrBlank()) {
                        android.widget.Toast.makeText(ctx, "Не удалось распознать",
                            android.widget.Toast.LENGTH_SHORT).show()
                        return@onScanBarcode
                    }
                    performBarcodeLookup(ctx, scanned, name, null,
                        protein, fat, carbs, amount, kcal)
                }
            }
        } else null
        if (scanBtn != null) {
            bottomRow.addView(scanBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = (8 * d).toInt() })
        }
        val saveBtn = Button(ctx).apply {
            text = "Сохранить"
            setBackgroundColor(0xFF4CAF50.toInt())
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                if (scanBtn != null) 1f else 0f)
            setOnClickListener {
                val title = name.text.toString().trim()
                if (title.isBlank()) {
                    android.widget.Toast.makeText(ctx, "Введите название", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!bjuValid) {
                    android.widget.Toast.makeText(ctx, "Б+Ж+У должны в сумме давать 100 г", android.widget.Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val a = amount.text.toString().toDoubleOrNull()?.coerceAtLeast(1.0) ?: 100.0
                val factor = 100.0 / a
                val p = (protein.text.toString().toDoubleOrNull() ?: 0.0) * factor
                val f = (fat.text.toString().toDoubleOrNull() ?: 0.0) * factor
                val c = (carbs.text.toString().toDoubleOrNull() ?: 0.0) * factor
                val db = NutritionDatabase(ctx)
                if (isProduct) {
                    val old = (initial as NutritionDatabase.Product)
                    db.upsertProduct(old.copy(
                        name = title,
                        protein = p, fat = f, carbs = c,
                        photoPath = photoPath
                    ))
                } else {
                    val old = (initial as NutritionDatabase.CustomItem)
                    db.upsertCustomItem(old.copy(
                        name = title, protein = p, fat = f, carbs = c,
                        photoPath = photoPath
                    ))
                }
                (parent as? ViewGroup)?.removeView(card)
                hideKeyboard(ctx)
                onSaved()
            }
        }
        bottomRow.addView(saveBtn)
        body.addView(bottomRow)
        } catch (e: Throwable) {
            CrashLog.log(ctx, e, "showItemCard")
            body.addView(TextView(ctx).apply {
                text = "⚠ ${e.javaClass.simpleName}: ${e.message}\n\n${e.stackTraceToString().take(1500)}"
                setTextColor(0xFFE57373.toInt())
                textSize = 11f
                setPadding(0, (16 * d).toInt(), 0, 0)
                setTypeface(android.graphics.Typeface.MONOSPACE)
                isFocusable = true
                isFocusableInTouchMode = true
            })
        }
        card.addView(scroll)
        container.addView(card)
    }

    private fun hideKeyboard(ctx: Context) {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow((ctx as? android.app.Activity)?.currentFocus?.windowToken, 0)
    }

    // ─── Диалог блюда с ингредиентами ───

    private fun showDishCard(
        container: ViewGroup,
        db: NutritionDatabase,
        existing: NutritionDatabase.Dish?,
        onPickPhoto: (((Uri?) -> Unit) -> Unit)?,
        onTakePhoto: (((Uri?) -> Unit) -> Unit)? = null,
        onScanBarcode: ((String?) -> Unit) -> Unit,
        onSaved: () -> Unit
    ) {
        val ctx = container.context
        try {
        val ctx = container.context
        val d = ctx.resources.displayMetrics.density
        var photoPath: String? = existing?.photoPath
        val ingredientsState = mutableListOf<NutritionDatabase.Ingredient>().apply {
            existing?.ingredients?.let { addAll(it) }
        }
        var servingG = existing?.servingG ?: 100.0
        var calcMode = "100"  // "100" = per 100g, "serving" = per порцию

        // Цветовая палитра — как в showProductView
        val BG = 0xFF0F0F0F.toInt()
        val SURFACE = 0xFF1A1A1A.toInt()
        val SURFACE2 = 0xFF232323.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
        val GRAY = 0xFFA0A0A0.toInt()
        val GREEN = 0xFF50C95A.toInt()
        val DIVIDER = android.graphics.Color.argb(20, 255, 255, 255)
        val BORDER = android.graphics.Color.argb(40, 255, 255, 255)

        fun roundedBg(color: Int, radiusPx: Float) = android.graphics.drawable.GradientDrawable().apply {
            setColor(color); cornerRadius = radiusPx
        }
        fun outlineRound(radiusPx: Float) = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
            }
        }

        // forward-declare nameEt: moreBtn (в appBar) ссылается на nameEt
        // раньше, чем EditText создаётся ниже
        var nameEt: EditText = EditText(ctx)
        // forward-declare updateKbjuAndSummary: settings row (вес/расчёт) ссылается
        // на неё, но объявлена ниже вместе с redrawIng
        var updateKbjuAndSummary: () -> Unit = {}
        // SwipeableCard + ConstraintLayout positioning (как в showProductView)
        val card = SwipeableCard(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            val lp = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT,
                0  // MATCH_CONSTRAINT — заполняем пространство между header и bottomContainer
            )
            lp.topToBottom = com.assistant.app.R.id.header
            lp.bottomToTop = com.assistant.app.R.id.bottomContainer
            lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            layoutParams = lp
            swipeZoneStartFraction = 0.05f
        }
        card.tag = CARD_TAG

        fun closeCard() {
            (card.parent as? ViewGroup)?.removeView(card)
            hideKeyboard(ctx)
            onSaved()
        }

        // AppBar 44dp #1B1B1B — [⋮] «Блюдо» [✕]
        val appBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1B1B1B.toInt())
            val vPad = (10 * d).toInt()
            val hPad = (16 * d).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            minimumHeight = (44 * d).toInt()
        }
        val appBarTitle = TextView(ctx).apply {
            text = "Блюдо"
            setTextColor(0xFFE6E6E6.toInt())
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val closeBtn = TextView(ctx).apply {
            text = "✕"
            setTextColor(0xFFE6E6E6.toInt())
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            isClickable = true
            isFocusable = true
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            setBackgroundResource(android.R.color.transparent)
            setOnClickListener { closeCard() }
        }
        // ⋮ меню: «Создать копию» / «Удалить карточку»
        val moreBtn = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_more_vert)
            layoutParams = LinearLayout.LayoutParams(
                (24 * d).toInt(), (24 * d).toInt()
            ).apply { marginEnd = (8 * d).toInt() }
            contentDescription = "Меню"
            isClickable = true
            isFocusable = true
            setColorFilter(0xFFE6E6E6.toInt())
            setOnClickListener { anchor ->
                val view = android.view.LayoutInflater.from(ctx)
                    .inflate(com.assistant.app.R.layout.popup_card_menu, null, false)
                val popup = android.widget.PopupWindow(
                    view,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    true
                )
                popup.setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(0xFF181818.toInt())
                )
                popup.isOutsideTouchable = true
                val tint = 0xFF8A8A8A.toInt()
                for (i in 0 until (view as ViewGroup).childCount) {
                    val row = view.getChildAt(i) as? ViewGroup ?: continue
                    val icon = row.getChildAt(0) as? ImageView ?: continue
                    icon.setColorFilter(tint)
                }
                val isExisting = existing != null
                val dupRow = view.findViewById<View>(R.id.menu_duplicate)
                dupRow.setOnClickListener {
                    popup.dismiss()
                    if (!isExisting) {
                        // Новая карточка — копировать нечего, выходим
                        return@setOnClickListener
                    }
                    // Снимаем текущую карточку и открываем копию с новым id
                    (card.parent as? ViewGroup)?.removeView(card)
                    hideKeyboard(ctx)
                    val copyDish = existing!!.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        favorite = false
                    )
                    showDishCard(
                        container = container,
                        db = db,
                        existing = copyDish,
                        onPickPhoto = onPickPhoto,
                        onTakePhoto = onTakePhoto,
                        onScanBarcode = onScanBarcode,
                        onSaved = onSaved
                    )
                }
                val delRow = view.findViewById<View>(R.id.menu_delete)
                delRow.setOnClickListener {
                    popup.dismiss()
                    if (!isExisting) {
                        // Новая карточка — закрываем без сохранения, как X
                        closeCard()
                        return@setOnClickListener
                    }
                    AlertDialog.Builder(ctx)
                        .setTitle("Удалить карточку?")
                        .setMessage("Это действие нельзя отменить.")
                        .setPositiveButton("Удалить") { _, _ ->
                            db.deleteDish(existing!!.id)
                            closeCard()
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
                popup.showAsDropDown(anchor, 0, 0)
            }
        }
        val leftGroup = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            addView(moreBtn)
            // Heart — тот же паттерн, что в showProductView
            val heartActiveColor = 0xFFE57373.toInt()
            val heartInactiveColor = 0xFF8A8A8A.toInt()
            var heartState: Boolean = existing?.favorite == true
            val heart = ImageView(ctx).apply {
                setImageResource(R.drawable.like)
                layoutParams = LinearLayout.LayoutParams(
                    (24 * d).toInt(), (24 * d).toInt()
                )
                contentDescription = "В избранном"
                isClickable = true
                isFocusable = true
                setColorFilter(if (heartState) heartActiveColor else heartInactiveColor)
                setOnClickListener {
                    heartState = !heartState
                    setColorFilter(if (heartState) heartActiveColor else heartInactiveColor)
                    // Существующее блюдо — сразу пишем в БД
                    if (existing != null) db.setDishFavorite(existing.id, heartState)
                }
            }
            addView(heart)
        }
        val rightGroup = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            addView(closeBtn)
        }
        appBar.addView(leftGroup)
        appBar.addView(appBarTitle)
        appBar.addView(rightGroup)
        card.addView(appBar)

        // Scroll + body
        val scroll = AutoScrollScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val hPad = (20 * d).toInt()
            val vPad = (20 * d).toInt()
            setPadding(hPad, vPad, hPad, vPad)
        }
        scroll.addView(body)

        // Фото 16:9 (220dp) — большая кликабельная область
        val photo = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (photoPath != null) {
                runCatching { setImageURI(Uri.fromFile(File(photoPath))) }
            } else {
                setImageResource(R.drawable.food)
                alpha = 0.4f
            }
            setBackgroundColor(SURFACE2)
            clipToOutline = true
            outlineProvider = outlineRound(24 * d)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                // Попап-меню: «Загрузить с телефона» / «Сделать снимок» — как в showProductView
                val view = android.view.LayoutInflater.from(ctx)
                    .inflate(com.assistant.app.R.layout.popup_photo_menu, null, false)
                val popup = android.widget.PopupWindow(
                    view,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    true
                )
                popup.setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(0xFF181818.toInt())
                )
                popup.isOutsideTouchable = true
                val tint = 0xFF8A8A8A.toInt()
                for (i in 0 until (view as ViewGroup).childCount) {
                    val row = view.getChildAt(i) as? ViewGroup ?: continue
                    val icon = row.getChildAt(0) as? ImageView ?: continue
                    icon.setColorFilter(tint)
                }
                fun pickWith(cb: (((Uri?) -> Unit) -> Unit)?) {
                    cb?.invoke { uri ->
                        if (uri == null) return@invoke
                        val newPath = copyPhoto(ctx, uri) ?: return@invoke
                        photoPath = newPath
                        runCatching { setImageURI(Uri.fromFile(File(newPath))) }
                        alpha = 1.0f
                    }
                }
                view.findViewById<View>(com.assistant.app.R.id.menu_gallery)
                    .setOnClickListener { popup.dismiss(); pickWith(onPickPhoto) }
                view.findViewById<View>(com.assistant.app.R.id.menu_camera)
                    .setOnClickListener { popup.dismiss(); pickWith(onTakePhoto) }
                popup.showAsDropDown(this, 0, 0)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (220 * d).toInt()
            )
        }
        body.addView(photo)

        // Название блюда — по центру, 30sp bold (присваиваем в forward-declared var)
        nameEt = EditText(ctx).apply {
            setText(existing?.name ?: "")
            setTextColor(WHITE)
            setHintTextColor(GRAY)
            textSize = 30f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isSingleLine = false
            maxLines = 2
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_NEXT
            hint = "Курица с рисом и брокколи"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (20 * d).toInt() }
        }
        body.addView(nameEt)

        // Строка настроек: «Вес блюда» | «Расчёт» — подписи над карточками
        val settingsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (10 * d).toInt()
            lp.bottomMargin = (20 * d).toInt()
            layoutParams = lp
        }
        // Левая колонка: подпись + карточка с полем ввода
        val weightCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = (8 * d).toInt() }
        }
        weightCol.addView(TextView(ctx).apply {
            text = "Вес блюда"
            setTextColor(GRAY)
            textSize = 12f
            setPadding((4 * d).toInt(), 0, (4 * d).toInt(), (6 * d).toInt())
        })
        val weightCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(SURFACE, 20 * d)
            val padH = (16 * d).toInt()
            val padV = (14 * d).toInt()
            setPadding(padH, padV, padH, padV)
        }
        val weightEt = EditText(ctx).apply {
            setText(servingG.toInt().toString())
            setTextColor(WHITE)
            setHintTextColor(GRAY)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            setSelectAllOnFocus(true)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val v = s?.toString()?.toIntOrNull()?.coerceAtLeast(10) ?: 100
                    if (v.toDouble() != servingG) {
                        servingG = v.toDouble()
                        updateKbjuAndSummary()
                    }
                }
            })
        }
        weightCard.addView(weightEt)
        weightCard.addView(TextView(ctx).apply {
            text = "г"
            setTextColor(GRAY)
            textSize = 14f
            setPadding((4 * d).toInt(), 0, 0, 0)
        })
        weightCol.addView(weightCard)
        settingsRow.addView(weightCol)

        // Правая колонка: подпись + карточка с выпадающим списком
        val calcCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = (8 * d).toInt() }
        }
        calcCol.addView(TextView(ctx).apply {
            text = "Расчёт"
            setTextColor(GRAY)
            textSize = 12f
            setPadding((4 * d).toInt(), 0, (4 * d).toInt(), (6 * d).toInt())
        })
        val calcCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(SURFACE, 20 * d)
            val padH = (16 * d).toInt()
            val padV = (14 * d).toInt()
            setPadding(padH, padV, padH, padV)
        }
        val calcText = TextView(ctx).apply {
            text = if (calcMode == "serving") "На порцию" else "На 100 г"
            setTextColor(WHITE)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val calcDropdownRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val options = arrayOf("На 100 г", "На порцию")
                AlertDialog.Builder(ctx)
                    .setItems(options) { _, which ->
                        calcMode = if (which == 0) "100" else "serving"
                        calcText.text = options[which]
                        updateKbjuAndSummary()
                    }
                    .show()
            }
        }
        calcDropdownRow.addView(calcText)
        calcDropdownRow.addView(TextView(ctx).apply {
            text = "▾"
            setTextColor(GRAY)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding((4 * d).toInt(), 0, 0, 0)
        })
        calcCard.addView(calcDropdownRow)
        calcCol.addView(calcCard)
        settingsRow.addView(calcCol)
        body.addView(settingsRow)

        // KBJU-карточка (read-only — значения пересчитываются из ингредиентов)
        // Типографика: подпись серым 12sp small-caps сверху, значение белым 26sp bold снизу.
        // Колонки равной ширины, вертикальные разделители 1px между ними.
        fun bjuColumn(caption: String, valueView: TextView, unit: String): LinearLayout {
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val vPad = (18 * d).toInt()
                val hPad = (8 * d).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(ctx).apply {
                text = caption.uppercase()
                setTextColor(GRAY)
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                letterSpacing = 0.10f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * d).toInt() }
            })
            val valueRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            valueRow.addView(valueView)
            if (unit.isNotEmpty()) {
                valueRow.addView(TextView(ctx).apply {
                    text = " $unit"
                    setTextColor(GRAY)
                    textSize = 13f
                    setTypeface(null, android.graphics.Typeface.NORMAL)
                    setPadding(0, (8 * d).toInt(), 0, 0)
                })
            }
            col.addView(valueRow)
            return col
        }
        fun vDiv() = View(ctx).apply {
            setBackgroundColor(DIVIDER)
            // Тонкий разделитель ~60% высоты родителя, центрирован по вертикали
            val lp = LinearLayout.LayoutParams(
                (1 * d).toInt(),
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            lp.topMargin = (16 * d).toInt()
            lp.bottomMargin = (16 * d).toInt()
            layoutParams = lp
        }
        val kcalVal = TextView(ctx).apply { setTextColor(WHITE); textSize = 26f; setTypeface(null, android.graphics.Typeface.BOLD); includeFontPadding = false }
        val protVal = TextView(ctx).apply { setTextColor(WHITE); textSize = 26f; setTypeface(null, android.graphics.Typeface.BOLD); includeFontPadding = false }
        val fatVal  = TextView(ctx).apply { setTextColor(WHITE); textSize = 26f; setTypeface(null, android.graphics.Typeface.BOLD); includeFontPadding = false }
        val carbVal = TextView(ctx).apply { setTextColor(WHITE); textSize = 26f; setTypeface(null, android.graphics.Typeface.BOLD); includeFontPadding = false }

        val bjuCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(SURFACE)
            clipToOutline = true
            outlineProvider = outlineRound(24 * d)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (24 * d).toInt()
            layoutParams = lp
        }
        bjuCard.addView(bjuColumn("Ккал",  kcalVal, ""))
        bjuCard.addView(vDiv())
        bjuCard.addView(bjuColumn("Белки",   protVal, ""))
        bjuCard.addView(vDiv())
        bjuCard.addView(bjuColumn("Жиры",    fatVal,  ""))
        bjuCard.addView(vDiv())
        bjuCard.addView(bjuColumn("Углеводы", carbVal, ""))
        body.addView(bjuCard)

        // ─── СОСТАВ БЛЮДА (компактный список) ────────────────────────
        // forward-declare redrawIng (используется в setOnClickListener-ах)
        var redrawIng: () -> Unit = {}
        // «СОСТАВ БЛЮДА» + счётчик ингредиентов в одной строке (счётчик справа)
        val ingHeader = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (8 * d).toInt()
            layoutParams = lp
        }
        ingHeader.addView(TextView(ctx).apply {
            text = "СОСТАВ БЛЮДА"
            setTextColor(GRAY)
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val ingCount = TextView(ctx).apply {
            setTextColor(GREEN)
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        ingHeader.addView(ingCount)
        body.addView(ingHeader)
        val ingList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(ingList)

        // Диалог редактирования граммовки ингредиента
        val showGramsDialog: (Int) -> Unit = { idx ->
            val current = ingredientsState[idx]
            val input = EditText(ctx).apply {
                setText(fmtNum(current.grams))
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setTextColor(WHITE)
                setHintTextColor(GRAY)
                setBackgroundColor(0xFF2B2B2B.toInt())
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setSelectAllOnFocus(true)
                setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
            }
            AlertDialog.Builder(ctx)
                .setTitle("Граммовка: ${db.nameFor(current.kind, current.refId)}")
                .setView(input)
                .setPositiveButton("OK") { _, _ ->
                    val newG = input.text.toString().toDoubleOrNull()
                    if (newG != null && newG > 0.0) {
                        ingredientsState[idx] = current.copy(grams = newG)
                        redrawIng()
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
        // Пересчёт КБЖУ + счётчик ингредиентов
        updateKbjuAndSummary = {
            val dishTmp = NutritionDatabase.Dish(
                id = existing?.id ?: "tmp", name = "", servingG = 1.0,
                ingredients = ingredientsState.toList()
            )
            val macros = db.dishMacrosPer100(dishTmp)
            val factor = if (calcMode == "serving") servingG / 100.0 else 1.0
            kcalVal.text = (macros.kcal * factor).toInt().toString()
            protVal.text = fmtNum(macros.protein * factor)
            fatVal.text  = fmtNum(macros.fat * factor)
            carbVal.text = fmtNum(macros.carbs * factor)
            val n = ingredientsState.size
            val word = when {
                n % 10 == 1 && n % 100 != 11 -> "ингредиент"
                n % 10 in 2..4 && (n % 100 !in 12..14) -> "ингредиента"
                else -> "ингредиентов"
            }
            ingCount.text = "$n $word"
        }

        redrawIng = {
            ingList.removeAllViews()
            if (ingredientsState.isEmpty()) {
                // Empty state — компактная заглушка
                val empty = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.dish_card_bg)
                    val pad = (20 * d).toInt()
                    setPadding(pad, (24 * d).toInt(), pad, (24 * d).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                empty.addView(TextView(ctx).apply {
                    text = "Добавьте ингредиенты"
                    setTextColor(WHITE)
                    textSize = 15f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, (4 * d).toInt())
                })
                empty.addView(TextView(ctx).apply {
                    text = "КБЖУ рассчитается автоматически"
                    setTextColor(GRAY)
                    textSize = 12f
                    gravity = Gravity.CENTER
                })
                ingList.addView(empty)
            } else {
                ingredientsState.forEachIndexed { idx, ing ->
                    val ingName = db.nameFor(ing.kind, ing.refId)
                    val ingMacros = db.macrosFor(ing.kind, ing.refId)
                    val prot100 = ingMacros?.first ?: 0.0
                    val fat100  = ingMacros?.second ?: 0.0
                    val carb100 = ingMacros?.third ?: 0.0
                    val kcal = (prot100 * 4 + fat100 * 9 + carb100 * 4) * ing.grams / 100.0

                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setBackgroundResource(R.drawable.dish_ingredient_bg)
                        val pad = (12 * d).toInt()
                        setPadding(pad, pad, pad, pad)
                        minimumHeight = (56 * d).toInt()
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = (6 * d).toInt() }
                    }
                    val txtCol = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            .apply { marginEnd = (8 * d).toInt() }
                    }
                    txtCol.addView(TextView(ctx).apply {
                        text = ingName
                        setTextColor(WHITE)
                        textSize = 14f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setSingleLine(true)
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    txtCol.addView(TextView(ctx).apply {
                        text = "${fmtNum(ing.grams)} г  ·  ${kcal.toInt()} ккал"
                        setTextColor(GRAY)
                        textSize = 12f
                        setPadding(0, (2 * d).toInt(), 0, 0)
                    })
                    row.addView(txtCol)
                    val arrow = TextView(ctx).apply {
                        text = "›"
                        setTextColor(GRAY)
                        textSize = 22f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setPadding((8 * d).toInt(), 0, (4 * d).toInt(), 0)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { showGramsDialog(idx) }
                        setOnLongClickListener {
                            ingredientsState.removeAt(idx)
                            redrawIng()
                            true
                        }
                    }
                    row.addView(arrow)
                    ingList.addView(row)
                }
            }
            updateKbjuAndSummary()
        }
        redrawIng()

        // Нижний ряд: «+ Добавить ингредиент» (слева) + «Добавить / Сохранить» (справа).
        // Обе кнопки занимают по 50% ширины экрана (weight=1).
        val isExisting = existing != null
        val btnLabel = if (isExisting) "Сохранить" else "Добавить"
        val bottomRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (12 * d).toInt()
            lp.bottomMargin = (8 * d).toInt()
            layoutParams = lp
        }
        fun actionBtn(label: String, iconRes: Int, bg: Int, weight: Float, onClick: () -> Unit): LinearLayout {
            val btn = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                val padV = (14 * d).toInt()
                setPadding((16 * d).toInt(), padV, (16 * d).toInt(), padV)
                background = roundedBg(bg, 12 * d)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
                setOnClickListener { onClick() }
            }
            btn.addView(ImageView(ctx).apply {
                setImageResource(iconRes)
                setColorFilter(WHITE)
                val sz = (20 * d).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz)
            })
            btn.addView(TextView(ctx).apply {
                text = label
                setTextColor(WHITE)
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.marginStart = (8 * d).toInt()
                layoutParams = lp
            })
            return btn
        }
        // «+ Добавить ингредиент» — левая половина (50% ширины, weight=1)
        val addIngBtn = Button(ctx).apply {
            text = "+ Добавить"
            setTextColor(WHITE)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            isAllCaps = false
            setBackgroundResource(R.drawable.dish_add_btn_bg)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, (56 * d).toInt(), 1f)
                .apply { marginEnd = (8 * d).toInt() }
            setOnClickListener {
                showIngredientPicker(ctx, db) { kind, refId ->
                    ingredientsState.add(NutritionDatabase.Ingredient(kind, refId, 100.0))
                    redrawIng()
                }
            }
        }
        bottomRow.addView(addIngBtn)
        // «Добавить / Сохранить» — правая половина (50% ширины, weight=1)
        bottomRow.addView(actionBtn(btnLabel, R.drawable.ic_check, GREEN, 1f) {
            val title = nameEt.text.toString().trim()
            if (title.isBlank()) {
                android.widget.Toast.makeText(ctx, "Введите название", android.widget.Toast.LENGTH_SHORT).show()
                return@actionBtn
            }
            if (ingredientsState.isEmpty()) {
                android.widget.Toast.makeText(ctx, "Добавьте хотя бы один ингредиент",
                    android.widget.Toast.LENGTH_SHORT).show()
                return@actionBtn
            }
            db.upsertDish(NutritionDatabase.Dish(
                id = if (isExisting) existing!!.id else java.util.UUID.randomUUID().toString(),
                name = title,
                servingG = servingG,
                photoPath = photoPath,
                ingredients = ingredientsState.toList()
            ))
            closeCard()
        })
        body.addView(bottomRow)

        // ─── СБОРКА + СВАЙП-ВПРАВО ДЛЯ ЗАКРЫТИЯ ───────────────────
        card.addView(scroll)
        // Добавляем в РОДИТЕЛЯ container (ConstraintLayout), не в сам infoContainer —
        // иначе modeTabs (выше по z-order) торчат поверх карточки
        (card.parent as? ViewGroup)?.removeView(card)
        (container.parent as? ViewGroup)?.addView(card) ?: container.addView(card)
        // Логотип WALDERHU должен быть ВИДИМ поверх карточки
        (ctx as? android.app.Activity)
            ?.findViewById<View>(com.assistant.app.R.id.header)
            ?.bringToFront()
        // Гасим клавиатуру
        hideKeyboard(ctx)
        card.post { hideKeyboard(ctx) }

        // Свайп-вправо: тот же EMA-сглаженный dx, порог 10% ширины, как в showProductView
        val cw = container.width
        val closeThreshold = cw * 0.1f
        var smoothedDx = 0f
        card.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val target = (event.x - card.startX).coerceAtLeast(0f)
                    smoothedDx = smoothedDx * 0.5f + target * 0.5f
                    card.translationX = smoothedDx
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val finalDx = (event.x - card.startX).coerceAtLeast(0f)
                    if (finalDx >= closeThreshold) {
                        card.animate()
                            .translationX(cw.toFloat())
                            .alpha(0f)
                            .setDuration(200)
                            .withEndAction { closeCard() }
                            .start()
                    } else {
                        card.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(200)
                            .start()
                    }
                }
            }
            true
        }
        } catch (e: Throwable) {
            CrashLog.log(ctx, e, "showDishCard")
            android.widget.Toast.makeText(ctx, "Ошибка открытия блюда: ${e.javaClass.simpleName}: ${e.message}",
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // ─── Премиальная карточка продукта (view-only с добавлением в meal) ───

    /**
     * LinearLayout, который умеет перехватывать горизонтальные свайпы через
     * onInterceptTouchEvent — чтобы «украсть» жест у вложенного ScrollView.
     * ScrollView продолжает обрабатывать вертикальные свайпы; горизонтальные
     * (вправо) карточка перехватывает и обрабатывает сама.
     */
    /**
     * ScrollView, который при фокусе дочернего EditText-а сам скроллится так,
     * чтобы сфокусированное поле оказалось в видимой зоне. (Используем
     * OnFocusChangeListener на EditText-ах, потому что requestChildFocus на
     * ScrollView не вызывается — между ним и EditText лежит body-LinearLayout,
     * который обрывает цепочку focus-вызовов.)
     */
    private class AutoScrollScrollView(ctx: Context) : ScrollView(ctx)

    private class SwipeableCard(ctx: Context) : LinearLayout(ctx) {
        var swipeZoneStartFraction = 0.05f
        var startX = 0f
        private var startY = 0f

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.x
                    startY = ev.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - startX
                    val dy = ev.y - startY
                    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                    val isHorizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)  // ±45°
                    val inZone = startX > width * swipeZoneStartFraction
                    if (inZone && kotlin.math.abs(dx) > slop && isHorizontal) {
                        // НЕ обновляем startX — иначе при медленном свайпе
                        // карточка первые touchSlop пикселей «догоняла» бы
                        // палец от точки перехвата, а не от оригинального
                        // тача, и это выглядело как рывки. С оригинальным
                        // startX карточка сразу плавно едет за пальцем.
                        return true
                    }
                }
            }
            return false
        }
    }

    private fun showProductView(
        container: ViewGroup,
        product: NutritionDatabase.Product?,
        customItem: NutritionDatabase.CustomItem?,
        onScanBarcode: ((String?) -> Unit) -> Unit,
        onPickPhoto: (((Uri?) -> Unit) -> Unit)?,
        onTakePhoto: (((Uri?) -> Unit) -> Unit)? = null,
        kindForNew: NutritionDatabase.Kind? = null,
        onPhotoChanged: ((String?) -> Unit)? = null,
        onSaved: () -> Unit = {},
        onClose: () -> Unit
    ) {
        val ctx = container.context
        val d = ctx.resources.displayMetrics.density

        val name = product?.name ?: customItem?.name ?: ""
        var photoPath = product?.photoPath ?: customItem?.photoPath
        val p100 = product?.protein ?: customItem?.protein ?: 0.0
        val f100 = product?.fat ?: customItem?.fat ?: 0.0
        val c100 = product?.carbs ?: customItem?.carbs ?: 0.0
        val k100 = (p100 * 4 + f100 * 9 + c100 * 4).toInt()
        val initialWeight = 100.0

        val BG = 0xFF0F0F0F.toInt()
        val SURFACE = 0xFF1A1A1A.toInt()
        val SURFACE2 = 0xFF232323.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
        val GRAY = 0xFFA0A0A0.toInt()
        val GREEN = 0xFF50C95A.toInt()
        val GREEN_LABEL = 0xFF61C86A.toInt()
        val DIVIDER = android.graphics.Color.argb(20, 255, 255, 255)
        val BORDER = android.graphics.Color.argb(40, 255, 255, 255)

        fun roundedBg(color: Int, radiusPx: Float) = android.graphics.drawable.GradientDrawable().apply {
            setColor(color); cornerRadius = radiusPx
        }
        fun circleBg(color: Int) = android.graphics.drawable.GradientDrawable().apply {
            setColor(color); shape = android.graphics.drawable.GradientDrawable.OVAL
        }
        fun outlineRound(radiusPx: Float) = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
            }
        }

        val card = SwipeableCard(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            // Карточка в ConstraintLayout (родитель infoContainer): сверху
            // прижата к низу header (чтобы WALDERHU-логотип оставался видимым),
            // снизу — к верху bottomContainer (чтобы поле ввода чата
            // оставалось доступным). Слева/справа — на всю ширину.
            // Высота = 0 (MATCH_CONSTRAINT) — иначе MATCH_PARENT игнорит
            // topToBottom/bottomToTop и карточка растягивается на весь
            // ConstraintLayout, перекрывая header. Шапка самой карточки
            // («✕ Продукт») находится ВНУТРИ карточки, поэтому видна
            // сразу под header'ом.
            val lp = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT,
                0  // MATCH_CONSTRAINT — заполняем пространство между constraints
            )
            lp.topToBottom = com.assistant.app.R.id.header
            lp.bottomToTop = com.assistant.app.R.id.bottomContainer
            lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            layoutParams = lp
            swipeZoneStartFraction = 0.05f
        }
        card.tag = CARD_TAG
        // modeTabs НЕ прячем: карточка лежит в infoContainer (ниже табов),
        // табы всегда видны сверху. Иначе при свайпе вправо был бы «прыжок» —
        // табы исчезали при открытии карточки и появлялись только когда closeCard
        // отрабатывал на полном отпускании. Теперь они видны и при половинчатом
        // свайпе, нет скачка.
        fun closeCard() {
            (card.parent as? ViewGroup)?.removeView(card)
            hideKeyboard(ctx)
            onClose()
        }

        // Логотип WALDERHU
        // (убрано — логотип уже есть в основном UI приложения)

        // Шапка: [⋮ ❤] «Продукт» [✕]. Заголовок глобально по центру — два крайних
        // контейнера с weight=1 делят свободное место поровну, заголовок (wrap_content)
        // сидит между ними.
        val appBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1B1B1B.toInt())
            val vPad = (10 * d).toInt()
            val hPad = (16 * d).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            minimumHeight = (44 * d).toInt()
        }
        val appBarTitle = TextView(ctx).apply {
            text = "Продукт"
            setTextColor(0xFFE6E6E6.toInt())
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val closeBtn = TextView(ctx).apply {
            text = "✕"
            setTextColor(0xFFE6E6E6.toInt())
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            isClickable = true
            isFocusable = true
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            setBackgroundResource(android.R.color.transparent)
            setOnClickListener { closeCard() }
        }
        // Состояние «в избранном» — единый источник для визуала и БД
        val heartActiveColor = 0xFFE57373.toInt()
        val heartInactiveColor = 0xFF8A8A8A.toInt()
        var heartState: Boolean = when {
            product != null -> product.favorite
            customItem != null -> customItem.favorite
            else -> false
        }
        val heart = ImageView(ctx).apply {
            setImageResource(R.drawable.like)
            layoutParams = LinearLayout.LayoutParams(
                (24 * d).toInt(), (24 * d).toInt()
            )
            contentDescription = "В избранном"
            isClickable = true
            isFocusable = true
            setColorFilter(if (heartState) heartActiveColor else heartInactiveColor)
            setOnClickListener {
                heartState = !heartState
                setColorFilter(if (heartState) heartActiveColor else heartInactiveColor)
                // Для существующей карточки сразу пишем в БД (без ожидания Save)
                val db = NutritionDatabase(ctx)
                if (product != null) db.setProductFavorite(product.id, heartState)
                else if (customItem != null) db.setCustomItemFavorite(customItem.id, heartState)
            }
        }
        val moreBtn = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_more_vert)
            layoutParams = LinearLayout.LayoutParams(
                (24 * d).toInt(), (24 * d).toInt()
            ).apply { marginEnd = (8 * d).toInt() }
            contentDescription = "Меню"
            isClickable = true
            isFocusable = true
            setColorFilter(0xFFE6E6E6.toInt())
            setOnClickListener {
                val view = android.view.LayoutInflater.from(ctx)
                    .inflate(com.assistant.app.R.layout.popup_card_menu, null, false)
                val popup = android.widget.PopupWindow(
                    view,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    true
                )
                popup.setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(0xFF181818.toInt())
                )
                popup.isOutsideTouchable = true
                val tint = 0xFF8A8A8A.toInt()
                for (i in 0 until (view as android.view.ViewGroup).childCount) {
                    val row = view.getChildAt(i) as? android.view.ViewGroup ?: continue
                    val icon = row.getChildAt(0) as? android.widget.ImageView ?: continue
                    icon.setColorFilter(tint)
                }
                val isExisting = product != null || customItem != null
                val dupRow = view.findViewById<View>(com.assistant.app.R.id.menu_duplicate)
                dupRow.visibility = if (isExisting) View.VISIBLE else View.GONE
                dupRow.setOnClickListener {
                    popup.dismiss()
                    // Снимаем текущую карточку (без сохранения правок формы) и открываем копию
                    (card.parent as? ViewGroup)?.removeView(card)
                    hideKeyboard(ctx)
                    val (prod, cust) = if (product != null) {
                        product.copy(id = java.util.UUID.randomUUID().toString()) to null
                    } else if (customItem != null) {
                        null to customItem.copy(id = java.util.UUID.randomUUID().toString())
                    } else null to null
                    showProductView(
                        container = container,
                        product = prod,
                        customItem = cust,
                        onScanBarcode = onScanBarcode,
                        onPickPhoto = onPickPhoto,
                        onTakePhoto = onTakePhoto,
                        onPhotoChanged = onPhotoChanged,
                        onSaved = onSaved,
                        onClose = {}
                    )
                }
                val delRow = view.findViewById<View>(com.assistant.app.R.id.menu_delete)
                delRow.setOnClickListener {
                    popup.dismiss()
                    if (!isExisting) {
                        // Новая карточка — закрываем без сохранения, как X
                        (card.parent as? ViewGroup)?.removeView(card)
                        hideKeyboard(ctx)
                        return@setOnClickListener
                    }
                    AlertDialog.Builder(ctx)
                        .setTitle("Удалить карточку?")
                        .setMessage("Это действие нельзя отменить.")
                        .setPositiveButton("Удалить") { _, _ ->
                            val db = NutritionDatabase(ctx)
                            if (product != null) db.deleteProduct(product.id)
                            else if (customItem != null) db.deleteCustomItem(customItem.id)
                            (card.parent as? ViewGroup)?.removeView(card)
                            hideKeyboard(ctx)
                            onSaved()
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
                popup.showAsDropDown(this, 0, 0)
            }
        }
        // Левый и правый контейнеры с weight=1 делят свободное место поровну —
        // заголовок (wrap_content) оказывается ровно по центру шапки.
        val leftGroup = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            addView(moreBtn)
            addView(heart)
        }
        val rightGroup = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            addView(closeBtn)
        }
        appBar.addView(leftGroup)
        appBar.addView(appBarTitle)
        appBar.addView(rightGroup)
        card.addView(appBar)

        card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                // modeTabs больше не трогаем — они всегда видимы (см. closeCard)
                v.removeOnAttachStateChangeListener(this)
            }
        })

        // Scroll + body
        val scroll = AutoScrollScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val hPad = (20 * d).toInt()
            val vPad = (20 * d).toInt()
            setPadding(hPad, vPad, hPad, vPad)
        }
        scroll.addView(body)

        // Фото 16:9 (220dp) — большая кликабельная область для смены фотки
        val photo = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (photoPath != null) {
                runCatching { setImageURI(Uri.fromFile(File(photoPath))) }
            } else {
                setImageResource(R.drawable.food)
                alpha = 0.4f
            }
            setBackgroundColor(SURFACE2)
            clipToOutline = true
            outlineProvider = outlineRound(24 * d)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                // Попап-меню: «Загрузить с телефона» / «Сделать снимок» —
                // как троеточие в боковом меню чатов
                val view = android.view.LayoutInflater.from(ctx)
                    .inflate(com.assistant.app.R.layout.popup_photo_menu, null, false)
                val popup = android.widget.PopupWindow(
                    view,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    true
                )
                popup.setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(0xFF181818.toInt())
                )
                popup.isOutsideTouchable = true
                // Тинт иконок
                val tint = 0xFF8A8A8A.toInt()
                for (i in 0 until (view as android.view.ViewGroup).childCount) {
                    val row = view.getChildAt(i) as? android.view.ViewGroup ?: continue
                    val icon = row.getChildAt(0) as? android.widget.ImageView ?: continue
                    icon.setColorFilter(tint)
                }
                fun pickWith(cb: (((Uri?) -> Unit) -> Unit)?) {
                    cb?.invoke { uri ->
                        if (uri == null) return@invoke
                        val newPath = copyPhoto(ctx, uri) ?: return@invoke
                        photoPath = newPath
                        runCatching { setImageURI(Uri.fromFile(File(newPath))) }
                        alpha = 1.0f
                        onPhotoChanged?.invoke(newPath)
                    }
                }
                view.findViewById<View>(com.assistant.app.R.id.menu_gallery)
                    .setOnClickListener { popup.dismiss(); pickWith(onPickPhoto) }
                view.findViewById<View>(com.assistant.app.R.id.menu_camera)
                    .setOnClickListener { popup.dismiss(); pickWith(onTakePhoto) }
                popup.showAsDropDown(this, 0, 0)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (220 * d).toInt()
            )
        }
        body.addView(photo)

        // Имя продукта
        val nameLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        nameLp.topMargin = (20 * d).toInt()
        // Список EditText-ов КБЖУ (объявлен ДО nameEt, чтобы onEditorAction мог ссылаться)
        val bjuValueEts = mutableListOf<EditText>()
        val bjuLabels = listOf("К", "Б", "Ж", "У")
        // Название продукта — редактируемое поле, фон прозрачный (сливается с body)
        val nameEt = EditText(ctx).apply {
            setText(name)
            setTextColor(WHITE)
            setHintTextColor(GRAY)
            textSize = 30f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_NEXT
            layoutParams = nameLp
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_NEXT && bjuValueEts.isNotEmpty()) {
                    bjuValueEts[0].requestFocus()
                    true
                } else false
            }
        }
        body.addView(nameEt)

        // Pill «На X грамм» — кликабельный, можно править граммовку.
        // КБЖУ ниже пересчитывается на лету; в БД всегда хранится «на 100 г».
        var pillWeight = 100
        // per-100g значения (источник истины), обновляются TextWatcher-ом при правке
        var p100u = p100
        var f100u = f100
        var c100u = c100
        var k100u = k100.toDouble()
        // Подавляем TextWatcher при программной установке текста
        var suppressWatcher = false
        // Холдер для ссылки на weightValue (объявляется ниже по коду,
        // но нужен в OnEditorActionListener внутри bjuCell)
        var weightValueHolder: EditText? = null
        fun updateBjuDisplay() {
            val factor = pillWeight / 100.0
            val pDisp = p100u * factor
            val fDisp = f100u * factor
            val cDisp = c100u * factor
            val kDisp = k100u * factor
            suppressWatcher = true
            try {
                bjuLabels.forEachIndexed { idx, label ->
                    val newText = when (label) {
                        "К" -> kDisp.toInt().toString()
                        "Б" -> fmtNum(pDisp)
                        "Ж" -> fmtNum(fDisp)
                        "У" -> fmtNum(cDisp)
                        else -> ""
                    }
                    val et = bjuValueEts[idx]
                    if (et.text.toString() != newText) {
                        // Обходим InputFilter при программной записи (например, скан).
                        // Ручной ввод по-прежнему ограничен 3 символами / 2 десятичными.
                        val saved = et.filters
                        et.filters = emptyArray()
                        et.setText(newText)
                        et.filters = saved
                        et.setSelection(et.text.length)
                    }
                }
            } finally {
                suppressWatcher = false
            }
        }
        // (applyScannedProduct переехал ниже — после weightCard,
        //  чтобы иметь доступ к pillText, photo, photoPath)
        val pillWrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (10 * d).toInt()
            lp.bottomMargin = (20 * d).toInt()
            layoutParams = lp
        }
        val pillBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(SURFACE)
            cornerRadius = 28 * d
            setStroke((1 * d).toInt(), BORDER)
        }
        val pillText = TextView(ctx).apply {
            text = "На $pillWeight грамм"
            setTextColor(WHITE)
            textSize = 14f
            setPadding(
                (20 * d).toInt(), (10 * d).toInt(),
                (20 * d).toInt(), (10 * d).toInt()
            )
            background = pillBg
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val input = EditText(ctx).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText(pillWeight.toString())
                    setTextColor(WHITE)
                    setHintTextColor(GRAY)
                    setBackgroundColor(0xFF2B2B2B.toInt())
                    val pad = (12 * d).toInt()
                    setPadding(pad, pad, pad, pad)
                    textSize = 16f
                    setSelectAllOnFocus(true)
                }
                val dlgContainer = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(
                        (24 * d).toInt(), (16 * d).toInt(),
                        (24 * d).toInt(), (8 * d).toInt()
                    )
                    addView(TextView(ctx).apply {
                        text = "Граммовка для отображения КБЖУ.\n" +
                            "В базе хранится всегда «на 100 г» — твоё значение " +
                            "используется только для удобного просмотра."
                        setTextColor(GRAY)
                        textSize = 12f
                    })
                    val pad = (8 * d).toInt()
                    input.setPadding(pad, pad, pad, pad)
                    addView(input)
                }
                AlertDialog.Builder(ctx)
                    .setTitle("Вес для отображения")
                    .setView(dlgContainer)
                    .setPositiveButton("OK") { _, _ ->
                        val newW = input.text.toString().toIntOrNull()?.coerceAtLeast(10) ?: 100
                        pillWeight = newW
                        text = "На $newW грамм"
                        updateBjuDisplay()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        }
        pillWrap.addView(pillText)
        body.addView(pillWrap)

        // Фильтр ввода для числовых полей: не больше maxLen символов,
        // не больше maxDecimals знаков после точки
        fun decimalInputFilter(maxLen: Int, maxDecimals: Int = 2): InputFilter =
            InputFilter { source, start, end, dest, dstart, dend ->
                val sb = StringBuilder(dest)
                sb.replace(dstart, dend, source.subSequence(start, end).toString())
                val res = sb.toString()
                if (res.isEmpty()) null  // разрешаем очистку поля
                else if (!res.matches(Regex("^\\d*\\.?\\d*$"))) ""  // не цифры / две точки — режектим
                else if (res.indexOf('.').let { it >= 0 && res.length - it - 1 > maxDecimals }) ""
                else if (res.length > maxLen) ""
                else null
            }

        // Карточка КБЖУ 2×2
        val bjuCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SURFACE)
            clipToOutline = true
            outlineProvider = outlineRound(24 * d)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (20 * d).toInt()
            layoutParams = lp
        }
        fun bjuCell(icon: String, isIcon: Boolean, value: String, unit: String = ""): LinearLayout {
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (12 * d).toInt()
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val topRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topRow.addView(TextView(ctx).apply {
                text = icon
                if (isIcon) {
                    textSize = 18f
                } else {
                    setTextColor(GRAY)
                    textSize = 18f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = (8 * d).toInt()
                layoutParams = lp
            })
            // Значение КБЖУ — редактируемое поле, фон прозрачный (сливается с ячейкой)
            val valueEt = EditText(ctx).apply {
                setText(value)
                setTextColor(WHITE)
                setHintTextColor(GRAY)
                textSize = 32f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isSingleLine = true
                includeFontPadding = false
                setPadding(0, (2 * d).toInt(), 0, (2 * d).toInt())
                inputType = if (icon == "К") InputType.TYPE_CLASS_NUMBER
                    else InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                imeOptions = EditorInfo.IME_ACTION_NEXT
                filters = if (icon == "К") arrayOf(InputFilter.LengthFilter(5))
                    else arrayOf(decimalInputFilter(3, 2))
                setSelectAllOnFocus(true)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnEditorActionListener { v, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_NEXT) {
                        val idx = bjuValueEts.indexOf(v as EditText)
                        val nextIdx = idx + 1
                        if (nextIdx < bjuValueEts.size) {
                            bjuValueEts[nextIdx].requestFocus()
                        } else {
                            // Последняя ячейка (У) → фокус на «Вес порции»
                            weightValueHolder?.requestFocus()
                        }
                        true
                    } else false
                }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        if (suppressWatcher) return
                        val text = s?.toString()?.trim() ?: return
                        if (text.isEmpty()) return
                        val typed = text.toDoubleOrNull()?.coerceAtLeast(0.0) ?: return
                        // Юзер правит то, что видит (с учётом pill-веса), переводим обратно в per-100g
                        val per100 = if (pillWeight > 0) typed * 100.0 / pillWeight else typed
                        when (icon) {
                            "К" -> k100u = per100
                            "Б" -> p100u = per100
                            "Ж" -> f100u = per100
                            "У" -> c100u = per100
                        }
                    }
                })
            }
            topRow.addView(valueEt)
            if (unit.isNotEmpty()) {
                topRow.addView(TextView(ctx).apply {
                    text = " $unit"
                    setTextColor(WHITE)
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 0, 0, (4 * d).toInt())
                })
            }
            cell.addView(topRow)
            bjuValueEts.add(valueEt)
            return cell
        }
        fun vDiv() = View(ctx).apply {
            setBackgroundColor(DIVIDER)
            // Тонкий разделитель ~60% высоты родителя, центрирован по вертикали
            val lp = LinearLayout.LayoutParams(
                (1 * d).toInt(),
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            lp.topMargin = (16 * d).toInt()
            lp.bottomMargin = (16 * d).toInt()
            layoutParams = lp
        }
        fun hDiv() = View(ctx).apply {
            setBackgroundColor(DIVIDER)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * d).toInt())
        }
        val bjuRow1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        bjuRow1.addView(bjuCell("К", false, k100.toString()))
        bjuRow1.addView(vDiv())
        bjuRow1.addView(bjuCell("Б", false, fmtNum(p100)))
        bjuCard.addView(bjuRow1)
        bjuCard.addView(hDiv())
        val bjuRow2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        bjuRow2.addView(bjuCell("Ж", false, fmtNum(f100)))
        bjuRow2.addView(vDiv())
        bjuRow2.addView(bjuCell("У", false, fmtNum(c100)))
        bjuCard.addView(bjuRow2)
        body.addView(bjuCard)

        // Карточка «Вес порции»
        var weightG = initialWeight
        val weightValue = EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(decimalInputFilter(4, 2))
            setTextColor(WHITE)
            setHintTextColor(GRAY)
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            isFocusable = true
            isFocusableInTouchMode = true
            setSelectAllOnFocus(true)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun setWeight(w: Double) {
            weightG = w.coerceIn(10.0, 2000.0)
            val txt = "${weightG.toInt()}"
            if (weightValue.text.toString() != txt) {
                weightValue.setText(txt)
                weightValue.setSelection(txt.length)
            }
        }
        weightValue.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val v = s?.toString()?.toIntOrNull() ?: return
                if (v.toDouble() != weightG) weightG = v.toDouble().coerceIn(10.0, 2000.0)
            }
        })
        weightValueHolder = weightValue
        setWeight(weightG)

        val weightCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SURFACE)
            clipToOutline = true
            outlineProvider = outlineRound(24 * d)
            val padV = (12 * d).toInt()
            val padH = (14 * d).toInt()
            setPadding(padH, padV, padH, padV)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (24 * d).toInt()
            layoutParams = lp
        }
        // Заголовок «Вес порции» — отдельной подписью НАД карточкой
        body.addView(TextView(ctx).apply {
            text = "Вес порции"
            setTextColor(GRAY)
            textSize = 12f
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (6 * d).toInt()
            layoutParams = lp
        })
        val weightRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun circleBtn(text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
            this.text = text
            setTextColor(WHITE)
            textSize = 28f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            val sz = (36 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            background = circleBg(SURFACE)
            setOnClickListener { onClick() }
        }
        weightRow.addView(circleBtn("−") { setWeight(weightG - 10) })
        weightRow.addView(weightValue)
        weightRow.addView(circleBtn("+") { setWeight(weightG + 10) })
        weightCard.addView(weightRow)
        body.addView(weightCard)

        // Применить данные отсканированного продукта: имя, per-100g КБЖУ,
        // вес порции (servingG), фото (если есть). Pill сбрасывается на 100 г.
        // Объявлена здесь, чтобы иметь доступ к pillText, photo, photoPath.
        fun applyScannedProduct(
            name: String, p: Double, f: Double, c: Double,
            servingG: Double? = null, newPhotoPath: String? = null
        ) {
            // 1) Название
            nameEt.setText(name)
            // 2) per-100g КБЖУ
            p100u = p
            f100u = f
            c100u = c
            k100u = p * 4 + f * 9 + c * 4
            // 3) Pill — сброс на 100 г
            pillWeight = 100
            pillText.text = "На $pillWeight грамм"
            updateBjuDisplay()
            // 4) Вес порции — из servingG, если пришёл
            if (servingG != null && servingG > 0) {
                setWeight(servingG)
            }
            // 5) Фото — если локальный продукт содержит photoPath
            if (newPhotoPath != null) {
                runCatching { photo.setImageURI(Uri.fromFile(File(newPhotoPath))) }
                photo.alpha = 1.0f
                photoPath = newPhotoPath
            }
        }

        // Нижние кнопки
        val bottomRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun actionBtn(label: String, iconRes: Int, bg: Int, weight: Float, onClick: () -> Unit): LinearLayout {
            val btn = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                val padV = (14 * d).toInt()
                setPadding((16 * d).toInt(), padV, (16 * d).toInt(), padV)
                background = roundedBg(bg, 12 * d)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
                setOnClickListener { onClick() }
            }
            btn.addView(ImageView(ctx).apply {
                setImageResource(iconRes)
                setColorFilter(WHITE)
                val sz = (20 * d).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz)
            })
            btn.addView(TextView(ctx).apply {
                text = label
                setTextColor(WHITE)
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.marginStart = (8 * d).toInt()
                layoutParams = lp
            })
            return btn
        }
        bottomRow.addView(actionBtn("Скан", R.drawable.scan, SURFACE2, 1f) {
            onScanBarcode { scanned ->
                if (scanned.isNullOrBlank()) {
                    android.widget.Toast.makeText(ctx, "Не удалось распознать",
                        android.widget.Toast.LENGTH_SHORT).show()
                    return@onScanBarcode
                }
                // 1) Сначала локальная БД — быстро и офлайн
                val lookupDb = NutritionDatabase(ctx)
                val local = lookupDb.findProductByBarcode(scanned)
                if (local != null) {
                    applyScannedProduct(
                        local.name, local.protein, local.fat, local.carbs,
                        servingG = local.servingG, newPhotoPath = local.photoPath
                    )
                    // Успех — молча подставляем, без тоста
                } else {
                    // 2) Не нашли — пробуем OpenFoodFacts
                    val scope = CoroutineScope(Dispatchers.Main + Job())
                    scope.launch {
                        val parsed = ProductLookupClient.fetchStructured(scanned)
                        if (parsed == null) {
                            android.widget.Toast.makeText(ctx, "Штрихкод не найден",
                                android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            applyScannedProduct(
                                parsed.name, parsed.protein, parsed.fat, parsed.carbs,
                                servingG = parsed.servingG
                            )
                            // Успех — молча подставляем, без тоста
                        }
                    }
                }
            }
        })
        bottomRow.addView(android.widget.Space(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((12 * d).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
        })
        // Зелёная кнопка: «Сохранить» если карточка уже в БД, «Добавить» если новая.
        val isExisting = product != null || customItem != null
        val btnLabel = if (isExisting) "Сохранить" else "Добавить"
        bottomRow.addView(actionBtn(btnLabel, R.drawable.ic_check, GREEN, 1f) {
            val finalName = nameEt.text.toString().trim().ifBlank { name }
            val db = NutritionDatabase(ctx)
            if (isExisting) {
                // Обновляем существующую запись
                if (product != null) {
                    db.upsertProduct(product.copy(
                        name = finalName,
                        protein = p100u, fat = f100u, carbs = c100u,
                        photoPath = photoPath,
                        favorite = heartState
                    ))
                } else if (customItem != null) {
                    db.upsertCustomItem(customItem.copy(
                        name = finalName,
                        protein = p100u, fat = f100u, carbs = c100u,
                        photoPath = photoPath,
                        favorite = heartState
                    ))
                }
            } else {
                // Создаём новую запись
                if (kindForNew == NutritionDatabase.Kind.PRODUCT) {
                    db.upsertProduct(NutritionDatabase.Product(
                        id = java.util.UUID.randomUUID().toString(),
                        name = finalName,
                        protein = p100u, fat = f100u, carbs = c100u,
                        photoPath = photoPath,
                        favorite = heartState
                    ))
                } else {
                    db.upsertCustomItem(NutritionDatabase.CustomItem(
                        id = java.util.UUID.randomUUID().toString(),
                        name = finalName,
                        protein = p100u, fat = f100u, carbs = c100u,
                        photoPath = photoPath,
                        favorite = heartState
                    ))
                }
            }
            closeCard()
            onSaved()
        })
        body.addView(bottomRow)

        // Авто-скролл в самый низ карточки при фокусе любого EditText-а.
        // scrollTo (мгновенно) вместо fullScroll — последний делает smooth-
        // анимацию и в процессе теряет фокус с EditText-а, после чего система
        // передаёт фокус первому focusable в дереве (имени), listener на
        // имени снова скроллит, фокус прыгает обратно — бесконечный цикл.
        // scrollTo не трогает focus и не анимирует — никаких прыжков и
        // дёрганий. post (без задержки) — на следующий кадр, чтобы focus
        // гарантированно установился.
        fun attachFocusAutoScroll(v: View) {
            if (v is EditText) {
                v.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        v.post {
                            val child = scroll.getChildAt(0)
                            if (child != null) {
                                scroll.scrollTo(0, child.height - scroll.height)
                            }
                        }
                    }
                }
            } else if (v is ViewGroup) {
                for (i in 0 until v.childCount) attachFocusAutoScroll(v.getChildAt(i))
            }
        }
        attachFocusAutoScroll(body)

        card.addView(scroll)
        // Карточку добавляем в РОДИТЕЛЯ container (ConstraintLayout), а не в
        // сам infoContainer — иначе modeTabs (выше по z-order) торчат поверх
        // карточки. Когда карточка в ConstraintLayout, она их перекрывает
        // (added last → on top), а при свайпе вправо modeTabs «выезжают»
        // из-под карточки. У card.layoutParams MATCH_PARENT — она
        // закрывает весь ConstraintLayout (включая modeTabs и
        // bottomContainer). Шапка самой карточки («✕ Продукт») на месте,
        // не меняем.
        (card.parent as? ViewGroup)?.removeView(card)
        (container.parent as? ViewGroup)?.addView(card) ?: container.addView(card)
        // Header с логотипом WALDERHU (ic_header.png) должен быть ВИДИМ
        // поверх карточки на всех страничках — принудительно поднимаем
        // его в z-order, иначе ConstraintLayout рисует карточку (added
        // last) поверх header'а.
        (ctx as? android.app.Activity)
            ?.findViewById<View>(com.assistant.app.R.id.header)
            ?.bringToFront()
        // Явно гасим клавиатуру при открытии карточки: и сразу, и после layout —
        // на случай если первый EditText внутри перехватил фокус
        hideKeyboard(ctx)
        card.post { hideKeyboard(ctx) }

        // Свайп-вправо для закрытия карточки (как back в Telegram).
        // Логика: onInterceptTouchEvent крадёт жест у ScrollView один раз,
        // дальше OnTouchListener на каждый MOVE применяет EMA-сглаживание
        // к dx (new = old*0.5 + target*0.5). Это съедает микро-движения
        // пальца (OS шлёт MOVE с дельтами в 1-2px даже когда юзер «держит»
        // палец на месте — карточка без сглаживания прыгала на ~10px).
        // Карточка плавно «догоняет» target с задержкой в ~1 фрейм.
        // На UP порог проверяем по raw-значению (не smoothed), чтобы
        // пользователь не терял чувствительность к 10%-порогу.
        val cw = container.width
        val closeThreshold = cw * 0.1f
        var smoothedDx = 0f
        card.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val target = (event.x - card.startX).coerceAtLeast(0f)
                    smoothedDx = smoothedDx * 0.5f + target * 0.5f
                    card.translationX = smoothedDx
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val finalDx = (event.x - card.startX).coerceAtLeast(0f)
                    if (finalDx >= closeThreshold) {
                        card.animate()
                            .translationX(cw.toFloat())
                            .alpha(0f)
                            .setDuration(200)
                            .withEndAction { closeCard() }
                            .start()
                    } else {
                        card.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(200)
                            .start()
                    }
                }
            }
            true
        }
    }

    /**
     * Полноэкранный пикер продуктов для добавления ингредиента в блюдо.
     * Повторяет визуал таба «Продукты» (как в renderProductsTab): строка поиска
     * сверху, ниже — карточки с фото/названием/КБЖУ, тап на карточку или на
     * зелёный плюсик добавляет продукт в ингредиенты блюда, ✕ закрывает пикер.
     */
    private fun showIngredientPicker(
        ctx: Context,
        db: NutritionDatabase,
        onPicked: (NutritionDatabase.Kind, String) -> Unit
    ) {
        val d = ctx.resources.displayMetrics.density
        val dialog = android.app.Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F0F0F.toInt())
        }
        dialog.setContentView(root)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        // AppBar: [✕] «Выберите продукт» — 44dp #1B1B1B
        val appBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1B1B1B.toInt())
            val vPad = (10 * d).toInt()
            val hPad = (16 * d).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            minimumHeight = (44 * d).toInt()
        }
        val closeBtn = TextView(ctx).apply {
            text = "✕"
            setTextColor(0xFFE6E6E6.toInt())
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            isClickable = true
            isFocusable = true
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            setBackgroundResource(android.R.color.transparent)
            setOnClickListener { dialog.dismiss() }
        }
        val title = TextView(ctx).apply {
            text = "Выберите продукт"
            setTextColor(0xFFE6E6E6.toInt())
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val leftSpacer = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                (40 * d).toInt(), ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        appBar.addView(leftSpacer)
        appBar.addView(title)
        appBar.addView(closeBtn)
        root.addView(appBar)

        // Поиск
        val search = EditText(ctx).apply {
            styleChatInput(ctx, this, "Поиск")
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt()) }
        }
        root.addView(search)

        // Контейнер для списка карточек продуктов
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * d).toInt(), 0, (24 * d).toInt())
        }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(list)
        }
        root.addView(scroll)

        // Помощник: преобразовать ItemCard в (Kind, id)
        fun kindId(card: ItemCard): Pair<NutritionDatabase.Kind, String>? = when (card) {
            is ItemCard.Product -> NutritionDatabase.Kind.PRODUCT to card.p.id
            is ItemCard.Custom -> NutritionDatabase.Kind.CUSTOM to card.c.id
        }
        // В режиме пикера тап на + или на саму карточку = выбор ингредиента
        val onPickClick: (ItemCard) -> Unit = { card ->
            val pair = kindId(card)
            if (pair != null) {
                onPicked(pair.first, pair.second)
                dialog.dismiss()
            }
        }
        var refreshList: () -> Unit = {}

        fun redraw() {
            val q = search.text.toString().trim().lowercase()
            val all = mutableListOf<ItemCard>()
            db.listProducts().forEach { all += ItemCard.Product(it) }
            db.listCustomItems().forEach { all += ItemCard.Custom(it) }
            val filtered = all.filter { card ->
                when (card) {
                    is ItemCard.Product -> q.isBlank() || card.p.name.lowercase().contains(q) || card.p.brand.lowercase().contains(q)
                    is ItemCard.Custom -> q.isBlank() || card.c.name.lowercase().contains(q)
                }
            }.sortedWith(compareByDescending<ItemCard> { it.isFavorite }
                .thenBy { it.name.lowercase() })
            if (filtered.isEmpty()) {
                list.removeAllViews()
                list.addView(TextView(ctx).apply {
                    text = if (q.isBlank()) "В базе пока нет продуктов"
                        else "Ничего не найдено"
                    setTextColor(TEXT_HINT)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(0, (48 * d).toInt(), 0, 0)
                })
                return
            }
            renderItemCards(ctx, list, filtered, db, refreshList, onScanBarcode = { /* no scan in picker */ },
                onMealClick = { _ -> /* no meal-add in picker */ },
                onView = onPickClick,  // тап на карточку или на + = pick
                onEdit = { /* no edit in picker */ },
                onDelete = { /* no delete in picker */ }
            )
        }
        refreshList = ::redraw
        redraw()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = redraw()
        })

        dialog.show()
    }

    // ─── Рендер списков ───

    private sealed class ItemCard {
        abstract val name: String
        abstract val isFavorite: Boolean
        data class Product(val p: NutritionDatabase.Product) : ItemCard() {
            override val name: String get() = p.name
            override val isFavorite: Boolean get() = p.favorite
        }
        data class Custom(val c: NutritionDatabase.CustomItem) : ItemCard() {
            override val name: String get() = c.name
            override val isFavorite: Boolean get() = c.favorite
        }
    }

    private fun renderItemCards(
        ctx: Context,
        list: LinearLayout,
        cards: List<ItemCard>,
        db: NutritionDatabase,
        refreshList: () -> Unit,
        onScanBarcode: ((String?) -> Unit) -> Unit,
        onMealClick: (NutritionDatabase.Product) -> Unit,
        onView: (ItemCard) -> Unit,
        onEdit: (ItemCard) -> Unit,
        onDelete: (ItemCard) -> Unit
    ) {
        val d = ctx.resources.displayMetrics.density
        list.removeAllViews()
        if (cards.isEmpty()) {
            list.addView(TextView(ctx).apply {
                text = "Пусто"
                setTextColor(TEXT_HINT)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, (24 * d).toInt(), 0, 0)
            })
            return
        }
        cards.forEach { card ->
            val p = when (card) {
                is ItemCard.Product -> card.p
                is ItemCard.Custom -> null
            }
            val c = (card as? ItemCard.Custom)?.c
            val title = p?.name ?: c?.name ?: "?"
            val subtitle = when {
                p != null -> (if (p.brand.isNotBlank()) "${p.brand} · " else "") +
                    "${p.kcal} ккал · Б ${fmtNum(p.protein)} · Ж ${fmtNum(p.fat)} · У ${fmtNum(p.carbs)} (100 г)" +
                    if (p.servingG > 0) "  · порция ${fmtNum(p.servingG)} г" else ""
                c != null -> "${c.kcal} ккал · Б ${fmtNum(c.protein)} · Ж ${fmtNum(c.fat)} · У ${fmtNum(c.carbs)} (100 г)" +
                    if (c.servingG > 0) "  · порция ${fmtNum(c.servingG)} г" else ""
                else -> ""
            }
            val photo = p?.photoPath ?: c?.photoPath
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * d).toInt(), (10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt())
                setBackgroundResource(R.drawable.meal_card_bg)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * d).toInt() }
                isClickable = true
                isFocusable = true
                isLongClickable = true
                setOnClickListener { onView(card) }
                setOnLongClickListener { onEdit(card); true }
            }
            val img = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt())
                    .apply { marginEnd = (10 * d).toInt() }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF2B2B2B.toInt())
                photo?.let { setImageURI(Uri.fromFile(File(it))) }
            }
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(ctx).apply {
                text = title
                setTextColor(TEXT_PRIMARY)
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            texts.addView(TextView(ctx).apply {
                text = subtitle
                setTextColor(TEXT_HINT)
                textSize = 12f
            })
            row.addView(img)
            row.addView(texts)
            // Сердечко слева от плюсика — переключает «избранное».
            // При сортировке избранные уходят наверх, между собой — по алфавиту.
            val isFav = card.isFavorite
            val heart = ImageButton(ctx).apply {
                setImageResource(if (isFav) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
                setBackgroundColor(Color.TRANSPARENT)
                setColorFilter(if (isFav) 0xFFE57373.toInt() else TEXT_HINT)
                contentDescription = if (isFav) "Убрать из избранного" else "В избранное"
                setOnClickListener {
                    val newFav = !card.isFavorite
                    when (card) {
                        is ItemCard.Product -> db.setProductFavorite(card.p.id, newFav)
                        is ItemCard.Custom -> db.setCustomItemFavorite(card.c.id, newFav)
                    }
                    // Перерисовка списка — sortByFavorite + алфавит сработают в redraw()
                    refreshList()
                }
            }
            // Вертикальное троеточие → попап «Создать копию / Удалить карточку».
            // Используем ту же разметку popup_card_menu, что и в карточке продукта.
            val moreBtn = ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_more_vert)
                setBackgroundColor(Color.TRANSPARENT)
                setColorFilter(TEXT_HINT)
                contentDescription = "Меню"
                setOnClickListener { anchor ->
                    val popupView = android.view.LayoutInflater.from(ctx)
                        .inflate(R.layout.popup_card_menu, null, false)
                    val popup = android.widget.PopupWindow(
                        popupView,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        true
                    )
                    popup.setBackgroundDrawable(
                        android.graphics.drawable.ColorDrawable(0xFF181818.toInt())
                    )
                    popup.isOutsideTouchable = true
                    // Тинт иконок в пунктах меню под общий стиль
                    val iconTint = 0xFF8A8A8A.toInt()
                    for (i in 0 until (popupView as ViewGroup).childCount) {
                        val row0 = popupView.getChildAt(i) as? ViewGroup ?: continue
                        val icon = row0.getChildAt(0) as? ImageView ?: continue
                        icon.setColorFilter(iconTint)
                    }
                    // Создать копию — открываем карточку-дубликат (новая запись в БД сохранится при onSave).
                    val dupRow = popupView.findViewById<View>(R.id.menu_duplicate)
                    dupRow.setOnClickListener {
                        popup.dismiss()
                        val (prod, cust) = when (card) {
                            is ItemCard.Product -> card.p.copy(id = java.util.UUID.randomUUID().toString()) to null
                            is ItemCard.Custom -> null to card.c.copy(id = java.util.UUID.randomUUID().toString())
                        }
                        showProductView(
                            container = list,
                            product = prod,
                            customItem = cust,
                            onScanBarcode = onScanBarcode,
                            onPickPhoto = null,
                            onTakePhoto = null,
                            onPhotoChanged = { _ -> refreshList() },
                            onSaved = { refreshList() },
                            onClose = {}
                        )
                    }
                    // Удалить — подтверждение + удаление из БД + перерисовка
                    val delRow = popupView.findViewById<View>(R.id.menu_delete)
                    delRow.setOnClickListener {
                        popup.dismiss()
                        android.app.AlertDialog.Builder(ctx)
                            .setTitle("Удалить карточку?")
                            .setMessage("Это действие нельзя отменить.")
                            .setPositiveButton("Удалить") { _, _ ->
                                when (card) {
                                    is ItemCard.Product -> db.deleteProduct(card.p.id)
                                    is ItemCard.Custom -> db.deleteCustomItem(card.c.id)
                                }
                                refreshList()
                            }
                            .setNegativeButton("Отмена", null)
                            .show()
                    }
                    // Показываем попап с привязкой к кнопке-источнику
                    popup.showAsDropDown(anchor, 0, 0)
                }
            }
            row.addView(moreBtn, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
            row.addView(heart, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
            if (p != null) {
                val plus = ImageButton(ctx).apply {
                    setImageResource(R.drawable.ic_plus)
                    setBackgroundColor(Color.TRANSPARENT)
                    setColorFilter(0xFF4CAF50.toInt())
                    setOnClickListener { onView(card) }
                    setOnLongClickListener { onMealClick(p); true }
                }
                row.addView(plus, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
            }
            list.addView(row)
        }
    }

    private fun renderDishCards(
        ctx: Context,
        list: LinearLayout,
        db: NutritionDatabase,
        dishes: List<NutritionDatabase.Dish>,
        onEdit: (NutritionDatabase.Dish) -> Unit,
        onAddToMeal: ((NutritionDatabase.Dish) -> Unit)? = null,
        onRefresh: () -> Unit = {}
    ) {
        val d = ctx.resources.displayMetrics.density
        list.removeAllViews()
        if (dishes.isEmpty()) {
            list.addView(TextView(ctx).apply {
                text = "Нет блюд"
                setTextColor(TEXT_HINT)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, (24 * d).toInt(), 0, 0)
            })
            return
        }
        val selected = mutableSetOf<String>()
        val countTv = TextView(ctx).apply {
            setTextColor(TEXT_PRIMARY)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        // Замыкания ниже вызывают updateBar() — объявляем как var и
        // присваиваем реальную реализацию после создания view-шек.
        var updateBar: () -> Unit = {}
        val dupBtn = TextView(ctx).apply {
            text = "Дублировать"
            setTextColor(0xFF0F0F0F.toInt())
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            setBackgroundColor(0xFF4CAF50.toInt())
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * d).toInt() }
            setOnClickListener {
                val toDup = dishes.filter { it.id in selected }
                toDup.forEach { dish ->
                    db.upsertDish(dish.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        favorite = false
                    ))
                }
                selected.clear()
                updateBar()
                onRefresh()
            }
        }
        val delBtn = TextView(ctx).apply {
            text = "Удалить"
            setTextColor(0xFFE57373.toInt())
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            setBackgroundColor(0xFF2B2B2B.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val n = selected.size
                AlertDialog.Builder(ctx)
                    .setTitle("Удалить $n блюд?")
                    .setMessage("Это действие нельзя отменить.")
                    .setPositiveButton("Удалить") { _, _ ->
                        selected.forEach { id -> db.deleteDish(id) }
                        selected.clear()
                        updateBar()
                        onRefresh()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        }
        val bottomBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1F1F1F.toInt())
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * d).toInt() }
            visibility = View.GONE
        }
        updateBar = {
            bottomBar.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
            countTv.text = "Выбрано: ${selected.size}"
        }
        bottomBar.addView(countTv)
        bottomBar.addView(dupBtn)
        bottomBar.addView(delBtn)

        dishes.forEach { dish ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * d).toInt(), (10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt())
                setBackgroundResource(R.drawable.meal_card_bg)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * d).toInt() }
                isClickable = true
                isFocusable = true
                setOnClickListener { onEdit(dish) }
            }
            val img = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt())
                    .apply { marginEnd = (10 * d).toInt() }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF2B2B2B.toInt())
                dish.photoPath?.let { setImageURI(Uri.fromFile(File(it))) }
            }
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(ctx).apply {
                text = dish.name
                setTextColor(TEXT_PRIMARY)
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            val macros = db.dishMacrosPer100(dish)
            texts.addView(TextView(ctx).apply {
                text = "${macros.kcal} ккал · Б ${fmtNum(macros.protein)} · Ж ${fmtNum(macros.fat)} · У ${fmtNum(macros.carbs)} (100 г)  · ${dish.ingredients.size} ингр."
                setTextColor(TEXT_HINT)
                textSize = 12f
            })
            val circle = ImageView(ctx).apply {
                setImageResource(R.drawable.select_circle_bg)
                layoutParams = LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt())
                    .apply { marginStart = (8 * d).toInt() }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (!selected.add(dish.id)) selected.remove(dish.id)
                    setImageResource(
                        if (dish.id in selected) R.drawable.select_circle_checked
                        else R.drawable.select_circle_bg
                    )
                    updateBar()
                }
            }
            row.addView(img); row.addView(texts)
            if (onAddToMeal != null) {
                val add = ImageButton(ctx).apply {
                    setImageResource(R.drawable.ic_plus)
                    setBackgroundColor(Color.TRANSPARENT)
                    setColorFilter(0xFF4CAF50.toInt())
                    setOnClickListener { onAddToMeal(dish) }
                }
                row.addView(add, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
            }
            row.addView(circle)
            list.addView(row)
        }
        list.addView(bottomBar)
    }

    // ─── Хелперы ───

    // Расширенная карточка: лейбл сверху, поле ввода MATCH_PARENT (на всю ширину)
    private fun paramCardWide(ctx: Context, d: Float, label: String, field: EditText): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            val pad = (14 * d).toInt()
            setPadding(pad, (10 * d).toInt(), pad, (10 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * d).toInt(); bottomMargin = (2 * d).toInt() }
        }
        card.addView(TextView(ctx).apply {
            text = label
            setTextColor(TEXT_PRIMARY)
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.04f
            setPadding(0, 0, 0, (4 * d).toInt())
        })
        card.addView(field, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return card
    }

    private fun sectionHeader(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(TEXT_HINT)
        textSize = 12f
        setTypeface(null, android.graphics.Typeface.BOLD)
        letterSpacing = 0.08f
        setPadding(0, 0, 0, 0)
    }

    private fun fmtNum(v: Double): String =
        if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)

    private fun formatProductMeal(p: NutritionDatabase.Product): String =
        "${p.name}${if (p.brand.isBlank()) "" else " (${p.brand})"}: ${p.kcal} ккал, Б ${fmtNum(p.protein)} г, Ж ${fmtNum(p.fat)} г, У ${fmtNum(p.carbs)} г"

    /** Подпись «N ккал · Б x · Ж y · У z» под именем приёма пищи. */
    private fun summaryLine(md: MealData, kcalTotal: Int, legacyKcal: Int): String {
        if (md.items.isEmpty() && legacyKcal <= 0) return ""
        val p = md.protein; val f = md.fat; val c = md.carbs
        return "$kcalTotal ккал${if (p + f + c > 0.0) "  ·  " + coloredMacroLine(p, f, c) else ""}"
    }

    /** Рендер одной раскрывающейся карточки приёма пищи. */
    private fun renderMealCard(
        ctx: Context,
        content: LinearLayout,
        inflater: android.view.LayoutInflater,
        name: String,
        dateKey: String,
        showCircle: Boolean,
        onLongPressDelete: ((Context) -> Unit)?,
        refresh: () -> Unit
    ) {
        val d = ctx.resources.displayMetrics.density
        val row = inflater.inflate(R.layout.item_meal, content, false)
        row.findViewById<TextView>(R.id.mealName).text = name
        val md = loadMealData(ctx)[dateKey]?.get(name) ?: MealData()
        val legacyKcal = loadMealKcal(ctx)[dateKey]?.get(name) ?: 0
        val kcalTotal = if (md.items.isNotEmpty()) md.kcal else legacyKcal
        val kcalTv = row.findViewById<TextView>(R.id.mealKcal)
        val summaryTv = row.findViewById<TextView>(R.id.mealSummary)
        if (kcalTotal > 0) {
            kcalTv.text = "$kcalTotal ккал"
            summaryTv.text = summaryLine(md, kcalTotal, legacyKcal)
        } else {
            kcalTv.text = ""
            summaryTv.text = ""
        }
        val header = row.findViewById<View>(R.id.mealHeader)
        val body = row.findViewById<View>(R.id.mealBody)
        val arrow = row.findViewById<TextView>(R.id.mealArrow)
        header.setOnClickListener {
            val expanded = body.visibility == View.VISIBLE
            body.visibility = if (expanded) View.GONE else View.VISIBLE
            arrow.text = if (expanded) "▾" else "▴"
        }
        if (onLongPressDelete != null) {
            header.setOnLongClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle("Удалить «$name»?")
                    .setMessage("Все продукты этого приёма пищи останутся в истории, но приём исчезнет из списка.")
                    .setPositiveButton("Удалить") { _, _ -> onLongPressDelete(ctx); refresh() }
                    .setNegativeButton("Отмена", null)
                    .show()
                true
            }
        }
        if (showCircle) {
            val d2 = ctx.resources.displayMetrics.density
            (header as LinearLayout).addView(View(ctx).apply {
                setBackgroundResource(R.drawable.select_circle_bg)
                layoutParams = LinearLayout.LayoutParams(
                    (22 * d2).toInt(), (22 * d2).toInt()
                )
            })
        }
        val itemsContainer = row.findViewById<LinearLayout>(R.id.mealItems)
        itemsContainer.removeAllViews()
        if (md.items.isNotEmpty()) {
            md.items.forEach { item ->
                itemsContainer.addView(buildMealProductCard(ctx, item) { /* без удаления */ })
            }
        }
        content.addView(row)
    }

    /** «Б 28 г · Ж 22 г · У 56 г» с цветными буквами Б/Ж/У. */
    private fun coloredMacroLine(p: Double, f: Double, c: Double): CharSequence {
        // Возвращаем CharSequence со Span'ами
        val sp = android.text.SpannableString("Б ${fmtNum(p)} г  ·  Ж ${fmtNum(f)} г  ·  У ${fmtNum(c)} г")
        val pIdx = sp.indexOf('Б')
        val fIdx = sp.indexOf('Ж')
        val cIdx = sp.indexOf('У')
        if (pIdx >= 0) sp.setSpan(
            android.text.style.ForegroundColorSpan(COLOR_PROTEIN),
            pIdx, pIdx + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (fIdx >= 0) sp.setSpan(
            android.text.style.ForegroundColorSpan(COLOR_FAT),
            fIdx, fIdx + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (cIdx >= 0) sp.setSpan(
            android.text.style.ForegroundColorSpan(COLOR_CARBS),
            cIdx, cIdx + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return sp
    }

    /** Компактная карточка продукта внутри раскрытого приёма пищи. */
    private fun buildMealProductCard(ctx: Context, item: MealItem, onDelete: () -> Unit): View {
        val d = ctx.resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val pad = (10 * d).toInt()
            setPadding(pad, pad, pad, pad)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (6 * d).toInt()
            layoutParams = lp
            setBackgroundResource(R.drawable.card_bg)
        }
        // Мини-фото / плейсхолдер
        val thumb = ImageView(ctx).apply {
            setImageResource(R.drawable.food)
            setBackgroundColor(0xFF2B2B2B.toInt())
            scaleType = ImageView.ScaleType.CENTER_CROP
            val sz = (44 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz)
        }
        row.addView(thumb)
        // Колонка: имя + граммовка + БЖУ
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins((10 * d).toInt(), 0, (8 * d).toInt(), 0) }
        }
        col.addView(TextView(ctx).apply {
            text = item.name
            setTextColor(TEXT_PRIMARY)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        col.addView(TextView(ctx).apply {
            text = "${fmtNum(item.grams)} г"
            setTextColor(TEXT_HINT)
            textSize = 12f
            setPadding(0, (2 * d).toInt(), 0, 0)
        })
        if (item.protein + item.fat + item.carbs > 0.0) {
            col.addView(TextView(ctx).apply {
                text = coloredMacroLine(item.protein, item.fat, item.carbs)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, (2 * d).toInt(), 0, 0)
            })
        }
        row.addView(col)
        // Ккал справа
        val kcalCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * d).toInt() }
        }
        kcalCol.addView(TextView(ctx).apply {
            text = "${item.kcal} ккал"
            setTextColor(TEXT_PRIMARY)
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.END
        })
        row.addView(kcalCol)
        return row
    }

    /** BottomSheet: список продуктов и блюд из БД → выбор → диалог граммовки → добавить в приём. */
    private fun showProductPickerForMeal(
        ctx: Context,
        meal: String,
        dateKey: String,
        onAdded: () -> Unit
    ) {
        val d = ctx.resources.displayMetrics.density
        val db = NutritionDatabase(ctx)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        sheet.setOnShowListener {
            val bottom = sheet.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            bottom?.setBackgroundColor(0xFF181818.toInt())
        }
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF181818.toInt())
            val pad = (16 * d).toInt()
            setPadding(pad, pad, pad, pad)
        }
        view.addView(TextView(ctx).apply {
            text = "Добавить в «$meal»"
            setTextColor(TEXT_PRIMARY)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (10 * d).toInt())
        })
        // Поле поиска: фильтрует список продуктов/блюд по подстроке в названии
        val searchField = EditText(ctx).apply {
            hint = "Поиск продукта"
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_HINT)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        }
        val searchBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF1A1A1A.toInt()); cornerRadius = 10f * d
        }
        val searchWrap = LinearLayout(ctx).apply { background = searchBg }
        searchWrap.addView(searchField, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        view.addView(searchWrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (10 * d).toInt() })

        view.addView(TextView(ctx).apply {
            text = "＋ Вручную"
            setTextColor(COLOR_GREEN)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
            setBackgroundResource(R.drawable.card_bg)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * d).toInt() }
            setOnClickListener {
                sheet.dismiss()
                promptManualItem(ctx, meal, dateKey, onAdded)
            }
        })
        val scroll = android.widget.ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (300 * d).toInt()
            )
        }
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        view.addView(scroll)

        // Пред-вычисляем список всех элементов; на каждое изменение текста
        // перерисовываем только отфильтрованные.
        data class Item(val title: String, val sub: String, val onClick: () -> Unit)
        val items = mutableListOf<Item>()
        db.listProducts().forEach { p ->
            items += Item(
                p.name + if (p.brand.isBlank()) "" else " · ${p.brand}",
                "${p.kcal} ккал/100г"
            ) { sheet.dismiss(); showAddProductToMeal(ctx, p, meal, dateKey, onAdded) }
        }
        db.listCustomItems().forEach { c ->
            val kcal = (c.protein * 4 + c.fat * 9 + c.carbs * 4).toInt()
            items += Item(c.name, "$kcal ккал/100г") {
                sheet.dismiss()
                val fakeProduct = NutritionDatabase.Product(
                    id = c.id, name = c.name, protein = c.protein, fat = c.fat, carbs = c.carbs,
                    servingG = if (c.servingG > 0) c.servingG else 100.0
                )
                showAddProductToMeal(ctx, fakeProduct, meal, dateKey, onAdded)
            }
        }
        db.listDishes().forEach { dish ->
            val macros = db.dishMacrosPer100(dish)
            items += Item(dish.name, "${macros.kcal} ккал/100г") {
                sheet.dismiss()
                showAddDishToMeal(ctx, dish, macros, meal, dateKey, onAdded)
            }
        }

        fun redraw() {
            list.removeAllViews()
            val q = searchField.text.toString().trim().lowercase()
            val filtered = if (q.isEmpty()) items
                else items.filter { it.title.lowercase().contains(q) }
            if (filtered.isEmpty()) {
                list.addView(TextView(ctx).apply {
                    text = if (items.isEmpty())
                        "База пуста. Добавьте продукты на вкладке «База данных»."
                    else "Ничего не найдено"
                    setTextColor(TEXT_HINT)
                    textSize = 13f
                    setPadding((12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt())
                })
            } else {
                filtered.forEach { list.addView(pickerRow(ctx, it.title, it.sub, it.onClick)) }
            }
        }
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = redraw()
        })
        redraw()

        sheet.setContentView(view)
        sheet.show()
        // Фокус + клавиатура сразу при открытии
        searchField.post {
            searchField.requestFocus()
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun pickerRow(ctx: Context, title: String, sub: String, onClick: () -> Unit): View {
        val d = ctx.resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (12 * d).toInt()
            setPadding(pad, pad, pad, pad)
            setBackgroundResource(R.drawable.card_bg)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * d).toInt() }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        row.addView(TextView(ctx).apply {
            text = title
            setTextColor(TEXT_PRIMARY)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(TextView(ctx).apply {
            text = sub
            setTextColor(TEXT_HINT)
            textSize = 12f
            setPadding(0, (2 * d).toInt(), 0, 0)
        })
        return row
    }

    /** Диалог «имя + граммы + ккал + Б/Ж/У» для ручного добавления. */
    private fun promptManualItem(
        ctx: Context,
        meal: String,
        dateKey: String,
        onAdded: () -> Unit
    ) {
        val d = ctx.resources.displayMetrics.density
        val name = EditText(ctx).apply {
            hint = "Название"
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_HINT)
        }
        val grams = EditText(ctx).apply {
            hint = "Граммы"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("100")
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_HINT)
        }
        val kcal = EditText(ctx).apply {
            hint = "Ккал"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_HINT)
        }
        fun macroField(label: String, color: Int): EditText = EditText(ctx).apply {
            hint = label
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("0")
            setTextColor(color)
            setHintTextColor(TEXT_HINT)
        }
        val protein = macroField("Белки, г", COLOR_PROTEIN)
        val fat = macroField("Жиры, г", COLOR_FAT)
        val carbs = macroField("Углеводы, г", COLOR_CARBS)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * d).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val bjuRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        listOf(protein, fat, carbs).forEachIndexed { i, f ->
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.marginStart = (6 * d).toInt()
            bjuRow.addView(f, lp)
        }
        listOf<View>(name, grams, kcal, bjuRow).forEach { v ->
            if (v is EditText) {
                v.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            }
            container.addView(v, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * d).toInt() })
        }
        AlertDialog.Builder(ctx)
            .setTitle("Продукт в «$meal»")
            .setView(container)
            .setPositiveButton("Добавить") { _, _ ->
                val n = name.text.toString().trim().ifBlank { "Без названия" }
                val g = grams.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                val k = kcal.text.toString().toIntOrNull() ?: 0
                if (g <= 0.0 || k <= 0) {
                    android.widget.Toast.makeText(ctx, "Заполните граммы и ккал",
                        android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val p = protein.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                val f = fat.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                val c = carbs.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                addMealItem(ctx, dateKey, meal, MealItem(
                    name = n, grams = g, kcal = k,
                    protein = p, fat = f, carbs = c
                ))
                onAdded()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun copyPhoto(ctx: Context, uri: Uri): String? = runCatching {
        val dir = File(ctx.filesDir, "nutrition_photos").apply { mkdirs() }
        val out = File(dir, "p_${System.currentTimeMillis()}.jpg")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { input.copyTo(it) }
        }
        out.absolutePath
    }.getOrNull()

    /** Полноэкранный просмотр фото продукта (для long-press на миниатюре). */
    private fun showPhotoPreview(ctx: Context, path: String) {
        val activity = ctx as? android.app.Activity ?: return
        val d = ctx.resources.displayMetrics.density
        val container = android.widget.FrameLayout(ctx).apply {
            setBackgroundColor(0xCC000000.toInt())
            isClickable = true
            setOnClickListener { (parent as? android.view.ViewGroup)?.removeView(this) }
        }
        val img = ImageView(ctx).apply {
            setImageURI(Uri.fromFile(File(path)))
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER }
        }
        val closeBtn = TextView(ctx).apply {
            text = "✕"
            setTextColor(0xFFE6E6E6.toInt())
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding((20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
            isClickable = true
            setOnClickListener { (parent as? android.view.ViewGroup)?.removeView(this) }
        }
        val closeLp = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.END }
        container.addView(img)
        container.addView(closeBtn, closeLp)
        // Добавляем в root, а не в scroll — на весь экран поверх всего
        val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        root.addView(container, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    /** Ищет [c] локально, затем в OpenFoodFacts, заполняет поля диалога продукта. */
    private fun performBarcodeLookup(
        ctx: Context, c: String,
        nameField: EditText, brandField: EditText?,
        proteinField: EditText, fatField: EditText, carbsField: EditText,
        servingField: EditText, kcalLabel: TextView
    ) {
        val localDb = NutritionDatabase(ctx)
        val local = localDb.findProductByBarcode(c)
        if (local != null) {
            applyParsedToFields(local.name, local.brand, local.protein, local.fat, local.carbs, local.servingG,
                nameField, brandField, proteinField, fatField, carbsField, servingField, kcalLabel)
            return
        }
        val scope = CoroutineScope(Dispatchers.Main + Job())
        scope.launch {
            val parsed = ProductLookupClient.fetchStructured(c)
            if (parsed == null) {
                android.widget.Toast.makeText(ctx, "Не найдено в OpenFoodFacts. Заполните руками.",
                    android.widget.Toast.LENGTH_LONG).show()
            } else {
                applyParsedToFields(parsed.name, parsed.brand, parsed.protein, parsed.fat, parsed.carbs, parsed.servingG,
                    nameField, brandField, proteinField, fatField, carbsField, servingField, kcalLabel)
            }
        }
    }

    private fun applyParsedToFields(
        name: String, brand: String,
        p: Double, f: Double, c: Double, serving: Double,
        nameField: EditText, brandField: EditText?,
        proteinField: EditText, fatField: EditText, carbsField: EditText,
        servingField: EditText, kcalLabel: TextView
    ) {
        if (nameField.text.isNullOrBlank()) nameField.setText(name)
        if (brandField != null && brandField.text.isNullOrBlank()) brandField.setText(brand)
        proteinField.setText(fmtNum(p))
        fatField.setText(fmtNum(f))
        carbsField.setText(fmtNum(c))
        if (serving > 0) servingField.setText(fmtNum(serving))
        val kcal = (p * 4 + f * 9 + c * 4).toInt()
        kcalLabel.text = "= $kcal ккал / 100 г"
    }

    /**
     * Заполняет контейнер содержимым таба «Параметры» — 4 колонки
     * (ккал/Белки/Жиры/Углеводы), каждая с полем «граммы» и (для макросов)
     * «проценты». Между граммами и процентами двусторонняя автоконверсия.
     * Проценты зажаты в 0-100.
     * @param onSaved вызывается после сохранения
     */
    fun renderParams(ctx: Context, content: LinearLayout, onSaved: () -> Unit) {
        val d = ctx.resources.displayMetrics.density
        content.removeAllViews()
        val p = load(ctx)

        fun parseInt(s: CharSequence?, default: Int = 0): Int =
            s?.toString()?.trim()?.toIntOrNull() ?: default

        // Заголовок
        content.addView(TextView(ctx).apply {
            text = "ПАРАМЕТРЫ КБЖУ"
            setTextColor(TEXT_HINT)
            textSize = 12f
            letterSpacing = 0.08f
            setPadding(0, (8 * d).toInt(), 0, (12 * d).toInt())
        })

        fun numberField(initial: Int): EditText = EditText(ctx).apply {
            setText(initial.toString())
            hint = "0"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(0xFF555555.toInt())
            setBackgroundColor(0xFF1F1F1F.toInt())
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            val pad = (12 * d).toInt()
            setPadding(pad, pad, pad, pad)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
        }

        fun paramCard(label: String, field: EditText, extraBelow: TextView? = null): LinearLayout {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                val pad = (14 * d).toInt()
                setPadding(pad, (10 * d).toInt(), pad, (10 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * d).toInt(); bottomMargin = (6 * d).toInt() }
            }
            val top = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            top.addView(TextView(ctx).apply {
                text = label
                setTextColor(TEXT_PRIMARY)
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(field, LinearLayout.LayoutParams(
                (140 * d).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            card.addView(top)
            if (extraBelow != null) {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (4 * d).toInt()
                card.addView(extraBelow, lp)
            }
            return card
        }

        // 1. Калории
        val kcalField = numberField(p.kcalNorm)
        content.addView(paramCard("Калории", kcalField))

        // 2-4. БЖУ
        val kcalPerGram = mapOf("protein" to 4, "fat" to 9, "carbs" to 4)
        val percentFields = mutableMapOf<String, EditText>()
        val gramsViews = mutableMapOf<String, TextView>()
        var localKcal = p.kcalNorm

        fun recalcGrams(key: String) {
            val f = percentFields[key] ?: return
            val perG = kcalPerGram[key] ?: return
            val kcal = localKcal.coerceAtLeast(1)
            val pct = parseInt(f.text).coerceAtLeast(0)
            val grams = (pct / 100.0 * kcal / perG).toInt()
            gramsViews[key]?.text = "= $grams г"
        }
        fun recalcAllGrams() {
            for (k in listOf("protein", "fat", "carbs")) recalcGrams(k)
        }

        data class Macro(val key: String, val label: String, val percent: Int)
        listOf(
            Macro("protein", "Белки", p.proteinPercent),
            Macro("fat", "Жиры", p.fatPercent),
            Macro("carbs", "Углеводы", p.carbsPercent)
        ).forEach { m ->
            val f = numberField(m.percent)
            percentFields[m.key] = f
            val grams = TextView(ctx).apply {
                setTextColor(TEXT_HINT)
                textSize = 12f
                gravity = Gravity.END
            }
            gramsViews[m.key] = grams
            content.addView(paramCard(m.label, f, grams))
            recalcGrams(m.key)
        }

        // Σ под БЖУ
        val sumText = TextView(ctx).apply {
            setTextColor(0xFFE57373.toInt())
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.END
            setPadding(0, (12 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
        }
        content.addView(sumText)
        var btnSave: Button? = null
        fun updateSum() {
            val total = parseInt(percentFields["protein"]?.text) +
                parseInt(percentFields["fat"]?.text) +
                parseInt(percentFields["carbs"]?.text)
            val ok = total == 100
            sumText.text = "Σ $total%"
            sumText.setTextColor(
                when {
                    total == 100 -> 0xFF4CAF50.toInt()
                    total in 95..105 -> 0xFFFFC107.toInt()
                    else -> 0xFFE57373.toInt()
                }
            )
            btnSave?.isEnabled = ok
            btnSave?.alpha = if (ok) 1f else 0.4f
        }

        // % меняется → пересчитать граммы этой строки и Σ
        for ((key, f) in percentFields) {
            f.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    recalcGrams(key)
                    updateSum()
                }
            })
        }
        updateSum()

        // ккал меняется → пересчитать все граммы (Σ не меняется)
        kcalField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                localKcal = parseInt(kcalField.text, p.kcalNorm).coerceAtLeast(1)
                recalcAllGrams()
            }
        })

        // Кнопка Сохранить
        val btn = Button(ctx).apply {
            text = "Сохранить"
            setBackgroundColor(0xFF4CAF50.toInt())
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            val m = (16 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = m * 2 }
            setOnClickListener {
                val kcal = parseInt(kcalField.text, p.kcalNorm).coerceAtLeast(1)
                save(ctx, Params(
                    kcalNorm = kcal,
                    proteinPercent = parseInt(percentFields["protein"]?.text, p.proteinPercent).coerceIn(0, 100),
                    fatPercent     = parseInt(percentFields["fat"]?.text, p.fatPercent).coerceIn(0, 100),
                    carbsPercent   = parseInt(percentFields["carbs"]?.text, p.carbsPercent).coerceIn(0, 100),
                    waterMl = p.waterMl,
                    productLookupEnabled = load(ctx).productLookupEnabled
                ))
                onSaved()
            }
        }
        btnSave = btn
        content.addView(btn)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Список покупок (Shopping List) — таб «Купить»
    // ═══════════════════════════════════════════════════════════════════

    private data class ShoppingItem(val id: Long, val title: String, val done: Boolean)

    private fun shoppingPrefs(ctx: Context) =
        ctx.getSharedPreferences("nutrition_shopping", Context.MODE_PRIVATE)

    private fun loadShoppingItems(ctx: Context): List<ShoppingItem> {
        val raw = shoppingPrefs(ctx).getString("items", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val title = o.optString("title").trim()
                if (title.isBlank()) null else ShoppingItem(
                    id = o.optLong("id"),
                    title = title,
                    done = o.optBoolean("done", false)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveShoppingItems(ctx: Context, items: List<ShoppingItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("done", item.done)
            })
        }
        shoppingPrefs(ctx).edit().putString("items", arr.toString()).apply()
    }

    fun renderShoppingList(ctx: Context, content: LinearLayout) {
        val d = ctx.resources.displayMetrics.density
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(ctx).apply {
            hint = "Что купить"
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_HINT)
            setBackgroundColor(0xFF1F1F1F.toInt())
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val add = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_plus)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(0xFF4CAF50.toInt())
        }
        inputRow.addView(input)
        inputRow.addView(add, LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt()))
        content.addView(inputRow)

        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (12 * d).toInt(), 0, (80 * d).toInt())
        }
        content.addView(list)

        fun redraw() { renderShoppingItems(ctx, list) { redraw() } }
        fun addItem() {
            val title = input.text.toString().trim()
            if (title.isBlank()) return
            saveShoppingItems(ctx, loadShoppingItems(ctx) + ShoppingItem(System.currentTimeMillis(), title, false))
            input.setText("")
            redraw()
        }
        add.setOnClickListener { addItem() }
        input.setOnEditorActionListener { _, _, _ -> addItem(); true }
        redraw()
    }

    private fun renderShoppingItems(ctx: Context, list: LinearLayout, onChanged: () -> Unit) {
        val d = ctx.resources.displayMetrics.density
        list.removeAllViews()
        val items = loadShoppingItems(ctx).sortedWith(compareBy<ShoppingItem> { it.done }.thenBy { it.id })
        if (items.isEmpty()) {
            list.addView(TextView(ctx).apply {
                text = "Список пуст"
                setTextColor(TEXT_HINT)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, (24 * d).toInt(), 0, 0)
            })
            return
        }
        items.forEach { item ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((8 * d).toInt(), (8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt())
                setBackgroundResource(R.drawable.meal_card_bg)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * d).toInt() }
            }
            val check = CheckBox(ctx).apply {
                isChecked = item.done
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt())
            }
            val title = TextView(ctx).apply {
                text = item.title
                setTextColor(if (item.done) TEXT_HINT else TEXT_PRIMARY)
                textSize = 16f
                paintFlags = if (item.done) paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                    else paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val del = ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_menu_delete)
                setBackgroundColor(Color.TRANSPARENT)
                setColorFilter(TEXT_HINT)
            }
            fun toggle() {
                saveShoppingItems(ctx, loadShoppingItems(ctx).map {
                    if (it.id == item.id) it.copy(done = !it.done) else it
                })
                onChanged()
            }
            check.setOnClickListener { toggle() }
            row.setOnClickListener { toggle() }
            del.setOnClickListener {
                saveShoppingItems(ctx, loadShoppingItems(ctx).filterNot { it.id == item.id })
                onChanged()
            }
            row.addView(check, LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()))
            row.addView(title)
            row.addView(del, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
            list.addView(row)
        }
    }
}
