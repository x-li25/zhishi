package com.zhishi.schedule.data

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.zhishi.schedule.domain.HomeworkSubmissionRules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomeworkUploadStoreTest {
    @Test fun `selected document is privately staged with safe name and cleanup preserves original`() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val original = File.createTempFile("homework-source", ".pdf", app.cacheDir).apply { writeText("original-report") }
        val provider = DocumentProvider(original, "报告/\r\n.pdf", original.length())
        provider.attachInfo(app, ProviderInfo().apply { authority = "homework.test" })
        ShadowContentResolver.registerProviderInternal("homework.test", provider)
        val store = HomeworkUploadStore(app)
        try {
            val staged = store.copy(Uri.parse("content://homework.test/report"))
            assertEquals("original-report", staged.file.readText())
            assertFalse(staged.name.contains('/')); assertFalse(staged.name.contains('\n')); assertFalse(staged.name.contains('\r'))
            assertNotEquals(original.canonicalPath, staged.file.canonicalPath)
            store.delete(staged)
            assertFalse(staged.file.exists()); assertTrue(original.exists())
        } finally { original.delete() }
    }

    @Test fun `overlarge and empty documents are rejected without retaining staged file`() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val original = File.createTempFile("homework-empty", ".pdf", app.cacheDir)
        val provider = DocumentProvider(original, "report.pdf", HomeworkSubmissionRules.MAX_FILE_BYTES + 1)
        provider.attachInfo(app, ProviderInfo().apply { authority = "homework.test" })
        ShadowContentResolver.registerProviderInternal("homework.test", provider)
        val store = HomeworkUploadStore(app)
        try {
            assertTrue(runCatching { store.copy(Uri.parse("content://homework.test/report")) }.isFailure)
            provider.declaredSize = 0
            assertTrue(runCatching { store.copy(Uri.parse("content://homework.test/report")) }.isFailure)
            assertTrue(File(app.cacheDir, "homework_uploads").listFiles().orEmpty().isEmpty())
        } finally { original.delete() }
    }

    private class DocumentProvider(val file: File, val name: String, var declaredSize: Long) : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf(name, declaredSize)) }
        override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun getType(uri: Uri) = "application/pdf"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
