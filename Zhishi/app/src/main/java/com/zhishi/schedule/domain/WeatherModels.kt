package com.zhishi.schedule.domain

import kotlinx.serialization.Serializable
import kotlin.math.abs
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 时刻指标为该时刻预报；降水、降水概率和阵风属于截至该时刻的前一小时。 */
data class WeatherHour(
    val time: Instant,
    val temperatureC: Double?,
    val apparentTemperatureC: Double?,
    val precipitationProbability: Int?,
    val precipitationMm: Double?,
    val windSpeedMs: Double?,
    val gustSpeedMs: Double?,
    val weatherCode: Int?
)

data class WeatherForecast(
    val location: WeatherLocation,
    val zoneId: ZoneId,
    val fetchedAt: Instant,
    val hours: List<WeatherHour>
)

data class DayWeatherSummary(
    val minTemperatureC: Double?,
    val maxTemperatureC: Double?,
    val descriptions: List<String>,
    val maxPrecipitationProbability: Int?,
    val maxWindSpeedMs: Double?,
    val complete: Boolean
)

/** 按日程时区汇总当天；次日零点的降水属于当天最后一小时，气温则属于次日。 */
fun WeatherForecast.daySummary(date: LocalDate): DayWeatherSummary? {
    val start = date.atStartOfDay(zoneId).toInstant()
    val end = date.plusDays(1).atStartOfDay(zoneId).toInstant()
    val instants = hours.filter { it.time >= start && it.time < end }
    val intervals = hours.filter { it.time > start && it.time <= end }
    if (instants.isEmpty()) return null
    val expectedInstants = generateSequence(start) { it.plusSeconds(3600) }.takeWhile { it < end }.toList()
    val expectedIntervals = expectedInstants.map { it.plusSeconds(3600) }
    val temperatures = instants.mapNotNull { it.temperatureC }
    return DayWeatherSummary(temperatures.minOrNull(), temperatures.maxOrNull(),
        instants.map { WeatherCodes.description(it.weatherCode) }.distinct(),
        intervals.mapNotNull { it.precipitationProbability }.maxOrNull(),
        instants.mapNotNull { it.windSpeedMs }.maxOrNull(),
        instants.map { it.time } == expectedInstants && intervals.map { it.time } == expectedIntervals &&
            instants.all { it.temperatureC != null && it.windSpeedMs != null && WeatherCodes.description(it.weatherCode) != "天气状况未知" } &&
            intervals.all { it.precipitationProbability != null })
}

@Serializable
data class WeatherLocation(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val region: String = "",
    val fromDevice: Boolean = false
) {
    init {
        require(name.isNotBlank() && latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0) { "天气位置无效" }
    }
    fun samePlace(other: WeatherLocation): Boolean =
        abs(latitude - other.latitude) < 0.000001 && abs(longitude - other.longitude) < 0.000001
}

@Serializable
data class WeatherReport(
    val location: WeatherLocation,
    val temperatureC: Double,
    val apparentTemperatureC: Double? = null,
    val humidityPercent: Int? = null,
    val windSpeedMs: Double? = null,
    val precipitationMm: Double? = null,
    val weatherCode: Int? = null,
    val isDay: Boolean? = null,
    val intervalSeconds: Int? = null,
    val dataTimeMillis: Long,
    val fetchedAtMillis: Long,
    val timezone: String
) {
    init {
        require(temperatureC.isFinite() && dataTimeMillis > 0 && fetchedAtMillis > 0) { "天气数据无效" }
    }
    fun isFresh(now: Long): Boolean = now - fetchedAtMillis in 0 until CACHE_MILLIS &&
        now - dataTimeMillis in -15 * 60_000L..90 * 60_000L

    companion object { const val CACHE_MILLIS = 30 * 60_000L }
}

data class WeatherState(
    val location: WeatherLocation? = null,
    val report: WeatherReport? = null,
    val loading: Boolean = false,
    val locating: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val searchResults: List<WeatherLocation> = emptyList(),
    val searching: Boolean = false,
    val searchError: String? = null,
    val selectedDate: LocalDate = LocalDate.now(),
    val forecast: WeatherForecast? = null
)

object WeatherCodes {
    fun description(code: Int?): String = when (code) {
        0 -> "晴"
        1 -> "大部晴朗"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "雾"
        51 -> "小毛毛雨"
        53 -> "毛毛雨"
        55 -> "强毛毛雨"
        56, 57 -> "冻毛毛雨"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        66, 67 -> "冻雨"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "雪粒"
        80 -> "小阵雨"
        81 -> "阵雨"
        82 -> "强阵雨"
        85, 86 -> "阵雪"
        95 -> "雷雨"
        96, 99 -> "雷雨伴冰雹"
        else -> "天气状况未知"
    }

    fun symbol(code: Int?, isDay: Boolean?): String = when (code) {
        0, 1 -> if (isDay == false) "🌙" else "☀️"
        2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "❄️"
        95, 96, 99 -> "⛈️"
        else -> "🌡️"
    }
}
