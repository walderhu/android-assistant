package com.assistant.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NutritionAgentRunner {

    suspend fun sendFoodText(ctx: Context, text: String, meal: String? = null): String =
        withContext(Dispatchers.IO) {
            val trimmed = text.trim()
            if (trimmed.isBlank()) return@withContext "Пустое сообщение"
            val repo = ChatRepository(ctx)
            val state = repo.load()
            val chat = repo.findModeChat(state, "nutrition")
                ?: repo.createChat(state, modeId = "nutrition", title = "Питание")
            state.currentId = chat.id
            val dateKey = NutritionFoodLogger.dateKeyFrom(state)
            val m = meal ?: NutritionController.mealForHour(java.time.LocalTime.now().hour)
            val msg = if (trimmed.startsWith("[")) trimmed else "[$m] $trimmed"
            repo.appendMessage(state, chat.id, "user", msg)
            val history = chat.messages
                .filter { !it.isLoading }
                .map { (if (it.isUser) "user" else "assistant") to it.text }
            val base = Modes.byId("nutrition")?.systemPrompt.orEmpty()
            val sysPrompt = base + "\n\n" + NutritionFoodLogger.diaryContext(ctx, dateKey) +
                "\n\n" + NutritionFoodLogger.LOG_INSTRUCTIONS
            val model = Settings.get(ctx, Settings.Category.TEXT)
            var reply = ChatClient.send(ctx, history, model, sysPrompt)
            val (visible, log) = NutritionFoodLogger.stripLogBlock(reply)
            NutritionFoodLogger.apply(ctx, dateKey, log)
            reply = visible.ifBlank { "Записал." }
            repo.appendMessage(state, chat.id, "assistant", reply)
            repo.save(state)
            NutritionWidgetHelper.updateAll(ctx)
            reply
        }
}
