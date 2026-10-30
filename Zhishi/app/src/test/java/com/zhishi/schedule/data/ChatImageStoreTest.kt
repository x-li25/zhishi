package com.zhishi.schedule.data

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBitmapFactory
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatImageStoreTest {
    @Test
    fun `imports PNG into private JPEG and exposes bounded thumbnail and data URL`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val source = File(context.cacheDir, "schedule.png")
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val store = ChatImageStore(context)
        val name = store.importImage(Uri.fromFile(source))
        assertTrue(name.endsWith(".jpg"))
        val copied = File(File(context.filesDir, "chat_images"), name)
        assertTrue(copied.exists())
        assertTrue(copied.length() <= ChatImageStore.MAX_IMAGE_BYTES)
        val url = store.dataUrl(name)
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
        val bytes = Base64.getDecoder().decode(url.substringAfter(','))
        assertEquals(0xff, bytes[0].toInt() and 0xff)
        assertEquals(0xd8, bytes[1].toInt() and 0xff)
        val preview = store.thumbnail(name)
        assertNotNull(preview)
        assertEquals(400, preview!!.width)
        assertEquals(200, preview.height)
        store.delete(name)
        assertFalse(copied.exists())
        assertTrue(source.exists())
    }

    @Test
    fun `invalid image and file traversal are rejected without touching source`() = runBlocking {
        // Robolectric 默认允许任意字节生成占位 Bitmap；这里使用真实解码失败行为。
        ShadowBitmapFactory.setAllowInvalidImageData(false)
        val context = RuntimeEnvironment.getApplication()
        val source = File(context.filesDir, "outside.txt").apply { writeText("not an image") }
        val store = ChatImageStore(context)
        assertTrue(runCatching { store.importImage(Uri.fromFile(source)) }.isFailure)
        assertTrue(runCatching { store.dataUrl("../outside.txt") }.isFailure)
        store.delete("../outside.txt")
        assertEquals("not an image", source.readText())
    }
}
