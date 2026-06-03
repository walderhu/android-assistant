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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
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
     * @param onMealClick вызывается при тапе по строке приёма пищи или `+`
     */
    fun renderInfo(
        ctx: Context,
        content: LinearLayout,
        onMealClick: (String) -> Unit,
        onPickPhoto: (((Uri?) -> Unit) -> Unit)? = null
    ) {
        val p = load(ctx)
        val d = ctx.resources.displayMetrics.density
        content.removeAllViews()

        // 1. Большая цифра «осталось» + зелёная подпись «ккал»
        val eaten = 0
        val remaining = (p.kcalNorm - eaten).coerceAtLeast(0)
        val bigText = TextView(ctx).apply {
            text = remaining.toString()
            textSize = 80f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF4CAF50.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, (16 * d).toInt(), 0, 0)
        }
        content.addView(bigText)
        val kcalLabel = TextView(ctx).apply {
            text = "ккал"
            setTextColor(0xFF4CAF50.toInt())
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, (4 * d).toInt(), 0, (20 * d).toInt())
        }
        content.addView(kcalLabel)

        renderComplianceGraph(ctx, content, p)

        // 2. Крупные макросы: label + value, цвета red / yellow / blue
        val macrosRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
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

        // 3. Заголовок «Приёмы пищи»
        val mealsHeader = TextView(ctx).apply {
            text = "ПРИЁМЫ ПИЩИ"
            setTextColor(TEXT_HINT)
            setPadding(0, (20 * d).toInt(), 0, (8 * d).toInt())
            textSize = 12f
            letterSpacing = 0.08f
        }
        content.addView(mealsHeader)

        // 4. Список приёмов пищи с +
        val inflater = android.view.LayoutInflater.from(ctx)
        listOf("Завтрак", "Обед", "Ужин", "Перекус").forEach { name ->
            val row = inflater.inflate(R.layout.item_meal, content, false)
            row.findViewById<TextView>(R.id.mealName).text = name
            row.setOnClickListener { onMealClick(name) }
            row.findViewById<View>(R.id.mealAdd).setOnClickListener { onMealClick(name) }
            content.addView(row)
        }
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

    private fun renderComplianceGraph(ctx: Context, content: LinearLayout, p: Params) {
        val d = ctx.resources.displayMetrics.density
        val values = loadDailyKcal(ctx)
        val today = dayKey()

        val header = TextView(ctx).apply {
            text = "ПРОГРЕСС 30 ДНЕЙ"
            setTextColor(TEXT_HINT)
            setPadding(0, (20 * d).toInt(), 0, (8 * d).toInt())
            textSize = 12f
            letterSpacing = 0.08f
        }
        content.addView(header)

        val input = EditText(ctx).apply {
            hint = "Съедено сегодня, ккал"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(values[today]?.toString().orEmpty())
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
                if (value == null) next.remove(today) else next[today] = value.coerceAtLeast(0)
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
            setPadding(0, (12 * d).toInt(), 0, 0)
        }
        content.addView(list)

        fun redraw() {
            renderShoppingItems(ctx, list) { redraw() }
        }
        fun addItem() {
            val title = input.text.toString().trim()
            if (title.isBlank()) return
            saveShoppingItems(ctx, loadShoppingItems(ctx) + ShoppingItem(System.currentTimeMillis(), title, false))
            input.setText("")
            redraw()
        }
        add.setOnClickListener { addItem() }
        input.setOnEditorActionListener { _, _, _ ->
            addItem()
            true
        }
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

    fun renderProductDatabase(
        ctx: Context,
        content: LinearLayout,
        onMealClick: (String) -> Unit,
        onPickPhoto: (((Uri?) -> Unit) -> Unit)?
    ) {
        val d = ctx.resources.displayMetrics.density
        val search = EditText(ctx).apply {
            hint = "Поиск продукта"
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_HINT)
            setBackgroundColor(0xFF1F1F1F.toInt())
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
        }
        content.addView(search)

        val add = Button(ctx).apply {
            text = "Добавить продукт"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF4CAF50.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * d).toInt() }
        }
        content.addView(add)

        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (12 * d).toInt(), 0, 0)
        }
        content.addView(list)

        fun redraw() {
            val q = search.text.toString().trim().lowercase()
            val products = NutritionProductStore.all(ctx).filter {
                q.isBlank() || it.name.lowercase().contains(q) || it.brand.lowercase().contains(q)
            }
            renderProductCards(ctx, list, products, onMealClick) {
                redraw()
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = redraw()
        })
        add.setOnClickListener { showProductDialog(ctx, onPickPhoto) { redraw() } }
        redraw()
    }

    private fun renderProductCards(
        ctx: Context,
        list: LinearLayout,
        products: List<NutritionProductStore.Product>,
        onMealClick: (String) -> Unit,
        onChanged: () -> Unit
    ) {
        val d = ctx.resources.displayMetrics.density
        list.removeAllViews()
        if (products.isEmpty()) {
            list.addView(TextView(ctx).apply {
                text = "Нет продуктов"
                setTextColor(TEXT_HINT)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, (24 * d).toInt(), 0, 0)
            })
            return
        }
        products.forEach { p ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * d).toInt(), (10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt())
                setBackgroundResource(R.drawable.meal_card_bg)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * d).toInt() }
            }
            val img = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt())
                    .apply { marginEnd = (10 * d).toInt() }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF2B2B2B.toInt())
                p.photoPath?.let { setImageURI(Uri.fromFile(File(it))) }
            }
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(ctx).apply {
                text = if (p.brand.isBlank()) p.name else "${p.name} · ${p.brand}"
                setTextColor(TEXT_PRIMARY)
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            texts.addView(TextView(ctx).apply {
                text = "${p.kcal} ккал · Б ${p.protein} / Ж ${p.fat} / У ${p.carbs}"
                setTextColor(TEXT_HINT)
                textSize = 12f
            })
            val plus = ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_plus)
                setBackgroundColor(Color.TRANSPARENT)
                setColorFilter(0xFF4CAF50.toInt())
                setOnClickListener { onMealClick(formatProduct(p)) }
            }
            val del = ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_menu_delete)
                setBackgroundColor(Color.TRANSPARENT)
                setColorFilter(TEXT_HINT)
                setOnClickListener {
                    NutritionProductStore.delete(ctx, p.id)
                    onChanged()
                }
            }
            row.addView(img)
            row.addView(texts)
            row.addView(plus, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
            row.addView(del, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
            list.addView(row)
        }
    }

    private fun showProductDialog(
        ctx: Context,
        onPickPhoto: (((Uri?) -> Unit) -> Unit)?,
        onSaved: () -> Unit
    ) {
        val d = ctx.resources.displayMetrics.density
        var photoPath: String? = null
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        fun field(hint: String, number: Boolean = false) = EditText(ctx).apply {
            this.hint = hint
            inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_HINT)
        }
        val name = field("Название")
        val brand = field("Бренд")
        val protein = field("Белки, г", true)
        val fat = field("Жиры, г", true)
        val carbs = field("Углеводы, г", true)
        val photo = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (120 * d).toInt()
            ).apply { topMargin = (8 * d).toInt() }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF2B2B2B.toInt())
        }
        val photoBtn = Button(ctx).apply {
            text = "Фото"
            setOnClickListener {
                onPickPhoto?.invoke { uri ->
                    photoPath = uri?.let { copyProductPhoto(ctx, it) }
                    photoPath?.let { photo.setImageURI(Uri.fromFile(File(it))) }
                }
            }
        }
        listOf(name, brand, protein, fat, carbs).forEach { box.addView(it) }
        box.addView(photoBtn)
        box.addView(photo)

        AlertDialog.Builder(ctx)
            .setTitle("Продукт")
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                val b = protein.text.toString().toIntOrNull() ?: 0
                val f = fat.text.toString().toIntOrNull() ?: 0
                val c = carbs.text.toString().toIntOrNull() ?: 0
                val title = name.text.toString().trim()
                if (title.isNotBlank()) {
                    NutritionProductStore.add(ctx, NutritionProductStore.Product(
                        name = title,
                        brand = brand.text.toString().trim(),
                        protein = b,
                        fat = f,
                        carbs = c,
                        photoPath = photoPath
                    ))
                    onSaved()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun copyProductPhoto(ctx: Context, uri: Uri): String? {
        return runCatching {
            val dir = File(ctx.filesDir, "nutrition_product_photos").apply { mkdirs() }
            val out = File(dir, "product_${System.currentTimeMillis()}.jpg")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { input.copyTo(it) }
            }
            out.absolutePath
        }.getOrNull()
    }

    private fun formatProduct(p: NutritionProductStore.Product): String =
        "${p.name}${if (p.brand.isBlank()) "" else " (${p.brand})"}: ${p.kcal} ккал, Б ${p.protein} г, Ж ${p.fat} г, У ${p.carbs} г"

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
        val kcalPerGram = mapOf("protein" to 4, "fat" to 9, "carbs" to 4)

        // Подавление рекурсии при программной установке текста
        var suppress = false

        fun gramsToPercent(key: String, grams: Int): Int {
            if (p.kcalNorm <= 0) return 0
            return (grams * kcalPerGram[key]!! * 100.0 / p.kcalNorm).toInt().coerceIn(0, 100)
        }
        fun percentToGrams(key: String, percent: Int): Int {
            return (percent / 100.0 * p.kcalNorm / kcalPerGram[key]!!).toInt().coerceAtLeast(0)
        }
        fun parseInt(s: CharSequence?, default: Int = 0): Int =
            s?.toString()?.trim()?.toIntOrNull() ?: default

        fun EditText.watch(onChange: () -> Unit) {
            this.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (suppress) return
                    onChange()
                }
            })
        }
        fun EditText.setTextSafe(value: String) {
            suppress = true
            setText(value)
            suppress = false
        }

        fun styledField(initial: Int, hintColor: Int = 0xFF555555.toInt()): EditText =
            EditText(ctx).apply {
                setText(initial.toString())
                this.hint = "0"
                inputType = InputType.TYPE_CLASS_NUMBER
                setTextColor(TEXT_PRIMARY)
                setHintTextColor(hintColor)
                setBackgroundColor(0xFF1F1F1F.toInt())
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                val pad = (8 * d).toInt()
                setPadding(pad, pad, pad, pad)
            }

        fun columnHeader(title: String): TextView = TextView(ctx).apply {
            text = title
            setTextColor(TEXT_HINT)
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        fun columnSubhead(text: String): TextView = TextView(ctx).apply {
            this.text = text
            setTextColor(0xFF666666.toInt())
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, (6 * d).toInt(), 0, (2 * d).toInt())
        }

        // 4 колонки
        val kcalField = styledField(p.kcalNorm)
        val gramsFields = mutableMapOf<String, EditText>()
        val percentFields = mutableMapOf<String, EditText>()

        fun recomputePercents() {
            for (key in listOf("protein", "fat", "carbs")) {
                val g = parseInt(gramsFields[key]?.text)
                percentFields[key]?.setTextSafe(gramsToPercent(key, g).toString())
            }
        }

        // Header row: 4 названия колонок
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * d).toInt() }
        }
        for (title in listOf("ккал", "Белки", "Жиры", "Углеводы")) {
            headerRow.addView(
                columnHeader(title),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        content.addView(headerRow)

        // Subhead: граммы
        val gramsSubhead = TextView(ctx).apply {
            text = "граммы"
            setTextColor(0xFF666666.toInt())
            textSize = 11f
            setPadding((16 * d).toInt(), (12 * d).toInt(), 0, (4 * d).toInt())
        }
        content.addView(gramsSubhead)

        // Grams row
        val gramsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        gramsRow.addView(
            kcalField,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins((3 * d).toInt(), 0, (3 * d).toInt(), 0) }
        )
        for ((key, value) in listOf(
            "protein" to p.proteinG,
            "fat" to p.fatG,
            "carbs" to p.carbsG
        )) {
            val f = styledField(value)
            gramsFields[key] = f
            gramsRow.addView(
                f,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins((3 * d).toInt(), 0, (3 * d).toInt(), 0) }
            )
        }
        content.addView(gramsRow)

        // Subhead: проценты
        val percentSubhead = TextView(ctx).apply {
            text = "проценты"
            setTextColor(0xFF666666.toInt())
            textSize = 11f
            setPadding((16 * d).toInt(), (8 * d).toInt(), 0, (4 * d).toInt())
        }
        content.addView(percentSubhead)

        // Percent row (только 3 колонки для макросов)
        val percentRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        // Пустой «ккал» слот (без процентов)
        percentRow.addView(
            View(ctx),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins((3 * d).toInt(), 0, (3 * d).toInt(), 0) }
        )
        for (key in listOf("protein", "fat", "carbs")) {
            val initial = gramsToPercent(key, parseInt(gramsFields[key]?.text))
            val f = styledField(initial, hintColor = 0xFF777777.toInt())
            percentFields[key] = f
            percentRow.addView(
                f,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins((3 * d).toInt(), 0, (3 * d).toInt(), 0) }
            )
        }
        content.addView(percentRow)

        // Сумма процентов — обновляется при правке
        val sumText = TextView(ctx).apply {
            text = "Сумма: 0%"
            setTextColor(0xFF8A8A8A.toInt())
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, (10 * d).toInt(), 0, 0)
        }
        content.addView(sumText)

        fun updateSum() {
            val total = parseInt(percentFields["protein"]?.text) +
                parseInt(percentFields["fat"]?.text) +
                parseInt(percentFields["carbs"]?.text)
            sumText.text = "Сумма: $total%"
            sumText.setTextColor(
                when {
                    total == 100 -> 0xFF4CAF50.toInt()
                    total in 90..110 -> 0xFFFFC107.toInt()
                    else -> 0xFFE57373.toInt()
                }
            )
        }
        updateSum()

        // Watchers — двусторонняя конверсия
        kcalField.watch {
            val newKcal = parseInt(kcalField.text)
            // при смене нормы — пересчитать все проценты
            p.copy(kcalNorm = newKcal).also { /* фиктивно — реальная перезапись ниже */ }
            // пересчёт делаем через gramsToPercent который читает p.kcalNorm,
            // значит надо сначала обновить p локально через лямбду замыкания
            // проще: пересчитываем здесь вручную
            for (key in listOf("protein", "fat", "carbs")) {
                val g = parseInt(gramsFields[key]?.text)
                percentFields[key]?.setTextSafe(gramsToPercent(key, g).toString())
            }
        }

        for (key in listOf("protein", "fat", "carbs")) {
            gramsFields[key]!!.watch {
                val g = parseInt(gramsFields[key]?.text)
                percentFields[key]?.setTextSafe(gramsToPercent(key, g).toString())
            }
            percentFields[key]!!.watch {
                val raw = parseInt(percentFields[key]?.text)
                val clamped = raw.coerceIn(0, 100)
                if (clamped != raw) {
                    percentFields[key]?.setTextSafe(clamped.toString())
                }
                gramsFields[key]?.setTextSafe(percentToGrams(key, clamped).toString())
                updateSum()
            }
        }

        // Кнопка Сохранить
        val btn = Button(ctx).apply {
            text = "Сохранить"
            setBackgroundColor(CORAL)
            setTextColor(Color.WHITE)
            val m = (16 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = m * 2 }
            setOnClickListener {
                val kcal = parseInt(kcalField.text, p.kcalNorm).coerceAtLeast(1)
                fun pct(key: String, grams: Int): Int =
                    (grams * kcalPerGram[key]!! * 100.0 / kcal).toInt().coerceIn(0, 100)
                save(ctx, Params(
                    kcalNorm = kcal,
                    proteinPercent = pct("protein", parseInt(gramsFields["protein"]?.text, p.proteinG)),
                    fatPercent = pct("fat", parseInt(gramsFields["fat"]?.text, p.fatG)),
                    carbsPercent = pct("carbs", parseInt(gramsFields["carbs"]?.text, p.carbsG)),
                    waterMl = p.waterMl,
                    productLookupEnabled = load(ctx).productLookupEnabled
                ))
                onSaved()
            }
        }
        content.addView(btn)
    }
}
