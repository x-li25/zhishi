package com.zhishi.schedule.data

import android.content.Context
import com.zhishi.schedule.domain.ChatMessage
import com.zhishi.schedule.domain.ConversationMemory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 聊天记录的本机持久化（SharedPreferences + JSON），
 * 让 AI 助手重启应用后仍能记住对话。最多保留最近 100 条。
 */
class ChatStore(context: Context) {
    private val prefs = context.getSharedPreferences("zhishi_chat", Context.MODE_PRIVATE)
    private val images = ChatImageStore(context)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): List<ChatMessage> {
        val raw = prefs.getString(KEY_MESSAGES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ChatMessage>>(raw) }
            .getOrDefault(emptyList())
    }

    fun save(messages: List<ChatMessage>, retainedImageFileName: String? = null) {
        val memory = memory().merge(messages.dropLast(ConversationMemory.RECENT_MESSAGES))
        val trimmed = messages.takeLast(MAX_MESSAGES)
        val retained = trimmed.mapNotNull { it.imageFileName }.toSet() + listOfNotNull(retainedImageFileName)
        val removed = load().mapNotNull { it.imageFileName }.toSet() - retained
        prefs.edit().putString(KEY_MESSAGES, json.encodeToString(trimmed))
            .putString(KEY_MEMORY, json.encodeToString(memory)).apply()
        removed.forEach(images::delete)
    }

    fun clear() {
        prefs.edit().remove(KEY_MESSAGES).remove(KEY_MEMORY).apply()
        images.retainOnly(emptySet())
    }

    fun memory(): ConversationMemory = prefs.getString(KEY_MEMORY, null)?.let {
        runCatching { json.decodeFromString<ConversationMemory>(it) }.getOrNull()
    } ?: ConversationMemory()

    companion object {
        private const val KEY_MESSAGES = "messages"
        private const val KEY_MEMORY = "memory_v1"
        private const val MAX_MESSAGES = 100
    }
}
