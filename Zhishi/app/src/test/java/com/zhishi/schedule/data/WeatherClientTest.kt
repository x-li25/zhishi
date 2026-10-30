package com.zhishi.schedule.data

import com.zhishi.schedule.domain.WeatherLocation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WeatherClientTest {
    private val beijing = WeatherLocation("北京", 39.9, 116.4)

    @Test
    fun `current weather requests explicit units and uses UTC epoch without adding timezone offset`() = runBlocking {
        val fixture = Fixture(currentJson)
        val weather = fixture.client.current(beijing)
        val request = fixture.requests.single()
        assertEquals("api.open-meteo.com", request.url.host)
        assertEquals("39.9", request.url.queryParameter("latitude"))
        assertEquals("celsius", request.url.queryParameter("temperature_unit"))
        assertEquals("ms", request.url.queryParameter("wind_speed_unit"))
        assertEquals("unixtime", request.url.queryParameter("timeformat"))
        assertNull(request.header("Authorization"))
        assertNull(request.url.queryParameter("apikey"))
        assertEquals(10.9, weather.temperatureC, 0.001)
        assertEquals(1.11, weather.windSpeedMs!!, 0.001)
        assertEquals(0.2, weather.precipitationMm!!, 0.001)
        assertEquals(900, weather.intervalSeconds)
        assertEquals(false, weather.isDay)
        assertEquals("Asia/Shanghai", weather.timezone)
        assertEquals(Instant.ofEpochSecond(1792935000).toEpochMilli(), weather.dataTimeMillis)
        assertEquals(beijing, weather.location)
    }

    @Test
    fun `missing optional weather measurements remain unknown instead of zero`() = runBlocking {
        val report = Fixture(currentJson.replace("\"apparent_temperature\":10.3", "\"apparent_temperature\":null")
            .replace("\"relative_humidity_2m\":94", "\"relative_humidity_2m\":-1")
            .replace("\"weather_code\":53", "\"weather_code\":null")).client.current(beijing)
        assertNull(report.apparentTemperatureC)
        assertNull(report.humidityPercent)
        assertNull(report.weatherCode)
    }

    @Test
    fun `invalid current payload units and time are never displayed as normal weather`() = runBlocking {
        listOf("{}", currentJson.replace("°C", "°F"), currentJson.replace("\"temperature_2m\":10.9", "\"temperature_2m\":null"),
            currentJson.replace("1792935000", "0"), currentJson.replace("Asia/Shanghai", "bad-zone"),
            """{"error":true,"reason":"bad input"}""").forEach { payload ->
            assertTrue(runCatching { Fixture(payload).client.current(beijing) }.exceptionOrNull() is IOException)
        }
    }

    @Test
    fun `city search returns multiple candidates with regions and skips invalid coordinates`() = runBlocking {
        val fixture = Fixture("""{"results":[
            {"name":"北京","latitude":39.9,"longitude":116.4,"admin1":"北京市","country":"中国"},
            {"name":"北京","latitude":35.2,"longitude":110.7,"admin1":"山西","country":"中国"},
            {"name":"无效","latitude":999,"longitude":0}
        ]}""")
        val results = fixture.client.searchCities(" 北京 ")
        assertEquals(2, results.size)
        assertTrue(results[0].region.contains("北京市"))
        assertTrue(results[1].region.contains("山西"))
        val request = fixture.requests.single()
        assertEquals("geocoding-api.open-meteo.com", request.url.host)
        assertEquals("北京", request.url.queryParameter("name"))
        assertEquals("zh", request.url.queryParameter("language"))
        assertTrue(Fixture("{}").client.searchCities("不存在的城市").isEmpty())
        assertTrue(runCatching { Fixture("""{"results":{}}""").client.searchCities("北京") }.exceptionOrNull() is IOException)
    }

    @Test
    fun `short search avoids network and rate limit produces actionable error`() = runBlocking {
        val fixture = Fixture("{}")
        assertTrue(fixture.client.searchCities("北").isEmpty())
        assertTrue(fixture.requests.isEmpty())
        val failure = runCatching { Fixture("{}", 429).client.current(beijing) }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("频繁"))
    }

    @Test
    fun `cancelling weather lookup cancels underlying HTTP request`() = runBlocking {
        val started = CompletableDeferred<Call>()
        val release = CountDownLatch(1)
        val client = WeatherClient(OkHttpClient.Builder().addInterceptor { chain ->
            started.complete(chain.call())
            check(release.await(5, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(currentJson.toResponseBody()).build()
        }.build())
        try {
            val request = async { client.current(beijing) }
            val call = withTimeout(3_000) { started.await() }
            withTimeout(3_000) { request.cancelAndJoin() }
            assertTrue(call.isCanceled())
        } finally { release.countDown() }
    }

    private class Fixture(body: String, status: Int = 200) {
        val requests = mutableListOf<Request>()
        val client = WeatherClient(OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody()).build()
        }.build())
    }

    companion object {
        val currentJson = """{"timezone":"Asia/Shanghai","utc_offset_seconds":28800,
            "current_units":{"time":"unixtime","temperature_2m":"°C","wind_speed_10m":"m/s","precipitation":"mm"},
            "current":{"time":1792935000,"interval":900,"temperature_2m":10.9,"apparent_temperature":10.3,
            "relative_humidity_2m":94,"weather_code":53,"is_day":0,"wind_speed_10m":1.11,"precipitation":0.2}}"""
    }
}
