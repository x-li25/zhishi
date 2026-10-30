package com.zhishi.schedule.data

import com.zhishi.schedule.domain.WeatherLocation
import com.zhishi.schedule.domain.WeatherReport
import com.zhishi.schedule.domain.WeatherForecast
import com.zhishi.schedule.domain.WeatherHour
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.ZoneId
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 独立于 AI 的公开天气接口，不携带 AI Key 或聊天内容。 */
class WeatherClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS).build()) {

    suspend fun searchCities(query: String): List<WeatherLocation> = withContext(Dispatchers.IO) {
        if (query.trim().length < 2) return@withContext emptyList()
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", query.trim()).addQueryParameter("count", "10")
            .addQueryParameter("language", "zh").addQueryParameter("format", "json").build()
        val root = get(url.toString())
        val entries = root["results"]
        if (entries == null || entries is JsonNull) return@withContext emptyList()
        val results = entries as? JsonArray ?: throw IOException("城市搜索结果格式异常，请稍后重试")
        results.mapNotNull { item ->
            val city = item as? JsonObject ?: return@mapNotNull null
            val name = city.string("name") ?: return@mapNotNull null
            val latitude = city.number("latitude") ?: return@mapNotNull null
            val longitude = city.number("longitude") ?: return@mapNotNull null
            runCatching {
                WeatherLocation(name, latitude, longitude,
                    listOfNotNull(city.string("admin1"), city.string("admin2"), city.string("country"))
                        .filterNot { it == name }.distinct().joinToString(" · "))
            }.getOrNull()
        }.distinctBy { it.latitude to it.longitude }
    }

