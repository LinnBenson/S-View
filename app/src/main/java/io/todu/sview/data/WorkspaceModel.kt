package io.todu.sview.data

import android.app.Application
import android.widget.Toast
import io.todu.sview.runtime.BrowserEngine
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 磁盘操作串行执行，状态只在主线程发布；关闭页面前也会提交编辑内容。 */
class WorkspaceModel(application: Application) : AndroidViewModel(application) {
    val repository = ProjectRepository(application)
    private val logStore = ProjectLogStore { repository.root(it).parentFile!! }
    var logText by mutableStateOf(""); private set
    var logProjectId by mutableStateOf<String?>(null); private set
    var logReady by mutableStateOf(false); private set
    var logSizes by mutableStateOf<Map<String, Long>>(emptyMap()); private set
    private var logGeneration = 0
    private val ioLock = Mutex()
    private val mutationLock = Mutex()
    var projects by mutableStateOf<List<Project>>(emptyList()); private set
    var categories by mutableStateOf<List<String>>(emptyList()); private set
    var files by mutableStateOf<List<ResourceFile>>(emptyList()); private set
    var logs by mutableStateOf<List<String>>(emptyList()); private set
    var archiveBusy by mutableStateOf(false); private set
    var archiveMessage by mutableStateOf<String?>(null)
    var clearingCache by mutableStateOf(false); private set
    var ready by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var projectId by mutableStateOf<String?>(null); private set
    var directory by mutableStateOf(""); private set
    var filePath by mutableStateOf("main.js"); private set
    var code by mutableStateOf(""); private set
    var saved by mutableStateOf(true); private set
    var editorKey by mutableStateOf(0); private set
    var fileReady by mutableStateOf(false); private set
    private var saveJob: Job? = null
    private var fileSaveToast: Toast? = null
    private var fileGeneration = 0
    private var resourceGeneration = 0
    private var closingEditor = false
    private var editorOpen = false
    val project: Project? get() = projects.find { it.id == projectId }

    private val initialization = CompletableDeferred<Unit>()

    /** 等待工作区初始化；失败直接传给调用方，调用方取消不取消共享初始化。 */
    suspend fun awaitReady() { initialization.await() }

    init { task {
        try {
            initializeWorkspace(application)
            initialization.complete(Unit)
        } catch (failure: Exception) {
            initialization.completeExceptionally(failure)
            throw failure
        }
    } }

    /** 项目和分类全部读取完成后才允许服务运行脚本。 */
    private suspend fun initializeWorkspace(application: Application) {
        projects = io { repository.initialize() }
        categories = if (io.todu.sview.wear.WatchDevice.isWatch(application)) emptyList() else io { repository.loadCategories() }
        val obsolete = io { io.todu.sview.wear.WatchWorkspaceStore.obsoleteAtStartup(application) }
        ready = true
        if (obsolete.isNotEmpty()) task {
            delay(1500)
            withContext(Dispatchers.IO) {
                io.todu.sview.wear.WatchWorkspaceStore.cleanupObsolete(application, obsolete)
            }
        }
    }

    init {
        viewModelScope.launch {
            androidx.compose.runtime.snapshotFlow { if (ready) projects else null }.collect { items ->
                if (items != null) io.todu.sview.share.ShareRegistration.update(application, items)
            }
        }
    }

    private suspend fun <T> io(action: () -> T): T = withContext(Dispatchers.IO) { ioLock.withLock { action() } }
    private fun task(action: suspend () -> Unit) = viewModelScope.launch {
        try { action() } catch (cancelled: CancellationException) { throw cancelled } catch (exception: Exception) { error = exception.message ?: "操作失败" }
    }

    private fun mutate(action: suspend () -> Unit) = task { mutationLock.withLock { action() } }

    /** 运行时只保存指定字段，不覆盖同时编辑的其他项目配置。 */
    fun resizeRunProject(id: String, width: WindowDimension, height: WindowDimension, completed: (Result<Unit>) -> Unit) =
        updateRunProject(id, completed) { it.copy(width = width, height = height) }

