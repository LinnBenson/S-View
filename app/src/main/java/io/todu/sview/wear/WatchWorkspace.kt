package io.todu.sview.wear

import io.todu.sview.device.DeviceVibration

import androidx.activity.compose.BackHandler
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.sview.data.*
import io.todu.sview.runtime.RuntimeWindow
import io.todu.sview.ui.components.*
import io.todu.sview.ui.screens.NotificationPermissionRow
import io.todu.sview.ui.screens.DevicePermissionRows
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// 系统页面与项目列表共用同一条目间距。
private val WatchListSpacing = 6.dp

private class WatchRun(val project: Project, val exitOnClose: Boolean) {
    val released = CompletableDeferred<Unit>()
    var mounted = false
}

/** 手表只组合当前系统页，项目脚本和浏览器直到用户点开才加载。 */
@Composable
fun WatchWorkspace(model: WorkspaceModel, onExit: () -> Unit, runRequest: Pair<String, Long>? = null, onRequestHandled: (Pair<String, Long>) -> Unit = {}) {
    androidx.wear.compose.material.MaterialTheme {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB3C8FF), background = Color.Black,
        surface = Color.Black, onSurface = Color(0xFFF0F2F8), onBackground = Color(0xFFF0F2F8))) {
        var page by remember { mutableStateOf("home") }
        var running by remember { mutableStateOf<WatchRun?>(null) }
        var leaving by remember { mutableStateOf(false) }
        var launching by remember { mutableStateOf(false) }
        var cleared by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val lock = remember { Mutex() }
        val round = LocalConfiguration.current.isScreenRound
        val context = LocalContext.current
        val preferences = remember(context) { context.getSharedPreferences("watch-appearance", android.content.Context.MODE_PRIVATE) }
        var grid by remember { mutableStateOf(preferences.getBoolean("grid", true)) }
        suspend fun stopRun() {
            val old = running ?: return
            running = null
            if (!old.mounted) old.released.complete(Unit)
            old.released.await()
        }
        fun launchProject(id: String, fromTile: Boolean = false) {
            if (model.watchSyncBusy || model.appExiting || model.clearingCache || launching) return
            launching = true
            page = "home"
            model.prepareRun(id) { prepared -> scope.launch {
                lock.withLock {
                    stopRun()
                    if (!model.watchSyncBusy && !model.appExiting) running = WatchRun(prepared, fromTile)
                    launching = false
                }
            } }
        }
        LaunchedEffect(model.ready, runRequest, model.watchSyncBusy, model.clearingCache, launching) {
            val request = runRequest ?: return@LaunchedEffect
            if (!model.ready || model.watchSyncBusy || model.clearingCache || model.appExiting || launching) return@LaunchedEffect
            onRequestHandled(request)
            if (model.projects.any { it.id == request.first }) launchProject(request.first, fromTile = true)
        }
        // 只有用户关闭磁贴启动的窗口时退出应用；同步替换仍只调用 stopRun。
        fun closeRun(active: WatchRun) {
            if (running !== active) return
            if (active.exitOnClose) leaving = true
            running = null
            if (active.exitOnClose && !model.appExiting) onExit()
        }
        val visibleRun = running.takeIf { io.todu.sview.runtime.background.BackgroundWindow.active == null }
        fun back() {
            val active = visibleRun
            if (active?.exitOnClose == true) closeRun(active)
            else if (active != null) scope.launch { lock.withLock { stopRun() } }
            else if (page != "home") page = "home" else onExit()
        }
        BackHandler { DeviceVibration.click(context); back() }
        DisposableEffect(model) {
            model.stopWatchRun = { lock.withLock { stopRun() } }
            onDispose { model.stopWatchRun = null }
        }
        LaunchedEffect(model.error, model.watchSyncBusy) { if (model.error != null || model.watchSyncBusy) launching = false }
        LaunchedEffect(model.error) { if (model.error != null) leaving = false }
        // MaterialTheme 只定义色板，Surface 负责向系统页面提供浅色文字。
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = MaterialTheme.colorScheme.onSurface) {
            Box(Modifier.fillMaxSize().background(Color.Black).then(if (visibleRun == null) Modifier.watchEdgeSwipe(round, ::back) else Modifier)) {
                when {
                    // 磁贴窗口淡出和退出落盘期间保持黑底，避免露出应用主页。
                    leaving || visibleRun?.exitOnClose == true || runRequest != null || launching -> Unit
                    !model.ready -> CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.Center), strokeWidth = 2.dp)
                    page == "home" -> WatchProjects(model.projects, model.watchSyncBusy || model.appExiting || launching, grid,
                        !model.clearingCache && !model.watchSyncBusy && !model.appExiting,
                        { DeviceVibration.click(context); page = "permissions" },
                        { DeviceVibration.click(context); cleared = false; page = "clear" },
                        { DeviceVibration.click(context); page = "about" },
                        { DeviceVibration.click(context); grid = !grid; preferences.edit().putBoolean("grid", grid).apply() }) { project ->
                        DeviceVibration.click(context)
                        launchProject(project.id)
                    }
                    page == "about" -> WatchAboutScreen()
                    page == "permissions" -> WatchPage("权限") {
                        item { NotificationPermissionRow() }
                        item { io.todu.sview.ui.screens.NotificationAccessPermissionRow() }
                        item { io.todu.sview.ui.screens.HealthPermissionRow() }
                        listOf("microphone", "camera", "overlay", "install", "storage", "bluetooth", "wifi", "location").forEach { permission ->
                            item { DevicePermissionRows(permission) }
                        }
                    }
                    else -> WatchPage(
                        title = if (cleared) "缓存已清除" else "清除缓存？",
                        bottomAction = {
                            androidx.wear.compose.material.Button(
                                onClick = { if (cleared) page = "home" else model.clearCaches { cleared = true } },
                                enabled = !model.clearingCache && !model.watchSyncBusy && !model.appExiting,
                                colors = androidx.wear.compose.material.ButtonDefaults.secondaryButtonColors(),
                                modifier = Modifier.size(48.dp)) {
                                if (model.clearingCache) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else AppIcon(if (cleared) "check" else "trash", if (cleared) "完成" else "清除缓存",
                                    tint = Color(0xFFB3C8FF), modifier = Modifier.size(22.dp))
                            }
                        }
                    ) {
                        item { Text("清除网页数据、缓存与日志\n保留项目和共享数据库", Modifier.fillMaxWidth().padding(vertical = 6.dp), textAlign = TextAlign.Center, color = Color(0xFFA6AFC1), fontSize = 12.sp, lineHeight = 17.sp) }
                    }
                }
                if (model.watchSyncBusy) Text("正在同步", Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).background(Color.Black), fontSize = 12.sp, color = Color(0xFFB3C8FF))
                running?.let { active -> key(active) {
                    DisposableEffect(active) { active.mounted = true; onDispose {} }
                    RuntimeWindow(active.project, model.repository, { model.appendLog(active.project, it) },
                        model::cornerRadiusRunProject, model::marginRunProject, model::locateRunProject, model::resizeRunProject,
                        { model.error = it }, onReleased = { active.released.complete(Unit) },
                        onReplaced = { if (running === active) running = null }) { closeRun(active) }
                } }
                model.error?.let { error ->
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        WatchPage("无法完成操作") {
                            item { Text(error, Modifier.fillMaxWidth().padding(vertical = 8.dp), fontSize = 12.sp, textAlign = TextAlign.Center) }
                            item { WatchRow("check", "关闭") { model.error = null } }
                        }
                    }
                }
            }
        }
    }
}

}

