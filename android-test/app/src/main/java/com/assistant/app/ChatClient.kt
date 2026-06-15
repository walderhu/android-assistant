package com.assistant.app

import android.content.Context

object ChatClient {
    suspend fun send(
        ctx: Context,
        messages: List<Pair<String, String>>,
        model: String,
        systemPrompt: String? = null
    ): String {
        if (Settings.isLocalModel(model)) {
            return LocalLlmClient.send(
                baseUrl = Settings.getLocalUrl(ctx),
                model = Settings.getLocalModelName(ctx),
                messages = messages,
                systemPrompt = systemPrompt,
                apiKey = Settings.getLocalApiKey(ctx)
            )
        }
        return OpenRouterClient.send(messages, model, systemPrompt)
    }
}