    fun locateRunProject(id: String, location: WindowLocation, completed: (Result<Unit>) -> Unit) =
        updateRunProject(id, completed) { it.copy(location = location) }

    fun marginRunProject(id: String, margins: WindowMargins, completed: (Result<Unit>) -> Unit) =
        updateRunProject(id, completed) { it.copy(margins = margins) }

    /** 圆角以 dp 写入现有配置字段，下次运行及项目导出沿用该值。 */
    fun cornerRadiusRunProject(id: String, radius: Double, completed: (Result<Unit>) -> Unit) =
        updateRunProject(id, completed) { it.copy(cornerRadius = radius) }

    /** 所有窗口配置共用修改锁，落盘成功后才更新列表并通知运行窗口。 */
    private fun updateRunProject(id: String, completed: (Result<Unit>) -> Unit, change: (Project) -> Project) {
        viewModelScope.launch {
            val result = try {
                mutationLock.withLock {
                    require(projects.any { it.id == id }) { "项目不存在" }
                    val next = projects.map { if (it.id == id) change(it) else it }
                    io { repository.saveProjects(next) }
                    projects = next
                }
                Result.success(Unit)
            } catch (cancelled: CancellationException) { completed(Result.failure(cancelled)); throw cancelled }
            catch (error: Exception) { Result.failure(error) }
            completed(result)
        }
    }

    /** 运行前等待最新脚本落盘，避免首次加载读到旧内容。 */
    fun prepareRun(id: String? = projectId, onReady: (Project) -> Unit) {
        if (watchSyncBusy || appExiting) return
        saveJob?.cancel()
        val current = projects.find { it.id == id } ?: return
        val editingId = projectId
        val path = filePath; val text = code; val needsSave = !saved && fileReady
        task {
            if (needsSave) persist(editingId, path, text)
            mutationLock.withLock {
                check(!watchSyncBusy && !appExiting && !clearingCache) { "请等待当前操作完成" }
                io { repository.ensureRuntime(current.id) }
                onReady(requireNotNull(projects.find { it.id == current.id }) { "项目不存在" })
            }
        }
    }

    private val logWriter = ProjectLogWriter(viewModelScope) { batch ->
        // 首页运行时只写日志文件，不在隐藏的编辑器中保留控制台正文。
        if (editorOpen) logs = (logs + batch.flatMap { it.message.lines() }).takeLast(200)
        io { batch.groupBy { it.projectId }.forEach { (id, records) -> logStore.appendBatch(id, records) } }
    }

    fun appendLog(project: Project, message: String) { logWriter.append(project.id, message) }
    fun flushLogWrites() = task { logWriter.flush() }

    /** 由主线程进入，沿用修改锁与 IO 队列，避免项目删除或同步切换时读错目录。 */
    suspend fun readProjectLog(currentId: String, name: String?): String = mutationLock.withLock {
        check(!watchSyncBusy && !clearingCache && !appExiting) { "请等待当前操作完成" }
        val id = ProjectLogTarget.resolve(projects, currentId, name)
        logWriter.flush()
        io { logStore.readAll(id) }
    }

    fun refreshLogSizes() = task {
        logWriter.flush()
        val ids = projects.map { it.id }
        logSizes = io { ids.associateWith(logStore::size) }
    }

    fun openLogs(id: String, query: String = "") {
        val generation = ++logGeneration
        logProjectId = id
        logReady = false
        logText = ""
        task {
            logWriter.flush()
            val content = io { logStore.search(id, query.trim()) }
            if (logProjectId == id && logGeneration == generation) { logText = content; logReady = true }
        }
    }

    /** 退出详情后释放正文，并使尚未结束的读取结果失效。 */
    fun closeLogs() {
        ++logGeneration
        logProjectId = null
        logText = ""
        logReady = false
    }

    fun clearProjectLogs(id: String) = task {
        ++logGeneration
        logWriter.flush()
        io { logStore.clear(id) }
        logSizes = logSizes + (id to 0L)
        if (logProjectId == id) { logText = ""; logReady = true }
    }

