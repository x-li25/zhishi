package com.zhishi.schedule.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.zhishi.schedule.domain.HomeworkSubmissionRules
import com.zhishi.schedule.domain.HomeworkUpload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Copy the explicitly selected document to private cache; no broad storage permission or URI in AI data. */
class HomeworkUploadStore(private val context: Context) {
    private val directory = File(context.cacheDir, "homework_uploads")

    suspend fun copy(uri: Uri): HomeworkUpload = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请选择手机文件选择器中的文档" }
        directory.mkdirs()
        val destination = File(directory, UUID.randomUUID().toString())
        try {
            var name = "作业附件"
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) require(cursor.getLong(sizeIndex) <= HomeworkSubmissionRules.MAX_FILE_BYTES) { "单个附件最多50MB" }
                }
            }
            name = name.replace(Regex("[\\r\\n\\u0000/\\\\]"), "_").trim().take(180).ifBlank { "作业附件" }
            var copied = 0L
            val input = context.contentResolver.openInputStream(uri) ?: error("无法打开附件")
            input.use { source -> destination.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    copied += count
                    require(copied <= HomeworkSubmissionRules.MAX_FILE_BYTES) { "单个附件最多50MB" }
                    output.write(buffer, 0, count)
                }
            } }
            require(copied > 0) { "附件为空，请重新选择" }
            HomeworkUpload(destination, name, context.contentResolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }, copied)
        } catch (e: Exception) { destination.delete(); throw e }
    }

    fun delete(upload: HomeworkUpload?) {
        upload?.file?.takeIf { it.parentFile?.canonicalFile == directory.canonicalFile }?.delete()
    }

    suspend fun copySaved(source: File, name: String, mime: String): HomeworkUpload = withContext(Dispatchers.IO) {
        require(source.isFile && source.length() in 1..HomeworkSubmissionRules.MAX_FILE_BYTES) { "保存的附件不可用" }
        directory.mkdirs()
        val destination = File(directory, UUID.randomUUID().toString())
        try {
            source.inputStream().use { input -> destination.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            } }
            HomeworkUpload(destination, name, mime, destination.length())
        } catch (e: Exception) { destination.delete(); throw e }
    }
}
