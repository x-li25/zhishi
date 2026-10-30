package com.zhishi.schedule.ui.screens

import androidx.lifecycle.viewModelScope
import com.zhishi.schedule.data.*
import com.zhishi.schedule.domain.HomeworkStatus
import com.zhishi.schedule.domain.HomeworkAttachmentNames
import com.zhishi.schedule.domain.HomeworkPriority
import com.zhishi.schedule.domain.priority
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomeworkViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<HomeworkViewModel>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }
    private fun model(factory: () -> HomeworkClient): HomeworkViewModel = HomeworkViewModel(RuntimeEnvironment.getApplication(), factory).also { models += it }
    private fun sharedModel(store: PendingHomeworkStore, factory: () -> HomeworkClient): HomeworkViewModel =
        HomeworkViewModel(RuntimeEnvironment.getApplication(), factory, store).also { models += it }
    private fun awaitCondition(check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!check() && System.nanoTime() < deadline) { dispatcher.scheduler.runCurrent(); Thread.sleep(5) }
        dispatcher.scheduler.runCurrent()
        assertTrue("Timed out waiting for local document IO", check())
    }
    private fun fixture() = HomeworkClientTest.Fixture { _, _ -> HomeworkClientTest.response(HomeworkClientTest.item("assignment")) }
    private fun attachFile(m: HomeworkViewModel) {
        val file = PendingHomeworkStoreTest.Fixture(temporary.newFolder())
        m.chooseSubmissionAttachment(file.uri)
        awaitCondition { m.state.value.submission?.attachment != null && m.state.value.submission?.pickingFile == false }
    }

    @Test fun `attachment only review cannot confirm or post without a file`() {
        val school = HomeworkSubmissionClientTest.Fixture()
        val m = model { HomeworkClient(school) }
        m.refresh(); dispatcher.scheduler.runCurrent()
        m.startSubmission(m.state.value.snapshot!!.items.single())
        m.reviewSubmission(); m.confirmSubmission(); dispatcher.scheduler.runCurrent()
        assertFalse(m.state.value.submission!!.confirming)
        assertNotNull(m.state.value.submission!!.error)
        assertEquals(0, school.posts)
    }

    @Test fun `dismissal of an old transient message cannot erase a newer message`() {
        val m = model { HomeworkClient(fixture()) }
        m.receiveSharedFiles("bad-share", emptyList())
        val current = m.state.value.notice!!
        m.dismissNotice("older message")
        assertEquals(current, m.state.value.notice)
        m.dismissNotice(current)
        assertNull(m.state.value.notice)
    }

    @Test fun `marking unnecessary survives refresh and viewmodel recreation without school submission`() {
        val school = HomeworkSubmissionClientTest.Fixture()
        val m = model { HomeworkClient(school) }
        m.refresh(); dispatcher.scheduler.runCurrent()
        val item = m.state.value.snapshot!!.items.single()
        m.setPriority(item, HomeworkPriority.NOT_REQUIRED)
        awaitCondition { !m.state.value.marking && m.state.value.snapshot!!.items.single().priorityMark == HomeworkPriority.NOT_REQUIRED }
        assertEquals(HomeworkStatus.UNSUBMITTED, m.state.value.snapshot!!.items.single().status)
        assertEquals(0, m.state.value.unsubmittedCount())
        m.refresh(); dispatcher.scheduler.runCurrent()
        assertEquals(HomeworkPriority.NOT_REQUIRED, m.state.value.snapshot!!.items.single().priority())
        val restored = model { HomeworkClient(school) }
        restored.refresh(); dispatcher.scheduler.runCurrent()
        assertEquals(HomeworkPriority.NOT_REQUIRED, restored.state.value.snapshot!!.items.single().priority())
        assertEquals(0, school.posts)
    }

    @Test fun `normal removes unnecessary marker and restores deadline based urgency`() {
        val school = HomeworkSubmissionClientTest.Fixture()
        val m = model { HomeworkClient(school) }
        m.refresh(); dispatcher.scheduler.runCurrent()
        val item = m.state.value.snapshot!!.items.single()
        m.setPriority(item, HomeworkPriority.NOT_REQUIRED)
        awaitCondition { !m.state.value.marking }
        m.setPriority(item, HomeworkPriority.NORMAL)
        awaitCondition { !m.state.value.marking }
        assertNull(m.state.value.snapshot!!.items.single().priorityMark)
        assertEquals(HomeworkPriority.URGENT, m.state.value.snapshot!!.items.single().priority())
        assertEquals(0, school.posts)
    }

    @Test fun `assignment and file entries open their own tab without starting a school request`() {
        var reads = 0
        val m = model { reads++; HomeworkClient(fixture()) }
        m.openPending(); dispatcher.scheduler.runCurrent()
        assertTrue(m.state.value.visible)
        assertEquals(HomeworkPage.FILES, m.state.value.page)
        m.selectPage(HomeworkPage.ASSIGNMENTS)
        assertEquals(HomeworkPage.ASSIGNMENTS, m.state.value.page)
        m.close(); m.openPending(); m.close(); m.open()
        assertEquals(HomeworkPage.ASSIGNMENTS, m.state.value.page)
        assertEquals(0, reads)
    }

    @Test fun `receiving a shared file opens files tab and keeps school untouched`() {
        val f = PendingHomeworkStoreTest.Fixture(temporary.newFolder())
        var reads = 0
        val m = sharedModel(PendingHomeworkStore(f.context)) { reads++; HomeworkClient(fixture()) }
        m.receiveSharedFiles("files-entry", listOf(f.uri))
        awaitCondition { !m.state.value.receivingSharedFiles && m.state.value.pendingFiles.isNotEmpty() }
        assertEquals(HomeworkPage.FILES, m.state.value.page)
        assertEquals(0, reads)
    }

    @Test fun `shared file is saved once auto attached and custom name is actually submitted`() {
        val f = PendingHomeworkStoreTest.Fixture(temporary.newFolder())
        val store = PendingHomeworkStore(f.context)
        val school = HomeworkSubmissionClientTest.Fixture()
        var sentContents: String? = null
        school.beforePost = { sentContents = school.lastPayload?.attachment?.file?.readText() }
        val m = sharedModel(store) { HomeworkClient(school) }
        m.receiveSharedFiles("event-1", listOf(f.uri)); m.receiveSharedFiles("event-1", listOf(f.uri))
        awaitCondition { !m.state.value.receivingSharedFiles && m.state.value.pendingFiles.size == 1 }
        assertEquals(0, school.posts)
        m.refresh(); dispatcher.scheduler.runCurrent()
        m.startSubmission(m.state.value.snapshot!!.items.single())
        awaitCondition { m.state.value.submission?.attachment != null && m.state.value.submission?.pickingFile == false }
        val draft = m.state.value.submission!!
        assertEquals(HomeworkAttachmentNames.resolve(HomeworkAttachmentNames.defaultBase(draft.homework.title), "原始文件.pdf", "application/pdf"), draft.uploadForSubmission()!!.name)
        m.updateSubmissionName("我的答案")
        m.reviewSubmission(); m.confirmSubmission()
        awaitCondition { m.state.value.submission == null && m.state.value.pendingFiles.isEmpty() && !m.state.value.loading }
        assertEquals(1, school.posts)
        assertEquals("我的答案.pdf", school.lastPayload!!.attachment!!.name)
        assertEquals("shared-report-content", sentContents)
        assertTrue(f.original.exists())
    }

    @Test fun `cancelling draft keeps shared original queued and uncertainty blocks future automatic attachment`() {
        val f = PendingHomeworkStoreTest.Fixture(temporary.newFolder())
        val store = PendingHomeworkStore(f.context)
        val school = HomeworkSubmissionClientTest.Fixture().apply { uncertain = true }
        val m = sharedModel(store) { HomeworkClient(school) }
        m.receiveSharedFiles("event-2", listOf(f.uri))
        awaitCondition { m.state.value.pendingFiles.size == 1 }
        m.refresh(); dispatcher.scheduler.runCurrent()
        val item = m.state.value.snapshot!!.items.single()
        m.startSubmission(item)
        awaitCondition { m.state.value.submission?.attachment != null && m.state.value.submission?.pickingFile == false }
        val cached = m.state.value.submission!!.attachment!!.file
        m.closeSubmission()
        assertFalse(cached.exists())
        assertEquals(1, m.state.value.pendingFiles.size)
        m.startSubmission(item)
        awaitCondition { m.state.value.submission?.attachment != null && m.state.value.submission?.pickingFile == false }
        m.reviewSubmission(); m.confirmSubmission()
        awaitCondition { m.state.value.submission?.resultUncertain == true }
        assertTrue(m.state.value.pendingFiles.single().awaitingResult)
        m.closeSubmission()
        val other = sharedModel(PendingHomeworkStore(f.context)) { HomeworkClient(school) }
        other.refresh(); dispatcher.scheduler.runCurrent()
        awaitCondition { other.state.value.pendingFiles.size == 1 }
        other.startSubmission(other.state.value.snapshot!!.items.single())
        dispatcher.scheduler.runCurrent()
        assertNull(other.state.value.submission!!.attachment)
        assertEquals(1, school.posts)
    }

    @Test fun `app entry refreshes once without opening login or homework list`() {
        var reads = 0
        val m = model { reads++; HomeworkClient(fixture()) }
        m.refreshOnAppStart(); m.refreshOnAppStart(); dispatcher.scheduler.runCurrent()
        assertEquals(1, reads)
        assertNotNull(m.state.value.snapshot)
        assertFalse(m.state.value.visible); assertFalse(m.state.value.loginVisible)
        m.refreshOnAppStart(); dispatcher.scheduler.runCurrent()
        assertEquals(1, reads)
    }

    @Test fun `editing and review do not submit until confirmed and repeated confirm sends once`() {
        val fixture = HomeworkSubmissionClientTest.Fixture()
        val release = CompletableDeferred<Unit>()
        fixture.beforePost = { release.await() }
        val m = model { HomeworkClient(fixture) }
        m.refresh(); dispatcher.scheduler.runCurrent()
        m.startSubmission(m.state.value.snapshot!!.items.single())
        attachFile(m)
        m.confirmSubmission(); dispatcher.scheduler.runCurrent()
        assertEquals(0, fixture.posts)
        m.reviewSubmission(); assertTrue(m.state.value.submission!!.confirming)
        assertEquals(0, fixture.posts)
        m.confirmSubmission(); m.confirmSubmission(); dispatcher.scheduler.runCurrent()
        assertEquals(1, fixture.posts)
        assertEquals("", fixture.lastPayload!!.contentHtml)
        assertNotNull(fixture.lastPayload!!.attachment)
        assertTrue(m.state.value.submission!!.busy)
        release.complete(Unit); dispatcher.scheduler.runCurrent()
        assertNull(m.state.value.submission)
        assertEquals(HomeworkStatus.SUBMITTED, m.state.value.snapshot!!.items.single().status)
        assertNotNull(m.state.value.notice)
    }

    @Test fun `uncertain submission requires fresh school records before retry`() {
        val fixture = HomeworkSubmissionClientTest.Fixture().apply {
            uncertain = true; beforePost = { status = HomeworkStatus.SUBMITTED }
        }
        val m = model { HomeworkClient(fixture) }
        m.refresh(); dispatcher.scheduler.runCurrent()
        val old = m.state.value.snapshot!!.items.single()
        m.startSubmission(old); attachFile(m); m.reviewSubmission(); m.confirmSubmission()
        dispatcher.scheduler.runCurrent()
        assertTrue(m.state.value.submission!!.resultUncertain)
        m.reviewSubmission(); m.confirmSubmission(); dispatcher.scheduler.runCurrent()
        assertEquals(1, fixture.posts)
        m.closeSubmission(); m.startSubmission(old)
        assertNull(m.state.value.submission)
        m.refresh(); dispatcher.scheduler.runCurrent()
        m.startSubmission(m.state.value.snapshot!!.items.single())
        assertEquals(HomeworkStatus.SUBMITTED, m.state.value.submission!!.homework.status)
        assertFalse(m.state.value.submission!!.acceptReplacement)
        assertEquals(1, fixture.posts)
    }

    @Test fun `switching school session clears submission draft and ignores late result`() {
        val fixture = HomeworkSubmissionClientTest.Fixture()
        val release = CompletableDeferred<Unit>()
        fixture.beforePost = { release.await() }
        val m = model { HomeworkClient(fixture) }
        m.refresh(); dispatcher.scheduler.runCurrent()
        m.startSubmission(m.state.value.snapshot!!.items.single()); attachFile(m)
        m.reviewSubmission(); m.confirmSubmission(); dispatcher.scheduler.runCurrent()
        CampusSession.invalidate(); dispatcher.scheduler.runCurrent()
        release.complete(Unit); dispatcher.scheduler.runCurrent()
        assertNull(m.state.value.submission); assertNull(m.state.value.snapshot)
        assertEquals(1, fixture.posts)
    }

    @Test fun `login completion refreshes homework without opening a second foreground list`() {
        var reads = 0
        val m = model { reads++; HomeworkClient(fixture()) }
        m.refreshAfterLogin(showList = false); dispatcher.scheduler.runCurrent()
        assertEquals(1, reads)
        assertNotNull(m.state.value.snapshot)
        assertFalse(m.state.value.visible)
        assertFalse(m.state.value.loading)
    }

    @Test fun `verified homework refreshes while the other school service is still authenticating`() {
        val m = model { HomeworkClient(fixture()) }
        m.login()
        m.refreshAfterLogin(showList = false, finishLogin = false)
        dispatcher.scheduler.runCurrent()
        assertNotNull(m.state.value.snapshot)
        assertTrue(m.state.value.loginVisible)
        assertFalse(m.state.value.visible)
        m.closeLogin(); m.open()
        assertFalse(m.state.value.loginVisible)
        assertTrue(m.state.value.visible)
    }

    @Test fun `repeated pull refresh while busy creates only one request and later refresh remains usable`() {
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val m = model {
            reads++
            HomeworkClient(CampusTransport { release.await(); error("offline fixture") })
        }
        m.refresh(); m.refresh(); dispatcher.scheduler.runCurrent()
        assertEquals(1, reads)
        release.complete(Unit); dispatcher.scheduler.runCurrent()
        assertFalse(m.state.value.loading)
        m.refresh(); dispatcher.scheduler.runCurrent()
        assertEquals(2, reads)
    }

    @Test fun `school login change removes old account homework from screen`() {
        val m = model { HomeworkClient(fixture()) }
        m.refresh(); dispatcher.scheduler.runCurrent()
        assertNotNull(m.state.value.snapshot)
        CampusSession.invalidate(); dispatcher.scheduler.runCurrent()
        assertNull(m.state.value.snapshot)
        assertFalse(m.state.value.loading)
    }

    @Test fun `stopped read cannot overwrite a later successful refresh`() {
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        val m = model {
            if (++attempts == 1) HomeworkClient(CampusTransport {
                withContext(NonCancellable) { release.await(); "expired old login" }
            }) else HomeworkClient(fixture())
        }
        m.refresh(); dispatcher.scheduler.runCurrent()
        assertTrue(m.state.value.loading)
        m.cancel(); m.refresh(); dispatcher.scheduler.runCurrent()
        val report = m.state.value.snapshot
        assertNotNull(report)
        release.complete(Unit); dispatcher.scheduler.runCurrent()
        assertEquals(report, m.state.value.snapshot)
        assertNull(m.state.value.error)
    }

    @Test fun `failed refresh retains explicitly old snapshot instead of clearing to no assignments`() {
        var attempts = 0
        val m = model { if (++attempts == 1) HomeworkClient(fixture()) else HomeworkClient(CampusTransport { error("expired") }) }
        m.refresh(); dispatcher.scheduler.runCurrent()
        val report = m.state.value.snapshot
        m.refresh(); dispatcher.scheduler.runCurrent()
        assertEquals(report, m.state.value.snapshot)
        assertNotNull(m.state.value.error)
        assertFalse(m.state.value.loading)
    }
}