    fun exportLogs(id: String, uri: Uri) = task {
        logWriter.flush()
        io { getApplication<Application>().contentResolver.openOutputStream(uri)?.use { logStore.export(id, it) }
            ?: error("无法导出日志") }
    }

    /** 在同一写入队列读取手表快照，避免清理缓存或同步切换到一半。 */
    suspend fun readWatchLogs(id: String): org.json.JSONObject = mutationLock.withLock {
        check(io.todu.sview.wear.WatchDevice.isWatch(getApplication()))
        check(!watchSyncBusy && !clearingCache && !appExiting) { "手表正在处理其他操作" }
        logWriter.flush()
        val current = projects.toList()
        io {
            if (id.isEmpty()) {
                val entries = org.json.JSONArray()
                current.forEach { project ->
                    entries.put(org.json.JSONObject().put("id", project.id).put("name", project.name)
                        .put("size", logStore.size(project.id)))
                }
                org.json.JSONObject().put("projects", entries)
            } else {
                check(current.any { it.id == id }) { "手表项目已移除，请刷新列表" }
                org.json.JSONObject().put("text", logStore.read(id))
            }
        }
    }

    fun clearLogs() { logs = emptyList() }

    fun openProject(id: String?) {
        flush()
        editorOpen = true
        projectId = id
        directory = ""
        refresh()
        openFile(if (id == null) "index.html" else "main.js")
    }

    fun refresh(path: String = directory) {
        directory = path
        val id = projectId
        val generation = ++resourceGeneration
        task { val result = io { repository.list(id, path) }; if (generation == resourceGeneration && id == projectId && path == directory) files = result }
    }

    fun openFile(path: String) {
        flush()
        val generation = ++fileGeneration
        val id = projectId
        fileReady = false
        task {
            val content = io { repository.read(id, path) }
            if (generation == fileGeneration) {
                filePath = path; code = content; saved = true; editorKey++; fileReady = true
            }
        }
    }

    /** 防抖合并输入；切换文件时使用已捕获的项目与路径，防止写入新文件。 */
    fun changeCode(text: String) {
        code = text; saved = false
        saveJob?.cancel()
        val id = projectId; val path = filePath
        saveJob = task { delay(650); persist(id, path, text) }
    }

    fun flush() {
        flushLogWrites()
        saveJob?.cancel()
        if (!saved && fileReady) {
            val id = projectId; val path = filePath; val text = code
            task { persist(id, path, text) }
        }
    }

    /** 只有明确点击保存才显示反馈，自动保存与页面切换保持静默。 */
    fun saveManually() {
        if (!fileReady) return
        saveJob?.cancel()
        val id = projectId
        val path = filePath
        val text = code
        task {
            persist(id, path, text)
            fileSaveToast?.cancel()
            fileSaveToast = Toast.makeText(getApplication(), "${path.substringAfterLast('/')} 已保存", Toast.LENGTH_SHORT)
                .also { it.show() }
        }
    }

    /** 保存成功才退出；保存失败时仍保留编辑内容供重试。 */
    fun closeEditor(onClosed: () -> Unit) = task {
        if (closingEditor) return@task
        closingEditor = true
        try {
            saveJob?.cancel()
            // IO 期间用户仍可能输入，直到最新内容已落盘再释放正文。
            while (!saved && fileReady) {
                persist(projectId, filePath, code)
            }
            saveJob?.cancel()
            saveJob = null
            ++fileGeneration
            ++resourceGeneration
            editorOpen = false
            fileReady = false
            code = ""
            files = emptyList()
            logs = emptyList()
            projectId = null
            directory = ""
            filePath = "main.js"
            editorKey++
            flushLogWrites()
            onClosed()
        } finally {
            closingEditor = false
        }
    }

    private suspend fun persist(id: String?, path: String, text: String) {
        io { repository.write(id, path, text) }
        if (id == projectId && path == filePath && text == code) {
            saved = true
            if (files.any { it.path == path && it.global }) refresh()
        }
    }

