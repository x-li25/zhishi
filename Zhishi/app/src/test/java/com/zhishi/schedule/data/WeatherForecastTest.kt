package com.zhishi.schedule.data

import com.zhishi.schedule.domain.WeatherLocation
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WeatherForecastTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-25T04:00:00Z")
    private val day = LocalDate.parse("2026-10-26")
    private val city = WeatherLocation("北京", 39.9, 116.4)

    @Test fun `forecast uses exact dates schedule timezone and units without AI secrets and keeps closing sample`() = runBlocking {
        val requests = mutableListOf<Request>()
        val result = client(payload(), requests).forecast(city, day, day, zone, now)
        val request = requests.single()
        assertEquals("2026-10-26", request.url.queryParameter("start_date"))
        assertEquals("2026-10-27", request.url.queryParameter("end_date"))
        assertEquals(zone.id, request.url.queryParameter("timezone"))
        assertEquals("ms", request.url.queryParameter("wind_speed_unit"))
        assertNull(request.header("Authorization"))
        assertEquals(25, result.hours.size)
        assertEquals(day.atStartOfDay(zone).toInstant(), result.hours.first().time)
        assertEquals(day.plusDays(1).atStartOfDay(zone).toInstant(), result.hours.last().time)
        assertEquals(65, result.hours.first().precipitationProbability)
        assertEquals(1.2, result.hours.first().precipitationMm!!, 0.001)
    }

    @Test fun `invalid dates are rejected before HTTP and latest date does not query beyond horizon`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = client(payload(), requests)
        listOf(day.minusDays(2) to day, day to day.plusDays(15), day to day.plusDays(7)).forEach { (start, end) ->
            assertTrue(runCatching { client.forecast(city, start, end, zone, now) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(requests.isEmpty())
        val last = now.atZone(zone).toLocalDate().plusDays(15)
        client(payload(last), requests).forecast(city, last, last, zone, now)
        assertEquals(last.toString(), requests.single().url.queryParameter("end_date"))
    }

    @Test fun `malformed columns units timestamps and zones fail while null values remain unknown`() = runBlocking {
        val raw = payload()
        listOf("{}", raw.replace("°C", "°F"), raw.replace("Asia/Shanghai", "UTC"),
            raw.replace("\"time\":[", "\"time\":[0,"), raw.replace("\"precipitation\":[1.2", "\"precipitation\":[")
        ).forEach { body -> assertTrue(runCatching { client(body).forecast(city, day, day, zone, now) }.isFailure) }
        val result = client(raw.replace("1.2", "null").replace("65,65", "-1,65")).forecast(city, day, day, zone, now)
        assertNull(result.hours.first().precipitationMm)
        assertNull(result.hours.first().precipitationProbability)
    }

    @Test fun `stopping a forecast cancels the HTTP call`() = runBlocking {
        val started = CompletableDeferred<Call>()
        val release = CountDownLatch(1)
        val client = WeatherClient(OkHttpClient.Builder().addInterceptor { chain ->
            started.complete(chain.call())
            check(release.await(5, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload().toResponseBody()).build()
        }.build())
        try {
            val request = async { client.forecast(city, day, day, zone, now) }
            val call = withTimeout(2000) { started.await() }
            withTimeout(2000) { request.cancelAndJoin() }
            assertTrue(call.isCanceled())
        } finally { release.countDown() }
    }

    private fun client(body: String, requests: MutableList<Request> = mutableListOf()) = WeatherClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build())

    private fun payload(start: LocalDate = day) = buildJsonObject {
        put("timezone", zone.id); put("utc_offset_seconds", 28800)
        put("hourly_units", buildJsonObject {
            put("time", "unixtime"); put("temperature_2m", "°C"); put("apparent_temperature", "°C")
            put("precipitation_probability", "%"); put("precipitation", "mm")
            put("wind_speed_10m", "m/s"); put("wind_gusts_10m", "m/s")
        })
        put("hourly", buildJsonObject {
            put("time", buildJsonArray { (0..47).forEach { add(JsonPrimitive(start.atStartOfDay(zone).toEpochSecond() + it * 3600)) } })
            mapOf("temperature_2m" to 18.0, "apparent_temperature" to 17.0, "precipitation" to 1.2,
                "wind_speed_10m" to 2.0, "wind_gusts_10m" to 4.0).forEach { (key, value) ->
                put(key, JsonArray(List(48) { JsonPrimitive(value) }))
            }
            put("precipitation_probability", JsonArray(List(48) { JsonPrimitive(65) }))
            put("weather_code", JsonArray(List(48) { JsonPrimitive(61) }))
        })
    }.toString()
}
