package com.zhishi.schedule.domain

import org.junit.Assert.*
import org.junit.Test

class HomeworkAttachmentNamesTest {
    @Test fun `default upload name uses assignment title and keeps real extension`() {
        assertEquals("概率论第一次作业-提交.pdf", HomeworkAttachmentNames.resolve(
            HomeworkAttachmentNames.defaultBase("概率论第一次作业"), "微信下载的报告.pdf", "application/pdf"))
        assertEquals("实验-提交.tar.gz", HomeworkAttachmentNames.resolve("实验-提交", "source.tar.gz", "application/gzip"))
    }
    @Test fun `custom names accept optional matching suffix without duplication`() {
        assertEquals("我的答案.pdf", HomeworkAttachmentNames.resolve("我的答案", "source.pdf", "application/pdf"))
        assertEquals("我的答案.pdf", HomeworkAttachmentNames.resolve("我的答案.pdf", "source.pdf", "application/pdf"))
        assertEquals("报告.docx", HomeworkAttachmentNames.resolve("报告", "未带后缀的文件", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
    }
    @Test fun `invalid custom names are rejected and long default title stays valid`() {
        listOf("", "  ", "../answer", "x\r\ny", "a\\b", "a:b", "name.").forEach {
            assertTrue(runCatching { HomeworkAttachmentNames.resolve(it, "file.pdf", "application/pdf") }.isFailure)
        }
        val base = HomeworkAttachmentNames.defaultBase("题目/一:" + "很长的题目".repeat(100))
        assertTrue(HomeworkAttachmentNames.resolve(base, "report.pdf", "application/pdf").length <= 180)
    }
}
