package io.todu.sview.ui.screens

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch

import io.todu.sview.BuildConfig

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.todu.sview.data.Project
import io.todu.sview.ui.components.*

/** 分类顺序由工作区保存；卡片拖动时即时让位，松手后一次落盘。 */
@Composable
fun HomeScreen(projects: List<Project>, categories: List<String>, wide: Boolean, onRun: (Project) -> Unit, onEdit: (Project) -> Unit, onProjectSettings: (Project) -> Unit,
    onUpdate: (Project) -> Unit, onDelete: (Project) -> Unit, onCreate: () -> Unit, onImport: () -> Unit,
    onSettings: () -> Unit, onCategories: () -> Unit, onReorder: (String, String) -> Unit, onReorderFinished: () -> Unit, onClearCache: (Project) -> Unit, onExport: (Project) -> Unit,
    watchSyncBusy: Boolean, onWatchSync: () -> Unit,
    ftpEditorRunning: Boolean, lanEditorRunning: Boolean, onFtpEditor: () -> Unit, onLanEditor: () -> Unit,
    onViewLogs: (Project) -> Unit) {
    val compact = LocalConfiguration.current.screenWidthDp < 300
    var query by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Project?>(null) }
    var pendingClearCache by remember { mutableStateOf<Project?>(null) }
    val filters = remember(categories) { listOf("全部") + categories }
    val pager = rememberPagerState { filters.size }
    val scope = rememberCoroutineScope()
    val tabs = rememberLazyListState()
    var draggingPage by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pager.currentPage) { tabs.animateScrollToItem(pager.currentPage) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("项目", showDivider = false, titleContent = if (wide) null else ({ SViewMark() })) {
            // 同步期间保持按钮占位，只显示不带进度的加载状态。
            if (watchSyncBusy) {
                Box(Modifier.size(44.dp).semantics { contentDescription = "正在同步手表" }, contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Muted, strokeWidth = 2.dp)
                }
            } else IconAction("folder-sync", "同步手表", onWatchSync)
            IconAction("import", "导入项目", onImport)
            IconAction("plus", "创建项目", onCreate)
        }
        Column(Modifier.weight(1f).padding(horizontal = if (compact) 4.dp else 24.dp)) {
            if (ftpEditorRunning || lanEditorRunning) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (ftpEditorRunning) EditorRunningNotice("FTP 编辑器已启用！", onFtpEditor)
                    if (lanEditorRunning) EditorRunningNotice("局域网编辑器已启用！", onLanEditor)
                }
            }
            Spacer(Modifier.height(12.dp))
            BasicTextField(query, { query = it }, Modifier.fillMaxWidth().height(44.dp).background(Panel, RoundedCornerShape(12.dp)),
                singleLine = true, textStyle = TextStyle(color = Ink, fontSize = 14.sp), decorationBox = { field ->
                    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon("search", modifier = Modifier.size(20.dp), tint = Muted)
                        Spacer(Modifier.width(12.dp))
                        Box { if (query.isEmpty()) Text("搜索项目", color = Muted, fontSize = 13.sp); field() }
                    }
                })
            Row(Modifier.fillMaxWidth().height(if (compact) 40.dp else 56.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.lazy.LazyRow(Modifier.weight(1f), state = tabs, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(filters.size) { index ->
                        val label = filters[index]
                        TextButton({ scope.launch { pager.animateScrollToPage(index) } }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                            Text(label, color = if (pager.currentPage == index) Blue else Muted, fontSize = 12.sp)
                        }
                    }
                }
                IconAction("folders", "管理分类", onCategories)
            }
            // 每页保留自己的网格状态；长按排序期间暂停横向翻页。
            HorizontalPager(pager, modifier = Modifier.weight(1f).fillMaxWidth(),
                key = { if (it == 0) "all" else "category:${filters[it]}" }, userScrollEnabled = draggingPage == null) { page ->
                val category = filters[page]
                val filtered = remember(projects, query, category) {
                    projects.filter { (page == 0 || it.category == category) && it.name.contains(query, true) }
                }
                val grid = rememberLazyGridState()
                val drag = rememberReorderDragState(
                    items = { grid.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                        (item.key as? String)?.takeIf { key -> filtered.any { it.id == key } }?.let { ReorderItem(it, item.index, item.offset, item.size) }
                    } }, move = onReorder, finish = onReorderFinished,
                    canMove = { a, b -> projects.find { it.id == a }?.pinned == projects.find { it.id == b }?.pinned })
                ReorderAutoScroll(drag, { grid.layoutInfo.viewportStartOffset.toFloat() to grid.layoutInfo.viewportEndOffset.toFloat() }, { grid.scrollBy(it) })
                DisposableEffect(page) {
                    onDispose { if (draggingPage == page) draggingPage = null }
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val columns = when { maxWidth < 260.dp -> 1; wide -> 3; else -> 2 }
                    // 网格始终占满可用区域；内容较少时也不能按最后一行收缩拖动视口。
                    LazyVerticalGrid(GridCells.Fixed(columns), modifier = Modifier.fillMaxSize(), state = grid,
                        contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(true, false).forEach { pinned ->
                            val group = filtered.filter { it.pinned == pinned }
                            if (group.isNotEmpty()) {
                                item(key = if (pinned) "header-pinned" else "header-projects", span = { GridItemSpan(maxLineSpan) }) {
                                    Text(if (pinned) "置顶" else "项目", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
                                }
                                items(group, key = { it.id }) { project ->
                                    val lifted = drag.dragging == project.id
                                    val moving = lifted || drag.settling == project.id
                                    val scale by animateFloatAsState(if (lifted) 1.045f else 1f, spring(dampingRatio = .8f, stiffness = 500f), label = "卡片抬起")
                                    ProjectCard(project, onRun, onEdit, onProjectSettings, onUpdate, { pendingDelete = it }, onExport, { pendingClearCache = it }, onViewLogs,
                                        Modifier.animateItem(placementSpec = if (moving) null else spring(dampingRatio = .85f, stiffness = 450f))
                                            .zIndex(if (moving) 1f else 0f).graphicsLayer {
                                                val offset = drag.offset(project.id)
                                                translationX = offset.x; translationY = offset.y
                                                scaleX = scale; scaleY = scale
                                                shape = RoundedCornerShape(12.dp)
                                                clip = false
                                            }.pointerInput(project.id) {
                                                detectDragGesturesAfterLongPress(onDragStart = { drag.start(project.id); if (drag.dragging != null) draggingPage = page },
                                                    onDrag = { change, amount -> change.consume(); drag.drag(amount) },
                                                    onDragCancel = { drag.end(); draggingPage = null }, onDragEnd = { drag.end(); draggingPage = null })
                                            })
                                }
                            }
                        }
                    }
                    if (filtered.isEmpty()) {
                        val searching = query.isNotEmpty()
                        EmptyState(
                            icon = if (searching) "search" else "folders",
                            title = when { searching -> "没有找到项目"; projects.isEmpty() -> "还没有项目"; else -> "这个分类还没有项目" },
                            description = if (searching) "换个关键词试试" else null,
                            actionLabel = when { searching -> "清除搜索"; projects.isEmpty() -> "创建项目"; else -> "查看全部" },
                            onAction = { when { searching -> query = ""; projects.isEmpty() -> onCreate(); else -> scope.launch { pager.animateScrollToPage(0) } } },
                            modifier = Modifier.fillMaxSize())
                    }
                }
            }
            if (!wide) Row(Modifier.fillMaxWidth().height(if (compact) 40.dp else 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("S View  ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                IconAction("settings-2", "设置", onSettings)
            }
        }
    }
    pendingClearCache?.let { project -> AppDialog(
        onDismissRequest = { pendingClearCache = null }, title = { Text("清除${project.name}缓存？") },
        text = { Text("清除该项目的网页存储、Cookie、运行缓存、日志及资源根目录下的 cache 文件夹。保留其他资源、项目配置、模板及共享数据库。") },
        confirmButton = { DialogAction("清除", { pendingClearCache = null; onClearCache(project) }, primary = true) },
        dismissButton = { DialogAction("取消", { pendingClearCache = null }) }) }
    pendingDelete?.let { project -> AppDialog(onDismissRequest = { pendingDelete = null }, title = { Text("删除${project.name}？") },
        text = { Text("项目中的文件将一并删除。") }, confirmButton = { DialogAction("删除", { onDelete(project); pendingDelete = null }) },
        dismissButton = { DialogAction("取消", { pendingDelete = null }) }) }
}