    fun updateProject(value: Project) = mutate {
        val old = projects
        val next = if (old.any { it.id == value.id }) old.map { if (it.id == value.id) value else it } else old + value
        io { if (old.none { it.id == value.id }) repository.createProject(value); repository.saveProjects(next) }
        projects = next
        if (value.category.isNotBlank() && value.category !in categories) {
            val updated = categories + value.category
            io { repository.saveCategories(updated) }
            categories = updated
        }
    }

    fun deleteProject(value: Project) = mutate {
        val next = projects.filterNot { it.id == value.id }
        logWriter.flush()
        BrowserEngine.clearProject(getApplication(), value.id)
        io { repository.saveProjects(next); repository.deleteProject(value) }
        projects = next
    }

    /** 项目菜单的清理入口复用写入锁；隔离旧网页容器后清理磁盘和界面日志。 */
    fun clearProjectCaches(project: Project) = mutate {
        require(projects.any { it.id == project.id }) { "项目不存在" }
        check(!BrowserEngine.isRunning(project.id)) { "请先关闭该项目的运行窗口" }
        logWriter.flush()
        BrowserEngine.clearProject(getApplication(), project.id)
        io {
            repository.clearProjectCaches(project.id)
            logStore.clear(project.id)
        }
        logSizes = logSizes + (project.id to 0L)
        if (logProjectId == project.id) {
            ++logGeneration
            logText = ""
            logReady = true
        }
        Toast.makeText(getApplication(), "${project.name}缓存已清除", Toast.LENGTH_SHORT).show()
    }

    /** 清理期间串行阻止新运行，先等日志落盘，再删除网页数据与生成缓存。 */
    fun clearCaches(onComplete: () -> Unit) {
        if (clearingCache) return
        if (archiveBusy) { error = "请等待导入或导出完成"; return }
        clearingCache = true
        task {
            try {
                mutationLock.withLock {
                    BrowserEngine.clearAll(getApplication())
                    logWriter.flush()
                    ++logGeneration
                    io {
                        projects.forEach { logStore.clear(it.id) }
                        repository.clearRuntimeCaches()
                    }
                    logs = emptyList()
                    logText = ""
                    logSizes = projects.associate { it.id to 0L }
                    logReady = true
                }
                onComplete()
            } finally { clearingCache = false }
        }
    }

    /** 拖动时仅更新内存顺序，让位动画不等待磁盘；松手后才保存。 */
    fun reorder(from: String, to: String) {
        val list = projects.toMutableList()
        val a = list.indexOfFirst { it.id == from }; val b = list.indexOfFirst { it.id == to }
        if (a >= 0 && b >= 0 && a != b && list[a].pinned == list[b].pinned) {
            list.add(b, list.removeAt(a))
            projects = list
        }
    }

    /** 引用拖动只改内存，沿用项目保存队列在松手时一次落盘。 */
    fun reorderReference(id: String, from: String, to: String) {
        val current = projects.find { it.id == id } ?: return
        val references = current.references.toMutableList()
        val start = references.indexOfFirst { it.id == from }
        val target = references.indexOfFirst { it.id == to }
        if (start < 0 || target < 0 || start == target) return
        references.add(target, references.removeAt(start))
        projects = projects.map { if (it.id == id) it.copy(references = references) else it }
    }

    fun saveProjectOrder() = mutate { io { repository.saveProjects(projects) } }

    fun reorderCategory(from: String, to: String) {
        val list = categories.toMutableList()
        val a = list.indexOf(from); val b = list.indexOf(to)
        if (a >= 0 && b >= 0 && a != b) {
            list.add(b, list.removeAt(a))
            categories = list
        }
    }

    fun saveCategoryOrder() = mutate { io { repository.saveCategories(categories) } }

    fun saveCategory(name: String, previous: String?) {
        val value = name.trim()
        require(value.isNotEmpty() && value != "全部") { "请输入有效的分类名称" }
        require(value == previous || value !in categories) { "分类名称已存在" }
        mutate {
            val nextCategories = if (previous == null) categories + value else categories.map { if (it == previous) value else it }
            val nextProjects = projects.map { if (it.category == previous) it.copy(category = value) else it }
            io { repository.saveProjects(nextProjects); repository.saveCategories(nextCategories) }
            projects = nextProjects
            categories = nextCategories
        }
    }

