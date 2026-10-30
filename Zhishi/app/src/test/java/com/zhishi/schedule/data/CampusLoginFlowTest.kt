package com.zhishi.schedule.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CampusLoginFlowTest {
    private val homeworkHome = CampusUrls.LEARN_HOME
    private val infoHome = "${CampusUrls.INFO}/f/info/gxfw_fg/common/index"

    @Test fun `duplicate finish and subsequent info navigations cannot restart or cancel a stage check`() {
        val flow = CampusLoginFlow(false, CampusLoginTarget.TIMETABLE)
        assertEquals(CampusLoginTarget.TIMETABLE, flow.beginVerification(infoHome))
        assertTrue(flow.navigationAllowed("$infoHome?refresh=1"))
        assertNull(flow.beginVerification("$infoHome?refresh=1"))
        assertNull(flow.beginVerification(infoHome))
        assertFalse(flow.verified(CampusLoginTarget.TIMETABLE))
        assertEquals(CampusLoginTarget.HOMEWORK, flow.beginVerification(homeworkHome))
        assertNull(flow.beginVerification(homeworkHome))
        assertTrue(flow.verified(CampusLoginTarget.HOMEWORK))
    }

    @Test fun `an unrelated page never consumes the only protected check`() {
        val flow = CampusLoginFlow(false, CampusLoginTarget.TIMETABLE)
        assertNull(flow.beginVerification(CampusUrls.INFO_AUTH))
        assertNull(flow.beginVerification(homeworkHome))
        assertEquals(CampusLoginTarget.TIMETABLE, flow.beginVerification(infoHome))
    }

    @Test fun `redirect budget cannot invalidate an already running protected check`() {
        val flow = CampusLoginFlow(false, CampusLoginTarget.TIMETABLE)
        assertEquals(CampusLoginTarget.TIMETABLE, flow.beginVerification(infoHome))
        repeat(6) { flow.navigationAllowed("$infoHome?again=$it") }
        assertEquals(CampusLoginTarget.TIMETABLE, flow.target)
        assertFalse(flow.verified(CampusLoginTarget.TIMETABLE))
        assertEquals(CampusLoginTarget.HOMEWORK, flow.target)
    }

    @Test fun `redirect query churn is bounded and next verified stage has its own budget`() {
        val flow = CampusLoginFlow(false, CampusLoginTarget.TIMETABLE)
        repeat(5) { assertTrue(flow.navigationAllowed("$infoHome?token=$it#new")) }
        assertFalse(flow.navigationAllowed("$infoHome?token=next"))
        assertFalse(flow.verified(CampusLoginTarget.TIMETABLE))
        assertTrue(flow.navigationAllowed(CampusUrls.LEARN_AUTH))
        flow.cancel(); assertFalse(flow.navigationAllowed(CampusUrls.LEARN_AUTH))
    }

    @Test fun `only official authentication may be revealed and never info or homework content`() {
        listOf(CampusUrls.INFO_AUTH, CampusUrls.LEARN_AUTH, CampusUrls.LOGIN, "https://oauth.tsinghua.edu.cn/oauth2/authorize").forEach {
            assertTrue(it, CampusLoginFlow.canShowVerificationPage(it))
        }
        listOf(infoHome, homeworkHome, CampusUrls.VPN, "https://info.tsinghua.edu.cn/", "https://id.tsinghua.edu.cn.evil.example/",
            "http://id.tsinghua.edu.cn/", "https://id.tsinghua.edu.cn:444/", "https://user@id.tsinghua.edu.cn/",
            "https://webvpn.tsinghua.edu.cn/login-extra").forEach { assertFalse(it, CampusLoginFlow.canShowVerificationPage(it)) }
    }

    @Test fun `auth page VPN landing and unrelated hosts never count as successful login`() {
        val flow = CampusLoginFlow(false, CampusLoginTarget.HOMEWORK)
        listOf(CampusUrls.LOGIN, CampusUrls.INFO_AUTH, CampusUrls.LEARN_AUTH, CampusUrls.VPN,
            "https://learn.tsinghua.edu.cn/f/wlxt/index/course/student/",
            homeworkHome.replace("webvpn.tsinghua.edu.cn", "webvpn.tsinghua.edu.cn.evil.org"),
            homeworkHome.replace("https://", "http://")).forEach { assertNull(flow.candidate(it)) }
        assertFalse(flow.finished)
    }

    @Test fun `page candidate alone does not finish login and both verified services complete exactly once`() {
        val flow = CampusLoginFlow(false, CampusLoginTarget.HOMEWORK)
        assertEquals(CampusLoginTarget.HOMEWORK, flow.candidate(homeworkHome))
        assertFalse(flow.finished)
        assertFalse(flow.verified(CampusLoginTarget.TIMETABLE))
        assertEquals(CampusLoginTarget.HOMEWORK, flow.target)
        assertFalse(flow.verified(CampusLoginTarget.HOMEWORK))
        assertEquals(CampusLoginTarget.TIMETABLE, flow.target)
        assertEquals(CampusUrls.INFO_AUTH, flow.entry())
        assertNull(flow.candidate(homeworkHome))
        assertEquals(CampusLoginTarget.TIMETABLE, flow.candidate(infoHome))
        assertTrue(flow.verified(CampusLoginTarget.TIMETABLE))
        assertTrue(flow.finished)
        assertFalse(flow.verified(CampusLoginTarget.TIMETABLE))
        assertNull(flow.candidate(infoHome))
    }

    @Test fun `timetable first supports graduate entry then advances to homework auth`() {
        val flow = CampusLoginFlow(true, CampusLoginTarget.TIMETABLE)
        assertEquals(CampusLoginTarget.TIMETABLE, flow.candidate(CampusUrls.entry(true)))
        assertNull(flow.candidate(CampusUrls.entry(false)))
        assertFalse(flow.verified(CampusLoginTarget.TIMETABLE))
        assertEquals(CampusUrls.LEARN_AUTH, flow.entry())
        assertTrue(flow.verified(CampusLoginTarget.HOMEWORK))
    }

    @Test fun `closing login immediately invalidates candidates and late verification results`() {
        val flow = CampusLoginFlow(false, CampusLoginTarget.HOMEWORK)
        flow.cancel()
        assertNull(flow.candidate(homeworkHome))
        assertFalse(flow.verified(CampusLoginTarget.HOMEWORK))
        assertFalse(flow.verified(CampusLoginTarget.TIMETABLE))
        assertFalse(flow.finished)
    }

    @Test fun `homework verification requires protected metadata and does not fetch assignment lists`() = runBlocking {
        val fixture = HomeworkClientTest.Fixture()
        HomeworkClient(fixture).verifyLogin()
        assertEquals(2, fixture.gets.size)
        assertTrue(fixture.posts.isEmpty())
        fixture.home = "<html>sm2publicKey</html>"
        assertTrue(runCatching { HomeworkClient(fixture).verifyLogin() }.isFailure)
    }

    @Test fun `timetable verification checks actual JSONP and rejects HTML login pages`() = runBlocking {
        var result = "m([]);"
        val urls = mutableListOf<String>()
        val client = CampusClient(CampusTransport { url ->
            urls += url
            when {
                url == CampusUrls.COOKIE -> "XSRF-TOKEN=fixture;"
                url.startsWith(CampusUrls.ROAM) -> """{"object":{"roamingurl":"http://zhjw.cic.tsinghua.edu.cn/jxmh.do?ticket=fixture"}}"""
                url.contains("jxmh_out.do") -> result
                else -> "<html>教学日历</html>"
            }
        })
        client.verifyLogin(true, LocalDate.of(2026, 10, 29))
        assertTrue(urls.last().contains("yjs_jxrl_all"))
        result = "<html>登录</html>"
        assertTrue(runCatching { client.verifyLogin(true, LocalDate.of(2026, 10, 29)) }.isFailure)
    }
}
