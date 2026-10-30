package com.zhishi.schedule.domain

import java.io.File

data class HomeworkUpload(val file: File, val name: String, val mimeType: String, val size: Long)
data class HomeworkSubmissionPayload(val studentHomeworkId: String, val contentHtml: String, val attachment: HomeworkUpload?)

class HomeworkSubmissionRejected(message: String) : IllegalStateException(message)
class HomeworkSubmissionUncertain : IllegalStateException("提交结果暂时无法确认，请先刷新学校记录并核对，避免重复提交")

data class HomeworkSubmissionDraft(
    val homework: CourseHomework,
    val attachment: HomeworkUpload? = null,
    val pickingFile: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val resultUncertain: Boolean = false,
    val confirming: Boolean = false,
    val acceptReplacement: Boolean = false,
    val attachmentBaseName: String = HomeworkAttachmentNames.defaultBase(homework.title),
    val pendingFileId: String? = null
) {
    fun uploadForSubmission(): HomeworkUpload? = attachment?.let {
        it.copy(name = HomeworkAttachmentNames.resolve(attachmentBaseName, it.name, it.mimeType))
    }
}

object HomeworkSubmissionRules {
    const val MAX_FILE_BYTES = 50L * 1024 * 1024
    const val MAX_TEXT_CHARS = 50_000

    fun validateAttachment(item: CourseHomework, upload: HomeworkUpload?, acceptReplacement: Boolean) {
        require(upload != null) { "请选择需要提交的附件" }
        validate(item, "", upload, acceptReplacement)
    }

    fun validate(item: CourseHomework, text: String, upload: HomeworkUpload?, acceptReplacement: Boolean) {
        require(item.onlineSubmission == true) { "这份作业的网络提交方式尚未确认，请到学校页面核实" }
        require(item.status != HomeworkStatus.GRADED) { "作业已批改，请到学校页面核实是否允许重交" }
        require(text.isNotBlank() || upload != null) { "请填写正文或选择一个附件" }
        require(text.length <= MAX_TEXT_CHARS) { "正文最多50000字" }
        require(item.id.isNotBlank() && item.courseId.isNotBlank()) { "作业信息无效，请刷新后重试" }
        require(item.status == HomeworkStatus.UNSUBMITTED || acceptReplacement) { "请确认替换已提交的正文和新选择的附件" }
        upload?.let {
            require(it.size in 1..MAX_FILE_BYTES && it.file.isFile && it.file.length() == it.size) { "附件不可用，请重新选择，单文件最多50MB" }
            require(it.name.isNotBlank() && it.name.length <= 180 && it.name.none { c -> c in "\r\n\u0000/\\" }) { "附件名称无效，请重新选择" }
        }
    }

    fun html(text: String): String = if (text.isEmpty()) "" else "<p>" + text.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
        .replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>") + "</p>"
}
