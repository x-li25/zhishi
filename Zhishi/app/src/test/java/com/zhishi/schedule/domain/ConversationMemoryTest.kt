package com.zhishi.schedule.domain

import org.junit.Assert.*
import org.junit.Test

class ConversationMemoryTest {
    @Test fun `old preference survives rolling archive while most recent notes stay bounded`() {
        val first = ChatMessage(1, ChatRole.USER, "记住我不喜欢早上跑步", timestamp = 1000)
        var memory = ConversationMemory().merge(listOf(first))
        (2L..200L).chunked(10).forEach { ids -> memory = memory.merge(ids.map { ChatMessage(it, ChatRole.USER, "普通记录$it") }) }
        assertTrue(memory.notes.any { it.messageId == 1L && it.preference })
        assertTrue(memory.notes.any { it.messageId == 200L })
        assertTrue(memory.notes.size <= 64)
    }

    @Test fun `summaries preserve date anchors in omitted middle and never copy full OCR or turn assistant into user facts`() {
        val user = ChatMessage(1, ChatRole.USER, "通知" + "长段落".repeat(300) + "2026-11-10 09:50 第七周周三" + "尾段".repeat(300),
            imageText = "不应进入记忆的OCR原文".repeat(1000))
        val assistant = ChatMessage(2, ChatRole.ASSISTANT, "我猜你以后喜欢清晨跑步")
        val memory = ConversationMemory().merge(listOf(user, assistant))
        val note = memory.notes.first()
        assertTrue(note.excerpt.length < 400)
        assertTrue(note.timeReferences.contains("2026-11-10"))
        assertTrue(note.timeReferences.contains("09:50"))
        assertTrue(note.timeReferences.contains("第七周周三"))
        assertFalse(note.excerpt.contains("OCR原文"))
        assertFalse(memory.notes.last().preference)
        assertEquals(ChatRole.ASSISTANT, memory.notes.last().role)
    }

    @Test fun `new user corrections are distinct chronological evidence and updates replace duplicate ids`() {
        val before = ChatMessage(1, ChatRole.USER, "以后每天早上跑步", timestamp = 1000)
        val after = ChatMessage(2, ChatRole.USER, "以后不在早上跑步，改为晚上", timestamp = 2000)
        val memory = ConversationMemory().merge(listOf(before, after)).merge(listOf(after.copy(text = "以后不跑步，改为游泳")))
        assertEquals(2, memory.notes.size)
        assertTrue(memory.notes.last().excerpt.contains("游泳"))
        assertEquals(listOf(1000L, 2000L), memory.notes.map { it.timestamp })
    }
}
