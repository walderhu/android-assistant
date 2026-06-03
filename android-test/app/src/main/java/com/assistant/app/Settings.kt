package com.assistant.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Хранилище выбранных моделей + каталог доступных вариантов.
 * costRank — относительная цена для сортировки (ниже = дешевле), едина для всех категорий.
 * popularity — чем больше, тем популярнее.
 */
object Settings {
    private const val PREFS = "settings"
    private const val K_TEXT = "model_text"
    private const val K_VOICE = "model_voice"
    private const val K_IMAGE = "model_image"
    private const val K_SORT = "sort_mode"

    data class ModelOption(
        val id: String,
        val label: String,
        val cost: String,
        val costRank: Double,
        val popularity: Int
    )

    enum class Category { TEXT, VOICE, IMAGE }

    enum class SortMode(val label: String) {
        DEFAULT("По умолчанию"),
        PRICE_ASC("Дешевле сначала"),
        PRICE_DESC("Дороже сначала"),
        POPULARITY("По популярности")
    }

    private val textOptions = listOf(
        ModelOption("openai/gpt-3.5-turbo", "GPT-3.5 Turbo", "\$0.50 in / \$1.50 out · 1M", 0.50, 60),
        ModelOption("openai/gpt-4o-mini", "GPT-4o Mini", "\$0.15 in / \$0.60 out · 1M", 0.15, 95),
        ModelOption("openai/gpt-4o", "GPT-4o", "\$2.50 in / \$10.00 out · 1M", 2.50, 80),
        ModelOption("anthropic/claude-3-haiku", "Claude 3 Haiku", "\$0.25 in / \$1.25 out · 1M", 0.25, 50),
        ModelOption("anthropic/claude-3.5-sonnet", "Claude 3.5 Sonnet", "\$3.00 in / \$15.00 out · 1M", 3.00, 90),
        ModelOption("google/gemini-flash-1.5", "Gemini 1.5 Flash", "\$0.075 in / \$0.30 out · 1M", 0.075, 70)
    )
    private val voiceOptions = listOf(
        ModelOption("openai/gpt-audio", "GPT-Audio (OpenRouter)", "\$10.00 in / \$20.00 out · 1M", 10.0, 50),
        ModelOption("openai/whisper-1", "Whisper-1 (OpenRouter)", "\$0.006 / мин", 0.5, 70),
        ModelOption("groq/whisper-large-v3", "Groq Whisper Large v3", "\$0.111 / час аудио", 0.02, 90)
    )
    private val imageOptions = listOf(
        ModelOption("openai/gpt-4o-mini", "GPT-4o Mini", "\$0.15 in / \$0.60 out · 1M", 0.15, 95),
        ModelOption("openai/gpt-4o", "GPT-4o", "\$2.50 in / \$10.00 out · 1M", 2.50, 80),
        ModelOption("anthropic/claude-3.5-sonnet", "Claude 3.5 Sonnet", "\$3.00 in / \$15.00 out · 1M", 3.00, 90),
        ModelOption("google/gemini-flash-1.5", "Gemini 1.5 Flash", "\$0.075 in / \$0.30 out · 1M", 0.075, 70)
    )

    private val defaults = mapOf(
        Category.TEXT to "openai/gpt-3.5-turbo",
        Category.VOICE to "openai/gpt-audio",
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
            SortMode.PRICE_ASC -> base.sortedBy { it.costRank }
            SortMode.PRICE_DESC -> base.sortedByDescending { it.costRank }
            SortMode.POPULARITY -> base.sortedByDescending { it.popularity }
        }
    }

    fun get(ctx: Context, cat: Category): String =
        prefs(ctx).getString(key(cat), defaults[cat]) ?: defaults[cat].orEmpty()

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

    private fun key(cat: Category) = when (cat) {
        Category.TEXT -> K_TEXT
        Category.VOICE -> K_VOICE
        Category.IMAGE -> K_IMAGE
    }
}
