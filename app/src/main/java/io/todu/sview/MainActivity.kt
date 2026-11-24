package io.todu.sview

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.todu.sview.data.*
import io.todu.sview.runtime.RuntimeWindow
import io.todu.sview.ui.components.*
import io.todu.sview.ui.screens.*
import io.todu.sview.ui.theme.SViewTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 单 Activity 承载自适应工作区，浏览器引擎不参与首页启动。 */
private class RunLifetime {
    val released = kotlinx.coroutines.CompletableDeferred<Unit>()
    var mounted = false
}

open class MainActivity : ComponentActivity() {
    private var redirectedToWatch = false
    private var watchExitRequested = false
    private var watchExitReady = false
    private val model by lazy { WorkspaceOwner.model(application) }
    private var shortcutRequest by mutableStateOf<Pair<String, Long>?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 手表使用独立窗口声明关闭系统返回手势，手机保留原有返回行为。
        if (javaClass == MainActivity::class.java && io.todu.sview.wear.WatchDevice.isWatch(this)) {
            redirectedToWatch = true
            startActivity(android.content.Intent(intent).setClass(this, io.todu.sview.wear.WatchActivity::class.java))
            finish()
            return
        }
        enableEdgeToEdge()
        acceptShortcut(intent)
        setContent {
            if (io.todu.sview.wear.WatchDevice.isWatch(this)) {
                io.todu.sview.wear.WatchWorkspace(model, ::exitWatch, shortcutRequest) { request ->
                    if (shortcutRequest == request) shortcutRequest = null
                }
            } else SViewTheme { Workspace() }
        }
    }
    /** 只在手表主动退出时结束进程，普通切后台与配置变更不走此路径。 */
    private fun exitWatch() {
        if (watchExitRequested || model.appExiting) return
        watchExitRequested = true
        lifecycleScope.launch {
            try {
                model.prepareAppExit()
                watchExitReady = true
                finishAndRemoveTask()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                watchExitRequested = false
                model.error = failure.message ?: "无法退出应用"
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptShortcut(intent)
    }

    private fun acceptShortcut(intent: android.content.Intent) {
        intent.getStringExtra("widgetRunToken")?.let(io.todu.sview.widget.WidgetRunRequest::consume)
            ?.let { shortcutRequest = it to System.nanoTime() }
        intent.removeExtra("widgetRunToken")
    }
    override fun onDestroy() {
        if (isFinishing && !redirectedToWatch) {
            model.closeWatchConnection()
            model.lanEditor.stop()
            model.stopFtpEditor()
            stopService(android.content.Intent(this, io.todu.sview.runtime.overlay.OverlayService::class.java))
            if (!watchExitReady) model.flush()
        }
        super.onDestroy()
        if (watchExitReady) {
            WorkspaceOwner.viewModelStore.clear()
            // 任务已移除且写入已完成，不让空闲引擎继续驻留后台。
            io.todu.sview.runtime.AppProcessExit.finishProcess(applicationContext)
        }
    }

    override fun onStop() {
        if (!redirectedToWatch) {
            if (!watchExitReady) model.flush()
        }
        super.onStop()
    }

    @Composable
    private fun Workspace() {
        var page by remember { mutableStateOf("home") }
        var homeLogProjectId by remember { mutableStateOf<String?>(null) }
        var running by remember { mutableStateOf<Project?>(null) }
        var released by remember { mutableStateOf(RunLifetime().apply { released.complete(Unit) }) }
        val runScope = rememberCoroutineScope()
        val runLock = remember { kotlinx.coroutines.sync.Mutex() }
        suspend fun stopRun() {
            val previous = released
            running = null
            if (!previous.mounted) previous.released.complete(Unit)
            previous.released.await()
        }
        fun showRun(project: Project) {
            released = RunLifetime()
            running = project
        }
        BackHandler(page == "home" && running == null) { finishAndRemoveTask() }
        var projectDialog by remember { mutableStateOf(false) }
        var editing by remember { mutableStateOf<Project?>(null) }
        var image by remember { mutableStateOf<ResourceFile?>(null) }
        val round = LocalConfiguration.current.isScreenRound
        val haptics = LocalHapticFeedback.current
        LaunchedEffect(model.ready, shortcutRequest) {
            val request = shortcutRequest ?: return@LaunchedEffect
            if (!model.ready) return@LaunchedEffect
            if (model.projects.none { it.id == request.first }) {
                model.error = "项目不存在"
                shortcutRequest = null
            }
            else {
                runLock.lock()
                try { stopRun() } finally { runLock.unlock() }
                model.prepareRun(request.first) {
                    // 请求是此 effect 的键，准备完成前清空会取消自动运行。
                    if (shortcutRequest == request) {
                        val prepared = it
                        runScope.launch {
                            runLock.lock()
                            try {
                                if (shortcutRequest == request) {
                                    stopRun()
                                    showRun(prepared)
                                    shortcutRequest = null
                                }
                            } finally { runLock.unlock() }
                        }
                    }
                }
            }
        }
        // 两个运行入口共用触感反馈，点击时立即响应，不等待文件准备完成。
        fun runProject(id: String?) {
            if (id == null) return
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            model.prepareRun(id) { project -> runScope.launch {
                runLock.lock()
                try { stopRun(); showRun(project) } finally { runLock.unlock() }
            } }
        }
        DisposableEffect(Unit) {
            model.remoteRun = { project ->
                check(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) { "请保持手机应用在前台" }
                runLock.lock()
                try {
                    stopRun()
                    check(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) { "请保持手机应用在前台" }
                    check(model.lanEditor.running) { "服务已停止" }
                    showRun(project)
                } finally { runLock.unlock() }
            }
            onDispose { model.remoteRun = null }
        }
        val projectImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { model.importProject(it, displayName(it)) }
        }
        var exportProjectId by rememberSaveable { mutableStateOf<String?>(null) }
        val projectExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val id = exportProjectId
            exportProjectId = null
            if (uri != null && id != null) model.exportProject(id, uri)
        }
        val resourceImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach { model.importFile(it, displayName(it)) }
        }
        fun back() {
            val destination = if (page in listOf("templates", "permissions", "logs", "interfaces", "lan", "database", "watch", "about", "help")) "settings" else "home"
            if (page == "editor" || page == "templates") {
                model.closeEditor { page = destination }
            } else {
                model.flush()
                page = destination
            }
        }
        BackHandler(page != "home") { back() }
        Box(Modifier.fillMaxSize()) {
            Surface(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().then(if (round) Modifier.padding(horizontal = 28.dp, vertical = 24.dp) else Modifier)) {
                    val wide = maxWidth >= 700.dp
                    Row(Modifier.fillMaxSize()) {
                        if (wide && page in listOf("home", "settings", "permissions", "interfaces", "categories")) Column(Modifier.width(72.dp).fillMaxHeight().background(Panel), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height(76.dp), contentAlignment = Alignment.Center) { SViewMark() }
                            IconAction("folders", "项目", { page = "home" }, tint = if (page in listOf("home", "categories")) Blue else Muted)
                            IconAction("code", "系统接口", { page = "interfaces" }, tint = if (page == "interfaces") Blue else Muted)
                            Spacer(Modifier.weight(1f))
                            IconAction("settings-2", "设置", { page = "settings" }, Modifier.padding(bottom = 20.dp), tint = if (page in listOf("settings", "permissions")) Blue else Muted)
                        }
                        Box(Modifier.weight(1f)) {
                            if (!model.ready) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            else if (shortcutRequest == null) when (page) {
                                "settings" -> SettingsScreen(wide, { page = "home" }, { model.openProject(null); page = "templates" }, { page = "permissions" }, { page = "logs" }, { page = "interfaces" }, model.clearingCache, model::clearCaches, { page = "lan" }, model.lanEditor.statusText, model.lanEditor.running, { page = "database" }, { page = "watch" }, { page = "about" }, { page = "help" }, { page = "ftp" }, model.ftpEditor.statusText, model.ftpEditor.running)
                                "watch" -> io.todu.sview.wear.WatchConnectionScreen(model.watchConnection, model.projects) { page = "settings" }
                                "help" -> UsageHelpScreen { page = "settings" }
                                "about" -> AboutScreen { page = "settings" }
                                "database" -> DatabaseScreen(model) { page = "settings" }
                                "lan" -> LanEditorScreen(model.lanEditor) { page = "settings" }
                                "ftp" -> LanEditorScreen(model.ftpEditor, ftp = true) { page = "settings" }
                                "interfaces" -> SystemInterfacesScreen(onBack = { page = "settings" })
                                "logs" -> ProjectLogsScreen(model) { page = "settings" }
                                "project-logs" -> ProjectLogsScreen(model, initialProjectId = homeLogProjectId) { page = "home" }
                                "permissions" -> PermissionsScreen { page = "settings" }
                                "categories" -> CategoriesScreen(model) { page = "home" }
                                "editor", "templates" -> EditorScreen(model, wide, page == "templates", 13, ::back,
                                    { runProject(model.projectId) }, { editing = model.project; projectDialog = true },
                                    { resourceImport.launch(arrayOf("*/*")) }, { image = it })
                                else -> HomeScreen(model.projects, model.categories, wide, { project -> runProject(project.id) }, { model.openProject(it.id); page = "editor" },
                                    { editing = it; projectDialog = true }, model::updateProject, model::deleteProject, { editing = null; projectDialog = true },
                                    { projectImport.launch(arrayOf("text/html", "text/javascript", "application/javascript", "text/plain", "application/zip", "application/x-zip-compressed", "application/octet-stream")) }, { page = "settings" }, { page = "categories" }, model::reorder, model::saveProjectOrder, { project -> model.clearProjectCaches(project) }, { project ->
                                        exportProjectId = project.id
                                        projectExport.launch(project.name.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".zip")
                                    }, watchSyncBusy = model.watchConnection.busy, onWatchSync = model.watchConnection::syncFromHome,
                                    ftpEditorRunning = model.ftpEditor.running, lanEditorRunning = model.lanEditor.running,
                                    onFtpEditor = { page = "ftp" }, onLanEditor = { page = "lan" },
                                    onViewLogs = { project -> homeLogProjectId = project.id; page = "project-logs" })
                            }
                        }
                    }
                }
            }
            if (projectDialog) ProjectDialog(editing, model.categories, { projectDialog = false }, model::updateProject)
            running?.let { val completion = released; key(completion) {
                DisposableEffect(completion) { completion.mounted = true; onDispose {} }
                RuntimeWindow(it, model.repository, { message -> model.appendLog(it, message) }, model::cornerRadiusRunProject, model::marginRunProject, model::locateRunProject, model::resizeRunProject, { message -> model.error = message }, onReleased = { completion.released.complete(Unit) }) { try { model.flushLogWrites() } finally { if (released === completion) running = null } } } }
            image?.let { entry -> ImageDialog(entry) { image = null } }
            if (model.archiveBusy) AppDialog(onDismissRequest = {}, title = { Text("正在处理项目") },
                text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {})
            model.archiveMessage?.let { message -> AppDialog(onDismissRequest = { model.archiveMessage = null }, title = { Text("完成") },
                text = { Text(message) }, confirmButton = { DialogAction("确定", { model.archiveMessage = null }) }) }
            model.error?.let { message -> AppDialog(onDismissRequest = { model.error = null }, title = { Text("无法完成操作") }, text = { Text(message) },
                confirmButton = { DialogAction("关闭", { model.error = null }) }) }
        }
    }

    private fun displayName(uri: android.net.Uri): String = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else "导入文件"
    } ?: "导入文件"

    /** 图片先读取尺寸再采样，避免原始大图占满内存。 */
    @Composable
    private fun ImageDialog(entry: ResourceFile, onClose: () -> Unit) {
        val bitmap by produceState<android.graphics.Bitmap?>(null, entry.path) {
            value = withContext(Dispatchers.IO) {
                val path = model.repository.resolve(model.projectId, entry.path).path
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(path, options)
                options.inSampleSize = maxOf(1, maxOf(options.outWidth, options.outHeight) / 1600)
                options.inJustDecodeBounds = false
                android.graphics.BitmapFactory.decodeFile(path, options)
            }
        }
        AppDialog(onDismissRequest = onClose, title = { Text(entry.path.substringAfterLast('/')) }, text = {
            bitmap?.let { Image(it.asImageBitmap(), entry.path, Modifier.fillMaxWidth().heightIn(max = 500.dp)) }
        }, confirmButton = { DialogAction("关闭", onClose) })
    }
}
