package io.todu.sview.runtime

import android.util.Log

/** 每项释放单独隔离异常，确保后续资源仍有机会关闭。 */
internal fun cleanupResource(name: String, action: () -> Unit) {
    try {
        action()
    } catch (error: Exception) {
        Log.w("RuntimeCleanup", "释放失败：$name", error)
    }
}
