package io.todu.sview.runtime.api

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build

/** 跳转交给系统选择接收应用，分享始终经过系统面板，不自动选择联系人或发送。 */
internal class SystemNavigation(private val context: Context) {
    fun openApp(packageName: String) {
        val manager = context.packageManager
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                manager.getLaunchIntentSenderForPackage(packageName).sendIntent(context, 0, null, null, null)
            } else {
                val intent = manager.getLaunchIntentForPackage(packageName)
                    ?: throw ActivityNotFoundException()
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (_: IntentSender.SendIntentException) {
            throw IllegalArgumentException("应用未安装或没有可打开的入口")
        } catch (_: ActivityNotFoundException) {
            throw IllegalArgumentException("应用未安装或没有可打开的入口")
        }
    }

    fun openLink(url: String) = launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    fun share(text: String, title: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (title.isNotEmpty()) intent.putExtra(Intent.EXTRA_SUBJECT, title)
        launch(Intent.createChooser(intent, "分享内容"))
    }

    private fun launch(intent: Intent) {
        try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: ActivityNotFoundException) { throw IllegalArgumentException("没有可处理此操作的应用") }
    }
}
