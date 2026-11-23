package io.todu.sview.runtime.api

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.location.LocationManagerCompat
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** 每个运行窗口持有自己的热点预约；系统只允许同一应用存在一个申请。 */
internal class HotspotApiBridge(context: Context, private val foreground: () -> Boolean, private val event: (JSONObject) -> Unit) : Closeable {
    private val context = context.applicationContext
    private val main = MainThreadCalls()
    private val handler = Handler(Looper.getMainLooper())
    private var active: Request? = null
    @Volatile private var closed = false

    private class Request(val id: String) {
        var finished = false
        var started = false
        val result = CompletableFuture<JSONObject>()
        var reservation: WifiManager.LocalOnlyHotspotReservation? = null
        var cancelled = false
    }

    fun invoke(json: JSONObject): JSONObject = try {
        val value = when (json.getString("method")) {
            "network.hotspot" -> start(json.getJSONObject("args").getString("id"))
            "network.hotspot.close" -> main.invoke {
                val id = json.getJSONObject("args").getString("id")
                // 旧对象再次 close 不得关闭后来创建的新热点。
                active?.takeIf { it.id == id }?.let(::cancel)
                JSONObject.NULL
            }
            else -> error("接口不存在")
        }
        JSONObject().put("ok", true).put("value", value)
    } catch (error: Exception) {
        ApiErrors.failure(error)
    }

    private fun start(id: String): JSONObject {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,64}"))) { "服务标识无效" }
        val request = main.invoke {
            check(!closed) { "运行窗口已关闭" }
            if (!foreground()) throw io.todu.sview.runtime.api.ApiException("NOT_FOREGROUND", "请在运行窗口位于前台时启动热点")
            requirePermissions()
            check(owner == null) { "已有项目正在启动、使用或关闭热点" }
            val wifi = context.getSystemService(WifiManager::class.java) ?: error("设备不支持 Wi-Fi 热点")
            val next = Request(id)
            active = next
            owner = next
            try { wifi.startLocalOnlyHotspot(callback(next), handler) }
            catch (error: Exception) { finish(next); throw error }
            next
        } as Request
        return try {
            request.result.get(30, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            handler.post { cancel(request) }
            throw ApiException("TIMEOUT", "启动热点超时")
        } catch (error: ExecutionException) {
            throw (error.cause as? Exception ?: error)
        } catch (error: InterruptedException) {
            handler.post { cancel(request) }
            Thread.currentThread().interrupt()
            throw IllegalStateException("热点申请已取消", error)
        }
    }

    private fun callback(request: Request) = object : WifiManager.LocalOnlyHotspotCallback() {
        override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
            request.reservation = reservation
            // 项目可能在系统启动期间已经退出，迟到的成功回调也必须马上释放。
            if (closed || request.cancelled || active !== request) {
                cancel(request)
                return
            }
            try {
                val (name, password) = credentials(reservation)
                check(name.isNotBlank()) { "系统未返回热点名称" }
                request.started = true
                request.result.complete(JSONObject().put("id", request.id).put("name", name).put("password", password))
            } catch (error: Exception) {
                request.result.completeExceptionally(error)
                cancel(request)
            }
        }
        override fun onStopped() {
            request.result.completeExceptionally(IllegalStateException("热点已被系统关闭"))
            finish(request)
        }
        override fun onFailed(reason: Int) {
            val message = when (reason) {
                ERROR_NO_CHANNEL -> "没有可用的热点信道"
                ERROR_INCOMPATIBLE_MODE -> "当前 Wi-Fi 或网络共享模式不允许启动本地热点"
                ERROR_TETHERING_DISALLOWED -> "系统不允许启动热点"
                else -> "设备无法启动本地热点"
            }
            if (request.started && !request.finished) {
                event(JSONObject().put("id", request.id).put("kind", "error")
                    .put("value", JSONObject().put("message", message)))
            }
            request.result.completeExceptionally(IllegalStateException(message))
            finish(request)
        }
    }

    @Suppress("DEPRECATION")
    private fun credentials(reservation: WifiManager.LocalOnlyHotspotReservation): Pair<String, String> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val config = reservation.softApConfiguration
            config.ssid.orEmpty() to config.passphrase.orEmpty()
        } else {
            val config = requireNotNull(reservation.wifiConfiguration) { "系统未返回热点配置" }
            config.SSID.orEmpty().removeSurrounding("\"") to config.preSharedKey.orEmpty().removeSurrounding("\"")
        }
    }

    private fun requirePermissions() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        io.todu.sview.runtime.api.checkPermission(context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= 33) "请在设置 → 权限中允许附近 Wi-Fi 设备" else "请在设置 → 权限中允许精确位置"
        }
        if (Build.VERSION.SDK_INT < 33) {
            val location = context.getSystemService(LocationManager::class.java)
            check(location != null && LocationManagerCompat.isLocationEnabled(location)) { "请开启系统位置服务" }
        }
    }

    /** 尚未成功的申请不能用公开 API 撤回，保留占位并等待回调，拿到预约后立即关闭。 */
    private fun cancel(request: Request) {
        request.cancelled = true
        request.result.completeExceptionally(IllegalStateException("热点申请已取消"))
        val reservation = request.reservation ?: return
        try { reservation.close() } finally { finish(request) }
    }

    /** 系统回调和主动关闭可能先后到达，只报告一次预约结束。 */
    private fun finish(request: Request) {
        if (request.finished) return
        request.finished = true
        if (request.started) event(JSONObject().put("id", request.id).put("kind", "closed"))
        request.reservation = null
        if (active === request) active = null
        if (owner === request) owner = null
    }

    override fun close() {
        if (closed) return
        try {
            main.invoke {
                closed = true
                active?.let(::cancel)
                JSONObject.NULL
            }
        } finally { main.close() }
    }

    companion object {
        // 仅在主线程访问，隔离不同项目，不能跨窗口关闭别人的预约。
        private var owner: Request? = null
    }
}
