package io.todu.sview.lan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import io.todu.sview.MainActivity
import io.todu.sview.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** HTTP 与 FTP 共用前台通知；所有会话操作均在主线程执行，不持有唤醒锁。 */
class LocalEditorService : Service() {
    /** 一次启动独占一个会话，旧启动的清理不会关闭后来启动的编辑器。 */
    class Session internal constructor(val name: String, internal val stop: () -> Unit) {
        internal val ready = CompletableDeferred<Unit>()
        fun close() {
            sessions.remove(this)
            ready.cancel()
            active?.refresh()
        }
    }

    companion object {
        private const val CHANNEL = "local_editors"
        private const val NOTIFICATION = 1002
        private val sessions = linkedSetOf<Session>()
        private var active: LocalEditorService? = null

        /** 先确认前台服务已就绪，再允许控制器创建监听套接字。 */
        suspend fun acquire(context: Context, name: String, stop: () -> Unit): Session {
            val session = Session(name, stop)
            sessions.add(session)
            try {
                val service = active
                if (service == null) context.startForegroundService(Intent(context, LocalEditorService::class.java))
                else service.refresh()
                withTimeout(10_000) { session.ready.await() }
                return session
            } catch (error: Exception) {
                session.close()
                throw error
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "局域网编辑器", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        active = this
        // 即使等待期间被取消，也先履行 startForegroundService 的前台启动约定。
        try {
            startForeground(NOTIFICATION, notification())
            if (intent?.action == "stop") stopEditors() else refresh()
        } catch (error: Exception) {
            sessions.toList().forEach { it.ready.completeExceptionally(error) }
            stopEditors()
        }
        return START_NOT_STICKY
    }

    /** 最后一个会话释放时结束服务；任一编辑器仍在运行时只更新通知。 */
    private fun refresh() {
        if (sessions.isEmpty()) {
            active = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
            sessions.forEach { it.ready.complete(Unit) }
        }
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, LocalEditorService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.lucide_server_crash)
            .setContentTitle("局域网编辑器")
            .setContentText(sessions.joinToString("、") { it.name }.ifEmpty { "正在停止" })
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
    }

    private fun stopEditors() {
        val closing = sessions.toList()
        sessions.clear()
        closing.forEach { it.ready.cancel(); it.stop() }
        refresh()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopEditors()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 已正常停止的旧实例不能清理紧接着启动的新会话。
        if (active === this) {
            active = null
            stopEditors()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }
}