@Composable
private fun ProjectCard(project: Project, onRun: (Project) -> Unit, onEdit: (Project) -> Unit, onProjectSettings: (Project) -> Unit,
    onUpdate: (Project) -> Unit, onDelete: (Project) -> Unit, onExport: (Project) -> Unit, onClearCache: (Project) -> Unit,
    onViewLogs: (Project) -> Unit, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier.height(80.dp).background(CardColors[project.color.coerceIn(CardColors.indices)], RoundedCornerShape(12.dp)).clickable { onRun(project) }) {
        AppIcon(project.icon, modifier = Modifier.padding(start = 14.dp, top = 13.dp).size(20.dp), tint = Ink.copy(alpha = .65f))
        Text(project.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 12.dp))
        Box(Modifier.align(Alignment.TopEnd)) {
            IconAction("ellipsis", "${project.name}菜单", { menu = true }, tint = Muted.copy(alpha = .52f), iconSize = 18.dp)
            ActionMenu(menu, { menu = false }) {
                ActionMenuItem("编辑项目", "pencil", { menu = false; onEdit(project) })
                ActionMenuItem("项目设置", "settings-2", { menu = false; onProjectSettings(project) })
                ActionMenuItem(if (project.pinned) "取消置顶" else "置顶", "pin", { menu = false; onUpdate(project.copy(pinned = !project.pinned)) })
                ActionMenuItem("导出", "upload", { menu = false; onExport(project) })
                ActionMenuItem("查看日志", "terminal", { menu = false; onViewLogs(project) })
                ActionMenuItem("清除缓存", "brush", { menu = false; onClearCache(project) })
                ActionMenuItem("删除", "trash", { menu = false; onDelete(project) }, destructive = true)
            }
        }
    }
}

/** 服务启用时提供直接入口，显示状态完全由已有控制器驱动。 */
@Composable
private fun EditorRunningNotice(text: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = Blue.copy(alpha = .07f)) {
        Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon("screen-share", modifier = Modifier.size(20.dp), tint = Blue)
            Spacer(Modifier.width(10.dp))
            Text(text, Modifier.weight(1f), color = Blue, fontSize = 13.sp)
            AppIcon("chevron-right", modifier = Modifier.size(18.dp), tint = Blue)
        }
    }
}
