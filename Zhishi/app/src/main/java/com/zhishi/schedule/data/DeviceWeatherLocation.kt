package com.zhishi.schedule.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.zhishi.schedule.domain.WeatherLocation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface WeatherLocationProvider { suspend fun current(): WeatherLocation }

/** 使用系统近似位置，不依赖 Google Play 定位服务，也不申请后台定位。 */
class DeviceWeatherLocation(private val context: Context) : WeatherLocationProvider {
    @SuppressLint("MissingPermission")
    override suspend fun current(): WeatherLocation {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw IOException("未获定位权限，可重新授权或手动选择城市")
        }
        val manager = context.getSystemService(LocationManager::class.java) ?: throw IOException("定位服务不可用，请手动选择城市")
        if (!LocationManagerCompat.isLocationEnabled(manager)) throw IOException("请开启手机定位，或手动选择城市")
        val provider = listOf(LocationManager.NETWORK_PROVIDER, "fused").firstOrNull {
            manager.allProviders.contains(it) && manager.isProviderEnabled(it)
        } ?: throw IOException("系统暂未提供可用的近似定位，请手动选择城市")
        val fix = withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine<android.location.Location> { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                if (!continuation.isActive) return@suspendCancellableCoroutine
                try {
                    LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                        if (continuation.isActive) {
                            if (location == null) continuation.resumeWithException(IOException("暂时无法定位，请重试或手动选择城市"))
                            else continuation.resume(location)
                        }
                    }
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        } ?: throw IOException("定位超时，请重试或手动选择城市")
        // 天气只需大致位置；发送与保存的坐标约为公里级，避免不必要的精细定位。
        val lat = String.format(Locale.US, "%.2f", fix.latitude).toDouble()
        val lon = String.format(Locale.US, "%.2f", fix.longitude).toDouble()
        return WeatherLocation("定位位置", lat, lon, String.format(Locale.CHINA, "%.2f, %.2f", lat, lon), fromDevice = true)
    }
}
