package io.todu.sview.runtime

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.StorageController
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 引擎按需初始化；所有会话和存储操作由主线程管理。 */
internal object BrowserEngine {
    private var runtime: GeckoRuntime? = null
    val initialized: Boolean get() = runtime != null
    private val sessions = mutableMapOf<GeckoSession, String>()
    private fun storageState(context: Context) = context.getSharedPreferences("browser_storage_state", Context.MODE_PRIVATE)

    /** 启动准备在 IO 线程调用；已标记的后续会话不再同步写盘。 */
    @Synchronized
    fun prepareStorage(context: Context) {
        BrowserEngineConfiguration.prepare(context)
        val state = storageState(context)
        if (!state.contains("empty") || state.getBoolean("empty", false)) {
            check(state.edit().putBoolean("empty", false).commit()) { "无法记录网页存储状态" }
        }
    }

    private fun get(context: Context): GeckoRuntime = runtime ?: run {
        // 清理接口也可能首先初始化内核，仍须先撤销已清空标记。
        prepareStorage(context)
        GeckoRuntime.create(context.applicationContext, BrowserEngineConfiguration.settings(context)).also {
            runtime = it
            BrowserEngineVisibility.install(context)
        }
    }

    /** 同一项目复用持久化容器，不同项目即使地址相同也不共享网页数据。 */
    fun openSession(context: Context, projectId: String, overlay: Boolean = false): GeckoSession {
        prepareStorage(context)
        check(sessions.isEmpty()) { "已有项目正在运行" }
        val session = GeckoSession(GeckoSessionSettings.Builder().contextId(contextId(context, projectId)).build())
        val engine = get(context)
        try {
            if (overlay) BrowserEngineVisibility.acquire(session)
            session.open(engine)
        } catch (error: Exception) {
            BrowserEngineVisibility.release(session)
            throw error
        }
        sessions[session] = projectId
        return session
    }

    fun closeSession(session: GeckoSession) {
        try { session.close() } finally {
            sessions.remove(session)
            BrowserEngineVisibility.release(session)
        }
    }

    /** 删除前检查活动会话，避免源文件已经删除后才发现窗口仍在运行。 */
    fun isRunning(projectId: String): Boolean = projectId in sessions.values

    fun clearProject(context: Context, projectId: String) {
        check(projectId !in sessions.values) { "请先关闭项目运行窗口" }
        val previous = contextId(context, projectId)
        val engine = get(context)
        // 此接口不返回完成回调。后续运行使用新的容器，避免与旧容器的异步删除竞争。
        check(context.getSharedPreferences("project_browser_contexts", Context.MODE_PRIVATE).edit()
            .putString(projectId, "project:$projectId:${java.util.UUID.randomUUID()}").commit()) { "无法更新项目网页存储" }
        engine.storageController.clearDataForSessionContext(previous)
    }

    /** 清除完成后才返回，防止新会话在清理过程中重新写入数据。 */
    suspend fun clearAll(context: Context) {
        check(sessions.isEmpty()) { "请先关闭运行窗口" }
        // 仅跳过已经由引擎确认清空且此后未初始化的情况。旧版本状态未知时仍完整清理。
        if (runtime == null && storageState(context).getBoolean("empty", false)) return
        val result = get(context).storageController.clearData(StorageController.ClearFlags.ALL)
        suspendCancellableCoroutine<Unit> { continuation ->
            result.accept({ if (continuation.isActive) continuation.resume(Unit) },
                { error -> if (continuation.isActive) continuation.resumeWithException(error ?: IllegalStateException("无法清除网页缓存")) })
        }
        check(storageState(context).edit().putBoolean("empty", true).commit()) { "无法记录网页存储状态" }
    }

    /** 仅用于即将结束进程的主动退出，Gecko 关闭后不能在同进程重新初始化。 */
    fun shutdownForExit() {
        runtime?.shutdown()
    }

    private fun contextId(context: Context, projectId: String): String =
        context.getSharedPreferences("project_browser_contexts", Context.MODE_PRIVATE)
            .getString(projectId, "project:$projectId")!!
}
