package io.todu.sview.wear

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID

/** 每次同步写入独立目录，原子切换指针让项目、模板和数据库同时生效。 */
object WatchWorkspaceStore {
    private var active: File? = null

    @Synchronized fun root(context: Context): File {
        active?.let { return it }
        val watch = WatchDevice.isWatch(context)
        val pointer = AtomicFile(File(context.filesDir, if (watch) "watch-current" else "phone-current"))
        val name = try {
            pointer.openRead().bufferedReader().use { it.readText() }
        } catch (_: java.io.FileNotFoundException) {
            "initial"
        }
        require(name == "initial" || runCatching { UUID.fromString(name) }.isSuccess) { "工作区记录损坏" }
        val directory = if (!watch && name == "initial") context.filesDir else File(generations(context), name)
        if (name == "initial") directory.mkdirs()
        check(directory.isDirectory) { "工作区目录不存在" }
        return directory.also { active = it }
    }

    private fun generations(context: Context) = File(context.filesDir, if (WatchDevice.isWatch(context)) "watch-workspaces" else "phone-workspaces")

    fun newStaging(context: Context): File = File(generations(context), UUID.randomUUID().toString()).apply {
        check(mkdirs()) { "无法准备同步目录" }
    }

    @Synchronized fun activate(context: Context, directory: File) {
        val watch = WatchDevice.isWatch(context)
        check(directory.parentFile?.canonicalFile == generations(context).canonicalFile)
        UUID.fromString(directory.name)
        val pointer = File(context.filesDir, if (watch) "watch-current" else "phone-current")
        val pending = File(context.filesDir, "${pointer.name}.next")
        pending.outputStream().use { output ->
            output.write(directory.name.toByteArray())
            output.fd.sync()
        }
        // 重命名失败时保留旧指针，由调用方丢弃尚未启用的新目录。
        java.nio.file.Files.move(pending.toPath(), pointer.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        active = directory
    }

    /** 固定启动时的旧代次，延后清理不包含随后创建的同步目录。 */
    @Synchronized fun obsoleteAtStartup(context: Context): List<File> {
        val current = root(context)
        return generations(context).listFiles()?.filter { it != current }.orEmpty()
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
        generations(context).listFiles()?.filter { it != current && it != keep }?.forEach {
            if (!it.deleteRecursively()) android.util.Log.w("WatchSync", "旧同步目录暂未清理：${it.name}")
        }
    }
}
