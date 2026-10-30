package com.zhishi.schedule.ui.screens

import android.app.Application
import android.content.Context
import androidx.lifecycle.viewModelScope
import com.zhishi.schedule.ai.GlmClient
import com.zhishi.schedule.data.ChatStore
import com.zhishi.schedule.domain.CalendarEvent
import com.zhishi.schedule.domain.ChatMessage
import com.zhishi.schedule.domain.ChatRole
import com.zhishi.schedule.domain.GlmConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppViewModelGenerationTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<AppViewModel>()
    private lateinit var app: Application

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        app = RuntimeEnvironment.getApplication()
        listOf("zhishi_chat", "zhishi_settings").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @After
    fun cleanup() {
        models.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun `stop restores draft and history then late cancelled response cannot overwrite new reply`() = runBlocking {
        val started = CompletableDeferred<Call>()
        val oldFinished = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val model = model(GlmClient(OkHttpClient.Builder().addInterceptor { chain ->
            val old = calls.incrementAndGet() == 1
            if (old) {
                started.complete(chain.call())
                check(release.await(5, TimeUnit.SECONDS))
                oldFinished.complete(Unit)
            }
            response(chain.request(), if (old) "旧回复" else "新回复")
        }.build()))
        val history = listOf(ChatMessage(1, ChatRole.USER, "之前的问题"), ChatMessage(2, ChatRole.ASSISTANT, "之前的回复"))
        val previousEvents = listOf(CalendarEvent(9, 1, "已知日程", 1000, 2000, false))
        state(model).value = model.state.value.copy(messages = history, knownEvents = previousEvents)
        try {
            model.updateText("  需要中断的提问  ")
            model.sendChatMessage()
            await { started.isCompleted }
            assertTrue(model.state.value.canStopGeneration)
            model.stopGeneration()
            assertTrue(started.await().isCanceled())
            assertEquals("  需要中断的提问  ", model.state.value.textInput)
            assertEquals(history, model.state.value.messages)
            assertEquals(previousEvents, model.state.value.knownEvents)
            assertFalse(model.state.value.parsing)
            assertFalse(model.state.value.canStopGeneration)
            assertEquals("已停止生成", model.state.value.notice)
            assertNull(model.state.value.error)

            model.updateText("新的提问")
            model.sendChatMessage()
            await { !model.state.value.parsing }
            assertEquals("新回复", model.state.value.messages.last().text)
            release.countDown()
            await { oldFinished.isCompleted }
            dispatcher.scheduler.runCurrent()
            assertEquals(history + model.state.value.messages.takeLast(2), model.state.value.messages)
            assertEquals("新回复", model.state.value.messages.last().text)
            assertEquals("", model.state.value.textInput)
            assertNull(model.state.value.notice)
            assertEquals(model.state.value.messages, ChatStore(app).load())
            val completed = model.state.value
            model.stopGeneration() // 完成后重复点击无副作用。
            assertEquals(completed, model.state.value)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `stopping queued image generation retains attachment without starting OCR or network`() {
        val calls = AtomicInteger()
        val model = model(GlmClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            response(chain.request(), "不应生成")
        }.build()))
        val directory = File(app.filesDir, "chat_images").apply { mkdirs() }
        val image = File(directory, "${UUID.randomUUID()}.jpg").apply { writeText("private attachment") }
        // 文件在 ViewModel 初始化后加入，模拟用户刚刚选中的附件。
        state(model).value = model.state.value.copy(selectedImageFileName = image.name)
        model.updateText("只安排图片中的周五")
        model.sendChatMessage()
        assertTrue(model.state.value.canStopGeneration)
        model.stopGeneration()
        model.stopGeneration()
        dispatcher.scheduler.runCurrent()
        assertEquals(0, calls.get())
        assertEquals(image.name, model.state.value.selectedImageFileName)
        assertEquals("只安排图片中的周五", model.state.value.textInput)
        assertTrue(image.exists())
        assertTrue(model.state.value.messages.isEmpty())
        assertTrue(ChatStore(app).load().isEmpty())
        assertFalse(model.state.value.parsing)
        assertNull(model.state.value.error)
    }

    @Test
    fun `ordinary generation failure restores draft and clears stop state while keeping real error`() = runBlocking {
        val model = model(GlmClient(OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), "服务器错误").newBuilder().code(500).build()
        }.build()))
        model.updateText("保留这条草稿")
        model.sendChatMessage()
        await { !model.state.value.parsing }
        assertEquals("保留这条草稿", model.state.value.textInput)
        assertTrue(model.state.value.messages.isEmpty())
        assertFalse(model.state.value.canStopGeneration)
        assertTrue(model.state.value.error!!.contains("500"))
        assertNull(model.state.value.notice)
        val failed = model.state.value
        model.stopGeneration()
        assertEquals(failed, model.state.value)
    }

    private fun model(client: GlmClient): AppViewModel = AppViewModel(app, client).also {
        models += it
        // 用测试配置绕开 Android Keystore；生成与存储仍走真实 ViewModel。
        state(it).value = it.state.value.copy(glmConfig = GlmConfig(baseUrl = "https://example.test", apiKey = "test"))
    }

    private fun state(model: AppViewModel): MutableStateFlow<AppUiState> = ReflectionHelpers.getField(model, "_state")

    private suspend fun await(condition: () -> Boolean) = withTimeout(3_000) {
        do {
            dispatcher.scheduler.runCurrent()
            if (condition()) break
            delay(5)
        } while (true)
    }

    private fun response(request: okhttp3.Request, reply: String): Response {
        val payload = buildJsonObject { put("reply", reply); put("actions", buildJsonArray {}) }
        val body = buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject { put("message", buildJsonObject { put("content", payload.toString()) }) })
            })
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toString().toResponseBody()).build()
    }
}
