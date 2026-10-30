package com.zhishi.schedule.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal object ClassroomFixture {
    val monday: LocalDate = LocalDate.of(2026, 10, 26)
    val buildings = """<html><div class='w30'><a href='/http/abc/pk.classroomctrl.do?m=qyClassroomState&amp;classroom=%D0%C2%CB%AE%C0%FB%B9%DD&amp;weeknumber=7'>新水利馆</a></div></html>"""
    fun html(number: Int = 7, start: LocalDate = monday, count: Int = 42): String = buildString {
        append("<html><select name='weeknumber' id='weeknumber'>")
        (1..18).forEach { append("<option value='$it' ${if (it == number) "selected" else ""}>第${it}周</option>") }
        append("</select><table><tr>")
        (0L..6).forEach { append("<td colspan='6'>星期${it + 1}(${start.plusDays(it).format(DateTimeFormatter.ofPattern("MM.dd"))})</td>") }
        append("</tr></table><div id='scrollContent'><table><tbody><tr><td><a>300</a>:50</td>")
        repeat(count) { n ->
            val c = listOf("", "onteaching", "onexam", "onborrowed", "ondisabled", "futureState")[n % 6]
            append("<td class='$c colBound'${if (n == 1) " title='概率论 &amp; 习题课'" else ""}>&nbsp;</td>")
        }
        append("</tr></tbody></table></div></html>")
    }
}

class ClassroomParserTest {
    @Test fun `school links decode gb2312 once and preserve server building identity`() {
        val building = ClassroomParser.buildings(ClassroomFixture.buildings).single()
        assertEquals("新水利馆", building.name); assertEquals("新水利馆", building.queryName); assertEquals(7, building.week)
        val url = ClassroomUrls.week(building, 7)
        assertTrue(url.contains("classroom=%D0%C2%CB%AE%C0%FB%B9%DD"))
        assertFalse(url.contains("%25D0")); assertTrue(url.endsWith("weeknumber=7"))
    }
    @Test fun `week preserves capacity forty two states and available explicit descriptions`() {
        val result = ClassroomParser.week(ClassroomFixture.html(), 7, ClassroomFixture.monday.plusDays(4))
        assertEquals((0L..6).map { ClassroomFixture.monday.plusDays(it) }, result.dates)
        val room = result.rooms.single(); assertEquals("300", room.name); assertEquals(50, room.capacity)
        assertEquals(42, room.slots.size)
        assertEquals(listOf(ClassroomOccupancy.AVAILABLE, ClassroomOccupancy.TEACHING, ClassroomOccupancy.EXAM, ClassroomOccupancy.BORROWED,
            ClassroomOccupancy.DISABLED, ClassroomOccupancy.UNKNOWN), room.slots.take(6).map { it.occupancy })
        assertEquals("概率论 & 习题课", room.slots[1].detail)
    }
    @Test fun `quoted greater than and omitted legacy cell row and option end tags are supported`() {
        val html = ClassroomFixture.html().replace("概率论 &amp; 习题课", "人数&gt;50 &amp; 讨论").replace("</td>", "").replace("</tr>", "").replace("</option>", "")
        val room = ClassroomParser.week(html, 7, ClassroomFixture.monday).rooms.single()
        assertEquals(42, room.slots.size); assertEquals("人数>50 & 讨论", room.slots[1].detail)
    }
    @Test fun `unknown capacity and new or conflicting styles never become available`() {
        val html = ClassroomFixture.html().replace(":50", ":未知").replace("onteaching colBound", "onteaching onexam colBound")
        val room = ClassroomParser.week(html, 7, ClassroomFixture.monday).rooms.single()
        assertNull(room.capacity); assertEquals(ClassroomOccupancy.UNKNOWN, room.slots[1].occupancy)
    }
    @Test fun `cross year headers resolve january into the following year`() {
        val monday = LocalDate.of(2026, 12, 28)
        val result = ClassroomParser.week(ClassroomFixture.html(16, monday), 16, LocalDate.of(2027, 1, 1))
        assertEquals(LocalDate.of(2027, 1, 3), result.dates.last())
    }
    @Test fun `missing slots inconsistent dates wrong selected week and login pages fail closed`() {
        listOf(ClassroomFixture.html(count = 41), ClassroomFixture.html(count = 43), ClassroomFixture.html().replace("10.28", "10.29"),
            ClassroomFixture.html().replace("value='7' selected", "value='6' selected"), "<html><form>学校登录</form></html>").forEach {
            assertTrue(runCatching { ClassroomParser.week(it, 7, ClassroomFixture.monday) }.isFailure)
        }
        assertTrue(runCatching { ClassroomParser.buildings("<script>${ClassroomFixture.buildings}</script><form>登录</form>") }.isFailure)
    }
    @Test fun `empty valid school table is distinct from authentication failure`() {
        val html = ClassroomFixture.html().replace(Regex("<tbody>[\\s\\S]*?</tbody>"), "<tbody></tbody>")
        assertTrue(ClassroomParser.week(html, 7, ClassroomFixture.monday).rooms.isEmpty())
    }
    @Test fun `read only authorization uses classroom application and rejects foreign ticket destination`() = runBlocking {
        val calls = mutableListOf<String>()
        val client = ClassroomClient(CampusTransport { url ->
            calls += url
            when {
                url == CampusUrls.COOKIE -> "XSRF-TOKEN=fixture;"
                url.startsWith(CampusUrls.ROAM) -> """{"object":{"roamingurl":"http://zhjw.cic.tsinghua.edu.cn/jxmh.do?ticket=fixture"}}"""
                url == ClassroomUrls.LIST -> ClassroomFixture.buildings
                else -> "authorized"
            }
        })
        assertEquals(1, client.buildings().size)
        assertTrue(calls[1].contains("yyfwid=${ClassroomUrls.APP_ID}")); assertEquals(4, calls.size)
        var count = 0
        val bad = ClassroomClient(CampusTransport { count++; if (count == 1) "XSRF-TOKEN=fixture;" else """{"object":{"roamingurl":"https://evil.example/"}}""" })
        assertTrue(runCatching { bad.buildings() }.isFailure); assertEquals(2, count)
    }
}
