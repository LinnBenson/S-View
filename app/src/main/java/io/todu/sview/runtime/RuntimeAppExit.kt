package io.todu.sview.runtime

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.widget.Toast
import io.todu.sview.data.WorkspaceOwner
import io.todu.sview.runtime.overlay.OverlayService
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 应用级退出独立于页面协程，关闭最后一个运行窗口后仍能完成保存。 */
internal object RuntimeAppExit {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private class Window(val background: () -> Boolean, val close: suspend (Boolean?) -> Unit)
    private val windows = linkedMapOf<Any, Window>()
    private val replacementLock = Mutex()
    private var request: Job? = null

    val hasWindows: Boolean get() = windows.isNotEmpty()

    val hasForegroundWindows: Boolean get() = windows.values.any { !it.background() }

    /** 注册与移除均由运行窗口的主线程组合生命周期调用。 */
    fun register(token: Any, background: () -> Boolean, close: suspend (Boolean?) -> Unit) {
        windows[token] = Window(background, close)
    }
    fun unregister(token: Any) { windows.remove(token) }

    /** 运行按钮和通知恢复是两个入口。只有启动脚本时才等待旧后台窗口结束。 */
    suspend fun finishBackgroundRuns(nextOverlay: Boolean = false) = replacementLock.withLock {
        windows.toMap().forEach { (token, window) ->
            if (windows[token] === window && window.background()) window.close(nextOverlay)
        }
    }

    /** 复制注册表后逐个通知关闭，并等待会话和资源服务释放。 */
    suspend fun closeWindows() {
        windows.values.toList().forEach { it.close(null) }
    }

    /** 接口响应发出后调用；重复退出请求共用正在执行的退出流程。 */
    fun request(context: Context) {
        if (request?.isActive == true) return
        val application = context.applicationContext as Application
        request = scope.launch {
            val model = WorkspaceOwner.model(application)
            if (model.appExiting) return@launch
            try {
                model.prepareAppExit()
                application.stopService(Intent(application, OverlayService::class.java))
                application.getSystemService(ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
                WorkspaceOwner.viewModelStore.clear()
                AppProcessExit.finishProcess(application)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Toast.makeText(application, error.message ?: "无法退出应用", Toast.LENGTH_LONG).show()
            }
        }
    }
}
