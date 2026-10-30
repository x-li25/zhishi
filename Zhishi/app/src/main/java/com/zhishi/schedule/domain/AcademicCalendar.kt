package com.zhishi.schedule.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.json.*

/** 用户提供的 2026–2027 秋季校历。只解释已配置的学期，不外推下一学期。 */
object AcademicCalendar {
    const val termName = "2026–2027 学年度秋季学期"
    const val weekCount = 18
    val firstMonday: LocalDate = LocalDate.of(2026, 9, 14)
    val lastSunday: LocalDate = firstMonday.plusWeeks(weekCount.toLong()).minusDays(1)
    private val winterStart = LocalDate.of(2027, 1, 18)
    private val winterEnd = LocalDate.of(2027, 2, 21)

    data class TeachingWeek(val number: Int, val start: LocalDate, val end: LocalDate)

    fun weekNumber(date: LocalDate): Int? =
        if (date < firstMonday || date > lastSunday) null
        else (ChronoUnit.DAYS.between(firstMonday, date) / 7).toInt() + 1

    fun week(number: Int): TeachingWeek {
        require(number in 1..weekCount) { "当前秋季校历仅配置第 1 至 $weekCount 周，请明确其他学期的日期" }
        val start = firstMonday.plusWeeks(number.toLong() - 1)
        return TeachingWeek(number, start, start.plusDays(6))
    }

    fun date(number: Int, weekday: Int): LocalDate {
        require(weekday in 1..7) { "weekday 须为 1（周一）至 7（周日）" }
        return week(number).start.plusDays(weekday.toLong() - 1)
    }

    fun label(date: LocalDate): String = weekNumber(date)?.let { "第${it}周" } ?: when {
        date >= winterStart && date <= winterEnd -> "寒假（本科生校历）"
        date >= LocalDate.of(2026, 8, 10) && date < firstMonday -> "夏季学期"
        else -> "未配置校历"
    }

    private val periods = listOf("08:00" to "09:35", "09:50" to "12:15", "13:30" to "15:05",
        "15:20" to "16:55", "17:05" to "18:40", "19:20" to "20:55")
    private const val NUMBER = "[0-9一二三四五六七八九十零〇两]{1,3}"

    /** 只展开问题涉及的周次；完整18周表继续保存在本机。 */
    fun aiContext(today: LocalDate, referenceText: String = ""): String = buildString {
        appendLine("$termName：第1周周一=$firstMonday，共18周，结束=$lastSunday；周一至周日自然周，节假日不跳周。")
        appendLine("今天 $today 对应：${label(today)}。单双周按教学周编号的奇偶；页面日期不替代今天。")
        val localReference = if (Regex("春季|其他学期|202[78][–—-]202[89]").containsMatchIn(referenceText)) "" else referenceText
        val requested = linkedSetOf<Int>()
        if (Regex("(完整|全部|所有).{0,8}(周次|校历|周表)").containsMatchIn(localReference)) requested += 1..18
        Regex("第($NUMBER)周").findAll(localReference).forEach { match -> number(match.groupValues[1])?.let { requested += it } }
        Regex("第($NUMBER)(?:周)?(?:到|至|[-—～~])(?:第)?($NUMBER)周").findAll(localReference).forEach { match ->
            val start = number(match.groupValues[1]); val end = number(match.groupValues[2])
            if (start != null && end != null && start in 1..18 && end in start..18) requested += start..end
        }
        requested.filter { it in 1..18 }.sorted().take(18).forEach { n ->
            val week = week(n); appendLine("本机换算第${n}周：${week.start} 至 ${week.end}")
        }
        Regex("第($NUMBER)周(?:周|星期)([一二三四五六日天1-7])").findAll(localReference).take(4).forEach { match ->
            val week = number(match.groupValues[1]); val weekday = when (match.groupValues[2]) {
                "日", "天" -> 7; else -> number(match.groupValues[2])
            }
            if (week != null && week in 1..18 && weekday != null) appendLine("本机换算第${week}周周${weekday}=${date(week, weekday)}")
        }
        appendLine("清华大节固定作息（用户指定）：")
        periods.forEachIndexed { index, (start, end) -> appendLine("第${index + 1}大节：$start–$end" + if (index == 4) "（原则上不排课，锻炼时间）" else "") }
        appendLine("一门课一般一个大节（2学时，课间不拆日程），具体时刻优先：第2大节不得缩短。已知大节直接套用；只写‘第N节’且大小节不明时确认。用户/图片的明确实际时间覆盖通用表。尊重第5大节明确排课，不自动创建锻炼。")
        appendLine("本科寒假=$winterStart 至 $winterEnd，教师/研究生=2027-01-21 至 2027-02-17；身份不明先确认。夏季=2026-08-10至09-13，未配置周次。第18周之后不能称为第19周；春季/其他学年未配置，先问起始日期。")
        append("不确定周次/节次用context_requests的academic在本机换算；校历不代表已有日程。")
    }

    fun resolve(request: JsonObject, zone: ZoneId = ZoneId.systemDefault()): JsonObject {
        require(request.keys.all { it in setOf("kind", "week", "weekday", "period", "date") }) { "校历请求仅支持week/weekday/period/date，其他学期未配置" }
        fun integer(key: String) = if (key !in request) null else
            requireNotNull((request[key] as? JsonPrimitive)?.intOrNull) { "${key}须为整数" }
        val week = integer("week")?.let(::week)
        val weekday = integer("weekday")
        require(weekday == null || week != null && weekday in 1..7) { "weekday须与week一起使用且为1至7" }
        val period = integer("period")
        require(period == null || period in 1..6) { "仅配置第1至6大节" }
        val explicit = request["date"]?.let { LocalDate.parse((it as? JsonPrimitive)?.content) }
        val calculated = if (week != null && weekday != null) date(week.number, weekday) else null
        require(explicit == null || calculated == null || explicit == calculated) { "日期与教学周不一致" }
        require(explicit == null || week == null || explicit in week.start..week.end) { "日期与教学周不一致" }
        require(week != null || explicit != null || period != null) { "请提供week、date或period" }
        val target = explicit ?: calculated
        return buildJsonObject {
            put("complete", true); put("term", termName)
            if (week != null) { put("start_date", week.start.toString()); put("end_date", week.end.toString()) }
            if (target != null) {
                put("date", target.toString()); put("academic_label", label(target))
                weekNumber(target)?.let { put("week", it) }
            }
            if (period != null) {
                val (start, end) = periods[period - 1]
                put("period", period); put("start_time", start); put("end_time", end)
                if (target != null) {
                    put("start", target.atTime(LocalTime.parse(start)).atZone(zone).toOffsetDateTime().toString())
                    put("end", target.atTime(LocalTime.parse(end)).atZone(zone).toOffsetDateTime().toString())
                }
            }
            put("note", "仅本机校历/作息换算，不是个人日历；其他学期及用户明确更新的时间不能套用。")
        }
    }

    private fun number(text: String): Int? {
        text.toIntOrNull()?.let { return it }
        val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (text == "十") return 10
        if ('十' in text) {
            val parts = text.split('十')
            if (parts.size != 2 || parts.any { it.length > 1 }) return null
            val tens = if (parts[0].isEmpty()) 1 else digits[parts[0][0]] ?: return null
            val ones = if (parts[1].isEmpty()) 0 else digits[parts[1][0]] ?: return null
            return tens * 10 + ones
        }
        return text.singleOrNull()?.let { digits[it] }
    }
}
