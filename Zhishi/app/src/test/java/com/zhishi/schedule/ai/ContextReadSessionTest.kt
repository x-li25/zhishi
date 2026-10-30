package com.zhishi.schedule.ai

import com.zhishi.schedule.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ContextReadSessionTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun `recent six messages retain exact latest request while older conversation becomes sourced memory`() {
        val history = (1L..21L).map { ChatMessage(it, if (it % 2 == 1L) ChatRole.USER else ChatRole.ASSISTANT,
            if (it == 1L) "记住我每周三晚上参加合唱团" else "消息$it", timestamp = 1792800000000L + it * 1000) }
        val session = ContextReadSession(history, ConversationMemory(), zone)
        assertEquals((16L..21L).toList(), session.recent.map { it.id })
        val memory = session.memoryMessage().toString()
        assertTrue(memory.contains("合唱团"))
        assertTrue(memory.contains("message_id"))
        assertTrue(memory.contains("+08:00"))
        assertFalse(session.recent.any { it.id == 1L })
        assertTrue(session.messageContent(history.last()).contains("消息21"))
    }

    @Test fun `picture followup prefers analysis and only explicit raw read returns OCR`() {
        val picture = ChatMessage(1, ChatRole.USER, "第一张课表", imageAnalysis = "第二项：周五14点数学课",
            imageText = "OCR独有标记：周五数学，可能错字")
        val session = ContextReadSession(listOf(picture, ChatMessage(2, ChatRole.USER, "第二项几点？")), ConversationMemory(), zone)
        val content = session.messageContent(picture)
        assertTrue(content.contains("周五14点数学课"))
        assertFalse(content.contains("OCR独有标记"))
        val raw = read(session, 1, "image_text")
        assertTrue(raw["text"]!!.jsonPrimitive.content.contains("OCR独有标记"))
        assertTrue(raw["complete"]!!.jsonPrimitive.boolean)
    }

    @Test fun `unrelated greeting does not attach picture body and missing analysis uses legacy OCR when related`() {
        val picture = ChatMessage(1, ChatRole.USER, "通知", imageText = "周二9点物理会议")
        val greeting = ContextReadSession(listOf(picture, ChatMessage(2, ChatRole.USER, "你好")), ConversationMemory(), zone)
        assertFalse(greeting.messageContent(picture).contains("周二9点物理会议"))
        val followup = ContextReadSession(listOf(picture, ChatMessage(2, ChatRole.USER, "之前那张图是周几")), ConversationMemory(), zone)
        assertTrue(followup.messageContent(picture).contains("周二9点物理会议"))
    }

    @Test fun `equally relevant pictures prefer newer sources and keep their ids for disambiguation`() {
        val pictures = (1L..4L).map { ChatMessage(it, ChatRole.USER, "课表", imageAnalysis = "第二项：图片${it}的独有内容") }
        val session = ContextReadSession(pictures + ChatMessage(5, ChatRole.USER, "第二项几点"), ConversationMemory(), zone)
        assertFalse(session.messageContent(pictures.first()).contains("图片1的独有内容"))
        assertTrue(session.messageContent(pictures.last()).contains("图片4的独有内容"))
        assertTrue(session.messageContent(pictures.last()).contains("message_id"))
    }

    @Test fun `long source reads paginate and budget counts unique reads rather than cached repeats`() {
        val raw = "开始" + "资料".repeat(20000) + "结束"
        val session = ContextReadSession(listOf(ChatMessage(1, ChatRole.USER, "图", imageText = raw)), ConversationMemory(), zone)
        val first = read(session, 1, "image_text", 0, 8000)
        assertFalse(first["complete"]!!.jsonPrimitive.boolean)
        assertEquals(8000, first["next_offset"]!!.jsonPrimitive.int)
        repeat(5) { assertEquals(first, read(session, 1, "image_text", 0, 8000)) }
        (1..3).forEach { read(session, 1, "image_text", it * 8000, 8000) }
        assertTrue(read(session, 1, "image_text", 32000, 8000).containsKey("error"))
    }

    @Test fun `unknown ids expired original bad fields and invalid offsets never become complete evidence`() {
        val messages = (1L..102L).map { ChatMessage(it, ChatRole.USER, "原文$it") }
        val session = ContextReadSession(messages, ConversationMemory(), zone)
        assertTrue(read(session, 1).containsKey("error")) // 已超出最近100条。
        assertTrue(read(session, 999).containsKey("error"))
        assertTrue(read(session, 100, "file_path").containsKey("error"))
        assertTrue(read(session, 100, offset = -1).containsKey("error"))
        assertTrue(read(session, 100, offset = 99).containsKey("error"))
        assertTrue(read(session, 100, limit = 9000).containsKey("error"))
        assertEquals("原文100", read(session, 100)["text"]!!.jsonPrimitive.content)
    }

    @Test fun `current OCR remains retrievable after replacing it with compact image analysis`() {
        val message = ChatMessage(12, ChatRole.USER, "按图安排")
        val session = ContextReadSession(listOf(message), ConversationMemory(), zone, currentImageText = "当前OCR日期2026-11-10")
        assertFalse(session.messageContent(message).contains("当前OCR日期"))
        assertEquals("当前OCR日期2026-11-10", read(session, 12, "image_text")["text"]!!.jsonPrimitive.content)
    }

    @Test fun `historical actions keep dates execution marker and original source without becoming a live calendar`() {
        val event = ParsedEvent("数学", 1793185200000L, 1793188800000L)
        val message = ChatMessage(3, ChatRole.ASSISTANT, "待确认", actions = listOf(event), executed = false)
        val session = ContextReadSession(listOf(message, ChatMessage(4, ChatRole.USER, "改晚一小时")), ConversationMemory(), zone)
        assertTrue(session.messageContent(message).contains("历史操作资料"))
        val data = read(session, 3, "actions")
        assertFalse(data["executed_marker"]!!.jsonPrimitive.boolean)
        assertTrue(data["note"]!!.jsonPrimitive.content.contains("重新读取日历"))
        assertTrue(data["text"]!!.jsonPrimitive.content.contains("1793185200000"))
    }

    @Test fun `academic lookup returns actual cross year dates and unusual second period locally`() {
        val session = ContextReadSession(listOf(ChatMessage(1, ChatRole.USER, "第十六周周五第二大节")), ConversationMemory(), zone)
        val data = session.read(Json.parseToJsonElement("""[{"kind":"academic","week":16,"weekday":5,"period":2}]""").jsonArray)
            .get("context_results")!!.jsonArray.single().jsonObject
        assertEquals("2027-01-01", data["date"]!!.jsonPrimitive.content)
        assertEquals("2027-01-01T09:50+08:00", data["start"]!!.jsonPrimitive.content)
        assertEquals("2027-01-01T12:15+08:00", data["end"]!!.jsonPrimitive.content)
        assertTrue(data["complete"]!!.jsonPrimitive.boolean)
    }

    private fun read(session: ContextReadSession, id: Long, field: String = "text", offset: Int = 0, limit: Int = 4000): JsonObject =
        session.read(buildJsonArray { add(buildJsonObject {
            put("kind", "history"); put("message_id", id); put("field", field); put("offset", offset); put("limit", limit)
        }) })["context_results"]!!.jsonArray.single().jsonObject
}