    fun deleteCategory(name: String) {
        mutate {
            val nextProjects = projects.map { if (it.category == name) it.copy(category = "") else it }
            val nextCategories = categories.filterNot { it == name }
            io { repository.saveProjects(nextProjects); repository.saveCategories(nextCategories) }
            projects = nextProjects
            categories = nextCategories
        }
    }

    fun createFile(name: String, folder: Boolean) {
        val id = projectId ?: return; val dir = directory
        task { io { repository.create(id, listOf(dir, name).filter(String::isNotEmpty).joinToString("/"), folder) }; refresh() }
    }

    fun deleteFile(entry: ResourceFile) {
        val id = projectId
        task { io { repository.delete(id, entry) }; refresh() }
    }

    var restoringTemplate by mutableStateOf(false); private set

    /** 单文件与整组还原共用保存屏障，避免编辑器将旧内容写回。 */
    fun restoreTemplate(entry: ResourceFile) {
        if (!entry.directory) restoreTemplates(listOf(entry.path))
    }

    /** 只还原内置文件清单，自建模板与项目覆盖文件保持原样。 */
    fun restoreAllTemplates() = restoreTemplates(ReferenceCatalog.systemTemplateNames.toList())

    private fun restoreTemplates(paths: List<String>) {
        if (projectId != null || restoringTemplate) return
        restoringTemplate = true
        val currentPath = filePath
        val current = currentPath in paths
        val generation = if (current) ++fileGeneration else fileGeneration
        val wasReady = fileReady
        if (current) {
            saveJob?.cancel()
            fileReady = false
        }
        task {
            try {
                for (path in paths) {
                    val content = io { repository.restoreTemplate(path) }
                    // 每个文件落盘后立即同步缓冲，后续文件失败也不会恢复旧编辑内容。
                    if (current && path == currentPath && projectId == null && generation == fileGeneration) {
                        code = content; saved = true; editorKey++
                    }
                }
                Toast.makeText(getApplication(), if (paths.size == 1) "${paths.first()} 已还原" else "系统模板已还原", Toast.LENGTH_SHORT).show()
            } finally {
                if (projectId == null) refresh()
                if (current && projectId == null && generation == fileGeneration) fileReady = wasReady
                restoringTemplate = false
            }
        }
    }

    fun importFile(uri: Uri, name: String) {
        val id = projectId; val dir = directory
        task { io { repository.importResource(id, dir, uri, name) }; refresh() }
    }

    /** ZIP 恢复完整项目，仍保留原有单脚本与 HTML 导入入口。 */
    fun importProject(uri: Uri, name: String) = mutate {
        archiveBusy = true
        try {
            val value = io {
                if (name.endsWith(".zip", true)) repository.importArchive(uri)
                else {
                    require(name.substringAfterLast('.', "").lowercase() in setOf("js", "html", "htm", "txt")) { "请选择项目 ZIP、JavaScript 或 HTML 文件" }
                    val created = Project(name = name.substringBeforeLast('.').ifBlank { "新项目" })
                    try {
                        repository.root(created.id).mkdirs()
                        repository.importResource(created.id, "", uri, if (name.endsWith(".html", true) || name.endsWith(".htm", true)) "index.html" else "main.js")
                        created
                    } catch (error: Exception) { repository.deleteProject(created); throw error }
                }
            }
            val next = projects + value
            try { io { repository.saveProjects(next) } }
            catch (error: Exception) { io { repository.deleteProject(value) }; throw error }
            projects = next
            if (value.category.isNotBlank() && value.category !in categories) {
                val updated = categories + value.category
                io { repository.saveCategories(updated) }
                categories = updated
            }
            archiveMessage = "已导入 ${value.name}"
        } finally { archiveBusy = false }
    }

