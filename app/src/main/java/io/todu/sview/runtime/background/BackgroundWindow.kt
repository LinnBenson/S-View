package io.todu.sview.runtime.background

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/** 仅在脚本请求收起时登记；所有状态切换均在主线程完成。 */
internal class BackgroundWindow(
    private val context: Context,
    private val activity: Activity?,
    private val name: String,
    private val visible: () -> Boolean,
    private val hide: (Boolean) -> Unit,
    val stop: () -> Unit,
) {
    private var token = ""
    var notificationId = 0
        private set
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var dynamic = false
    private var hidden = false
    private var restoreOnReturn = false
    private var pending: CompletableFuture<Boolean>? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { fail(IllegalStateException("启动后台运行超时")) }

    val requested: Boolean get() = hidden || pending != null

    fun change(enabled: Boolean, promote: Boolean): CompletableFuture<Boolean> {
        if (!enabled) return CompletableFuture.completedFuture(restore())
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "脚本后台运行", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
        })
        check(manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE) {
            "请先开启脚本后台运行通知"
        }
        dynamic = promote
        restoreOnReturn = false
        pending?.let { return it }
        if (hidden) {
            manager.notify(notificationId, notification())
            return CompletableFuture.completedFuture(true)
        }
        check(visible()) { "请在窗口可见时开启后台运行" }
        val result = CompletableFuture<Boolean>()
        pending = result
        // 每次收起分配独立标识，旧服务指令和通知按钮无法命中新请求。
        token = UUID.randomUUID().toString()
        notificationId = ids.incrementAndGet()
        windows[token] = this
        try {
            // 从可见窗口启动，服务成功发布通知后才解绑显示层。
            context.startService(command("show"))
            handler.postDelayed(timeout, 5000)
        } catch (error: Exception) { fail(error) }
        return result
    }

    val awaitingStart: Boolean get() = pending != null && !hidden

    fun started() {
        if (!awaitingStart) return
        handler.removeCallbacks(timeout)
        hidden = true
        hide(true)
        active = this
        pending?.complete(true)
        pending = null
    }

    fun fail(error: Exception) {
        pending?.completeExceptionally(error)
        pending = null
        close()
    }

    fun restore(): Boolean {
        if (hidden && !visible()) {
            restoreOnReturn = true
            return false
        }
        close()
        return true
    }

    fun onReturn() { if (restoreOnReturn) restore() }

    fun close() {
        handler.removeCallbacks(timeout)
        pending?.completeExceptionally(IllegalStateException("后台运行请求已结束"))
        pending = null
        restoreOnReturn = false
        if (windows.remove(token) == null) return
        if (active === this) active = null
        if (hidden) { hidden = false; hide(false) }
        BackgroundWindowService.current?.remove(this)
        manager.cancel(notificationId)
    }

    private fun command(action: String) = Intent(context, BackgroundWindowService::class.java)
        .setAction(action).putExtra(EXTRA, token)

    private fun restoreIntent(host: Activity) = Intent(host, host.javaClass)
        .setAction(RESTORE).putExtra(EXTRA, token)
        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** 首页也能找回分享入口创建的窗口，复用通知的运行实例标识。 */
    fun restoreFromHome(caller: Context) {
        if (activity == null || visible()) restore()
        else caller.startActivity(restoreIntent(activity))
    }

    fun notification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = if (activity == null) PendingIntent.getService(context, notificationId, command("restore"), flags)
        else PendingIntent.getActivity(activity, notificationId, restoreIntent(activity), flags)
        val stop = PendingIntent.getService(context, notificationId, command("stop"), flags)
        return Notification.Builder(context, CHANNEL).setSmallIcon(io.todu.sview.R.drawable.lucide_server_crash)
            .setContentTitle(name).setContentText("正在后台运行").setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setForegroundServiceBehaviorIfSupported()
            .addAction(Notification.Action.Builder(null, "停止", stop).build()).apply {
                if (Build.VERSION.SDK_INT >= 36 && dynamic) setRequestPromotedOngoing(true)
            }.build()
    }

    private fun Notification.Builder.setForegroundServiceBehaviorIfSupported() = apply {
        if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
    }

    companion object {
        var active by androidx.compose.runtime.mutableStateOf<BackgroundWindow?>(null)
            private set

        private const val CHANNEL = "script_background"
        private const val EXTRA = "backgroundWindowToken"
        private const val RESTORE = "io.todu.sview.RESTORE_WINDOW"
        private val ids = AtomicInteger(50000)
        private val windows = mutableMapOf<String, BackgroundWindow>()

        fun from(intent: Intent?): BackgroundWindow? = windows[intent?.getStringExtra(EXTRA)]
        fun accept(activity: Activity, intent: Intent): Boolean {
            if (intent.action != RESTORE) return false
            from(intent)?.takeIf { it.activity === activity }?.restore()
            return true
        }
    }
}
