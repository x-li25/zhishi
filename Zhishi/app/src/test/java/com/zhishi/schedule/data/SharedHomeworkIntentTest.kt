package com.zhishi.schedule.data

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SharedHomeworkIntentTest {
    private val uri = Uri.parse("content://documents/report.pdf")
    @Test fun `extracts single file and multiple files while preserving order`() {
        val single = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
        assertEquals(listOf(uri), SharedHomeworkIntent.files(single))
        val second = Uri.parse("content://documents/second.pdf")
        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri, second, uri))
        assertEquals(listOf(uri, second), SharedHomeworkIntent.files(multiple))
    }
    @Test fun `supports ClipData and rejects URLs file paths and unrelated actions`() {
        val clip = Intent(Intent.ACTION_SEND).apply { clipData = ClipData.newRawUri("report", uri) }
        assertEquals(listOf(uri), SharedHomeworkIntent.files(clip))
        listOf("file:///data/private", "https://evil.org/report").forEach {
            assertTrue(SharedHomeworkIntent.files(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse(it))).isEmpty())
        }
        assertTrue(SharedHomeworkIntent.files(Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_STREAM, uri)).isEmpty())
        assertTrue(SharedHomeworkIntent.files(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "a link")).isEmpty())
    }
}