    /** 导出前提交待保存的代码，配置与资源在同一 IO 队列中读取。 */
    fun exportProject(id: String, uri: Uri) = mutate {
        archiveBusy = true
        try {
            saveJob?.cancel()
            if (!saved && fileReady) persist(projectId, filePath, code)
            val value = projects.firstOrNull { it.id == id } ?: error("项目不存在")
            io { repository.exportProject(value, uri) }
            archiveMessage = "项目已导出"
        } finally { archiveBusy = false }
    }

    private val database by lazy { SharedDatabase.get(getApplication()) }
    var watchSyncBusy by mutableStateOf(false); private set
    var appExiting by mutableStateOf(false); private set
    var stopWatchRun: (suspend () -> Unit)? = null
    private val watchConnectionDelegate = lazy { io.todu.sview.wear.WatchConnection(getApplication(), viewModelScope, ::createWatchSnapshot) }
    val watchConnection get() = watchConnectionDelegate.value
    fun closeWatchConnection() { if (watchConnectionDelegate.isInitialized()) watchConnection.close() }

    /** 主动退出前等正在提交的同步和缓存操作结束，禁止新的同步与项目启动。 */
    suspend fun prepareAppExit() {
        appExiting = true
        try {
            androidx.compose.runtime.snapshotFlow { !watchSyncBusy && !clearingCache && !archiveBusy }.first { it }
            io.todu.sview.runtime.RuntimeAppExit.closeWindows()
            mutationLock.withLock {
                saveJob?.cancel()
                if (!saved && fileReady) persist(projectId, filePath, code)
                logWriter.flush()
                io { Unit } // 等待此前排队的磁盘写入完成。
            }
            closeWatchConnection()
            lanEditor.stop()
            stopFtpEditor()
        } catch (failure: Exception) {
            appExiting = false
            throw failure
        }
    }

    /** 手机端先保存编辑内容，项目、模板与共享库在同一快照中打包。 */
    private suspend fun createWatchSnapshot(ids: List<String>, expandedIds: Set<String>, tileIds: Set<String>): java.io.File = mutationLock.withLock {
        saveJob?.cancel()
        if (!saved && fileReady) persist(projectId, filePath, code)
        val selected = ids.map { id ->
            val project = projects.find { it.id == id } ?: error("项目已删除，请重新选择")
            // 只改同步快照，手机项目配置及关闭扩展后的同步仍使用原始值。
            if (id in expandedIds) project.copy(
                width = WindowDimension(100.0, WindowDimension.Unit.PERCENT),
                height = WindowDimension(100.0, WindowDimension.Unit.PERCENT),
                margins = WindowMargins()
            ) else project
        }
        io {
            val archive = repository.temporaryFiles.create(getApplication<Application>().cacheDir, "watch-export-", ".zip")
            try {
                io.todu.sview.wear.WatchSyncArchive.write(archive, repository, selected, database.list(), tileIds)
                archive
            } catch (error: Exception) { repository.temporaryFiles.release(archive); throw error }
        }
    }

    /** 接收锁覆盖解压到发布期间，新运行不会读到替换中的资源。 */
    fun beginWatchSync() {
        check(!watchSyncBusy && !clearingCache && !appExiting) { "手表正在处理其他操作" }
        watchSyncBusy = true
    }

    fun endWatchSync() { watchSyncBusy = false }

    suspend fun applyWatchSnapshot(staging: java.io.File, incoming: List<Project>) = mutationLock.withLock {
        check(io.todu.sview.wear.WatchDevice.isWatch(getApplication()))
        stopWatchRun?.invoke()
        logWriter.flush()
        // 同步只更新工作区，保留浏览器数据；网页数据仍由手动清除缓存入口清理。
        io {
            // 同步只替换资源；仍保留的项目继续使用原有运行日志。
            incoming.forEach { project ->
                val previous = java.io.File(repository.root(project.id).parentFile, "runtime.log")
                val next = java.io.File(staging, "projects/${project.id}/runtime.log")
                if (previous.isFile) previous.copyTo(next, overwrite = true)
            }
            database.closeForSync()
            io.todu.sview.wear.WatchWorkspaceStore.activate(getApplication(), staging)
            repository.acceptWatchProjects(incoming)
        }
        io.todu.sview.wear.WatchProjectTileService.refresh(getApplication())
        projects = incoming
        categories = emptyList()
        io { io.todu.sview.wear.WatchWorkspaceStore.cleanup(getApplication()) }
    }
    var databaseEntries by mutableStateOf<List<ScriptVariable>>(emptyList()); private set
    var databaseReady by mutableStateOf(false); private set

