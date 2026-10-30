package com.zhishi.schedule.data

import android.content.Context
import com.zhishi.schedule.domain.ChatMessage
import com.zhishi.schedule.domain.ChatRole
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatStoreTest {
    @Test
    fun `older chat records without image fields still load`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("zhishi_chat", Context.MODE_PRIVATE).edit()
            .putString("messages", """[{"id":1,"role":"USER","text":"你好"}]""").commit()
        val message = ChatStore(context).load().single()
        assertEquals("你好", message.text)
        assertNull(message.imageFileName)
        assertNull(message.imageText)
    }

    @Test
    fun `persists image reference and OCR text separately from binary data`() {
        val context = RuntimeEnvironment.getApplication()
        val image = localImage()
        val message = ChatMessage(1, ChatRole.USER, "安排会议", imageFileName = image.name,
            imageText = "11月10日\n09:00 会议", imageAnalysis = "已核对：2026年11月10日9点会议")
        ChatStore(context).save(listOf(message))
        assertEquals(message, ChatStore(context).load().single())
        val raw = context.getSharedPreferences("zhishi_chat", Context.MODE_PRIVATE).getString("messages", "")!!
        assertFalse(raw.contains("data:image"))
        assertFalse(raw.contains("private binary"))
        assertTrue(image.exists())
    }

    @Test
    fun `persists semantic picture schedule and uncertainties for later followup`() {
        val context = RuntimeEnvironment.getApplication()
        val image = localImage()
        val message = ChatMessage(1, ChatRole.USER, "请按图安排", imageFileName = image.name,
            imageAnalysis = "第一项：周五14点会议\n第二项：物理课，节次时刻待确认")
        ChatStore(context).save(listOf(message))
        val restored = ChatStore(context).load().single()
        assertEquals(message, restored)
        assertTrue(restored.imageAnalysis!!.contains("节次时刻待确认"))
        assertNull(restored.imageText)
    }

    @Test
    fun `failed send rollback preserves selected image and clear removes it`() {
        val image = localImage()
        val store = ChatStore(RuntimeEnvironment.getApplication())
        store.save(listOf(ChatMessage(1, ChatRole.USER, "安排", imageFileName = image.name)))
        store.save(emptyList(), retainedImageFileName = image.name)
        assertTrue(store.load().isEmpty())
        assertTrue(image.exists())
        store.clear()
        assertFalse(image.exists())
    }

    @Test
    fun `trimming old history removes its image while keeping recent images`() {
        val oldImage = localImage()
        val recentImage = localImage()
        val store = ChatStore(RuntimeEnvironment.getApplication())
        val first = ChatMessage(1, ChatRole.USER, "old", imageFileName = oldImage.name)
        store.save(listOf(first))
        val messages = listOf(first) + (2L..101L).map {
            ChatMessage(it, ChatRole.USER, "message$it", imageFileName = if (it == 101L) recentImage.name else null)
        }
        store.save(messages)
        assertEquals(100, store.load().size)
        assertFalse(oldImage.exists())
        assertTrue(recentImage.exists())
    }

    private fun localImage(): File {
        val directory = File(RuntimeEnvironment.getApplication().filesDir, "chat_images").apply { mkdirs() }
        return File(directory, "${UUID.randomUUID()}.jpg").apply { writeText("private binary") }
    }

    @Test fun `rolling memory survives original eviction and clear removes both records and memory`() {
        val store = ChatStore(RuntimeEnvironment.getApplication())
        store.clear()
        store.save(listOf(ChatMessage(1, ChatRole.USER, "记住我每周三晚上参加合唱团")) +
            (2L..110L).map { ChatMessage(it, ChatRole.USER, "普通对话$it") })
        val restored = ChatStore(RuntimeEnvironment.getApplication())
        assertEquals(100, restored.load().size)
        assertFalse(restored.load().any { it.id == 1L })
        assertTrue(restored.memory().notes.any { it.messageId == 1L && it.excerpt.contains("合唱团") })
        assertFalse(restored.memory().notes.any { it.messageId >= 105L })
        restored.clear()
        assertTrue(ChatStore(RuntimeEnvironment.getApplication()).memory().notes.isEmpty())
        assertTrue(restored.load().isEmpty())
    }

    @Test fun `legacy records migrate into local memory on save and corrupt summary does not delete chat`() {
        val context = RuntimeEnvironment.getApplication()
        val store = ChatStore(context)
        store.clear()
        val messages = (1L..10L).map { ChatMessage(it, ChatRole.USER, if (it == 1L) "以后我不想早起" else "原文$it") }
        store.save(messages)
        context.getSharedPreferences("zhishi_chat", Context.MODE_PRIVATE).edit().putString("memory_v1", "bad-json").commit()
        assertEquals(messages, store.load())
        assertTrue(store.memory().notes.isEmpty())
        store.save(store.load())
        assertTrue(store.memory().notes.any { it.messageId == 1L })
        assertEquals(messages, store.load())
    }

    @Test fun `cancelled pending user message never becomes persistent memory`() {
        val store = ChatStore(RuntimeEnvironment.getApplication())
        store.clear()
        val before = (1L..6L).map { ChatMessage(it, ChatRole.USER, "历史$it") }
        store.save(before + ChatMessage(7, ChatRole.USER, "记住取消的这句话"))
        store.save(before)
        assertFalse(store.load().any { it.id == 7L })
        assertFalse(store.memory().notes.any { it.messageId == 7L })
    }
}
