package com.assistant.app

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
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
        val consumed = loadDailyKcal(ctx)[dateKey] ?: 0
        val totalBudget = p.kcalNorm + activeKcal.toInt()
        val remaining = (totalBudget - consumed).coerceAtLeast(0)
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
            setTextColor(0xFF4CAF50.toInt())
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
        data class M(val label: String, val current: Int, val total: Int, val color: Int)
        listOf(
            M("Белки", 0, p.proteinG, 0xFFF44336.toInt()),     // red
            M("Жиры", 0, p.fatG, 0xFFFFC107.toInt()),          // yellow
            M("Углеводы", 0, p.carbsG, 0xFF2196F3.toInt())     // blue
        ).forEach { m ->
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(
                    (10 * d).toInt(), (14 * d).toInt(),
                    (10 * d).toInt(), (14 * d).toInt()
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
                text = "${m.current} / ${m.total} г"
                setTextColor(TEXT_PRIMARY)
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
            }
            card.addView(tvLabel)
            card.addView(tvValue)
            macrosRow.addView(card)
        }
        content.addView(macrosRow)

        // 3. Зелёная кнопка «Добавить приём пищи» — определяет время суток
        val now = java.time.LocalTime.now().hour
        val suggestedMeal = mealForHour(now)
        val addMealBtn = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            isClickable = true
            isFocusable = true
            setOnClickListener { onMealClick(suggestedMeal) }
            val pad = (14 * d).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (20 * d).toInt() }
        }
        addMealBtn.setOnClickListener { onMealClick(suggestedMeal) }
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

        // 4. Список приёмов пищи — клик ТОЛЬКО на + (mealAdd), не на всю строку
        val inflater = android.view.LayoutInflater.from(ctx)
        listOf("Завтрак", "Обед", "Ужин", "Перекус").forEach { name ->
            val row = inflater.inflate(R.layout.item_meal, content, false)
            row.findViewById<TextView>(R.id.mealName).text = name
            // строка НЕ кликабельна — иначе свайпы ложно срабатывают как тап
            row.findViewById<View>(R.id.mealAdd).setOnClickListener { onMealClick(name) }
            content.addView(row)
        }

        // 5. Прогресс за 30 дней — временно скрыт по запросу
        // renderComplianceGraph(ctx, content, p, dateKey)
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
     *  до 11 — Завтрак, 11-14 — Обед, 15-17 — Перекус, 18+ — Ужин. */
    fun mealForHour(hour: Int): String = when (hour) {
        in 0..10 -> "Завтрак"
        in 11..14 -> "Обед"
        in 15..17 -> "Перекус"
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
        onMealClick: (String) -> Unit,
        onPickPhoto: (((Uri?) -> Unit) -> Unit)?,
        onScanBarcode: ((String?) -> Unit) -> Unit
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
                .sortedBy { it.name.lowercase() }
            renderItemCards(ctx, list, filtered,
                onMealClick = { onMealClick(formatProductMeal(it)) },
                onEdit = { card ->
                    when (card) {
                        is ItemCard.Product -> showItemDialog(ctx, NutritionDatabase.Kind.PRODUCT,
                            card.p, onPickPhoto, onScanBarcode) { refreshList() }
                        is ItemCard.Custom -> showItemDialog(ctx, NutritionDatabase.Kind.CUSTOM,
                            card.c, onPickPhoto, onScanBarcode) { refreshList() }
                    }
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

    /** Создать новую карточку продукта/своей записи (вызывается из FAB). */
    fun createProduct(
        ctx: Context,
        onScanBarcode: ((String?) -> Unit) -> Unit,
        onSaved: () -> Unit
    ) {
        showItemDialog(ctx, NutritionDatabase.Kind.PRODUCT, null, null, onScanBarcode, onSaved)
    }

    /** Создать новое блюдо (вызывается из FAB). */
    fun createDish(
        ctx: Context,
        onScanBarcode: ((String?) -> Unit) -> Unit,
        onSaved: () -> Unit
    ) {
        showDishDialog(ctx, NutritionDatabase(ctx), null, null, onScanBarcode, onSaved)
    }

    /** Таб «Блюда» — композитные блюда с ингредиентами. */
    fun renderDishesTab(
        ctx: Context,
        content: LinearLayout,
        onPickPhoto: (((Uri?) -> Unit) -> Unit)?,
        onScanBarcode: ((String?) -> Unit) -> Unit
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
                onEdit = { dish -> showDishDialog(ctx, db, dish, onPickPhoto, onScanBarcode) { refreshList() } },
                onDelete = { dish -> db.deleteDish(dish.id); refreshList() })
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

    private fun showItemDialog(
        ctx: Context,
        kind: NutritionDatabase.Kind,
        existing: Any?,
        onPickPhoto: (((Uri?) -> Unit) -> Unit)?,
        onScanBarcode: ((String?) -> Unit) -> Unit,
        onSaved: () -> Unit
    ) {
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

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        fun field(hint: String, initial: String, number: Boolean = false) = EditText(ctx).apply {
            styleChatInput(ctx, this, hint, number)
            setText(initial)
        }
        val name = field("Название", nameInit)
        val brand = if (isProduct) field("Бренд", brandInit) else null
        val protein = field("Белки / 100 г", fmtNum(proteinInit), true)
        val fat = field("Жиры / 100 г", fmtNum(fatInit), true)
        val carbs = field("Углеводы / 100 г", fmtNum(carbsInit), true)
        val serving = field("Размер порции, г", fmtNum(servingInit), true)
        val kcalLabel = TextView(ctx).apply {
            setTextColor(0xFF4CAF50.toInt())
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.END
        }
        val photo = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (120 * d).toInt()
            ).apply { topMargin = (8 * d).toInt() }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF2B2B2B.toInt())
            photoPath?.let { setImageURI(Uri.fromFile(File(it))) }
        }
        val photoBtn = Button(ctx).apply {
            text = "Фото"
            setOnClickListener {
                onPickPhoto?.invoke { uri ->
                    photoPath = uri?.let { copyPhoto(ctx, it) }
                    photoPath?.let { photo.setImageURI(Uri.fromFile(File(it))) }
                }
            }
        }
        // Кнопка «Штрихкод» — открывает мини-диалог (ручной ввод + камера)
        val barcodeBtn = Button(ctx).apply {
            text = "🔍  Штрихкод"
            setOnClickListener {
                showBarcodeDialog(ctx, name, brand, protein, fat, carbs, serving, kcalLabel, onScanBarcode)
            }
        }
        fun updateKcal() {
            val p = protein.text.toString().toDoubleOrNull() ?: 0.0
            val f = fat.text.toString().toDoubleOrNull() ?: 0.0
            val c = carbs.text.toString().toDoubleOrNull() ?: 0.0
            val kcal = (p * 4 + f * 9 + c * 4).toInt()
            kcalLabel.text = "= $kcal ккал / 100 г"
        }
        listOf(protein, fat, carbs).forEach { f ->
            f.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = updateKcal()
            })
        }
        updateKcal()

        listOf(name, brand, protein, fat, carbs, kcalLabel, serving).forEach { f -> f?.let { box.addView(it) } }
        if (isProduct) box.addView(barcodeBtn)
        box.addView(photoBtn)
        box.addView(photo)

        AlertDialog.Builder(ctx)
            .setTitle(if (isProduct) "Продукт" else "Своя запись")
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                val title = name.text.toString().trim()
                if (title.isBlank()) return@setPositiveButton
                val p = protein.text.toString().toDoubleOrNull() ?: 0.0
                val f = fat.text.toString().toDoubleOrNull() ?: 0.0
                val c = carbs.text.toString().toDoubleOrNull() ?: 0.0
                val sg = serving.text.toString().toDoubleOrNull() ?: 0.0
                val db = NutritionDatabase(ctx)
                if (isProduct) {
                    val old = (initial as NutritionDatabase.Product)
                    db.upsertProduct(old.copy(
                        name = title,
                        brand = brand?.text?.toString()?.trim() ?: "",
                        protein = p, fat = f, carbs = c,
                        servingG = sg, photoPath = photoPath
                    ))
                } else {
                    val old = (initial as NutritionDatabase.CustomItem)
                    db.upsertCustomItem(old.copy(
                        name = title, protein = p, fat = f, carbs = c,
                        servingG = sg, photoPath = photoPath
                    ))
                }
                onSaved()
            }
            .setNegativeButton("Отмена", null)
            .create()
            .also { d -> d.show(); showKeyboard(name) }
    }

    // ─── Диалог блюда с ингредиентами ───

    private fun showDishDialog(
        ctx: Context,
        db: NutritionDatabase,
        existing: NutritionDatabase.Dish?,
        onPickPhoto: (((Uri?) -> Unit) -> Unit)?,
        onScanBarcode: ((String?) -> Unit) -> Unit,
        onSaved: () -> Unit
    ) {
        val d = ctx.resources.displayMetrics.density
        val photoPath = existing?.photoPath
        val ingredientsState = mutableListOf<NutritionDatabase.Ingredient>().apply {
            existing?.ingredients?.let { addAll(it) }
        }

        val scroll = ScrollView(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        scroll.addView(box)
        fun field(hint: String, initial: String, number: Boolean = false) = EditText(ctx).apply {
            styleChatInput(ctx, this, hint, number)
            setText(initial)
        }
        val name = field("Название блюда", existing?.name ?: "")
        val serving = field("Размер порции, г", fmtNum(existing?.servingG ?: 100.0), true)
        val photo = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (100 * d).toInt()
            ).apply { topMargin = (8 * d).toInt() }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF2B2B2B.toInt())
            photoPath?.let { setImageURI(Uri.fromFile(File(it))) }
        }
        val photoBtn = Button(ctx).apply {
            text = "Фото"
            setOnClickListener {
                onPickPhoto?.invoke { u ->
                    val saved = u?.let { copyPhoto(ctx, it) }
                    saved?.let { photo.setImageURI(Uri.fromFile(File(it))) }
                }
            }
        }

        listOf(name, serving).forEach { box.addView(it) }
        box.addView(photoBtn); box.addView(photo)

        // Список ингредиентов
        val ingHeader = TextView(ctx).apply {
            text = "ИНГРЕДИЕНТЫ"
            setTextColor(TEXT_HINT)
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(0, (12 * d).toInt(), 0, (8 * d).toInt())
        }
        box.addView(ingHeader)
        val ingList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(ingList)
        val summary = TextView(ctx).apply {
            setTextColor(0xFF4CAF50.toInt())
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, (8 * d).toInt(), 0, 0)
            gravity = Gravity.END
        }
        box.addView(summary)

        fun redrawIng() {
            ingList.removeAllViews()
            ingredientsState.forEachIndexed { idx, ing ->
                val ingName = db.nameFor(ing.kind, ing.refId)
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
                }
                row.addView(TextView(ctx).apply {
                    text = "• $ingName — ${fmtNum(ing.grams)} г"
                    setTextColor(TEXT_PRIMARY)
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                val del = ImageButton(ctx).apply {
                    setImageResource(R.drawable.ic_menu_delete)
                    setBackgroundColor(Color.TRANSPARENT)
                    setColorFilter(TEXT_HINT)
                    setOnClickListener { ingredientsState.removeAt(idx); redrawIng() }
                }
                row.addView(del, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
                ingList.addView(row)
            }
            val total = ingredientsState.sumOf { it.grams }
            val dishTmp = NutritionDatabase.Dish(
                id = existing?.id ?: "tmp", name = "", servingG = 1.0,
                ingredients = ingredientsState.toList()
            )
            val macros = db.dishMacrosPer100(dishTmp)
            summary.text = "Σ ${fmtNum(total)} г → ${macros.kcal} ккал · Б ${fmtNum(macros.protein)} · Ж ${fmtNum(macros.fat)} · У ${fmtNum(macros.carbs)} (на 100 г)"
        }
        redrawIng()

        val addIng = Button(ctx).apply {
            text = "＋ Добавить ингредиент"
            setOnClickListener {
                showPickIngredient(ctx, db) { kind, refId ->
                    ingredientsState.add(NutritionDatabase.Ingredient(kind, refId, 100.0))
                    redrawIng()
                }
            }
        }
        box.addView(addIng)

        AlertDialog.Builder(ctx)
            .setTitle(if (existing == null) "Новое блюдо" else "Блюдо")
            .setView(scroll as android.view.View)
            .setPositiveButton("Сохранить") { _, _ ->
                val title = name.text.toString().trim()
                if (title.isBlank() || ingredientsState.isEmpty()) return@setPositiveButton
                val sg = serving.text.toString().toDoubleOrNull() ?: 100.0
                db.upsertDish(NutritionDatabase.Dish(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    name = title,
                    servingG = sg,
                    photoPath = photoPath,
                    ingredients = ingredientsState.toList()
                ))
                onSaved()
            }
            .setNegativeButton("Отмена", null)
            .create()
            .also { d -> d.show(); showKeyboard(name) }
    }

    private fun showPickIngredient(
        ctx: Context,
        db: NutritionDatabase,
        onPicked: (NutritionDatabase.Kind, String) -> Unit
    ) {
        val products = db.listProducts()
        val customs = db.listCustomItems()
        val labels = mutableListOf<Pair<String, Pair<NutritionDatabase.Kind, String>>>()
        products.forEach { labels += (it.name to (NutritionDatabase.Kind.PRODUCT to it.id)) }
        customs.forEach { labels += (it.name + " (своё)" to (NutritionDatabase.Kind.CUSTOM to it.id)) }
        if (labels.isEmpty()) {
            android.widget.Toast.makeText(ctx, "Сначала добавьте продукты в базу",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle("Ингредиент")
            .setItems(labels.map { it.first }.toTypedArray()) { _, which ->
                val (_, pair) = labels[which]
                onPicked(pair.first, pair.second)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ─── Рендер списков ───

    private sealed class ItemCard {
        abstract val name: String
        data class Product(val p: NutritionDatabase.Product) : ItemCard() {
            override val name: String get() = p.name
        }
        data class Custom(val c: NutritionDatabase.CustomItem) : ItemCard() {
            override val name: String get() = c.name
        }
    }

    private fun renderItemCards(
        ctx: Context,
        list: LinearLayout,
        cards: List<ItemCard>,
        onMealClick: (NutritionDatabase.Product) -> Unit,
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
                setOnClickListener { onEdit(card) }
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
            if (p != null) {
                val plus = ImageButton(ctx).apply {
                    setImageResource(R.drawable.ic_plus)
                    setBackgroundColor(Color.TRANSPARENT)
                    setColorFilter(0xFF4CAF50.toInt())
                    setOnClickListener { onMealClick(p) }
                }
                row.addView(plus, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
            }
            val del = ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_menu_delete)
                setBackgroundColor(Color.TRANSPARENT)
                setColorFilter(TEXT_HINT)
                setOnClickListener { onDelete(card) }
            }
            row.addView(del, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
            list.addView(row)
        }
    }

    private fun renderDishCards(
        ctx: Context,
        list: LinearLayout,
        db: NutritionDatabase,
        dishes: List<NutritionDatabase.Dish>,
        onEdit: (NutritionDatabase.Dish) -> Unit,
        onDelete: (NutritionDatabase.Dish) -> Unit
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
            val del = ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_menu_delete)
                setBackgroundColor(Color.TRANSPARENT)
                setColorFilter(TEXT_HINT)
                setOnClickListener { onDelete(dish) }
            }
            row.addView(img); row.addView(texts)
            row.addView(del, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
            list.addView(row)
        }
    }

    // ─── Хелперы ───

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

    private fun copyPhoto(ctx: Context, uri: Uri): String? = runCatching {
        val dir = File(ctx.filesDir, "nutrition_photos").apply { mkdirs() }
        val out = File(dir, "p_${System.currentTimeMillis()}.jpg")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { input.copyTo(it) }
        }
        out.absolutePath
    }.getOrNull()

    /** Мини-диалог: ввод штрихкода + поиск → заполнение полей формы продукта. */
    private fun showBarcodeDialog(
        ctx: Context,
        nameField: EditText,
        brandField: EditText?,
        proteinField: EditText,
        fatField: EditText,
        carbsField: EditText,
        servingField: EditText,
        kcalLabel: TextView,
        onScanBarcode: ((String?) -> Unit) -> Unit
    ) {
        val d = ctx.resources.displayMetrics.density
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        val code = EditText(ctx).apply {
            hint = "Введите штрихкод"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_HINT)
        }
        val status = TextView(ctx).apply {
            setTextColor(TEXT_HINT)
            textSize = 12f
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        val dlg = AlertDialog.Builder(ctx)
            .setTitle("Поиск по штрихкоду")
            .setView(box)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Искать", null) // переопределим ниже чтобы не закрывать
            .create()
        val scanCamera = Button(ctx).apply {
            text = "📷  Сканировать камерой"
            setOnClickListener {
                onScanBarcode { scanned ->
                    if (scanned.isNullOrBlank()) {
                        status.text = "Не удалось распознать"
                        return@onScanBarcode
                    }
                    code.setText(scanned)
                    performBarcodeLookup(ctx, scanned, status, code, dlg, nameField, brandField,
                        proteinField, fatField, carbsField, servingField, kcalLabel)
                }
            }
        }
        box.addView(code); box.addView(scanCamera); box.addView(status)
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val c = code.text.toString().trim()
                if (c.isBlank()) return@setOnClickListener
                performBarcodeLookup(ctx, c, status, code, dlg, nameField, brandField,
                    proteinField, fatField, carbsField, servingField, kcalLabel)
            }
        }
        dlg.show()
    }

    /** Ищет штрихкод локально → в OpenFoodFacts; заполняет поля диалога продукта. */
    private fun performBarcodeLookup(
        ctx: Context, c: String, status: TextView, code: EditText, dlg: AlertDialog,
        nameField: EditText, brandField: EditText?,
        proteinField: EditText, fatField: EditText, carbsField: EditText,
        servingField: EditText, kcalLabel: TextView
    ) {
        status.text = "Ищу…"
        val localDb = NutritionDatabase(ctx)
        val local = localDb.findProductByBarcode(c)
        if (local != null) {
            applyParsedToFields(local.name, local.brand, local.protein, local.fat, local.carbs, local.servingG,
                nameField, brandField, proteinField, fatField, carbsField, servingField, kcalLabel)
            dlg.dismiss()
            return
        }
        val scope = CoroutineScope(Dispatchers.Main + Job())
        scope.launch {
            val parsed = ProductLookupClient.fetchStructured(c)
            if (parsed == null) {
                status.text = "Не найдено. Попробуйте другой код или заполните руками."
            } else {
                applyParsedToFields(parsed.name, parsed.brand, parsed.protein, parsed.fat, parsed.carbs, parsed.servingG,
                    nameField, brandField, proteinField, fatField, carbsField, servingField, kcalLabel)
                dlg.dismiss()
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
