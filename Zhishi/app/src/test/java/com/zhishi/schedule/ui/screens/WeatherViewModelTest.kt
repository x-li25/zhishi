package com.zhishi.schedule.ui.screens

import android.app.Application
import android.content.Context
import androidx.lifecycle.viewModelScope
import com.zhishi.schedule.data.WeatherClient
import com.zhishi.schedule.data.WeatherClientTest
import com.zhishi.schedule.data.WeatherLocationProvider
import com.zhishi.schedule.data.WeatherStore
import com.zhishi.schedule.domain.WeatherLocation
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WeatherViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var app: Application
    private val models = mutableListOf<WeatherViewModel>()
    private val beijing = WeatherLocation("北京", 39.9, 116.4)
    private val shanghai = WeatherLocation("上海", 31.2, 121.5)

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("zhishi_weather", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun cleanup() {
        models.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun `switching city cancels previous request and late result cannot overwrite selected city or cache`() = runBlocking {
        val oldStarted = CompletableDeferred<Call>()
        val oldFinished = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val client = WeatherClient(OkHttpClient.Builder().addInterceptor { chain ->
            val old = chain.request().url.queryParameter("latitude") == "39.9"
            if (old) {
                oldStarted.complete(chain.call())
                check(release.await(5, TimeUnit.SECONDS))
                oldFinished.complete(Unit)
            }
            response(chain.request(), weatherJson(if (old) 5 else 20))
        }.build())
        val model = model(client)
        try {
            model.selectCity(beijing)
            await { oldStarted.isCompleted }
            model.selectCity(shanghai)
            await { !model.state.value.loading }
            assertTrue(oldStarted.await().isCanceled())
            release.countDown()
            await { oldFinished.isCompleted }
            assertEquals(shanghai, model.state.value.location)
            assertEquals(shanghai, model.state.value.report!!.location)
            assertEquals(20.0, model.state.value.report!!.temperatureC, 0.001)
            assertEquals(shanghai, WeatherStore(app).location())
            assertEquals(shanghai, WeatherStore(app).report(shanghai)!!.location)
            assertNull(WeatherStore(app).report(beijing))
        } finally { release.countDown() }
    }

    @Test
    fun `fresh cache skips normal refresh and failed manual refresh retains weather with explicit error`() = runBlocking {
        val calls = AtomicInteger()
        val model = model(WeatherClient(OkHttpClient.Builder().addInterceptor { chain ->
            val number = calls.incrementAndGet()
            response(chain.request(), weatherJson(15), if (number == 1) 200 else 503)
        }.build()))
        model.selectCity(beijing)
        await { !model.state.value.loading }
        val cached = model.state.value.report
        assertNotNull(cached)
        model.refresh()
        dispatcher.scheduler.runCurrent()
        assertEquals(1, calls.get())
        model.refresh(force = true)
        await { !model.state.value.loading }
        assertEquals(2, calls.get())
        assertEquals(cached, model.state.value.report)
        assertTrue(model.state.value.error!!.contains("503"))
        assertEquals(cached, WeatherStore(app).report(beijing))
    }

    @Test
    fun `city query debounces and cancelled old search cannot replace newer candidates`() = runBlocking {
        val started = CompletableDeferred<Call>()
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val model = model(WeatherClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            val old = chain.request().url.queryParameter("name") == "北京"
            if (old) { started.complete(chain.call()); check(release.await(5, TimeUnit.SECONDS)) }
            response(chain.request(), if (old) """{"results":[{"name":"北京","latitude":39.9,"longitude":116.4}]}"""
                else """{"results":[{"name":"上海","latitude":31.2,"longitude":121.5}]}""")
        }.build()))
        try {
            model.searchCities("北")
            dispatcher.scheduler.advanceTimeBy(400)
            dispatcher.scheduler.runCurrent()
            assertEquals(0, calls.get())
            model.searchCities("北京")
            dispatcher.scheduler.advanceTimeBy(351)
            await { started.isCompleted }
            model.searchCities("上海")
            dispatcher.scheduler.advanceTimeBy(351)
            await { !model.state.value.searching }
            assertTrue(started.await().isCanceled())
            release.countDown()
            assertEquals(listOf("上海"), model.state.value.searchResults.map { it.name })
            assertEquals("上海", model.state.value.searchQuery)
        } finally { release.countDown() }
    }

    @Test
    fun `no default weather city means no network or automatic location permission request`() {
        val calls = AtomicInteger()
        val model = model(WeatherClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet(); response(chain.request(), weatherJson(15))
        }.build()))
        model.onForeground()
        dispatcher.scheduler.runCurrent()
        assertNull(model.state.value.location)
        assertFalse(model.state.value.loading)
        assertEquals(0, calls.get())
        model.locationDenied()
        assertTrue(model.state.value.error!!.contains("选择城市"))
    }

    @Test
    fun `leaving foreground cancels pending location and never follows it with weather request`() = runBlocking {
        val locating = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val provider = WeatherLocationProvider {
            locating.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        val model = model(WeatherClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet(); response(chain.request(), weatherJson(15))
        }.build()), provider)
        model.useCurrentLocation()
        await { locating.isCompleted }
        model.onBackground()
        await { cancelled.isCompleted }
        assertFalse(model.state.value.loading)
        assertFalse(model.state.value.locating)
        assertEquals(0, calls.get())
    }

    @Test
    fun `failed location keeps chosen city available for manual weather lookup`() = runBlocking {
        val model = model(WeatherClient(OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), weatherJson(15))
        }.build()), WeatherLocationProvider { throw IOException("请开启手机定位，或手动选择城市") })
        model.selectCity(beijing)
        await { !model.state.value.loading }
        val cached = model.state.value.report
        model.useCurrentLocation()
        await { !model.state.value.loading }
        assertEquals(beijing, model.state.value.location)
        assertEquals(cached, model.state.value.report)
        assertTrue(model.state.value.error!!.contains("定位"))
    }

    @Test
    fun `selected future date loads its forecast and returning to either day reuses matching fresh data`() = runBlocking {
        val requests = mutableListOf<Request>()
        val model = model(WeatherClient(OkHttpClient.Builder().addInterceptor { chain ->
            synchronized(requests) { requests += chain.request() }
            response(chain.request(), if (chain.request().url.queryParameter("hourly") == null) weatherJson(15)
                else forecastJson(LocalDate.parse(chain.request().url.queryParameter("start_date"))))
        }.build()))
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        model.selectCity(beijing)
        await { !model.state.value.loading }
        model.selectDate(tomorrow)
        await { !model.state.value.loading }
        assertEquals(tomorrow, model.state.value.selectedDate)
        val forecast = model.state.value.forecast!!
        assertEquals(tomorrow.atStartOfDay(ZoneId.systemDefault()).toInstant(), forecast.hours.first().time)
        assertEquals(beijing, forecast.location)
        model.selectDate(today)
        assertNotNull(model.state.value.report)
        assertNull(model.state.value.forecast)
        model.selectDate(tomorrow)
        dispatcher.scheduler.runCurrent()
        assertEquals(forecast, model.state.value.forecast)
        assertEquals(2, requests.size)
    }

    @Test
    fun `past and beyond forecast horizon show unavailable without fetching current weather instead`() = runBlocking {
        val calls = AtomicInteger()
        val model = model(WeatherClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet(); response(chain.request(), weatherJson(15))
        }.build()))
        model.selectDate(LocalDate.now().minusDays(1))
        model.selectCity(beijing)
        dispatcher.scheduler.runCurrent()
        assertTrue(model.state.value.error!!.contains("历史天气"))
        assertNull(model.state.value.forecast)
        assertNull(model.state.value.report)
        model.selectDate(LocalDate.now().plusDays(16))
        assertTrue(model.state.value.error!!.contains("超出预报范围"))
        assertEquals(0, calls.get())
        assertFalse(model.state.value.loading)
    }

    @Test
    fun `rapid day changes cancel old forecast and late response cannot overwrite selected date`() = runBlocking {
        WeatherStore(app).saveLocation(beijing)
        val first = LocalDate.now().plusDays(1)
        val second = first.plusDays(1)
        val oldStarted = CompletableDeferred<Call>()
        val oldFinished = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val model = model(WeatherClient(OkHttpClient.Builder().addInterceptor { chain ->
            val date = LocalDate.parse(chain.request().url.queryParameter("start_date"))
            if (date == first) {
                oldStarted.complete(chain.call())
                check(release.await(5, TimeUnit.SECONDS))
                oldFinished.complete(Unit)
            }
            response(chain.request(), forecastJson(date))
        }.build()))
        try {
            model.selectDate(first)
            await { oldStarted.isCompleted }
            model.selectDate(second)
            await { !model.state.value.loading }
            assertTrue(oldStarted.await().isCanceled())
            release.countDown()
            await { oldFinished.isCompleted }
            assertEquals(second, model.state.value.selectedDate)
            assertEquals(second.atStartOfDay(ZoneId.systemDefault()).toInstant(), model.state.value.forecast!!.hours.first().time)
        } finally { release.countDown() }
    }

    @Test
    fun `future forecast refresh failure retains only matching date and city cache`() = runBlocking {
        WeatherStore(app).saveLocation(beijing.copy(fromDevice = true))
        val calls = AtomicInteger()
        val model = model(WeatherClient(OkHttpClient.Builder().addInterceptor { chain ->
            val date = LocalDate.parse(chain.request().url.queryParameter("start_date"))
            response(chain.request(), forecastJson(date), if (calls.incrementAndGet() == 1) 200 else 503)
        }.build()))
        val tomorrow = LocalDate.now().plusDays(1)
        // 浏览未来日期沿用已选位置，不会调用测试中禁止的定位提供器。
        model.selectDate(tomorrow)
        await { !model.state.value.loading }
        val cached = model.state.value.forecast
        assertNotNull(cached)
        model.refresh(force = true)
        await { !model.state.value.loading }
        assertEquals(cached, model.state.value.forecast)
        assertTrue(model.state.value.error!!.contains("503"))
        model.selectDate(tomorrow.plusDays(1))
        await { !model.state.value.loading }
        assertNull(model.state.value.forecast)
        model.selectCity(shanghai)
        await { !model.state.value.loading }
        assertEquals(shanghai, model.state.value.location)
        assertNull(model.state.value.forecast)
    }

    private fun forecastJson(date: LocalDate) = buildJsonObject {
        put("timezone", ZoneId.systemDefault().id)
        put("hourly_units", buildJsonObject {
            put("time", "unixtime"); put("temperature_2m", "°C"); put("apparent_temperature", "°C")
            put("precipitation_probability", "%"); put("precipitation", "mm")
            put("wind_speed_10m", "m/s"); put("wind_gusts_10m", "m/s")
        })
        put("hourly", buildJsonObject {
            put("time", JsonArray((0..24).map { JsonPrimitive(date.atStartOfDay(ZoneId.systemDefault()).toEpochSecond() + it * 3600L) }))
            mapOf("temperature_2m" to 15.0, "apparent_temperature" to 14.0, "precipitation" to 0.0,
                "wind_speed_10m" to 2.0, "wind_gusts_10m" to 3.0).forEach { (key, value) ->
                put(key, JsonArray(List(25) { JsonPrimitive(value) }))
            }
            put("precipitation_probability", JsonArray(List(25) { JsonPrimitive(20) }))
            put("weather_code", JsonArray(List(25) { JsonPrimitive(0) }))
        })
    }.toString()

    private fun model(client: WeatherClient, location: WeatherLocationProvider = WeatherLocationProvider { error("Unexpected location access") }) =
        WeatherViewModel(app, client, location).also { models += it }

    private fun weatherJson(temperature: Int) = WeatherClientTest.currentJson
        .replace("1792935000", (System.currentTimeMillis() / 1000).toString())
        .replace("\"temperature_2m\":10.9", "\"temperature_2m\":$temperature")

    private fun response(request: Request, body: String, status: Int = 200) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture").body(body.toResponseBody()).build()

    private suspend fun await(condition: () -> Boolean) = withTimeout(3_000) {
        do {
            dispatcher.scheduler.runCurrent()
            if (condition()) break
            delay(5)
        } while (true)
    }
}
