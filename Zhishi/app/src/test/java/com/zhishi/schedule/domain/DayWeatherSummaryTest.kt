package com.zhishi.schedule.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DayWeatherSummaryTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.parse("2026-10-26")
    private val start = day.atStartOfDay(zone).toInstant()
    private val city = WeatherLocation("北京", 39.9, 116.4)

    @Test fun `summary includes closing midnight rain but not its next day temperature`() {
        val hours = (0..24).map { index ->
            WeatherHour(start.plusSeconds(index * 3600L), if (index == 24) -30.0 else 10.0 + index,
                null, if (index == 24) 80 else if (index == 0) 100 else 10, null, 2.0, null, 0)
        }
        val summary = WeatherForecast(city, zone, Instant.now(), hours).daySummary(day)!!
        assertEquals(10.0, summary.minTemperatureC!!, 0.001)
        assertEquals(33.0, summary.maxTemperatureC!!, 0.001)
        assertEquals(80, summary.maxPrecipitationProbability)
        assertTrue(summary.complete)
    }

    @Test fun `missing times and null metrics remain incomplete without inventing zero`() {
        val hour = WeatherHour(start, null, null, null, null, null, null, null)
        val forecast = WeatherForecast(city, zone, Instant.now(), listOf(hour))
        val summary = forecast.daySummary(day)!!
        assertNull(summary.minTemperatureC)
        assertNull(summary.maxPrecipitationProbability)
        assertNull(summary.maxWindSpeedMs)
        assertFalse(summary.complete)
        assertEquals(listOf("天气状况未知"), summary.descriptions)
        assertNull(forecast.daySummary(day.plusDays(1)))
    }

    @Test fun `day boundary follows schedule timezone including a daylight saving transition`() {
        val dstZone = ZoneId.of("America/New_York")
        val date = LocalDate.parse("2026-11-01")
        val lower = date.atStartOfDay(dstZone).toInstant()
        val hours = (0..25).map { WeatherHour(lower.plusSeconds(it * 3600L), 15.0, null, 0, null, 1.0, null, 0) }
        assertTrue(WeatherForecast(city, dstZone, Instant.now(), hours).daySummary(date)!!.complete)
        assertFalse(WeatherForecast(city, dstZone, Instant.now(), hours.dropLast(1)).daySummary(date)!!.complete)
    }
}
