package com.assistant.app

/**
 * Моды (отдельные чаты с собственным системным промптом и памятью).
 * Название / цвет / системный промпт — всё в одном месте.
 */
object Modes {
    data class Mode(
        val id: String,
        val name: String,
        val color: Int,
        val systemPrompt: String
    )

    val all: List<Mode> = listOf(
        Mode(
            id = "nutrition",
            name = "Питание",
            color = 0xFF4CAF50.toInt(),
            systemPrompt = """Ты — ассистент по питанию. Помогаешь вести дневник: добавлять, исправлять, переносить между днями и приёмами, удалять записи.
Можешь переносить продукты, целые приёмы и весь день на вчера/завтра через LOG ops move, move_meal, move_day.
Отвечай кратко по-русски. Учитывай текущий дневник и явные указания пользователя."""
        )
    )

    fun byId(id: String): Mode? = all.firstOrNull { it.id == id }
}
