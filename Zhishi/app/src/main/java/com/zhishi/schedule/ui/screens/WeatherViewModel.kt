package com.zhishi.schedule.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhishi.schedule.data.DeviceWeatherLocation
import com.zhishi.schedule.data.WeatherClient
import com.zhishi.schedule.data.WeatherLocationProvider
import com.zhishi.schedule.data.WeatherStore
import com.zhishi.schedule.domain.WeatherLocation
import com.zhishi.schedule.domain.WeatherReport
import com.zhishi.schedule.domain.WeatherState
import com.zhishi.schedule.domain.WeatherForecast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class WeatherViewModel @JvmOverloads constructor(
    application: Application,
    private val client: WeatherClient = WeatherClient(),
    private val deviceLocation: WeatherLocationProvider = DeviceWeatherLocation(application)
) : AndroidViewModel(application) {
    private val store = WeatherStore(application)
    private val savedLocation = store.location()
    private val _state = MutableStateFlow(WeatherState(location = savedLocation, report = store.report(savedLocation)))
    val state = _state.asStateFlow()
    private var weatherJob: Job? = null
    private var searchJob: Job? = null
    private var foregroundJob: Job? = null
    private var weatherRequest = 0L
    private var searchRequest = 0L
    private data class ForecastKey(val location: WeatherLocation, val date: LocalDate, val zone: ZoneId)
    private val forecasts = linkedMapOf<ForecastKey, WeatherForecast>()

    fun selectDate(date: LocalDate) {
        if (date == _state.value.selectedDate) return
        weatherRequest++
        weatherJob?.cancel()
        val current = _state.value
        _state.value = current.copy(selectedDate = date, forecast = cachedForecast(current.location, date),
            loading = false, locating = false, error = null)
        refresh()
    }

    private fun cachedForecast(location: WeatherLocation?, date: LocalDate): WeatherForecast? =
        location?.let { forecasts[ForecastKey(it, date, ZoneId.systemDefault())] }

    private fun dateError(date: LocalDate): String? = when {
        date.isBefore(LocalDate.now()) -> "该日期已过去，暂不提供历史天气"
        date.isAfter(LocalDate.now().plusDays(15)) -> "该日期超出预报范围，暂时没有天气数据"
        else -> null
    }

    fun onForeground() {
        if (foregroundJob?.isActive == true) return
        foregroundJob = viewModelScope.launch {
            refresh()
            while (isActive) {
                delay(WeatherReport.CACHE_MILLIS)
                refresh()
            }
        }
    }

    fun onBackground() {
        foregroundJob?.cancel()
        foregroundJob = null
        if (_state.value.locating) {
            weatherRequest++
            weatherJob?.cancel()
            _state.value = _state.value.copy(loading = false, locating = false)
        }
    }

    fun searchCities(query: String) {
        val request = ++searchRequest
        searchJob?.cancel()
        _state.value = _state.value.copy(searchQuery = query, searchResults = emptyList(),
            searchError = null, searching = query.trim().length >= 2)
        if (query.trim().length < 2) return
        searchJob = viewModelScope.launch {
            delay(350)
            try {
                val results = client.searchCities(query)
                if (request == searchRequest) _state.value = _state.value.copy(searchResults = results, searching = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (request == searchRequest) _state.value = _state.value.copy(searching = false, searchError = failure.message ?: "城市搜索失败")
            }
        }
    }

    fun clearSearch() = searchCities("")

    fun selectCity(location: WeatherLocation) {
        weatherRequest++
        weatherJob?.cancel()
        val selected = location.copy(fromDevice = false)
        store.saveLocation(selected)
        _state.value = _state.value.copy(location = selected, report = store.report(selected),
            forecast = cachedForecast(selected, _state.value.selectedDate), loading = false,
            locating = false, error = null)
        clearSearch()
        refresh()
    }

    fun locationDenied() {
        weatherRequest++
        weatherJob?.cancel()
        _state.value = _state.value.copy(loading = false, locating = false,
            error = "未获定位权限，仍可通过“选择城市”查看天气")
    }

    fun useCurrentLocation() = load(useDevice = true)

    fun refresh(force: Boolean = false) {
        val current = _state.value
        val location = current.location ?: return
        if (current.loading) return
        dateError(current.selectedDate)?.let {
            _state.value = current.copy(forecast = null, error = it)
            return
        }
        if (!force) {
            if (current.selectedDate == LocalDate.now() && current.report?.isFresh(System.currentTimeMillis()) == true) return
            if (current.selectedDate != LocalDate.now() && current.forecast?.let {
                    System.currentTimeMillis() - it.fetchedAt.toEpochMilli() in 0 until WeatherReport.CACHE_MILLIS
                } == true) return
        }
        load(useDevice = location.fromDevice && current.selectedDate == LocalDate.now())
    }

    private fun load(useDevice: Boolean) {
        val request = ++weatherRequest
        val date = _state.value.selectedDate
        val zone = ZoneId.systemDefault()
        weatherJob?.cancel()
        _state.value = _state.value.copy(loading = true, locating = useDevice, error = null)
        weatherJob = viewModelScope.launch {
            try {
                val location = if (useDevice) deviceLocation.current() else _state.value.location ?: return@launch
                if (request != weatherRequest) return@launch
                store.saveLocation(location)
                _state.value = _state.value.copy(location = location, locating = false,
                    report = _state.value.report?.takeIf { it.location.samePlace(location) },
                    forecast = cachedForecast(location, date))
                val unavailable = dateError(date)
                if (unavailable != null) {
                    _state.value = _state.value.copy(loading = false, locating = false, error = unavailable)
                } else if (date == LocalDate.now()) {
                    val report = client.current(location)
                    if (request != weatherRequest) return@launch
                    store.saveReport(report)
                    _state.value = _state.value.copy(report = report, loading = false, locating = false, error = null)
                } else {
                    val forecast = client.forecast(location, date, date, zone)
                    if (request != weatherRequest) return@launch
                    forecasts[ForecastKey(location, date, zone)] = forecast
                    while (forecasts.size > 16) forecasts.remove(forecasts.keys.first())
                    _state.value = _state.value.copy(forecast = forecast, loading = false, locating = false, error = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (request == weatherRequest) _state.value = _state.value.copy(loading = false, locating = false,
                    error = failure.message ?: "天气获取失败，可稍后重试")
            }
        }
    }
}
