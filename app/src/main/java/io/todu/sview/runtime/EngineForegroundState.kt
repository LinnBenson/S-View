package io.todu.sview.runtime

/** 为可见悬浮会话配对持有 Gecko 的运行计数，所有操作只在主线程执行。 */
internal class EngineForegroundState(
    initiallyResumed: Boolean,
    private val resumeNative: () -> Unit,
    private val pauseNative: () -> Unit
) {
    private val owners = mutableSetOf<Any>()
    private var initialBackground = !initiallyResumed
    val hasOverlay: Boolean get() = owners.isNotEmpty()

    init {
        // 内核在后台首次创建时收不到过去的 ON_PAUSE，补齐初始后台状态。
        if (initialBackground) pauseNative()
    }

    /** 多个窗口共享一份持有计数，重复登记不会改变内核状态。 */
    fun acquire(owner: Any) {
        if (owners.add(owner) && owners.size == 1) resumeNative()
    }

    /** 最后一个窗口释放时归还计数，后台恢复省电，前台仍由 Activity 保持运行。 */
    fun release(owner: Any) {
        if (owners.remove(owner) && owners.isEmpty()) pauseNative()
    }

    /** Gecko 首次 ON_RESUME 不减计数，只补偿本类额外设置的初始后台状态。 */
    fun processResumed() {
        if (!initialBackground) return
        initialBackground = false
        resumeNative()
    }
}
