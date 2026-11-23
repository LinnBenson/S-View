package io.todu.sview.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** 项目配置与网页资源分开保存，资源目录不会暴露配置文件。 */
data class Project(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: String = "",
    val color: Int = 0,
    val pinned: Boolean = false,
    val width: WindowDimension = WindowDimension(360.0),
    val height: WindowDimension = WindowDimension(80.0, WindowDimension.Unit.PERCENT),
    val variables: List<ScriptVariable> = emptyList(),
    val references: List<HeadReference> = listOf(ReferenceCatalog.globalStyle.createReference(), ReferenceCatalog.globalScript.createReference()),
    val icon: String = "code",
    val location: WindowLocation = WindowLocation.CENTER,
    val cornerRadius: Double = 20.0,
    val backdropTransparency: Double = 40.0,
    val margins: WindowMargins = WindowMargins(),
    val shareSource: ShareSource = ShareSource.DISABLED
) {
    init {
        require(backdropTransparency.isFinite() && backdropTransparency in 0.0..100.0) { "遮罩透明度应为 0 到 100" }
        require(cornerRadius.isFinite() && cornerRadius >= 0 && cornerRadius <= Float.MAX_VALUE) { "圆角值应为非负数" }
    }
}

data class ScriptVariable(val name: String, val type: String, val value: String) {
    /** 将编辑内容转换为真正的 JS 值，对象与数组保留嵌套 JSON 类型。 */
    fun jsonValue(): Any = when (type) {
        "boolean" -> value.toBooleanStrict()
        "number" -> value.toDouble().also { require(it.isFinite()) { "请输入有效数字" } }
        "array" -> JSONArray(value)
        "object" -> JSONObject(value)
        "string" -> value
        else -> throw IllegalArgumentException("变量类型无效")
    }

    companion object {
        val TYPES = listOf("boolean", "string", "number", "array", "object")
    }
}

data class ResourceFile(val path: String, val directory: Boolean, val global: Boolean, val size: Long)

/** 所有读写由 ViewModel 的 IO 队列调度，界面不直接扫描磁盘。 */
class ProjectRepository(private val context: Context) {
    /** 下载到设备前统一检查系统存储授权。 */
    fun deviceStorageRoot(): File = io.todu.sview.storage.DeviceStorage.root(context)

    val temporaryFiles = io.todu.sview.storage.TemporaryFiles.get(context)

    private val workspace get() = io.todu.sview.wear.WatchWorkspaceStore.root(context)
    val templates get() = File(workspace, "templates")
    private val projects get() = File(workspace, "projects")
    private val catalog get() = File(workspace, "projects.json")
    private val categoryFile get() = File(workspace, "categories.json")

    private val runtimeReadiness get() = RuntimeReadiness(File(workspace, "runtime-ready"), RuntimeArtifactStore.CACHE_VERSION, ::writeAtomic)
    private var knownProjects: List<Project> = emptyList()
    private val runtimeArtifacts = RuntimeArtifactStore({ root(it).parentFile!! }, { id, path -> resolve(id, path) }, ::writeAtomic)

    fun initialize(): List<Project> {
        // 手表首次安装尚无网页数据，同步空工作区不必为清理而启动浏览器。
        if (io.todu.sview.wear.WatchDevice.isWatch(context) && workspace.name == "initial" &&
            !templates.exists() && !catalog.exists() && !File(context.filesDir, "templates").exists() &&
            !File(context.filesDir, "projects").exists()) {
            val state = context.getSharedPreferences("browser_storage_state", Context.MODE_PRIVATE)
            if (!state.contains("empty")) check(state.edit().putBoolean("empty", true).commit())
        }
        templates.mkdirs()
        projects.mkdirs()
        val template = File(templates, "index.html")
        if (!template.exists()) {
            runtimeReadiness.invalidate()
            writeAtomic(template, context.assets.open("templates/index.html").bufferedReader().use { it.readText() })
        }
        // 仅补齐缺失的全局文件，保留用户在模板编辑器中的修改。
        ReferenceCatalog.items.forEach { preset ->
            val file = File(templates, preset.fileName)
            if (!file.exists()) writeAtomic(file,
                context.assets.open("templates/${preset.fileName}").bufferedReader().use { it.readText() })
        }
        val existing = if (catalog.exists()) {
            val array = JSONArray(catalog.readText())
            List(array.length()) { ProjectJson.decode(array.getJSONObject(it)) }
        } else emptyList()
        knownProjects = existing
        if (!runtimeReadiness.isReady()) {
            // 上次写入中断时只补齐已有项目，新工作区保持为空。
            // 运行入口按签名检查目标项目，首页不批量重建运行文件。
            runtimeReadiness.markReady()
        }
        return existing
    }