/** Wear OS 列表负责边缘缩放、旋转输入与圆屏滚动指示。 */
@Composable
private fun WatchPage(title: String, bottomAction: (@Composable () -> Unit)? = null, content: ScalingLazyListScope.() -> Unit) {
    val list = rememberScalingLazyListState(initialCenterItemIndex = 0)
    Box(Modifier.fillMaxSize()) {
        ScalingLazyColumn(state = list, autoCentering = null, modifier = Modifier.fillMaxSize().padding(bottom = if (bottomAction != null) 76.dp else 0.dp), contentPadding = PaddingValues(start = 30.dp, end = 30.dp, top = 36.dp, bottom = if (bottomAction != null) 8.dp else 36.dp), verticalArrangement = Arrangement.spacedBy(WatchListSpacing)) {
            item { Text(title, Modifier.fillMaxWidth().padding(bottom = 4.dp), fontSize = 16.sp, textAlign = TextAlign.Center) }
            content()
        }
        WatchPositionIndicator(list)
        // 操作按钮独立于滚动内容，固定在圆屏下方并保留底部安全距离。
        bottomAction?.let { action ->
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), contentAlignment = Alignment.Center) {
                action()
            }
        }
    }
}

@Composable
private fun WatchProjects(projects: List<Project>, busy: Boolean, grid: Boolean, canClear: Boolean,
    onPermissions: () -> Unit, onClear: () -> Unit, onAbout: () -> Unit, onLayout: () -> Unit, onRun: (Project) -> Unit) {
    val list = rememberScalingLazyListState(initialCenterItemIndex = 0)
    Box(Modifier.fillMaxSize()) {
        ScalingLazyColumn(state = list, autoCentering = null, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(if (grid) 12.dp else WatchListSpacing)) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    HomeSViewMark(modifier = Modifier.size(26.dp), tint = Color(0xFFF0F2F8))
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp).offset(y = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        IconAction("shield", "权限设置", onPermissions, tint = Color(0xFFBAC3D6), iconSize = 18.dp)
                        IconAction("trash", "清除缓存", onClear, tint = Color(0xFFBAC3D6), enabled = canClear, iconSize = 18.dp)
                        IconAction("info", "关于应用", onAbout, tint = Color(0xFFBAC3D6), iconSize = 18.dp)
                        IconAction(if (grid) "list" else "layout-grid", if (grid) "切换列表" else "切换宫格", onLayout, tint = Color(0xFFBAC3D6), iconSize = 18.dp)
                    }
                }
            }
            if (projects.isEmpty()) item { Text("在手机上选择项目并同步", Modifier.fillMaxWidth().padding(vertical = 16.dp), color = Color(0xFFA6AFC1), fontSize = 12.sp, textAlign = TextAlign.Center) }
            if (!grid) items(projects, key = { it.id }) { project ->
                WatchProjectListItem(project, busy, onRun)
            }
            else items(projects.chunked(3), key = { it.first().id }) { row ->
                WatchProjectGridRow(row, busy, onRun)
            }
        }
        WatchPositionIndicator(list)
    }
}

