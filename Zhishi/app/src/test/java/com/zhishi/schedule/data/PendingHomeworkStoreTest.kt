package com.zhishi.schedule.data

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PendingHomeworkStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    class Fixture(root: File) {
        val original = File(root, "original.pdf").apply { writeText("shared-report-content") }
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir(): File = File(root, "private").apply { mkdirs() }
        }
        val uri: Uri
        init {
            val authority = "homework.share." + java.util.UUID.randomUUID().toString()
            val provider = DocumentProvider(original)
            provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
            ShadowContentResolver.registerProviderInternal(authority, provider)
            uri = Uri.parse("content://$authority/file")
        }
    }

    @Test fun `share survives store recreation and repeated receipt cannot duplicate or revive consumed file`() = runBlocking {
        val f = Fixture(temporary.newFolder())
        val first = PendingHomeworkStore(f.context)
        val entry = first.enqueue(f.uri, "share/0")!!
        val restored = PendingHomeworkStore(f.context)
        assertEquals(listOf(entry), restored.list())
        assertNull(restored.enqueue(f.uri, "share/0"))
        val upload = restored.copyForSubmission(entry.id)
        assertEquals("shared-report-content", upload.file.readText())
        HomeworkUploadStore(f.context).delete(upload)
        assertEquals(1, restored.list().size)
        restored.remove(entry.id)
        assertNull(PendingHomeworkStore(f.context).enqueue(f.uri, "share/0"))
        assertTrue(restored.list().isEmpty())
        assertTrue(f.original.exists())
    }

    @Test fun `awaiting result survives restart and requires explicit restoration before automatic use`() = runBlocking {
        val f = Fixture(temporary.newFolder())
        val store = PendingHomeworkStore(f.context)
        val entry = store.enqueue(f.uri, "share/0")!!
        store.markAwaitingResult(entry.id, "概率论作业")
        val restored = PendingHomeworkStore(f.context)
        assertTrue(restored.list().single().awaitingResult)
        assertTrue(runCatching { restored.copyForSubmission(entry.id) }.isFailure)
        restored.restore(entry.id)
        assertFalse(restored.list().single().awaitingResult)
        val upload = restored.copyForSubmission(entry.id)
        HomeworkUploadStore(f.context).delete(upload)
    }

    @Test fun `parallel deliveries with same receipt commit only one saved file`() = runBlocking {
        val f = Fixture(temporary.newFolder())
        val a = PendingHomeworkStore(f.context); val b = PendingHomeworkStore(f.context)
        val results = listOf(async { a.enqueue(f.uri, "same/0") }, async { b.enqueue(f.uri, "same/0") }).awaitAll()
        assertEquals(1, results.count { it != null })
        assertEquals(1, a.list().size)
    }

    @Test fun `queue capacity does not evict earlier files`() = runBlocking {
        val f = Fixture(temporary.newFolder()); val store = PendingHomeworkStore(f.context)
        repeat(PendingHomeworkStore.MAX_FILES) { store.enqueue(f.uri, "event-$it/0") }
        val before = store.list()
        assertTrue(runCatching { store.enqueue(f.uri, "overflow/0") }.isFailure)
        assertEquals(before, store.list())
    }

    private class DocumentProvider(val file: File) : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf("原始文件.pdf", file.length())) }
        override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun getType(uri: Uri) = "application/pdf"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
