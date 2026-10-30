package com.zhishi.schedule.ai

import com.zhishi.schedule.domain.ParsedActionType
import com.zhishi.schedule.domain.ParsedEvent
import com.zhishi.schedule.domain.WeatherCodes
import com.zhishi.schedule.domain.WeatherForecast
import com.zhishi.schedule.domain.WeatherLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 每次提问重新获取预报；同一提问内复用结果，不把当前天气或旧对话当作未来预报。 */
internal class WeatherReadSession(
    private val now: Instant,
    private val zone: ZoneId,
    private val location: WeatherLocation?,
    private val reader: (suspend (LocalDate, LocalDate) -> WeatherForecast)?
) {
    val warnings = linkedSetOf<String>()
    private val cache = mutableMapOf<Pair<LocalDate, LocalDate>, JsonObject>()
    private val attemptedDays = mutableSetOf<LocalDate>()
    private val requestedDays = mutableSetOf<LocalDate>()
    private val today = now.atZone(zone).toLocalDate()

    fun context(): String = buildJsonObject {
        put("available", location != null && reader != null)
        if (location != null) {
            put("city", location.name); put("region", location.region)
            put("from_device_location", location.fromDevice)
        }
        put("date_timezone", zone.id)
        put("earliest_date", today.toString()); put("latest_date", today.plusDays(15).toString())
    }.toString()

    suspend fun read(requests: JsonArray): JsonObject = buildJsonObject {
        put("weather_results", buildJsonArray {
            if (requests.size > 3) {
                add(error("每轮最多3个天气范围，请缩小查询")); return@buildJsonArray
            }
            for (item in requests) {
                currentCoroutineContext().ensureActive()
                val result = try {
                    val request = item as? JsonObject ?: throw IllegalArgumentException("天气请求必须为对象")
                    require(request.keys.all { it in setOf("start_date", "end_date", "city") }) { "天气只支持起止日期和当前所选城市" }
                    fun date(key: String) = LocalDate.parse((request[key] as? JsonPrimitive)?.content)
                    val start = date("start_date"); val end = date("end_date")
                    require(!end.isBefore(start) && ChronoUnit.DAYS.between(start, end) < 7) { "每个天气范围最多7天且起止日期需正确" }
                    val days = (0..ChronoUnit.DAYS.between(start, end)).map { start.plusDays(it) }
                    // 记录失败尝试，防止模型最后输出同一事项时自动补查陷入循环。
                    attemptedDays += days
                    require(!start.isBefore(today) && !end.isAfter(today.plusDays(15))) { "预报仅支持今天起16天内；历史及更远日期天气未知" }
                    require(location != null && reader != null) { "请先在今天页天气卡片选择活动所在城市，再查询天气" }
                    val city = (request["city"] as? JsonPrimitive)?.contentOrNull
                    require(city == null || city == location.name) { "请求城市与天气卡片所选城市不符，请先切换活动所在城市" }
                    require((requestedDays + days).size <= 16) { "本次天气查询累计最多16天" }
                    requestedDays += days
                    cache.getOrPutSuspend(start to end) {
                        val forecast = reader.invoke(start, end)
                        currentCoroutineContext().ensureActive()
                        require(forecast.location.samePlace(location) && forecast.zoneId.rules == zone.rules) { "预报位置或时区不匹配" }
                        require(ChronoUnit.MINUTES.between(forecast.fetchedAt, now) in -10..30) { "预报已过期，请重新查询" }
                        encode(forecast, start, end)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error(failure.message ?: "天气查询失败，天气未知")
                }
                add(JsonObject(result + ("request" to item)))
            }
        })
    }

    fun unreadActionRanges(events: List<ParsedEvent>, force: Boolean = false): JsonArray = buildJsonArray {
        if (location == null || reader == null) return@buildJsonArray
        events.filter { isSensitive(it, force) }.flatMap { event ->
            val start = Instant.ofEpochMilli(event.startMillis).atZone(zone).toLocalDate()
            val end = Instant.ofEpochMilli(event.endMillis - 1).atZone(zone).toLocalDate()
            if (ChronoUnit.DAYS.between(start, end) !in 0..15) listOf(start)
            else (0..ChronoUnit.DAYS.between(start, end)).map { start.plusDays(it) }
        }.distinct().filterNot { it in attemptedDays }.take(3).forEach { date ->
            add(buildJsonObject { put("start_date", date.toString()); put("end_date", date.toString()) })
        }
    }

    fun noteUnavailable(events: List<ParsedEvent>, force: Boolean = false) {
        if ((location == null || reader == null) && events.any { isSensitive(it, force) }) {
            warnings += "尚未选择天气城市，未核对户外活动天气；请在今天页选择活动所在城市"
        }
    }

    private fun isSensitive(event: ParsedEvent, force: Boolean): Boolean = event.action != ParsedActionType.DELETE &&
        !INDOOR.containsMatchIn(event.title) && (force || event.weatherSensitive || OUTDOOR.containsMatchIn(event.title))

    private fun error(message: String): JsonObject {
        warnings += "天气未核实：$message"
        return buildJsonObject { put("complete", false); put("error", message); put("weather_known", false) }
    }

    private fun encode(forecast: WeatherForecast, start: LocalDate, end: LocalDate): JsonObject {
        val lower = start.atStartOfDay(zone).toInstant()
        val upper = end.plusDays(1).atStartOfDay(zone).toInstant()
        val rows = forecast.hours.filter { it.time >= lower && it.time <= upper }.sortedBy { it.time }
        // 包含下一日零点；缺小时、空指标均不能解释为无雨、无风。
        val expected = generateSequence(lower) { it.plusSeconds(3600) }.takeWhile { it <= upper }.toList()
        val complete = rows.map { it.time } == expected && rows.all {
            it.temperatureC != null && it.apparentTemperatureC != null && it.precipitationMm != null &&
                it.precipitationProbability != null && it.windSpeedMs != null && it.gustSpeedMs != null &&
                WeatherCodes.description(it.weatherCode) != "天气状况未知"
        }
        if (!complete) warnings += "${start}至${end}的预报部分时段或指标缺失，缺失部分天气未知"
        return buildJsonObject {
            put("city", forecast.location.name); put("region", forecast.location.region)
            put("start_date", start.toString()); put("end_date", end.toString())
            put("timezone", zone.id); put("fetched_at", forecast.fetchedAt.toString())
            put("source", "https://open-meteo.com/en/docs"); put("kind", "hourly_forecast")
            put("complete", complete)
            put("columns", buildJsonArray {
                listOf("time", "temperature_c", "feels_like_c", "precipitation_probability_percent",
                    "precipitation_mm", "wind_ms", "gust_ms", "weather").forEach { add(JsonPrimitive(it)) }
            })
            put("hours", buildJsonArray { rows.forEach { hour -> add(buildJsonArray {
                add(JsonPrimitive(hour.time.atZone(zone).toOffsetDateTime().toString()))
                add(hour.temperatureC?.let(::JsonPrimitive) ?: JsonNull)
                add(hour.apparentTemperatureC?.let(::JsonPrimitive) ?: JsonNull)
                add(hour.precipitationProbability?.let(::JsonPrimitive) ?: JsonNull)
                add(hour.precipitationMm?.let(::JsonPrimitive) ?: JsonNull)
                add(hour.windSpeedMs?.let(::JsonPrimitive) ?: JsonNull)
                add(hour.gustSpeedMs?.let(::JsonPrimitive) ?: JsonNull)
                add(JsonPrimitive(WeatherCodes.description(hour.weatherCode)))
            }) } })
            put("interval_note", "time是时刻；降水量、降水概率和阵风属于(time-1小时,time]。判断活动全程需覆盖所有相交小时。null/缺行/未知天气代码不代表晴天或零降水。其他指标为time时刻预报。")
        }
    }

    private suspend fun MutableMap<Pair<LocalDate, LocalDate>, JsonObject>.getOrPutSuspend(
        key: Pair<LocalDate, LocalDate>, block: suspend () -> JsonObject
    ): JsonObject = get(key) ?: block().also { put(key, it) }

    companion object {
        private val INDOOR = Regex("室内|跑步机|健身房|\\b(indoor|treadmill)\\b", RegexOption.IGNORE_CASE)
        private val OUTDOOR = Regex("户外|跑步|散步|骑行|徒步|爬山|登山|露营|野餐|\\b(run|running|walk|walking|cycling|hiking|picnic|camping)\\b", RegexOption.IGNORE_CASE)
    }
}
