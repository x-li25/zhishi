package com.zhishi.schedule.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.zhishi.schedule.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Durable local inbox, excluded from cloud backup. Sharing never triggers a school submission. */
class PendingHomeworkStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "pending_homework")
    private val index = AtomicFile(File(directory, "index.json"))
    private val uploads = HomeworkUploadStore(context)
    private val json = Json { ignoreUnknownKeys = true }
    @Serializable private data class State(val files: List<PendingHomeworkFile> = emptyList(), val receipts: List<String> = emptyList())

    suspend fun list(): List<PendingHomeworkFile> = withContext(Dispatchers.IO) { lock.withLock { read().files } }

    suspend fun enqueue(uri: Uri, receipt: String): PendingHomeworkFile? = withContext(Dispatchers.IO) {
        lock.withLock {
            require(receipt.length in 1..128)
            val state = read()
            if (receipt in state.receipts) return@withLock null
            require(state.files.size < MAX_FILES) { "待提交附件已满，请先提交或移除部分文件" }
            var staged: HomeworkUpload? = null
            var destination: File? = null
            try {
                staged = uploads.copy(uri)
                require(state.files.sumOf { it.size } + staged.size <= MAX_TOTAL_BYTES) { "待提交附件总量不能超过200MB" }
                directory.mkdirs()
                val id = UUID.randomUUID().toString()
                destination = content(id)
                staged.file.inputStream().use { source -> destination.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = source.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                    output.fd.sync()
                } }
                currentCoroutineContext().ensureActive()
                val entry = PendingHomeworkFile(id, staged.name, staged.mimeType, staged.size, System.currentTimeMillis())
                write(State(state.files + entry, (state.receipts + receipt).takeLast(64)))
                destination = null
                entry
            } finally { destination?.delete(); uploads.delete(staged) }
        }
    }

    suspend fun copyForSubmission(id: String): HomeworkUpload = withContext(Dispatchers.IO) {
        lock.withLock {
            val entry = read().files.firstOrNull { it.id == id } ?: error("待提交附件已被移除")
            require(!entry.awaitingResult) { "请先核实上次提交结果，再恢复使用这个附件" }
            val source = content(id)
            require(source.isFile && source.length() == entry.size) { "保存的附件不可用，请重新分享" }
            uploads.copySaved(source, entry.name, entry.mimeType)
        }
    }

    suspend fun markAwaitingResult(id: String, assignment: String) = change(id) { it.copy(awaitingResult = true, assignment = assignment.take(300)) }
    suspend fun restore(id: String) = change(id) { it.copy(awaitingResult = false, assignment = null) }
    private suspend fun change(id: String, transform: (PendingHomeworkFile) -> PendingHomeworkFile) = withContext(Dispatchers.IO) {
        lock.withLock {
            val state = read()
            require(state.files.any { it.id == id }) { "待提交附件已被移除" }
            write(state.copy(files = state.files.map { if (it.id == id) transform(it) else it }))
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val state = read()
            write(state.copy(files = state.files.filterNot { it.id == id }))
            content(id).delete()
            Unit
        }
    }

    private fun read(): State {
        val state = if (!index.baseFile.exists() && !File(index.baseFile.path + ".bak").exists()) State()
        else index.openRead().bufferedReader(Charsets.UTF_8).use { json.decodeFromString<State>(it.readText()) }
        require(state.files.size <= MAX_FILES && state.files.map { it.id }.distinct().size == state.files.size &&
            state.files.all { idPattern.matches(it.id) && it.size in 1..HomeworkSubmissionRules.MAX_FILE_BYTES }) { "待提交附件记录无效" }
        // No other writer can be active while this lock is held. Remove only our uncommitted binary files.
        val keep = state.files.map { it.id + ".bin" }.toSet()
        directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".bin") &&
            idPattern.matches(it.name.removeSuffix(".bin")) && it.name !in keep }.forEach { it.delete() }
        return state
    }

    private fun write(state: State) {
        directory.mkdirs()
        val stream = index.startWrite()
        try { stream.write(json.encodeToString(state).toByteArray(Charsets.UTF_8)); index.finishWrite(stream) }
        catch (e: Exception) { index.failWrite(stream); throw e }
    }

    private fun content(id: String): File {
        require(idPattern.matches(id)) { "待提交附件编号无效" }
        return File(directory, "$id.bin")
    }

    companion object {
        const val MAX_FILES = 10
        const val MAX_TOTAL_BYTES = 200L * 1024 * 1024
        private val lock = Mutex()
        private val idPattern = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    }
}
