package io.todu.sview.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.Process

/** 任务退出且数据落盘后，结束本应用的引擎辅助进程和主进程。 */
internal object AppProcessExit {
    fun finishProcess(context: Context) {
        val manager = context.getSystemService(ActivityManager::class.java)
        val prefix = "${context.packageName}:"
        // 先记录同 UID、同包的子进程，避免 Gecko 关闭后残留的辅助进程脱离引擎管理。
        val children = manager.runningAppProcesses.orEmpty()
            .filter { it.uid == Process.myUid() && it.processName.startsWith(prefix) }
            .map { it.pid }
        try {
            BrowserEngine.shutdownForExit()
        } finally {
            children.forEach { Process.killProcess(it) }
            Process.killProcess(Process.myPid())
        }
    }
}
