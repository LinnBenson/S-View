package io.todu.sview.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import org.mozilla.gecko.GeckoAppShell
import org.mozilla.gecko.GeckoNetworkManager
import org.mozilla.gecko.GeckoThread

/** 补充 Gecko 只识别 Activity 的前后台判断，不修改进程生命周期或定时唤醒内核。 */
internal object BrowserEngineVisibility {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var state: EngineForegroundState
    private lateinit var context: Context
    private val process get() = ProcessLifecycleOwner.get().lifecycle
    private val resumed get() = process.currentState.isAtLeast(Lifecycle.State.RESUMED)

    /** 必须在 GeckoRuntime 初始化并注册自身监听后调用，且只注册一次。 */
    fun install(context: Context) {
        if (::state.isInitialized) return
        this.context = context.applicationContext
        state = EngineForegroundState(resumed, GeckoThread::onResume, GeckoThread::onPause)
        process.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) { state.processResumed() }
            override fun onPause(owner: LifecycleOwner) {
                // 生命周期向下分发按逆序执行；等 Gecko 停止监听后再按可见窗口恢复。
                main.post { syncBackgroundServices() }
            }
        })
    }

    fun acquire(owner: Any) {
        state.acquire(owner)
        syncBackgroundServices()
    }

    fun release(owner: Any) {
        if (!::state.isInitialized) return
        state.release(owner)
        syncBackgroundServices()
    }

    /** 悬浮窗口仍可见时保留网页需要的监听；关闭后立即恢复后台状态。 */
    private fun syncBackgroundServices() {
        if (resumed) return
        if (state.hasOverlay) {
            GeckoAppShell.resumeLocation()
            GeckoNetworkManager.getInstance().start(context)
        } else {
            GeckoAppShell.pauseLocation()
            GeckoNetworkManager.getInstance().stop()
        }
    }
}