/** 图标与标题分行排版，中文和数字标题按基线对齐，图标位置不随字体变化。 */
@Composable
private fun WatchProjectGridRow(projects: List<Project>, busy: Boolean, onRun: (Project) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            projects.forEach { project ->
                Box(Modifier.weight(1f).clickable(enabled = !busy) { onRun(project) }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(CardColors[project.color.coerceIn(CardColors.indices)]), contentAlignment = Alignment.Center) {
                        AppIcon(project.icon, project.name, modifier = Modifier.size(22.dp), tint = Color(0xFF3D485C))
                    }
                }
            }
            repeat(3 - projects.size) { Spacer(Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            projects.forEach { project ->
                Text(project.name, Modifier.weight(1f).alignByBaseline().clickable(enabled = !busy) { onRun(project) },
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = 11.sp, lineHeight = 13.sp)
            }
            repeat(3 - projects.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** 列表布局提供更宽的项目名称区域，图标与宫格共用项目配色。 */
@Composable
private fun WatchProjectListItem(project: Project, busy: Boolean, onRun: (Project) -> Unit) {
    Chip(onClick = { onRun(project) }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        colors = ChipDefaults.secondaryChipColors(),
        label = { Text(project.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, lineHeight = 16.sp) },
        icon = {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(CardColors[project.color.coerceIn(CardColors.indices)]), contentAlignment = Alignment.Center) {
                AppIcon(project.icon, modifier = Modifier.size(20.dp), tint = Color(0xFF3D485C))
            }
        })
}

@Composable
private fun WatchRow(icon: String, text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Chip(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        colors = ChipDefaults.secondaryChipColors(),
        label = { Text(text, fontSize = 14.sp) },
        icon = { AppIcon(icon, modifier = Modifier.size(20.dp), tint = Color(0xFFB3C8FF)) })
}
