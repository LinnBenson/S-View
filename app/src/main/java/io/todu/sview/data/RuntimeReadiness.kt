package io.todu.sview.data

import java.io.File

/** 就绪标记只在完整生成后发布；异常退出或保存失败时保留待检查状态。 */
class RuntimeReadiness(private val file: File, private val version: String, private val write: (File, String) -> Unit) {
    fun isReady(): Boolean = file.isFile && file.readText() == version
    fun markReady() = write(file, version)
    fun invalidate() { if (file.exists()) check(file.delete()) { "无法更新运行状态" } }

    fun <T> change(action: () -> T): T {
        val restore = isReady()
        invalidate()
        val result = action()
        // 若进入时已有未完成操作，本次局部保存不能冒充全量恢复完成。
        if (restore) markReady()
        return result
    }
}
