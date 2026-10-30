package com.zhishi.schedule.ui.screens

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhishi.schedule.data.CampusSession
import com.zhishi.schedule.data.HomeworkClient
import com.zhishi.schedule.data.HomeworkUploadStore
import com.zhishi.schedule.data.PendingHomeworkStore
import com.zhishi.schedule.data.HomeworkPriorityStore
import com.zhishi.schedule.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.Duration

enum class HomeworkPage { ASSIGNMENTS, FILES }

data class HomeworkState(
    val snapshot: HomeworkSnapshot? = null, val loading: Boolean = false,
    val progress: String? = null, val error: String? = null,
    val visible: Boolean = false, val loginVisible: Boolean = false,
    val page: HomeworkPage = HomeworkPage.ASSIGNMENTS,
    val filter: HomeworkFilter = HomeworkFilter.UNSUBMITTED, val search: String = "",
    val priorityFilter: HomeworkPriority = HomeworkPriority.URGENT,
    val marking: Boolean = false,
    val submission: HomeworkSubmissionDraft? = null, val notice: String? = null,
    val pendingFiles: List<PendingHomeworkFile> = emptyList(), val receivingSharedFiles: Boolean = false
)

/** Unknown, incomplete or stale reads must not look like a current count of outstanding assignments. */
fun HomeworkState.unsubmittedCount(now: Instant = Instant.now()): Int? {
    val report = snapshot ?: return null
    if (loading || error != null || !report.complete || Duration.between(report.fetchedAt, now).seconds !in 0..900) return null
    return report.items.count { it.status == HomeworkStatus.UNSUBMITTED && it.priorityMark != HomeworkPriority.NOT_REQUIRED }
}

