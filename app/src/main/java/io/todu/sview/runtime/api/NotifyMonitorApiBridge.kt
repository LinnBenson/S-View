package io.todu.sview.runtime.api

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.todu.sview.runtime.notifications.NotificationMonitorService
import org.json.JSONObject
import java.io.Closeable

/** 通知监听归运行窗口所有；切后台保留，窗口释放时注销并取消启动超时。 */
internal class NotifyMonitorApiBridge(private val context: Context, private val publish: (JSONObject) -> Unit) : Closeable {
    private val main = MainThreadCalls()
    private val handler = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var disposed = false

    private inner class Session(val id: String) : NotificationMonitorService.Observer {
        var ready = false
        val timeout = Runnable { failed("START_TIMEOUT", "系统通知监听连接超时，请检查通知使用权") }
        override fun started() {
            if (session !== this || ready) return
            ready = true
            handler.removeCallbacks(timeout)
            emit("start", JSONObject.NULL)
        }
        override fun message(value: JSONObject) { if (session === this && ready) emit("message", value) }
        override fun failed(code: String, message: String) {
            if (session !== this) return
            emit("error", JSONObject().put("code", code).put("message", message))
            stop("error")
        }
        fun emit(type: String, value: Any) = publish(JSONObject().put("id", id).put("type", type).put("value", value))
    }

    fun invoke(request: JSONObject): JSONObject = try {
        val id = request.getJSONObject("args").getString("id")
        require(id.length in 1..100) { "无效的监听编号" }
        val value = main.invoke {
            when (request.getString("method")) {
                "system.notifyMonitor.start" -> start(id)
                "system.notifyMonitor.close" -> { if (session?.id == id) stop("manual"); JSONObject.NULL }
                else -> error("未知通知监听接口")
            }
        }
        JSONObject().put("ok", true).put("value", value)
    } catch (error: Exception) { ApiErrors.failure(error) }

    private fun start(id: String): Any {
        check(!disposed) { "运行窗口已关闭" }
        if (session != null) throw ApiException("ALREADY_LISTENING", "当前窗口已有通知监听")
        if (!NotificationMonitorService.allowed(context)) throw ApiException("PERMISSION_DENIED", "请在设置中授予通知使用权")
        val next = Session(id)
        session = next
        handler.postDelayed(next.timeout, 15_000)
        try { NotificationMonitorService.subscribe(context, next) }
        catch (error: Exception) { stop("error", false); throw error }
        return JSONObject.NULL
    }

    private fun stop(reason: String, notify: Boolean = true) {
        val previous = session ?: return
        session = null
        handler.removeCallbacks(previous.timeout)
        runCatching { NotificationMonitorService.unsubscribe(previous) }
        if (notify) previous.emit("close", reason)
    }

    override fun close() {
        disposed = true
        stop("windowClosed")
        main.close()
        handler.removeCallbacksAndMessages(null)
    }
}
