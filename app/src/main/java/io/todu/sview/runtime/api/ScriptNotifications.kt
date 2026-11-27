package io.todu.sview.runtime.api

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.todu.sview.MainActivity
import io.todu.sview.R
import io.todu.sview.data.Project
import java.util.concurrent.atomic.AtomicInteger

/** 所有项目共用通知渠道，权限与渠道设置遵循系统选择。 */
object ScriptNotifications {
    private const val CHANNEL = "script_notifications"
    private val sequence = AtomicInteger((System.currentTimeMillis() and 0x3fffffff).toInt())

    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun post(context: Context, project: Project, request: SystemApiRequest.Notify): Int {
        check(allowed(context)) { "请在设置 → 权限中允许通知" }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "脚本通知", NotificationManager.IMPORTANCE_DEFAULT))
        val id = sequence.incrementAndGet()
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.lucide_server_crash)
            .setContentTitle(request.title)
            .setContentText(request.message)
            .setSubText(project.name)
            .setStyle(Notification.BigTextStyle().bigText(request.message))
            .setContentIntent(intent).setAutoCancel(true).build()
        manager.notify(project.id, id, notification)
        return id
    }
}
