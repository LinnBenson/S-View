package io.todu.sview.widget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import io.todu.sview.MainActivity
import io.todu.sview.runtime.overlay.OverlayService

/** 桌面 PendingIntent 进入可见 Activity 后分流，避免后台启动限制及权限状态过期。 */
class WidgetLaunchActivity : Activity() {
    private var dispatched = false
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState) }
    override fun onResume() {
        super.onResume()
        if (dispatched) return
        dispatched = true
        val id = intent.getStringExtra("projectId")
        if (id != null && id.matches(Regex("[a-zA-Z0-9-]+"))) {
            val overlay = Settings.canDrawOverlays(this) && runCatching {
                startForegroundService(Intent(this, OverlayService::class.java).putExtra("projectId", id))
            }.isSuccess
            if (!overlay) startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("widgetRunToken", WidgetRunRequest.issue(id)))
        }
        finish()
    }
}