    suspend fun current(location: WeatherLocation): WeatherReport = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", location.latitude.toString())
            .addQueryParameter("longitude", location.longitude.toString())
            .addQueryParameter("current", "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,wind_speed_10m,precipitation")
            .addQueryParameter("temperature_unit", "celsius").addQueryParameter("wind_speed_unit", "ms")
            .addQueryParameter("precipitation_unit", "mm").addQueryParameter("timezone", "auto")
            .addQueryParameter("timeformat", "unixtime").addQueryParameter("forecast_days", "1").build()
        val root = get(url.toString())
        val current = root["current"] as? JsonObject ?: throw IOException("天气接口缺少当前天气数据")
        val units = root["current_units"] as? JsonObject ?: throw IOException("天气接口缺少单位信息")
        if (units.string("temperature_2m") != "°C" || units.string("wind_speed_10m") != "m/s" ||
            units.string("precipitation") != "mm" || units.string("time") != "unixtime") {
            throw IOException("天气接口返回的单位不匹配，请稍后重试")
        }
        val temperature = current.number("temperature_2m") ?: throw IOException("当前气温暂不可用")
        val epoch = (current["time"] as? JsonPrimitive)?.longOrNull
            ?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 } ?: throw IOException("天气数据时间无效")
        val timezone = root.string("timezone")?.takeIf { runCatching { ZoneId.of(it) }.isSuccess }
            ?: throw IOException("天气数据时区无效")
        WeatherReport(
            location = location, temperatureC = temperature,
            apparentTemperatureC = current.number("apparent_temperature"),
            humidityPercent = current.integer("relative_humidity_2m")?.takeIf { it in 0..100 },
            windSpeedMs = current.number("wind_speed_10m")?.takeIf { it >= 0 },
            precipitationMm = current.number("precipitation")?.takeIf { it >= 0 },
            weatherCode = current.integer("weather_code"),
            isDay = current.integer("is_day")?.let { when (it) { 0 -> false; 1 -> true; else -> null } },
            intervalSeconds = current.integer("interval")?.takeIf { it > 0 },
            dataTimeMillis = epoch * 1000, fetchedAtMillis = System.currentTimeMillis(), timezone = timezone
        )
    }

    /** 额外取结束日之后的零点，才能判断结束日 23–24 点的降水；不超出 16 天范围。 */
    suspend fun forecast(location: WeatherLocation, start: LocalDate, end: LocalDate,
                         zoneId: ZoneId, now: Instant = Instant.now()): WeatherForecast = withContext(Dispatchers.IO) {
        val today = now.atZone(zoneId).toLocalDate()
        require(!start.isBefore(today) && !end.isBefore(start) && !end.isAfter(today.plusDays(15)) &&
            java.time.temporal.ChronoUnit.DAYS.between(start, end) < 7) { "预报只支持今天起16天内，每次最多7天" }
        val fetchEnd = end.plusDays(1).coerceAtMost(today.plusDays(15))
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", location.latitude.toString())
            .addQueryParameter("longitude", location.longitude.toString())
            .addQueryParameter("hourly", "temperature_2m,apparent_temperature,precipitation_probability,precipitation,wind_speed_10m,wind_gusts_10m,weather_code")
            .addQueryParameter("temperature_unit", "celsius").addQueryParameter("wind_speed_unit", "ms")
            .addQueryParameter("precipitation_unit", "mm").addQueryParameter("timeformat", "unixtime")
            .addQueryParameter("timezone", zoneId.id).addQueryParameter("start_date", start.toString())
            .addQueryParameter("end_date", fetchEnd.toString()).build()
        val root = get(url.toString())
        val units = root["hourly_units"] as? JsonObject ?: throw IOException("预报缺少单位信息")
        mapOf("time" to "unixtime", "temperature_2m" to "°C", "apparent_temperature" to "°C",
            "precipitation_probability" to "%", "precipitation" to "mm", "wind_speed_10m" to "m/s",
            "wind_gusts_10m" to "m/s").forEach { (key, unit) ->
            if (units.string(key) != unit) throw IOException("预报单位不匹配")
        }
        val returnedZone = root.string("timezone")?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: throw IOException("预报时区无效")
        if (returnedZone.rules != zoneId.rules) throw IOException("预报时区不匹配")
        val hourly = root["hourly"] as? JsonObject ?: throw IOException("天气接口缺少逐小时预报")
        val times = hourly["time"] as? JsonArray ?: throw IOException("预报缺少时刻")
        if (times.isEmpty() || times.size > 220) throw IOException("预报时段异常")
        val keys = listOf("temperature_2m", "apparent_temperature", "precipitation_probability", "precipitation",
            "wind_speed_10m", "wind_gusts_10m", "weather_code")
        val columns = keys.associateWith { key ->
            (hourly[key] as? JsonArray)?.takeIf { it.size == times.size } ?: throw IOException("预报指标长度不匹配")
        }
        val lower = start.atStartOfDay(zoneId).toInstant()
        val upper = end.plusDays(1).atStartOfDay(zoneId).toInstant()
        var previous: Instant? = null
        val hours = times.mapIndexedNotNull { index, value ->
            val epoch = (value as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }
                ?: throw IOException("预报时刻无效")
            val time = Instant.ofEpochSecond(epoch)
            if (previous?.let { !time.isAfter(it) } == true) throw IOException("预报时刻顺序异常")
            previous = time
            if (time < lower || time > upper) return@mapIndexedNotNull null
            fun number(key: String) = (columns.getValue(key)[index] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
            fun integer(key: String) = (columns.getValue(key)[index] as? JsonPrimitive)?.intOrNull
            WeatherHour(time, number("temperature_2m"), number("apparent_temperature"),
                integer("precipitation_probability")?.takeIf { it in 0..100 },
                number("precipitation")?.takeIf { it >= 0 }, number("wind_speed_10m")?.takeIf { it >= 0 },
                number("wind_gusts_10m")?.takeIf { it >= 0 }, integer("weather_code"))
        }
        if (hours.isEmpty()) throw IOException("请求日期没有可用预报")
        WeatherForecast(location, zoneId, Instant.now(), hours)
    }

    private suspend fun get(url: String): JsonObject {
        val body = suspendCancellableCoroutine<String> { continuation ->
            val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
            continuation.invokeOnCancellation { call.cancel() }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("天气服务连接失败，请检查网络或稍后重试", e))
                }
                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) { response.close(); return }
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) throw IOException(if (it.code == 429) "天气请求过于频繁，请稍后重试" else "天气服务暂不可用（HTTP ${it.code}）")
                            it.body?.string().orEmpty()
                        }
                    }
                    if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
                }
            })
        }
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw IOException("天气服务返回格式异常，请稍后重试")
        if ((root["error"] as? JsonPrimitive)?.booleanOrNull == true) throw IOException("天气服务暂时无法处理此查询")
        return root
    }

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonObject.integer(key: String) = (get(key) as? JsonPrimitive)?.intOrNull
}
