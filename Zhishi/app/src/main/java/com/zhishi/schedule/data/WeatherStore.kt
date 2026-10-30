package com.zhishi.schedule.data

import android.content.Context
import com.zhishi.schedule.domain.WeatherLocation
import com.zhishi.schedule.domain.WeatherReport
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WeatherStore(context: Context) {
    private val prefs = context.getSharedPreferences("zhishi_weather", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun location(): WeatherLocation? = prefs.getString("location", null)?.let {
        runCatching { json.decodeFromString<WeatherLocation>(it) }.getOrNull()
    }
    fun report(location: WeatherLocation?): WeatherReport? = prefs.getString("report", null)?.let {
        runCatching { json.decodeFromString<WeatherReport>(it) }.getOrNull()
            ?.takeIf { report -> location != null && report.location.samePlace(location) }
    }
    fun saveLocation(location: WeatherLocation) {
        prefs.edit().putString("location", json.encodeToString(location)).apply()
    }
    fun saveReport(report: WeatherReport) {
        prefs.edit().putString("report", json.encodeToString(report)).apply()
    }
}
