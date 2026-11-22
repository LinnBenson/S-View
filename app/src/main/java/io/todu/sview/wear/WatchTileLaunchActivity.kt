package io.todu.sview.wear

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import io.todu.sview.widget.WidgetRunRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 系统磁贴通过公开入口跳转，项目权限检查完成后交给应用内部的一次性运行请求。 */
class WatchTileLaunchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val id = intent.getStringExtra("projectId")
            val authorized = withContext(Dispatchers.IO) {
                runCatching {
                    WatchDevice.isWatch(this@WatchTileLaunchActivity) && id != null &&
                        intent.getStringExtra("token") == WatchTileStore.token(this@WatchTileLaunchActivity) &&
                        WatchTileStore.allowsLaunch(this@WatchTileLaunchActivity, id)
                }.getOrDefault(false)
            }
            if (authorized && id != null) startActivity(Intent(this@WatchTileLaunchActivity, WatchActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("widgetRunToken", WidgetRunRequest.issue(id)))
            finish()
        }
    }
}
