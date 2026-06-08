package com.assistant.app

import android.content.Context
import android.content.SharedPreferences

object Settings {
    private const val PREFS = "settings"
    private const val K_TEXT = "model_text"
    private const val K_VOICE = "model_voice"
    private const val K_IMAGE = "model_image"
    private const val K_SORT = "sort_mode"

    /** Цена за 1M токенов (in/out) для текста/изображений, $/мин — для голоса. */
    data class ModelOption(
        val id: String,
        val label: String,
        val inputPrice: Double?,
        val outputPrice: Double?,
        val popularity: Int
    )

    enum class Category { TEXT, VOICE, IMAGE }

    enum class SortMode(val label: String) {
        DEFAULT("По умолчанию"),
        PRICE_ASC("Дешевле сначала"),
        PRICE_DESC("Дороже сначала"),
        POPULARITY("По популярности")
    }

    /** Стоимость для сортировки (ниже — дешевле). */
    private fun cost(o: ModelOption): Double = o.inputPrice ?: Double.MAX_VALUE

    private val textOptions = listOf(
        ModelOption("openai/gpt-4o-mini", "GPT-4o Mini", 0.15, 0.60, 95),
        ModelOption("openai/gpt-4o", "GPT-4o", 2.50, 10.00, 80),
        ModelOption("anthropic/claude-3-haiku", "Claude 3 Haiku", 0.25, 1.25, 50),
        ModelOption("anthropic/claude-3.5-sonnet", "Claude 3.5 Sonnet", 3.00, 15.00, 90),
        ModelOption("google/gemini-flash-1.5", "Gemini 1.5 Flash", 0.075, 0.30, 70)
    )
    private val voiceOptions = listOf(
        ModelOption("openai/gpt-audio", "GPT-Audio", 10.00, 20.00, 50),
        ModelOption("openai/whisper-1", "Whisper-1", 0.006, null, 70),
        ModelOption("groq/whisper-large-v3", "Groq Whisper", 0.00185, null, 90)
    )
    private val imageOptions = listOf(
        ModelOption("openai/gpt-4o-mini", "GPT-4o Mini", 0.15, 0.60, 95),
        ModelOption("openai/gpt-4o", "GPT-4o", 2.50, 10.00, 80),
        ModelOption("anthropic/claude-3.5-sonnet", "Claude 3.5 Sonnet", 3.00, 15.00, 90),
        ModelOption("google/gemini-flash-1.5", "Gemini 1.5 Flash", 0.075, 0.30, 70)
    )

    private val defaults = mapOf(
        Category.TEXT to "openai/gpt-4o-mini",
        Category.VOICE to "openai/gpt-4o-mini",
        Category.IMAGE to "openai/gpt-4o-mini"
    )

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun options(cat: Category): List<ModelOption> = when (cat) {
        Category.TEXT -> textOptions
        Category.VOICE -> voiceOptions
        Category.IMAGE -> imageOptions
    }

    fun sortedOptions(cat: Category, mode: SortMode): List<ModelOption> {
        val base = options(cat)
        return when (mode) {
            SortMode.DEFAULT -> base
            SortMode.PRICE_ASC -> base.sortedBy { cost(it) }
            SortMode.PRICE_DESC -> base.sortedByDescending { cost(it) }
            SortMode.POPULARITY -> base.sortedByDescending { it.popularity }
        }
    }

    fun get(ctx: Context, cat: Category): String {
        val saved = prefs(ctx).getString(key(cat), null)
        // Миграция со старых/сломанных моделей
        val broken = saved == "openai/gpt-3.5-turbo" || saved == "openai/gpt-audio" || saved == "openai/whisper-1"
        if (broken) {
            val newModel = defaults[cat]!!
            prefs(ctx).edit().putString(key(cat), newModel).apply()
            return newModel
        }
        return saved ?: defaults[cat].orEmpty()
    }

    fun set(ctx: Context, cat: Category, id: String) {
        prefs(ctx).edit().putString(key(cat), id).apply()
    }

    fun getSort(ctx: Context): SortMode {
        val name = prefs(ctx).getString(K_SORT, SortMode.DEFAULT.name)
        return runCatching { SortMode.valueOf(name ?: SortMode.DEFAULT.name) }
            .getOrDefault(SortMode.DEFAULT)
    }

    fun setSort(ctx: Context, mode: SortMode) {
        prefs(ctx).edit().putString(K_SORT, mode.name).apply()
    }

    /** Шапка таблицы под текущую категорию. */
    fun header(cat: Category): Triple<String, String, String> = when (cat) {
        Category.TEXT -> Triple("Название", "In \$/1M", "Out \$/1M")
        Category.IMAGE -> Triple("Название", "In \$/1M", "Out \$/1M")
        Category.VOICE -> Triple("Название", "Цена \$/мин", "—")
    }

    /** Форматирует цену: 0.075 -> "$0.075", 10.0 -> "$10.00", null -> "—". */
    fun fmtPrice(p: Double?): String {
        if (p == null) return "—"
        return when {
            p >= 1.0 -> "$%.2f".format(p)
            p >= 0.01 -> "$%.3f".format(p)
            else -> "$%.4f".format(p)
        }
    }

    private fun key(cat: Category) = when (cat) {
        Category.TEXT -> K_TEXT
        Category.VOICE -> K_VOICE
        Category.IMAGE -> K_IMAGE
    }
}
