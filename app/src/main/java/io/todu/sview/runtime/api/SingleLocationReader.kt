package io.todu.sview.runtime.api

import io.todu.sview.runtime.cleanupResource
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 只允许一个单次定位；无轮询，完成、超时、退到后台或关闭窗口都会取消传感器请求。 */
internal class SingleLocationReader(private val context: Context, private val foreground: () -> Boolean) : Closeable {
    private class Request {
        val signal = CancellationSignal()
        val result = CompletableFuture<JSONObject>()
    }
    private val handler = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(true)
    private val pending = AtomicReference<Request?>(null)

    fun read(timeout: Long): JSONObject {
        check(active.get()) { "运行窗口已关闭" }
        val request = Request()
        check(pending.compareAndSet(null, request)) { "正在获取位置，请等待本次请求结束" }
        handler.post { start(request) }
        return try {
            request.result.get(timeout, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            throw ApiException("TIMEOUT", "定位超时，请确认已开启位置服务")
        } catch (error: ExecutionException) {
            throw (error.cause as? Exception ?: error)
        } finally {
            request.signal.cancel()
            request.result.cancel(false)
            pending.compareAndSet(request, null)
        }
    }

    private fun start(request: Request) {
        try {
            check(active.get() && pending.get() === request && !request.signal.isCanceled) { "定位已取消" }
            if (!foreground()) throw io.todu.sview.runtime.api.ApiException("NOT_FOREGROUND", "请在运行窗口位于前台时获取位置")
            val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            checkPermission(fine || coarse) { "请在设置 → 权限中允许位置访问" }
            val manager = context.getSystemService(LocationManager::class.java)
            val provider = when {
                LocationManager.NETWORK_PROVIDER in manager.allProviders && manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                fine && LocationManager.GPS_PROVIDER in manager.allProviders && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> throw IllegalStateException("没有可用的位置来源，请开启位置服务或允许精确位置")
            }
            LocationManagerCompat.getCurrentLocation(manager, provider, request.signal, ContextCompat.getMainExecutor(context)) { location ->
                if (location == null) request.result.completeExceptionally(IllegalStateException("未获取到当前位置"))
                else runCatching { toJson(location) }.fold(request.result::complete, request.result::completeExceptionally)
            }
        } catch (error: Exception) { request.result.completeExceptionally(error) }
    }

    private fun toJson(location: Location): JSONObject {
        check(SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos <= TimeUnit.MINUTES.toNanos(1)) { "位置结果已过期，请重试" }
        return JSONObject().put("latitude", location.latitude).put("longitude", location.longitude)
            .put("accuracy", if (location.hasAccuracy()) location.accuracy else JSONObject.NULL)
            .put("altitude", if (location.hasAltitude()) location.altitude else JSONObject.NULL)
            .put("timestamp", location.time)
    }

    fun cancel() {
        pending.getAndSet(null)?.let {
            cleanupResource("定位取消信号") { it.signal.cancel() }
            it.result.completeExceptionally(IllegalStateException("定位已取消"))
        }
    }

    override fun close() {
        active.set(false)
        cleanupResource("单次定位") { cancel() }
        cleanupResource("定位回调") { handler.removeCallbacksAndMessages(null) }
    }
}
