package io.todu.sview.runtime.prompts

import android.content.Context
import android.net.Uri
import io.todu.sview.data.ResourceFile
import io.todu.sview.storage.DeviceStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** 扫描和过滤全部留在 IO 线程；每个条目检查取消，不读取界面没有展示的文件大小。 */
internal suspend fun loadPromptFiles(context: Context, request: WebPromptRequest, device: Boolean,
    path: String, accepted: Array<out String>): List<ResourceFile> = withContext(Dispatchers.IO) {
    val job = currentCoroutineContext()
    if (device) {
        val directory = request.repository.scoped(DeviceStorage.root(context), path)
        scanPromptDirectory(directory, path, accepted) { job.ensureActive() }
    } else {
        // 仓库已经排好序，过滤后无需再排序一次。
        request.repository.list(request.projectId, path).filter {
            job.ensureActive()
            it.directory || acceptsFile(it.path, accepted)
        }
    }
}

/** 系统单次目录枚举返回后，扫描、过滤和排序都响应调用方取消。 */
internal fun scanPromptDirectory(directory: File, path: String, accepted: Array<out String>, checkActive: () -> Unit): List<ResourceFile> {
    checkActive()
    val files = directory.listFiles() ?: throw IOException("无法读取目录")
    val entries = files.mapNotNull { file ->
        checkActive()
        val folder = file.isDirectory
        if (!folder && !acceptsFile(file.name, accepted)) null
        else ResourceFile(listOf(path, file.name).filter(String::isNotEmpty).joinToString("/"), folder, false, 0)
    }
    val order = compareByDescending<ResourceFile> { it.directory }.thenBy { it.path }
    return entries.sortedWith { left, right -> checkActive(); order.compare(left, right) }
}

/** 固定选中来源，文件删除后不能回退上传同名模板；检查和路径解析不阻塞界面。 */
internal suspend fun resolvePromptFiles(context: Context, request: WebPromptRequest, device: Boolean,
    selected: List<ResourceFile>): Array<Uri> = withContext(Dispatchers.IO) {
    val deviceRoot = if (device) DeviceStorage.root(context) else null
    selected.map { entry ->
        currentCoroutineContext().ensureActive()
        val root = deviceRoot ?: if (entry.global) request.repository.templates else request.repository.root(request.projectId)
        val file = request.repository.scoped(root, entry.path)
        require(file.isFile && file.canRead()) { "文件已移除或无法读取" }
        Uri.fromFile(file)
    }.toTypedArray()
}
