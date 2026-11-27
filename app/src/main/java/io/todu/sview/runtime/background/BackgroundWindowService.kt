package io.todu.sview.runtime.background

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** 最后一个后台窗口恢复或关闭后，立即释放前台服务。 */
class BackgroundWindowService : Service() {
    private val active = linkedSetOf<BackgroundWindow>()
    private var primary: BackgroundWindow? = null

    override fun onCreate() { super.onCreate(); current = this }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val window = BackgroundWindow.from(intent)
        if (window != null) when (intent?.action) {
            "show" -> if (window.awaitingStart) try {
                val notification = window.notification()
                if (primary == null) {
                    startForeground(window.notificationId, notification)
                    primary = window
                } else getSystemService(android.app.NotificationManager::class.java).notify(window.notificationId, notification)
                active.add(window)
                window.started()
            } catch (error: Exception) { window.fail(error) }
            "restore" -> window.restore()
            "stop" -> window.stop()
        }
        if (active.isEmpty()) stopSelf(startId)
        return START_NOT_STICKY
    }

    internal fun remove(window: BackgroundWindow) {
        active.remove(window)
        if (primary === window) {
            primary = active.firstOrNull()
            primary?.let { startForeground(it.notificationId, it.notification()) }
                ?: stopForeground(STOP_FOREGROUND_REMOVE)
        }
        if (active.isEmpty()) stopSelf()
    }

    override fun onDestroy() {
        current = null
        active.toList().forEach { it.close() }
        active.clear()
        super.onDestroy()
    }

    companion object {
        internal var current: BackgroundWindowService? = null
            private set
    }
}
