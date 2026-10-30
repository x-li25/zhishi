package com.zhishi.schedule.data

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.zhishi.schedule.domain.HomeworkSubmissionPayload
import com.zhishi.schedule.domain.HomeworkSubmissionRules
import com.zhishi.schedule.domain.HomeworkSubmissionRejected
import com.zhishi.schedule.domain.HomeworkSubmissionUncertain
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSink

fun interface CampusTransport {
    suspend fun get(url: String): String
    suspend fun postForm(url: String, fields: Map<String, String>): String = error("不支持这个学校查询")
    suspend fun submitHomework(url: String, payload: HomeworkSubmissionPayload): String = error("不支持提交作业")
}

/** Dedicated campus client. Never uses the configurable AI base URL or its HTTP headers. */
class CampusHttpTransport(private val cookies: CookieManager, private val userAgent: String,
    client: OkHttpClient = OkHttpClient()) : CampusTransport {
    private data class Reply(val code: Int, val location: String?, val cookies: List<String>, val text: String)
    private val epoch = CampusSession.changes.value
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).build()
    private val submissionClient = this.client.newBuilder().retryOnConnectionFailure(false)
        .writeTimeout(90, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()

    override suspend fun get(url: String): String = request(url, null)

    override suspend fun postForm(url: String, fields: Map<String, String>): String {
        // POST is used only by these read-only list endpoints. Never permit submission or school mutations.
        require(url.toHttpUrl().let { u ->
            u.encodedPath == "${CampusUrls.LEARN.toHttpUrl().encodedPath}/b/wlxt/kczy/zy/student/${u.pathSegments.last()}" &&
                u.pathSegments.last() in setOf("zyListWj", "zyListYjwg", "zyListYpg") && fields.keys == setOf("aoData")
        }) { "仅支持读取作业列表" }
        return request(url, MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            fields.forEach { (key, value) -> addFormDataPart(key, value) }
        }.build())
    }

    override suspend fun submitHomework(url: String, payload: HomeworkSubmissionPayload): String {
        val endpoint = url.toHttpUrl()
        require(CampusUrls.allowedApi(endpoint) && endpoint.encodedPath == "${CampusUrls.LEARN.toHttpUrl().encodedPath}/b/wlxt/kczy/zy/student/tjzy" &&
            endpoint.queryParameterNames == setOf("_csrf") && !endpoint.queryParameter("_csrf").isNullOrBlank()) { "作业提交地址无效" }
        require(payload.studentHomeworkId.isNotBlank() && payload.studentHomeworkId.length <= 128 && payload.contentHtml.length <= 310_000) { "提交内容无效" }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("xszyid", payload.studentHomeworkId).addFormDataPart("zynr", payload.contentHtml)
            .addFormDataPart("isDeleted", "0")
        payload.attachment?.let { upload ->
            require(upload.size in 1..HomeworkSubmissionRules.MAX_FILE_BYTES && upload.file.isFile && upload.file.length() == upload.size &&
                upload.name.isNotBlank() && upload.name.length <= 180 && upload.name.none { it in "\r\n\u0000/\\" }) { "附件无效，请重新选择" }
            multipart.addFormDataPart("fileupload", upload.name,
                upload.file.asRequestBody(upload.mimeType.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()))
        } ?: multipart.addFormDataPart("fileupload", "undefined")
        val body = multipart.build()
        val oneShot = object : RequestBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
        }
        return request(url, oneShot, submission = true)
    }

    private suspend fun request(url: String, body: RequestBody?, submission: Boolean = false): String = withContext(Dispatchers.IO) {
        var target = url.toHttpUrl()
        repeat(10) {
            CampusSession.check(epoch)
            require(CampusUrls.allowedApi(target)) { "教学系统跳转需要重新登录" }
            val builder = Request.Builder().url(target).header("Accept", "application/json,text/html,*/*")
                .header("User-Agent", userAgent)
                .header("Cookie", cookies.getCookie(target.toString()).orEmpty())
            if (body != null) builder.post(body)
            val request = builder.build()
            val call = (if (submission) submissionClient else client).newCall(request)
            val response = suspendCancellableCoroutine<Reply> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(if (submission) HomeworkSubmissionUncertain() else IOException("无法连接教学系统，请检查网络后重试"))
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use { r ->
                                if (!continuation.isActive) return
                                val text = if (r.isSuccessful) readBody(r.body ?: error("教学系统未返回内容")) else ""
                                continuation.resume(Reply(r.code, r.header("Location"), r.headers("Set-Cookie"), text))
                            }
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(
                                if (submission) HomeworkSubmissionUncertain() else if (e is IOException) IOException("无法连接教学系统，请检查网络后重试") else e)
                        }
                    }
                })
            }
            try { CampusSession.check(epoch) } catch (e: Exception) {
                if (submission) throw HomeworkSubmissionUncertain() else throw e
            }
            response.cookies.forEach { cookies.setCookie(target.toString(), it) }
            if (response.code in 300..399) {
                if (submission) throw HomeworkSubmissionUncertain()
                check(body == null) { "作业查询需要重新登录，请先打开网络学堂" }
                val redirect = response.location?.let(target::resolve) ?: error("教学系统跳转地址无效")
                val next = runCatching { CampusUrls.proxied(redirect.toString()).toHttpUrl() }.getOrNull()
                require(next != null && !next.encodedPath.startsWith("/login")) {
                    "登录已过期，请在官方页面重新登录后读取"
                }
                target = next
            } else {
                if (submission && response.code in listOf(400, 401, 403, 404, 413, 422))
                    throw HomeworkSubmissionRejected("学校未接受本次提交，请核对登录、附件大小和提交要求")
                if (submission && response.code !in 200..299) throw HomeworkSubmissionUncertain()
                check(response.code in 200..299) { "教学系统暂时不可用，请稍后重试" }
                return@withContext response.text
            }
        }
        error("教学系统跳转次数过多，请重新登录")
    }

    private fun readBody(body: ResponseBody): String {
        require(body.contentLength() <= 2_000_000) { "教学系统数据过多，请缩小范围" }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        val input = body.byteStream()
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 2_000_000) { "教学系统数据过多，请缩小范围" }
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        val charset = body.contentType()?.charset()
        val utf8 = bytes.toString(charset ?: Charsets.UTF_8)
        return if (charset == null && '\uFFFD' in utf8) bytes.toString(charset("GB18030")) else utf8
    }
}

