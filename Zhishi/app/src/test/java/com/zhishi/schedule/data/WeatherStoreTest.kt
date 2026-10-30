package com.zhishi.schedule.data

import android.content.Context
import com.zhishi.schedule.domain.WeatherCodes
import com.zhishi.schedule.domain.WeatherLocation
import com.zhishi.schedule.domain.WeatherReport
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WeatherStoreTest {
    private val place = WeatherLocation("北京", 39.9, 116.4)

    @Test
    fun `cached weather persists but cannot appear under a different city`() {
        val app = RuntimeEnvironment.getApplication()
        val store = WeatherStore(app)
        val report = report()
        store.saveLocation(place)
        store.saveReport(report)
        assertEquals(place, WeatherStore(app).location())
        assertEquals(report, WeatherStore(app).report(place))
        assertNull(store.report(place.copy(latitude = 31.2)))
        app.getSharedPreferences("zhishi_weather", Context.MODE_PRIVATE).edit().putString("report", "broken").commit()
        assertNull(store.report(place))
    }

    @Test
    fun `cache expires at thirty minutes and stale model time cannot count as fresh`() {
        val report = report()
        val now = report.fetchedAtMillis
        assertTrue(report.isFresh(now + WeatherReport.CACHE_MILLIS - 1))
        assertFalse(report.isFresh(now + WeatherReport.CACHE_MILLIS))
        assertFalse(report.isFresh(now - 1))
        assertFalse(report.copy(dataTimeMillis = now - 91 * 60_000).isFresh(now))
        assertFalse(report.copy(dataTimeMillis = now + 16 * 60_000).isFresh(now))
    }

    @Test
    fun `weather interpretation distinguishes freezing rain snow thunderstorms and unknown codes`() {
        assertEquals("冻雨", WeatherCodes.description(66))
        assertEquals("大雪", WeatherCodes.description(75))
        assertEquals("雷雨伴冰雹", WeatherCodes.description(99))
        assertEquals("天气状况未知", WeatherCodes.description(null))
        assertEquals("天气状况未知", WeatherCodes.description(999))
        assertNotEquals(WeatherCodes.symbol(0, true), WeatherCodes.symbol(0, false))
    }

    private fun report() = WeatherReport(location = place, temperatureC = 15.0,
        dataTimeMillis = 1_792_935_000_000, fetchedAtMillis = 1_792_935_000_000, timezone = "Asia/Shanghai")
}
