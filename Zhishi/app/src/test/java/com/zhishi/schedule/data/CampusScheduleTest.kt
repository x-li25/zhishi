package com.zhishi.schedule.data

import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.Instant
import java.time.temporal.ChronoUnit

class CampusScheduleTest {
    private val day = LocalDate.of(2026, 10, 26)
    private val row = """{"nr":"概率论","dd":"六教 A101","fl":"课程","nq":"2026-10-26","kssj":"09：50","jssj":"12:15"}"""

    @Test fun `primary calendar retains actual times locations and exam categories in school timezone`() {
        val rows = CampusScheduleParser.parse("m([$row,${row.replace("课程", "考试").replace("概率论", "统计学")}]);", day, day)
        assertEquals(2, rows.size)
        assertEquals("六教 A101", rows[0].location)
        assertEquals(Instant.parse("2026-10-26T01:50:00Z").toEpochMilli(), rows[0].startMillis)
        assertEquals(Instant.parse("2026-10-26T04:15:00Z").toEpochMilli(), rows[0].endMillis)
        assertFalse(rows[0].exam); assertTrue(rows[1].exam)
    }

    @Test fun `personal calendar excluded and empty response valid only in expected callback`() {
        assertTrue(CampusScheduleParser.parse("m([${row.replace("课程", "个人日历")}])", day, day).isEmpty())
        assertTrue(CampusScheduleParser.parse(" m ( [] ); ", day, day).isEmpty())
        listOf("<html>登录</html>", "[]", "evil([])", "m([]);alert(1)", "m({})").forEach {
            assertTrue(it, runCatching { CampusScheduleParser.parse(it, day, day) }.isFailure)
        }
    }

    @Test fun `malformed or out of range entry aborts entire preview rather than silently dropping it`() {
        listOf(row.replace("12:15", "未定"), row.replace("12:15", "08:00"), row.replace("2026-10-26", "2026-10-27"),
            row.replace("概率论", ""), row.replace("09：50", "25:00")).forEach {
            assertTrue(runCatching { CampusScheduleParser.parse("m([$row,$it])", day, day) }.isFailure)
        }
    }

    @Test fun `three week batches cover semester without gaps overlaps or unbounded dates`() {
        val first = LocalDate.of(2026, 9, 14); val last = LocalDate.of(2027, 1, 17)
        val chunks = CampusScheduleParser.chunks(first, last)
        assertEquals(6, chunks.size)
        assertEquals(first, chunks.first().first); assertEquals(last, chunks.last().second)
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.second.plusDays(1), b.first) }
        assertTrue(chunks.all { ChronoUnit.DAYS.between(it.first, it.second) <= 20 })
        assertTrue(runCatching { CampusScheduleParser.chunks(first, first.plusDays(183)) }.isFailure)
        assertTrue(runCatching { CampusScheduleParser.chunks(last, first) }.isFailure)
    }

    @Test fun `only matching same day periods merge and keys remain stable across repeated reads`() {
        val a = CampusScheduleParser.parse("m([$row])", day, day).single()
        val b = a.copy(startMillis = a.endMillis + 600_000, endMillis = a.endMillis + 3_000_000)
        val other = b.copy(location = "不同地点")
        val nextDay = a.copy(startMillis = a.startMillis + 86_400_000, endMillis = a.endMillis + 86_400_000)
        val merged = CampusScheduleParser.merge(listOf(a, a, b, other, nextDay))
        assertEquals(3, merged.size)
        assertEquals(b.endMillis, merged.first().endMillis)
        assertEquals(a.key, a.copy().key)
        assertNotEquals(a.key, a.copy(location = "新地点").key)
    }

    @Test fun `URL checks reject host suffix tricks credentials cleartext ports and external roaming`() {
        assertTrue(CampusUrls.allowedBrowser(CampusUrls.LOGIN))
        listOf("https://id.tsinghua.edu.cn.evil.org/", "https://id.tsinghua.edu.cn@evil.org/", "http://id.tsinghua.edu.cn/",
            "https://evil@id.tsinghua.edu.cn/", "https://id.tsinghua.edu.cn:444/", "file:///data/a", "javascript:alert(1)").forEach {
            assertFalse(it, CampusUrls.allowedBrowser(it))
        }
        assertFalse(CampusUrls.allowedApi("https://id.tsinghua.edu.cn/".toHttpUrl()))
        assertTrue(CampusUrls.proxied("http://zhjw.cic.tsinghua.edu.cn/a?x=1&amp;y=2").startsWith(CampusUrls.TEACH))
        listOf("https://evil.org/a", "http://info.tsinghua.edu.cn/", "http://zhjw.cic.tsinghua.edu.cn:8080/").forEach {
            assertTrue(runCatching { CampusUrls.proxied(it) }.isFailure)
        }
    }

    @Test fun `client roams before reading and switches undergraduate and graduate endpoint`() = runBlocking {
        listOf(false, true).forEach { graduate ->
            val urls = mutableListOf<String>()
            val client = CampusClient(CampusTransport { url ->
                urls += url
                when {
                    url == CampusUrls.COOKIE -> "XSRF-TOKEN=test-csrf;"
                    url.startsWith(CampusUrls.ROAM) -> """{"object":{"roamingurl":"http://zhjw.cic.tsinghua.edu.cn/jxmh.do?ticket=fixture"}}"""
                    url.contains("jxmh_out.do") -> "m([$row])"
                    else -> "<html>教学日历</html>"
                }
            })
            assertEquals(1, client.read(graduate, day, day).size)
            assertEquals(CampusUrls.COOKIE, urls.first())
            assertEquals(CampusUrls.appId(graduate), urls[1].toHttpUrl().queryParameter("yyfwid"))
            assertEquals(if (graduate) "yjs_jxrl_all" else "bks_jxrl_all", urls.last().toHttpUrl().queryParameter("m"))
            assertTrue(urls.all { CampusUrls.allowedApi(it.toHttpUrl()) })
        }
    }

    @Test fun `expired login and foreign roam stop before any schedule request`() = runBlocking {
        var calls = 0
        val expired = CampusClient(CampusTransport { calls++; "<html>登录</html>" })
        assertTrue(runCatching { expired.read(false, day, day) }.isFailure)
        assertEquals(1, calls)
        calls = 0
        val external = CampusClient(CampusTransport { url ->
            calls++
            if (url == CampusUrls.COOKIE) "XSRF-TOKEN=fixture;" else """{"object":{"roamingurl":"https://evil.org/"}}"""
        })
        assertTrue(runCatching { external.read(false, day, day) }.isFailure)
        assertEquals(2, calls)
    }

    @Test fun `cancellation propagates through campus reads`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val client = CampusClient(CampusTransport { started.complete(Unit); awaitCancellation() })
        val job = launch { client.read(false, day, day); fail("cancelled read must not complete") }
        started.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }
}
