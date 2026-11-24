package io.todu.sview.runtime.notifications

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/** 系统负责绑定；仅向活跃运行窗口转发通知，不保存历史或启动额外线程。 */
class NotificationMonitorService : NotificationListenerService() {
    interface Observer {
        fun started()
        fun message(value: JSONObject)
        fun failed(code: String, message: String)
    }

    companion object {
        private val observers = linkedSetOf<Observer>()
        private var connected: NotificationMonitorService? = null

        fun component(context: Context) = ComponentName(context, NotificationMonitorService::class.java)

        /** 通知使用权与发送通知权限独立，按组件检查用户授权。 */
        fun allowed(context: Context): Boolean = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component(context) }

        /** 主线程登记；未连接时请求系统绑定，不自行启动常驻服务。 */
        fun subscribe(context: Context, observer: Observer) {
            observers.add(observer)
            try {
                if (connected != null) observer.started() else requestRebind(component(context))
            } catch (error: Exception) {
                unsubscribe(observer)
                throw error
            }
        }

        fun unsubscribe(observer: Observer) {
            observers.remove(observer)
            if (observers.isEmpty()) {
                val service = connected
                connected = null
                service?.requestUnbind()
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (observers.isEmpty()) { requestUnbind(); return }
        connected = this
        observers.toList().forEach { it.started() }
    }

    override fun onNotificationPosted(notification: StatusBarNotification) = deliver(notification, "posted")
    override fun onNotificationRemoved(notification: StatusBarNotification) = deliver(notification, "removed")

    private fun deliver(notification: StatusBarNotification, type: String) {
        if (connected !== this || observers.isEmpty()) return
        try {
            val value = NotificationSnapshot.read(notification, type)
            observers.toList().forEach { it.message(value) }
        } catch (_: Exception) {
            // 不将第三方通知正文或异常内容写入日志。
            observers.toList().forEach { it.failed("READ_FAILED", "无法读取通知") }
        }
    }

    private fun disconnected() {
        if (connected !== this) return
        connected = null
        val previous = observers.toList()
        observers.clear()
        val granted = allowed(this)
        previous.forEach { it.failed(if (granted) "DISCONNECTED" else "PERMISSION_DENIED",
            if (granted) "系统通知监听已断开" else "通知使用权已关闭") }
    }

    override fun onListenerDisconnected() { disconnected(); super.onListenerDisconnected() }
    override fun onDestroy() { disconnected(); super.onDestroy() }
}