class CampusClient(private val transport: CampusTransport) {
    suspend fun verifyLogin(graduate: Boolean, date: LocalDate = LocalDate.now(CampusScheduleParser.zone)) {
        authorize(graduate)
        val raw = transport.get(CampusUrls.schedule(graduate, date, date))
        val body = Regex("(?s)^\\s*m\\s*\\((.*)\\)\\s*;?\\s*$").matchEntire(raw)?.groupValues?.get(1)
            ?: error("教学日历尚未登录")
        require(Json.parseToJsonElement(body) is JsonArray) { "教学日历未返回有效数据" }
    }

    suspend fun read(graduate: Boolean, start: LocalDate, end: LocalDate,
        progress: (Int, Int) -> Unit = { _, _ -> }): List<CampusEvent> {
        val ranges = CampusScheduleParser.chunks(start, end)
        authorize(graduate)
        val rows = mutableListOf<CampusEvent>()
        ranges.forEachIndexed { index, (from, to) ->
            progress(index + 1, ranges.size)
            val raw = transport.get(CampusUrls.schedule(graduate, from, to))
            rows += try { CampusScheduleParser.parse(raw, from, to) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { throw IllegalStateException("$from 至 $to 读取失败：${if (e is IllegalArgumentException || e is java.time.DateTimeException) "学校返回的日程时间或格式无效" else e.message ?: "请重新登录"}") }
            require(rows.size <= 5000) { "学校返回日程过多，请缩小范围" }
        }
        return CampusScheduleParser.merge(rows)
    }

    private suspend fun authorize(graduate: Boolean) {
        // The portal must issue a teaching-system ticket even when SSO is already logged in.
        val cookie = transport.get(CampusUrls.COOKIE)
        val csrf = Regex("XSRF-TOKEN=([^;\\s\"\\\\]+)").find(cookie)?.groupValues?.get(1)
            ?: error("信息门户尚未登录，请先完成官方登录并打开信息门户")
        val roamUrl = CampusUrls.ROAM.toHttpUrl().newBuilder().addQueryParameter("yyfwid", CampusUrls.appId(graduate))
            .addQueryParameter("_csrf", csrf).addQueryParameter("machine", "p").build().toString()
        val roam = try {
            val o = Json.parseToJsonElement(transport.get(roamUrl)).jsonObject["object"]?.jsonObject
            o?.get("roamingurl")?.jsonPrimitive?.content
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null }
        require(!roam.isNullOrBlank()) { "教学日历尚未授权，请在官方页面打开教学日历后重试" }
        transport.get(CampusUrls.proxied(roam))
    }
}