    /** 只在数据库页订阅变化；脚本写入后列表刷新，不做定时轮询。 */
    suspend fun watchDatabase() {
        databaseReady = false
        try {
            database.changes.collect {
                databaseEntries = io { database.list() }
                databaseReady = true
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (exception: Exception) { error = exception.message ?: "读取数据库失败" }
        finally { databaseEntries = emptyList(); databaseReady = false }
    }

    fun saveDatabase(entry: ScriptVariable, previous: ScriptVariable?) {
        DatabaseValue.normalized(entry)
        require(databaseEntries.none { it.name == entry.name && it.name != previous?.name }) { "键已存在" }
        task { io { database.edit(entry, previous) } }
    }

    fun removeDatabase(entry: ScriptVariable) = task { io { database.remove(entry.name, entry) } }

    private val ftpWorkspace by lazy { io.todu.sview.ftp.FtpWorkspace(repository) }
    private val ftpDelegate = lazy {
        check(!io.todu.sview.wear.WatchDevice.isWatch(getApplication()))
        io.todu.sview.ftp.FtpEditorController(getApplication()) { request ->
            kotlinx.coroutines.runBlocking { ftpRequest(request) }
        }
    }
    val ftpEditor get() = ftpDelegate.value

    /** 生命周期清理不能为了停止服务而在手表端初始化控制器。 */
    fun stopFtpEditor() { if (ftpDelegate.isInitialized()) ftpDelegate.value.stop() }

    /** 上传只在完整接收后提交，快照与文件修改共用锁，传输等待不占用工作区。 */
    private suspend fun ftpRequest(request: io.todu.sview.ftp.FtpRequest): io.todu.sview.ftp.FtpResult =
        withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
            mutationLock.withLock {
                check(ready && ftpEditor.accepts(request) && !appExiting && !clearingCache) { "服务已停止或工作区正忙" }
                val writing = request.action in setOf("write", "mkdir", "delete", "rmdir", "rename")
                if (writing) check(!editorOpen) { "请先关闭手机上的代码编辑页面" }
                saveJob?.cancel()
                if (!saved && fileReady) persist(projectId, filePath, code)
                io { ftpWorkspace.execute(request, projects) }
            }
        }

    private val lanWorkspace by lazy { io.todu.sview.lan.LanWorkspace(repository, getApplication<Application>().cacheDir) }
    val lanEditor = io.todu.sview.lan.LanEditorController(application, ::lanRequest)
    var remoteRun: (suspend (Project) -> Unit)? = null

    /** 主线程串行协调手机和网页修改，所有磁盘工作仍进入原有 IO 队列。 */
    private suspend fun lanRequest(action: String, body: org.json.JSONObject): io.todu.sview.lan.LanResponse =
        // 停止监听会中断请求线程；已开始的事务仍须完成发布或回滚。
        withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
            try {
                mutationLock.withLock {
                    check(ready && lanEditor.running) { "服务已停止" }
                    saveJob?.cancel()
                    if (!saved && fileReady) persist(projectId, filePath, code)
                    when (action) {
                        "database/list", "database/edit", "database/remove" -> io {
                            io.todu.sview.lan.LanDatabase(database).handle(action, body)
                        }
                        "state" -> io { io.todu.sview.lan.LanResponse.json(lanWorkspace.snapshot(projects, categories)) }
                        "save" -> {
                            // 手机编辑器打开时不覆盖它的内存正文，避免随后自动保存回写旧内容。
                            check(!editorOpen) { "请先关闭手机上的代码编辑页面" }
                            val operations = body.getJSONArray("operations")
                            for (index in 0 until operations.length()) {
                                val operation = operations.getJSONObject(index)
                                if (operation.optString("type") == "deleteProject") {
                                    check(!BrowserEngine.isRunning(operation.getString("id"))) { "请先关闭要删除项目的运行窗口" }
                                }
                            }
                            logWriter.flush()
                            val previous = projects
                            val result = io { lanWorkspace.apply(body, projects, categories) }
                            projects = result.first; categories = result.second
                            io.todu.sview.share.ShareRegistration.update(getApplication(), projects)
                            val removed = previous.map { it.id }.toSet() - projects.map { it.id }.toSet()
                            removed.forEach { id ->
                                runCatching { BrowserEngine.clearProject(getApplication(), id) }
                                    .onFailure { error = "项目已删除，但网页存储清理失败：${it.message}" }
                            }
                            io { io.todu.sview.lan.LanResponse.json(org.json.JSONObject()) }
                        }
                        "file" -> io {
                            val id = if (body.isNull("id")) null else body.getString("id")
                            val snapshot = repository.temporaryFiles.create(getApplication<Application>().cacheDir, "lan-export-", ".tmp")
                            try {
                                lanWorkspace.file(id, body.getString("path"), projects).inputStream().use { input ->
                                    snapshot.outputStream().use { input.copyTo(it, 65536) }
                                }
                                io.todu.sview.lan.LanResponse.file(snapshot, "application/octet-stream") { repository.temporaryFiles.release(snapshot) }
                            } catch (error: Exception) { repository.temporaryFiles.release(snapshot); throw error }
                        }
                        "export" -> io {
                            val archive = repository.temporaryFiles.create(getApplication<Application>().cacheDir, "lan-export-", ".zip")
                            try {
                                val id = if (body.isNull("id")) null else body.getString("id")
                                archive.outputStream().use { output ->
                                    if (id == null) java.util.zip.ZipOutputStream(output).use { zip ->
                                        repository.templates.walkTopDown().filter { it != repository.templates }.forEach { file ->
                                            val path = file.relativeTo(repository.templates).invariantSeparatorsPath + if (file.isDirectory) "/" else ""
                                            zip.putNextEntry(java.util.zip.ZipEntry(path))
                                            if (file.isFile) file.inputStream().use { it.copyTo(zip) }
                                            zip.closeEntry()
                                        }
                                    } else {
                                        val project = projects.firstOrNull { it.id == id } ?: error("项目不存在")
                                        ProjectArchive.write(project, repository.root(id), repository.templates, output)
                                    }
                                }
                                require(archive.length() <= 64 * 1024 * 1024) { "压缩包超过 64 MB，请在手机中导出" }
                                io.todu.sview.lan.LanResponse.file(archive, "application/zip") { repository.temporaryFiles.release(archive) }
                            } catch (error: Exception) { repository.temporaryFiles.release(archive); throw error }
                        }
                        "run" -> {
                            val project = projects.firstOrNull { it.id == body.getString("id") } ?: error("项目不存在")
                            io { repository.ensureRuntime(project.id) }
                            val run = remoteRun ?: error("请保持手机应用在前台")
                            run(project)
                            io.todu.sview.lan.LanResponse.json(org.json.JSONObject().put("ok", true))
                        }
                        else -> io.todu.sview.lan.LanResponse.error(404, "接口不存在")
                    }
                }
            } catch (error: Exception) {
                io.todu.sview.lan.LanResponse.error(409, error.message ?: "操作失败")
            }
        }

    override fun onCleared() {
        lanEditor.stop()
        stopFtpEditor()
        closeWatchConnection()
        super.onCleared()
    }


    fun putVariable(variable: ScriptVariable, previousName: String?) {
        val current = project ?: return
        val next = current.variables.filterNot { it.name == previousName } + variable
        ProjectVariables.validate(next)
        updateProject(current.copy(variables = next))
    }
}
