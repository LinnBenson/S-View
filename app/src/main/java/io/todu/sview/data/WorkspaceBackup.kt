package io.todu.sview.data

import android.content.Context
import android.net.Uri
import io.todu.sview.wear.WatchDatabaseSnapshot
import io.todu.sview.wear.WatchSyncArchive
import io.todu.sview.wear.WatchWorkspaceStore
import java.io.File

/** 项目源文件、模板和数据库使用同一份快照，系统文件选择器负责备份的位置。 */
internal class WorkspaceBackup(private val context: Context, private val repository: ProjectRepository) {
    private val database = SharedDatabase.get(context)

    fun write(uri: Uri, projects: List<Project>, categories: List<String>) = withArchive { archive ->
        WatchSyncArchive.write(archive, repository, projects, database.list(), emptySet(), categories)
        val output = requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法写入备份文件" }
        output.use { stream -> archive.inputStream().use { it.copyTo(stream) } }
        Unit
    }

    /** 在独立目录内还原所有内容，数据库写入成功后才一次切换工作区。 */
    fun restore(uri: Uri): WatchSyncArchive.Snapshot = withArchive { archive ->
        val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取备份文件" }
        input.use { stream ->
            archive.outputStream().use { output ->
                val buffer = ByteArray(65536)
                var total = 0L
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= WatchSyncArchive.BACKUP_BYTES) { "备份文件超过 8 GB" }
                    output.write(buffer, 0, count)
                }
            }
        }
        val previous = WatchWorkspaceStore.root(context)
        val staging = WatchWorkspaceStore.newStaging(context)
        try {
            val snapshot = try {
                WatchSyncArchive.read(archive, staging, backup = true)
            } catch (error: java.util.zip.ZipException) {
                throw IllegalArgumentException("备份 ZIP 已损坏或不完整", error)
            }
            WatchDatabaseSnapshot.write(File(staging, "shared-values.db"), snapshot.database)
            synchronized(database) {
                database.closeForSync()
                WatchWorkspaceStore.activate(context, staging)
                repository.acceptWorkspaceProjects(snapshot.projects)
            }
            // 首次还原前手机数据与其他应用文件共用根目录，只移除已被替换的工作区文件。
            if (previous == context.filesDir) {
                listOf("projects", "templates", "projects.json", "categories.json", "runtime-ready").forEach {
                    File(previous, it).deleteRecursively()
                }
                context.deleteDatabase("shared-values.db")
            } else previous.deleteRecursively()
            snapshot
        } finally {
            if (WatchWorkspaceStore.root(context) != staging) staging.deleteRecursively()
        }
    }

    private fun <T> withArchive(action: (File) -> T): T {
        val archive = repository.temporaryFiles.create(context.cacheDir, "workspace-backup-", ".zip")
        return try { action(archive) } finally { repository.temporaryFiles.release(archive) }
    }
}
