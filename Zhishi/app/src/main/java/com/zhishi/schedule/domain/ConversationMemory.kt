package com.zhishi.schedule.domain

import kotlinx.serialization.Serializable
import java.time.Instant

/** 摘录保留来源与时间，不把助手的推测提炼成用户事实。原文仍由 ChatStore 保留。 */
@Serializable
data class MemoryNote(
    val messageId: Long,
    val timestamp: Long,
    val role: ChatRole,
    val excerpt: String,
    val hasImage: Boolean = false,
    val preference: Boolean = false,
    val timeReferences: List<String> = emptyList()
)

@Serializable
data class ConversationMemory(val notes: List<MemoryNote> = emptyList()) {
    fun merge(messages: List<ChatMessage>): ConversationMemory {
        val combined = notes.associateByTo(linkedMapOf()) { it.messageId }
        messages.forEach { message ->
            val hint = if (message.actions.isEmpty()) "" else message.actions.take(3).joinToString("；", prefix = "〔历史操作：", postfix = "〕") {
                "${it.action}:${it.title.take(40)}@${Instant.ofEpochMilli(it.startMillis)}" }
            combined[message.id] = MemoryNote(message.id, message.timestamp, message.role,
                excerpt(message.text, 180) + hint.take(180),
                message.imageFileName != null || message.imageAnalysis != null || message.imageText != null,
                message.role == ChatRole.USER && PREFERENCE.containsMatchIn(message.text),
                TIME_REFERENCE.findAll(message.text).map { it.value }.distinct().take(6).toList())
        }
        val all = combined.values.toList()
        val retained = (all.filter { it.preference }.takeLast(24) + all.takeLast(40))
            .distinctBy { it.messageId }.sortedBy { all.indexOf(it) }
        return ConversationMemory(retained)
    }

    companion object {
        const val RECENT_MESSAGES = 6
        private val PREFERENCE = Regex("记住|记忆|以后|偏好|习惯|喜欢|不喜欢|不要|不想|每周|每天|通常|一般|固定|不再")
        private val TIME_REFERENCE = Regex("\\d{4}-\\d{2}-\\d{2}(?:T\\d{2}:\\d{2}(?::\\d{2})?(?:Z|[+-]\\d{2}:\\d{2})?)?|(?:\\d{4}年)?\\d{1,2}月\\d{1,2}[日号]|第[0-9一二三四五六七八九十两]{1,3}(?:周|大节|大课)(?:周[一二三四五六日天])?|(?:今天|明天|后天|昨天|今晚|明晚|下周|本周|上周|下个月|本月|上个月)|\\d{1,2}[:：]\\d{2}")
        fun excerpt(text: String, limit: Int): String = if (text.length <= limit) text
            else text.take(limit * 2 / 3) + "…〔有省略〕…" + text.takeLast(limit / 3)
    }
}