class HomeworkViewModel @JvmOverloads constructor(app: Application,
    private val clientFactory: () -> HomeworkClient = { HomeworkClient.forAndroid(app) },
    private val pendingFiles: PendingHomeworkStore = PendingHomeworkStore(app)
) : AndroidViewModel(app) {
    private val mutable = MutableStateFlow(HomeworkState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var generation = 0L
    private var epoch = CampusSession.changes.value
    private var startupRefreshed = false
    private val uploads = HomeworkUploadStore(app)
    private val priorities = HomeworkPriorityStore(app)
    private var attachmentWork: Job? = null
    private var submissionWork: Job? = null
    private var submissionId = 0L
    private var uncertainSubmissionKey: String? = null
    private var pendingQuery = 0L
    private val sharedEvents = mutableSetOf<String>()
    private var incomingShares = 0

    fun refreshOnAppStart() {
        if (startupRefreshed) return
        startupRefreshed = true
        refresh(openList = false, finishLogin = false)
    }

    init {
        viewModelScope.launch { reloadPendingFiles() }
        viewModelScope.launch {
            CampusSession.changes.collect { current ->
                if (current != epoch) {
                    epoch = current; cancel()
                    submissionId++; attachmentWork?.cancel(); submissionWork?.cancel()
                    uncertainSubmissionKey = null
                    val draft = mutable.value.submission
                    if (draft?.busy != true) uploads.delete(draft?.attachment)
                    mutable.update { it.copy(snapshot = null, submission = null, marking = false, notice = null, error = "学校登录已更改，请重新读取作业") }
                }
            }
        }
    }

    fun open() { mutable.update { it.copy(visible = true, page = HomeworkPage.ASSIGNMENTS) } }
    fun openPending() { mutable.update { it.copy(visible = true, page = HomeworkPage.FILES) } }
    fun selectPage(page: HomeworkPage) { mutable.update { it.copy(page = page) } }
    fun close() { mutable.update { it.copy(visible = false) } }
    fun filter(value: HomeworkFilter) { mutable.update { it.copy(filter = value) } }
    fun priorityFilter(value: HomeworkPriority) { mutable.update { it.copy(priorityFilter = value) } }
    fun setPriority(item: CourseHomework, mark: HomeworkPriority) {
        val report = mutable.value.snapshot ?: return
        if (mutable.value.marking) return
        val current = report.items.firstOrNull { it.key == item.key && it.status == HomeworkStatus.UNSUBMITTED } ?: return
        val session = CampusSession.changes.value
        mutable.update { it.copy(marking = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { priorities.save(report.semesterId, current, mark) }
                ensureActive(); CampusSession.check(session)
                mutable.update { state ->
                    if (state.snapshot?.semesterId != report.semesterId) state.copy(marking = false) else {
                        val updated = priorities.decorate(state.snapshot)
                        val automatic = mark == HomeworkPriority.NORMAL && updated.items.firstOrNull { it.key == item.key }?.automaticallyUrgent() == true
                        state.copy(snapshot = updated, marking = false,
                            notice = if (automatic) "距离截止已在3天以内或已逾期，自动列为紧急；无需提交可另行标记。" else "已设为${mark.label}，仅保存在本机。")
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (session == CampusSession.changes.value) mutable.update { it.copy(marking = false, notice = "分类未能保存，请重试") }
            }
        }
    }
    fun search(value: String) { mutable.update { it.copy(search = value.take(100)) } }
    fun dismissNotice(message: String) { mutable.update { if (it.notice == message) it.copy(notice = null) else it } }
    fun login() {
        if (mutable.value.submission?.busy == true) return
        closeSubmission()
        cancel(); CampusSession.invalidate(); epoch = CampusSession.changes.value
        mutable.update { it.copy(snapshot = null, marking = false, error = null, loginVisible = true, visible = false) }
    }
    fun closeLogin() { mutable.update { it.copy(loginVisible = false) } }
    fun cancel() {
        generation++; work?.cancel(); work = null
        mutable.update { it.copy(loading = false, progress = null) }
    }

    fun refresh() = refresh(openList = true, finishLogin = true)

    fun refreshAfterLogin(showList: Boolean, finishLogin: Boolean = true) = refresh(openList = showList, finishLogin = finishLogin)

    private fun refresh(openList: Boolean, finishLogin: Boolean) {
        if (mutable.value.loading || mutable.value.submission?.busy == true) return
        val token = ++generation
        val session = CampusSession.changes.value
        mutable.update { it.copy(loading = true, error = null, progress = "正在刷新网络学堂作业…", loginVisible = if (finishLogin) false else it.loginVisible, visible = openList || it.visible) }
        work = viewModelScope.launch {
            try {
                val rawReport = clientFactory().read { label ->
                    if (token == generation && session == CampusSession.changes.value) mutable.update { it.copy(progress = label) }
                }
                ensureActive(); CampusSession.check(session)
                if (token == generation) {
                    val report = priorities.decorate(rawReport)
                    val rechecked = uncertainSubmissionKey != null && report.complete
                    if (rechecked) uncertainSubmissionKey = null
                    mutable.update { it.copy(snapshot = report, loading = false, progress = null,
                        priorityFilter = if (it.snapshot == null) HomeworkPriority.entries.firstOrNull { group -> report.items.any { row -> row.priority() == group } } ?: it.priorityFilter else it.priorityFilter,
                        notice = if (rechecked) "已重新读取学校记录，请核对对应作业后再决定是否重交" else it.notice) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (token == generation) mutable.update { it.copy(loading = false, progress = null,
                    error = "作业读取失败，请检查网络，并在“学校登录”中完成网络学堂认证后重试。原清单如有保留，仅供参考。") }
            }
        }
    }

    fun startSubmission(item: CourseHomework) {
        if (mutable.value.loading || mutable.value.submission != null) return
        val current = mutable.value.snapshot?.items?.firstOrNull { it.key == item.key } ?: return
        if (current.key == uncertainSubmissionKey) {
            mutable.update { it.copy(notice = "上次提交结果待核实，请先下拉刷新学校记录后再操作") }; return
        }
        if (current.onlineSubmission != true || current.status == HomeworkStatus.GRADED) {
            mutable.update { it.copy(notice = "这份作业请到学校页面核实提交方式或重交权限") }; return
        }
        submissionId++
        mutable.update { it.copy(submission = HomeworkSubmissionDraft(current), notice = null) }
        val ready = mutable.value.pendingFiles.firstOrNull { !it.awaitingResult }
        if (ready != null) choosePendingAttachment(ready.id) else {
            val token = submissionId
            viewModelScope.launch {
                val next = try { pendingFiles.list().firstOrNull { !it.awaitingResult } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
                val draft = mutable.value.submission
                if (token == submissionId && next != null && draft != null && draft.attachment == null &&
                    !draft.busy && !draft.pickingFile && !draft.confirming && !draft.resultUncertain) choosePendingAttachment(next.id)
            }
        }
    }

    fun closeSubmission() {
        val draft = mutable.value.submission ?: return
        if (draft.busy) return
        submissionId++; attachmentWork?.cancel(); attachmentWork = null
        uploads.delete(draft.attachment)
        mutable.update { it.copy(submission = null) }
    }

    fun updateSubmissionName(name: String) { editSubmission { it.copy(attachmentBaseName = name.take(180), confirming = false, error = null) } }

    fun acceptReplacement(accepted: Boolean) { editSubmission { it.copy(acceptReplacement = accepted, confirming = false) } }
    fun removeSubmissionAttachment() {
        val draft = mutable.value.submission ?: return
        if (draft.busy || draft.pickingFile || draft.resultUncertain) return
        uploads.delete(draft.attachment)
        editSubmission { it.copy(attachment = null, pendingFileId = null, confirming = false) }
    }

    fun chooseSubmissionAttachment(uri: Uri) {
        val draft = mutable.value.submission ?: return
        if (draft.busy || draft.pickingFile || draft.resultUncertain) return
        val token = submissionId
        val session = CampusSession.changes.value
        mutable.update { it.copy(submission = draft.copy(pickingFile = true, confirming = false, error = null)) }
        attachmentWork = viewModelScope.launch {
            var pending: HomeworkUpload? = null
            try {
                pending = uploads.copy(uri)
                ensureActive(); CampusSession.check(session)
                if (token == submissionId) {
                    val current = mutable.value.submission ?: return@launch
                    uploads.delete(current.attachment)
                    mutable.update { it.copy(submission = current.copy(attachment = pending, pickingFile = false, pendingFileId = null)) }
                    pending = null
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (token == submissionId) mutable.update { s -> s.copy(submission = s.submission?.copy(pickingFile = false,
                    error = "无法读取附件，请重新选择不超过50MB的非空文档")) }
            } finally { uploads.delete(pending) }
        }
    }

    fun reviewSubmission() {
        val draft = mutable.value.submission ?: return
        if (draft.busy || draft.pickingFile || draft.resultUncertain) return
        try {
            HomeworkSubmissionRules.validateAttachment(draft.homework, draft.uploadForSubmission(), draft.acceptReplacement)
            editSubmission { it.copy(confirming = true, error = null) }
        } catch (e: Exception) { editSubmission { it.copy(error = e.message ?: "提交内容无效") } }
    }

    fun cancelSubmissionConfirmation() { editSubmission { it.copy(confirming = false) } }

    fun confirmSubmission() {
        val draft = mutable.value.submission ?: return
        if (!draft.confirming || draft.busy || draft.pickingFile || draft.resultUncertain) return
        val upload = try { draft.uploadForSubmission() } catch (e: Exception) {
            editSubmission { it.copy(confirming = false, error = e.message) }; return
        }
        val token = submissionId
        val session = CampusSession.changes.value
        mutable.update { it.copy(submission = draft.copy(busy = true, confirming = false, error = null)) }
        submissionWork = viewModelScope.launch {
            try {
                draft.pendingFileId?.let { pendingFiles.markAwaitingResult(it, draft.homework.title); reloadPendingFiles() }
                clientFactory().submit(draft.homework, "", upload, draft.acceptReplacement)
                ensureActive(); CampusSession.check(session)
                if (token == submissionId) {
                    val cleaned = if (draft.pendingFileId == null) true else withContext(NonCancellable) {
                        runCatching { pendingFiles.remove(draft.pendingFileId); reloadPendingFiles() }.isSuccess
                    }
                    ensureActive()
                    if (token != submissionId) return@launch
                    mutable.update { it.copy(submission = null, notice = if (cleaned) "学校已确认收到本次提交，正在刷新作业状态…" else
                        "学校已确认收到本次提交，请手动移除对应的待提交附件") }
                    refresh(openList = true, finishLogin = false)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e !is HomeworkSubmissionUncertain && draft.pendingFileId != null) {
                    try { pendingFiles.restore(draft.pendingFileId); reloadPendingFiles() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Keep the inbox item blocked if local recovery failed. */ }
                }
                if (token == submissionId && e is HomeworkSubmissionUncertain) uncertainSubmissionKey = draft.homework.key
                if (token == submissionId) mutable.update { s -> s.copy(submission = s.submission?.copy(busy = false,
                    resultUncertain = e is HomeworkSubmissionUncertain,
                    error = if (e is HomeworkSubmissionUncertain) e.message else e.message?.take(200) ?: "提交未完成，请刷新学校记录后重试"),
                    notice = if (e is HomeworkSubmissionUncertain) "上次提交结果待核实，请先下拉刷新学校记录后再操作" else s.notice) }
            } finally {
                if (mutable.value.submission?.attachment?.file != draft.attachment?.file) uploads.delete(draft.attachment)
            }
        }
    }

    fun returnToVerifySubmission() { closeSubmission(); refresh() }

    fun receiveSharedFiles(event: String, uris: List<Uri>) {
        if (!sharedEvents.add(event)) return
        if (sharedEvents.size > 64) sharedEvents.remove(sharedEvents.first())
        if (uris.isEmpty()) { mutable.update { it.copy(visible = true, notice = "分享中没有可读取的文件，请从文件的分享菜单选择知时") }; return }
        incomingShares++
        mutable.update { it.copy(visible = true, page = if (it.submission == null) HomeworkPage.FILES else it.page,
            receivingSharedFiles = true, notice = "正在保存分享的附件…") }
        viewModelScope.launch {
            var saved = 0
            var alreadySaved = 0
            var failed = (uris.size - PendingHomeworkStore.MAX_FILES).coerceAtLeast(0)
            try {
                uris.take(PendingHomeworkStore.MAX_FILES).forEachIndexed { index, uri ->
                    try { if (pendingFiles.enqueue(uri, "$event/$index") != null) saved++ else alreadySaved++ }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { failed++ }
                }
                reloadPendingFiles()
                mutable.update { it.copy(notice = when {
                    failed > 0 -> "已保存 $saved 个附件，部分附件未能保存；请核对文件权限、50MB单文件和待提交列表容量"
                    saved == 0 && alreadySaved > 0 -> "分享的附件已保存在待提交列表，可选择作业使用"
                    else -> "已保存 $saved 个待提交附件，选择作业后自动带入；尚未上传学校"
                }) }
                val draft = mutable.value.submission
                if (draft != null && draft.attachment == null && !draft.busy && !draft.pickingFile && !draft.confirming && !draft.resultUncertain)
                    mutable.value.pendingFiles.firstOrNull { !it.awaitingResult }?.let { choosePendingAttachment(it.id) }
            } finally {
                incomingShares--
                mutable.update { it.copy(receivingSharedFiles = incomingShares > 0) }
            }
        }
    }

    fun choosePendingAttachment(id: String) {
        val draft = mutable.value.submission ?: return
        if (draft.busy || draft.pickingFile || draft.resultUncertain) return
        val token = submissionId
        mutable.update { it.copy(submission = draft.copy(pickingFile = true, confirming = false, error = null)) }
        attachmentWork = viewModelScope.launch {
            var upload: HomeworkUpload? = null
            try {
                upload = pendingFiles.copyForSubmission(id)
                ensureActive()
                if (token == submissionId) {
                    val current = mutable.value.submission ?: return@launch
                    uploads.delete(current.attachment)
                    mutable.update { it.copy(submission = current.copy(attachment = upload, pendingFileId = id, pickingFile = false)) }
                    upload = null
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (token == submissionId) mutable.update { s -> s.copy(submission = s.submission?.copy(pickingFile = false,
                    error = "无法使用该待提交附件，请核实提交结果或重新分享文件")) }
            } finally { uploads.delete(upload) }
        }
    }

    fun removePendingFile(id: String) = changePendingFile { pendingFiles.remove(id) }
    fun restorePendingFile(id: String) = changePendingFile { pendingFiles.restore(id) }

    private fun changePendingFile(change: suspend () -> Unit) {
        if (mutable.value.submission?.busy == true) return
        viewModelScope.launch {
            try { change(); reloadPendingFiles() }
            catch (_: Exception) { mutable.update { it.copy(notice = "待提交附件未能更新，请重试") } }
        }
    }

    private suspend fun reloadPendingFiles() {
        val token = ++pendingQuery
        try {
            val records = pendingFiles.list()
            if (token == pendingQuery) mutable.update { it.copy(pendingFiles = records) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (token == pendingQuery) mutable.update { it.copy(notice = "待提交附件读取失败，请稍后重试") } }
    }

    private fun editSubmission(change: (HomeworkSubmissionDraft) -> HomeworkSubmissionDraft) {
        mutable.update { s -> val draft = s.submission
            if (draft == null || draft.busy || draft.pickingFile || draft.resultUncertain) s else s.copy(submission = change(draft)) }
    }

    override fun onCleared() {
        attachmentWork?.cancel(); submissionWork?.cancel()
        uploads.delete(mutable.value.submission?.attachment)
        super.onCleared()
    }
}
