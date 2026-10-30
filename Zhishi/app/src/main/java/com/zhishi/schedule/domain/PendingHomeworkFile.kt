package com.zhishi.schedule.domain

import kotlinx.serialization.Serializable

@Serializable
data class PendingHomeworkFile(
    val id: String, val name: String, val mimeType: String, val size: Long,
    val receivedAt: Long, val awaitingResult: Boolean = false, val assignment: String? = null
)

object HomeworkAttachmentNames {
    private val invalid = Regex("[\\p{Cntrl}/\\\\:*?\"<>|]")
    fun defaultBase(title: String): String = (title.replace(invalid, "-").trim().trimEnd('.', '-').ifBlank { "作业" }).take(150) + "-提交"

    fun extension(original: String, mime: String): String {
        val lower = original.lowercase()
        listOf(".tar.gz", ".tar.bz2", ".tar.xz").firstOrNull { lower.endsWith(it) }?.let { return original.takeLast(it.length) }
        val dot = original.lastIndexOf('.')
        if (dot > 0 && Regex("[A-Za-z0-9]{1,12}").matches(original.substring(dot + 1))) return original.substring(dot)
        return when (mime.lowercase().substringBefore(';').trim()) {
            "application/pdf" -> ".pdf"
            "application/msword" -> ".doc"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx"
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ".pptx"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx"
            "application/zip" -> ".zip"
            "text/plain" -> ".txt"
            "text/csv" -> ".csv"
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            else -> ""
        }
    }

    fun resolve(base: String, original: String, mime: String): String {
        val trimmed = base.trim()
        require(trimmed.isNotBlank() && !invalid.containsMatchIn(trimmed) && trimmed !in setOf(".", "..") && !trimmed.endsWith('.')) {
            "请填写有效的提交名称，不要使用路径分隔符或特殊符号"
        }
        val suffix = extension(original, mime)
        val name = if (suffix.isNotEmpty() && !trimmed.endsWith(suffix, true)) trimmed + suffix else trimmed
        require(name.length <= 180) { "提交文件名过长，请缩短名称" }
        return name
    }
}
