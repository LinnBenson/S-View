package io.todu.sview.runtime

import android.content.Context
import org.mozilla.geckoview.GeckoRuntimeSettings
import java.io.File

/** 固定单窗口的进程策略；保留沙箱、站点隔离和各类辅助进程的默认设置。 */
internal object BrowserEngineConfiguration {
    private var configurationPath: String? = null
    private const val CONFIGURATION = "prefs:\n  dom.ipc.processPrelaunch.enabled: false\n  dom.ipc.processCount: 1\n"

    /** 通常由运行准备的 IO 线程调用；配置不变时不写盘，后续会话直接复用路径。 */
    @Synchronized
    fun prepare(context: Context): String {
        configurationPath?.let { return it }
        val file = File(context.noBackupFilesDir, "gecko-runtime.yaml")
        if (!file.exists() || file.readText() != CONFIGURATION) file.writeText(CONFIGURATION)
        return file.absolutePath.also { configurationPath = it }
    }

    /** 内部偏好项针对当前 GeckoView 版本验证，升级内核时需复核进程行为。 */
    fun settings(context: Context): GeckoRuntimeSettings = GeckoRuntimeSettings.Builder()
        .configFilePath(prepare(context))
        .extensionsProcessEnabled(false)
        .build()
}