    /** 分类独立保存，空分类也能保留。 */
    fun loadCategories(): List<String> {
        if (!categoryFile.exists()) {
            val defaults = listOf("工具", "生活")
            saveCategories(defaults)
            return defaults
        }
        val array = JSONArray(categoryFile.readText())
        return List(array.length()) { array.getString(it) }
    }

    fun saveCategories(categories: List<String>) {
        writeAtomic(categoryFile, JSONArray(categories).toString(2))
    }

    fun root(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        return File(projects, "$id/files")
    }

    /** 使用规范路径校验，导入与运行时均不可越过所属资源目录。 */
    fun scoped(root: File, path: String): File {
        val file = File(root, path.removePrefix("/")).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator) || file == root.canonicalFile) {
            "文件路径超出项目目录"
        }
        return file
    }

    fun resolve(id: String?, path: String): File {
        if (id == null) return scoped(templates, path)
        val local = scoped(root(id), path)
        return if (local.exists()) local else scoped(templates, path)
    }

    /** 正常启动不逐项检查产物，运行前仅验证选中项目，缺失时补齐。 */
    fun ensureRuntime(id: String) { knownProjects.find { it.id == id }?.let { runtimeArtifacts.update(it) } }

    /** 已校验的手表工作区切换后只更新索引，生成网页入口仍留到点击运行时。 */
    fun acceptWatchProjects(items: List<Project>) {
        check(io.todu.sview.wear.WatchDevice.isWatch(context))
        knownProjects = items.toList()
    }

    private fun <T> sourceChange(path: String, action: () -> T): T =
        if (path.removePrefix("/") in setOf("index.html", "main.js")) runtimeReadiness.change(action) else action()

    /** 文件接口提交源码后复用入口刷新逻辑。 */
    fun changeSource(id: String, path: String, action: () -> Unit) = sourceChange(path) {
        action()
        refreshRuntime(id, path)
    }

    fun runtimeFile(id: String, path: String): File = runtimeArtifacts.resolve(id, path) ?: resolve(id, path)

    /** 只在入口或主脚本变化时重建；全局模板变化更新使用它的项目。 */
    private fun refreshRuntime(id: String?, path: String) {
        if (path.removePrefix("/") !in setOf("index.html", "main.js")) return
        knownProjects.filter { if (id == null) !scoped(root(it.id), path).exists() else it.id == id }.forEach { runtimeArtifacts.update(it, changedPath = path.removePrefix("/")) }
    }

    fun list(id: String?, directory: String): List<ResourceFile> {
        val entries = linkedMapOf<String, ResourceFile>()
        fun collect(base: File, global: Boolean) {
            scoped(base, directory).listFiles()?.forEach { file ->
                val path = listOf(directory, file.name).filter(String::isNotEmpty).joinToString("/")
                entries[file.name] = ResourceFile(path, file.isDirectory, global, file.length())
            }
        }
        collect(templates, id != null)
        if (id != null) collect(root(id), false)
        // 主脚本由代码页编辑，内置库由引用项管理，不重复占用项目资源列表。
        return entries.values.filterNot { entry ->
            id != null && (entry.path == "main.js" || ReferenceCatalog.items.any { it.fileName == entry.path })
        }.sortedWith(compareByDescending<ResourceFile> { it.directory }.thenBy { it.path })
    }

    fun read(id: String?, path: String): String {
        val file = resolve(id, path)
        require(file.length() <= 2 * 1024 * 1024) { "文件超过 2 MB，无法在编辑器中打开" }
        return file.readText()
    }

    fun write(id: String?, path: String, text: String) = sourceChange(path) {
        writeAtomic(scoped(if (id == null) templates else root(id), path), text)
        refreshRuntime(id, path)
    }

    /** 局域网上传与文本保存共用原子写入，并刷新运行入口缓存。 */
    fun writeBytes(id: String?, path: String, bytes: ByteArray) = sourceChange(path) {
        val file = scoped(if (id == null) templates else root(id), path)
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
        refreshRuntime(id, path)
    }

    fun create(id: String?, path: String, directory: Boolean) = sourceChange(path) {
        require(path.substringAfterLast('/').isNotBlank()) { "请输入名称" }
        val file = scoped(if (id == null) templates else root(id), path)
        require(!file.exists()) { "同名文件已存在" }
        if (directory) check(file.mkdirs()) else writeAtomic(file, "")
        refreshRuntime(id, path)
    }

    /** 系统模板的删除保护放在存储层，项目自己的同名文件仍可删除。 */
    fun delete(id: String?, entry: ResourceFile) {
        require(!entry.global) { "请在设置中管理全局模板" }
        val base = if (id == null) templates else root(id)
        val file = scoped(base, entry.path)
        require(file != base.canonicalFile)
        if (id == null) require(ReferenceCatalog.systemTemplateNames.none { name ->
            val protected = scoped(templates, name)
            protected == file || protected.path.startsWith(file.path + File.separator)
        }) { "系统模板不能删除" }
        sourceChange(entry.path) {
            check(file.deleteRecursively()) { "删除失败" }
            refreshRuntime(id, entry.path)
        }
    }

    fun createProject(project: Project) {
        root(project.id).mkdirs()
        write(project.id, "main.js", """
            const app = document.querySelector( 'main#app' );
            app.classList.add( 'center' );
            app.style.width = '100vw';
            app.style.height = '100vh';
            app.innerHTML = `
                <h5>Hello World!</h5>
            `;
        """.trimIndent() + "\n")
    }

    /** 源文件事务回滚后废弃派生入口，不触碰项目的 Cookie 和网页存储。 */
    fun invalidateSourceCaches() {
        runtimeReadiness.invalidate()
        knownProjects.forEach { runtimeArtifacts.clear(it.id) }
    }

    /** 清理派生缓存及项目资源根目录的 cache，下次运行按需重新生成。 */
    fun clearRuntimeCaches() {
        io.todu.sview.runtime.api.LocalServerApiBridge.clearCaches(context)
        io.todu.sview.runtime.api.CustomServerApiBridge.clearCaches(context)
        runtimeReadiness.invalidate()
        knownProjects.forEach { clearProjectCaches(it.id) }
        temporaryFiles.cleanup()
        // 这些前缀仅在应用私有目录使用；事务恢复备份和安装器副本不属于此清理范围。
        projects.listFiles()?.filter { it.name.startsWith(".import-") }?.forEach {
            check(it.deleteRecursively()) { "无法清除导入临时目录" }
        }
        context.cacheDir.listFiles()?.filter {
            (it.name.startsWith("project-export-") || it.name.startsWith("lan-export-")) && !temporaryFiles.isActive(it)
        }?.forEach { check(it.delete()) { "无法清除导出临时文件" } }
    }

    /** 仅清理指定项目的派生文件、原生 Cookie 和资源缓存，不动其他项目或模板。 */
    fun clearProjectCaches(id: String) {
        require(knownProjects.any { it.id == id }) { "项目不存在" }
        runtimeArtifacts.clear(id)
        val network = networkCookies(id).parentFile!!
        check(!network.exists() || network.deleteRecursively()) { "无法清除项目网络缓存" }
        clearProjectResourceCache(id)
    }

    /** 仅删除根目录下的 cache 文件夹；不跟随其中的符号链接访问其他资源。 */
    private fun clearProjectResourceCache(id: String) {
        val cache = File(root(id), "cache").toPath()
        if (!Files.isDirectory(cache, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(cache, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** 原生 Cookie 属于项目缓存，删除项目时随项目目录一起移除。 */
    val transferChunkSize: Int get() = if (io.todu.sview.wear.WatchDevice.isWatch(context)) 262144 else 65536
    private fun runtimeScript(name: String): String = context.assets.open("runtime/$name.js").bufferedReader().use {
        it.readText().replace("__SVIEW_TRANSFER_CHUNK__", transferChunkSize.toString())
    }
    private val runtimeScript by lazy {
        buildString {
            append(RuntimeDocument.bootstrap()).append('\n')
            append(runtimeScript("network")).append('\n')
            append(runtimeScript("storage"))
        }.toByteArray()
    }
    fun runtimeScript() = runtimeScript

    fun networkCookies(id: String) = File(root(id).parentFile, "network/cookies.json")

    fun deleteProject(project: Project) { File(projects, project.id).deleteRecursively() }

    fun importResource(id: String?, directory: String, uri: Uri, name: String) {
        require(name.isNotBlank() && !name.contains('/') && !name.contains('\\')) { "文件名无效" }
        val path = listOf(directory, name).filter(String::isNotEmpty).joinToString("/")
        sourceChange(path) {
            val target = scoped(if (id == null) templates else root(id), path)
            require(!target.exists()) { "同名文件已存在" }
            target.parentFile?.mkdirs()
            val atomic = AtomicFile(target)
            val output = atomic.startWrite()
            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "无法读取文件" }
                    input.copyTo(output)
                }
                atomic.finishWrite(output)
            } catch (error: Exception) {
                atomic.failWrite(output)
                throw error
            }
            refreshRuntime(id, path)
        }
    }

    /** 先完成本地压缩，再写入用户选择的文档，避免压缩失败留下半个文件。 */
    fun exportProject(project: Project, uri: Uri) {
        val archive = temporaryFiles.create(context.cacheDir, "project-export-", ".zip")
        try {
            archive.outputStream().use { ProjectArchive.write(project, root(project.id), templates, it) }
            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法写入导出文件" }.use { output ->
                archive.inputStream().use { it.copyTo(output) }
            }
        } finally { temporaryFiles.release(archive) }
    }

    /** 暂存与校验全部完成后才提交；任何失败均清理新项目，不影响已有项目。 */
    fun importArchive(uri: Uri): Project {
        val staging = File(projects, ".import-${UUID.randomUUID()}")
        check(staging.mkdirs()) { "无法创建导入目录" }
        try {
            val project = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取压缩包" }.use {
                ProjectArchive.read(it, staging)
            }
            val destination = root(project.id)
            check(!destination.parentFile!!.exists() && destination.parentFile!!.mkdirs()) { "无法创建项目" }
            try {
                check(File(staging, "files").renameTo(destination)) { "无法保存导入的项目文件" }
                return project
            } catch (error: Exception) {
                destination.parentFile!!.deleteRecursively()
                throw error
            }
        } finally { staging.deleteRecursively() }
    }

    fun saveProjects(items: List<Project>) {
        val array = JSONArray()
        items.forEach { array.put(ProjectJson.encode(it)) }
        val old = knownProjects.associateBy { it.id }
        val changed = items.filter { old[it.id] == null || old[it.id]?.variables != it.variables || old[it.id]?.references != it.references }
        val save = {
            changed.forEach { runtimeArtifacts.update(it) }
            writeAtomic(catalog, array.toString(2))
            knownProjects = items.toList()
            // 项目名称、图标和删除结果及时同步到桌面，更新失败不回滚已保存配置。
            runCatching { io.todu.sview.widget.ProjectWidget.refresh(context) }
                .onFailure { android.util.Log.w("ProjectWidget", "刷新桌面快捷方式失败", it) }
        }
        if (changed.isEmpty()) save() else runtimeReadiness.change(save)
    }

    /** 原子替换防止应用被系统回收时留下半个配置或脚本。 */
    private fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(text.toByteArray()); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }

}
