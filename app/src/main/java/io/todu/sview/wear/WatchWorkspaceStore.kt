package io.todu.sview.wear

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID

/** 每次同步写入独立目录，原子切换指针让项目、模板和数据库同时生效。 */
object WatchWorkspaceStore {
    private var active: File? = null

    @Synchronized fun root(context: Context): File {
        if (!WatchDevice.isWatch(context)) return context.filesDir
        return active ?: run {
            val pointer = AtomicFile(File(context.filesDir, "watch-current"))
            val name = if (pointer.baseFile.exists()) pointer.openRead().bufferedReader().use { it.readText() } else "initial"
            require(name == "initial" || runCatching { UUID.fromString(name) }.isSuccess) { "手表工作区记录损坏" }
            File(context.filesDir, "watch-workspaces/$name").also { it.mkdirs(); active = it }
        }
    }

    fun newStaging(context: Context): File = File(context.filesDir, "watch-workspaces/${UUID.randomUUID()}").apply {
        check(mkdirs()) { "无法准备同步目录" }
    }

    @Synchronized fun activate(context: Context, directory: File) {
        check(WatchDevice.isWatch(context))
        check(directory.parentFile?.canonicalFile == File(context.filesDir, "watch-workspaces").canonicalFile)
        UUID.fromString(directory.name)
        val pointer = AtomicFile(File(context.filesDir, "watch-current"))
        val output = pointer.startWrite()
        try { output.write(directory.name.toByteArray()); pointer.finishWrite(output) }
        catch (error: Exception) { pointer.failWrite(output); throw error }
        active = directory
    }

    /** 固定启动时的旧代次，延后清理不包含随后创建的同步目录。 */
    @Synchronized fun obsoleteAtStartup(context: Context): List<File> {
        if (!WatchDevice.isWatch(context)) return emptyList()
        val current = root(context)
        return File(context.filesDir, "watch-workspaces").listFiles()?.filter { it != current }.orEmpty()
    }

    /** 再次检查当前指针，不占用项目读写队列。 */
    @Synchronized fun cleanupObsolete(context: Context, candidates: List<File>) {
        val current = root(context)
        candidates.filter { it != current }.forEach {
            if (!it.deleteRecursively()) android.util.Log.w("WatchSync", "旧同步目录暂未清理：${it.name}")
        }
    }

    /** 只清理本应用的旧同步代次；正在接收的目录由调用方保留。 */
    fun cleanup(context: Context, keep: File? = null) {
        if (!WatchDevice.isWatch(context)) return
        val current = root(context)
        File(context.filesDir, "watch-workspaces").listFiles()?.filter { it != current && it != keep }?.forEach {
            if (!it.deleteRecursively()) android.util.Log.w("WatchSync", "旧同步目录暂未清理：${it.name}")
        }
    }
}
