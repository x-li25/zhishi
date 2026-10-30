package com.zhishi.schedule.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AppDateFormatTest {
    @Test
    fun `detail times show full dates across midnight and year boundary`() {
        val zone = ZoneId.of("Asia/Shanghai")
        assertEquals("2026年12月31日 星期四 23:30", AppDateFormat.fullDateTime(at("2026-12-31T15:30:00Z"), zone))
        assertEquals("2027年1月1日 星期五 01:30", AppDateFormat.fullDateTime(at("2026-12-31T17:30:00Z"), zone))
    }

    @Test
    fun `single all day event uses UTC date and excludes next midnight`() {
        assertEquals("2026年10月24日 星期六 · 全天",
            AppDateFormat.allDayDateRange(at("2026-10-24T00:00:00Z"), at("2026-10-25T00:00:00Z")))
    }

    @Test
    fun `multiple all day dates include last occupied date without extra day`() {
        assertEquals("2026年12月31日 星期四 至 2027年1月1日 星期五 · 全天",
            AppDateFormat.allDayDateRange(at("2026-12-31T00:00:00Z"), at("2027-01-02T00:00:00Z")))
    }

    private fun at(value: String) = Instant.parse(value).toEpochMilli()
}
