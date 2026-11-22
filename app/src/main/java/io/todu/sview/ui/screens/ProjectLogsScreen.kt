package io.todu.sview.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.SolidColor
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.sview.data.WorkspaceModel
import io.todu.sview.ui.components.*

/** 按项目查看持久日志；只显示末尾内容，导出保留文件中的完整记录。 */
@Composable
fun ProjectLogsScreen(model: WorkspaceModel, onBack: () -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    var exportId by remember { mutableStateOf<String?>(null) }
    var clear by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val context = LocalContext.current
    val projectIds = model.projects.map { it.id }
    LaunchedEffect(selected, projectIds) { model.refreshLogSizes() }
    LaunchedEffect(selected, query) {
        selected?.let {
            if (query.isNotEmpty()) delay(180)
            model.openLogs(it, query)
        }
    }
    DisposableEffect(selected) {
        onDispose { model.closeLogs() }
    }
    val project = model.projects.find { it.id == selected }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val id = exportId
        if (uri != null && id != null) model.exportLogs(id, uri)
        exportId = null
    }
    fun back() { if (selected != null) { selected = null; query = "" } else onBack() }
    BackHandler(selected != null) { back() }
    Column(Modifier.fillMaxSize()) {
        PageHeader(project?.name ?: "项目日志", ::back) {
            if (project != null) {
                IconAction("upload", "导出日志", { exportId = project.id; exporter.launch("${project.name}-日志.txt") })
                IconAction("trash", "清空日志", { clear = true })
            }
        }
        if (project != null || model.projects.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)
                .background(Panel, RoundedCornerShape(12.dp)).heightIn(min = 44.dp).padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(query, { query = it }, modifier = Modifier.weight(1f), singleLine = true,
                    textStyle = TextStyle(color = Ink, fontSize = 13.sp), cursorBrush = SolidColor(Blue),
                    decorationBox = { field ->
                        Box {
                            if (query.isEmpty()) Text(if (project == null) "搜索项目" else "搜索日志内容", color = Muted, fontSize = 13.sp)
                            field()
                        }
                    })
                if (query.isNotEmpty()) Box(Modifier.height(44.dp).clickable { query = "" }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text("清除", fontSize = 12.sp, color = Muted)
                }
            }
        }
        val matches = remember(model.projects, query) { model.projects.filter { it.name.contains(query.trim(), ignoreCase = true) } }
        when {
            project == null && model.projects.isEmpty() -> EmptyState("terminal", "还没有项目日志", modifier = Modifier.weight(1f),
                description = "运行项目后，打印和报错会保存在这里", showIcon = false)
            project == null && matches.isEmpty() -> EmptyState("search", "没有匹配的项目", modifier = Modifier.weight(1f),
                description = "换个关键词试试", actionLabel = "清除搜索", onAction = { query = "" }, showIcon = false)
            project == null -> LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp)) {
                items(matches, key = { it.id }) { item ->
                    Row(Modifier.fillMaxWidth().height(48.dp).clickable {
                        query = ""; selected = item.id
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Text(item.name, Modifier.weight(1f).padding(end = 16.dp), fontSize = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(model.logSizes[item.id]?.let { Formatter.formatShortFileSize(context, it) } ?: "—", color = Muted, fontSize = 12.sp)
                    }
                    HorizontalDivider(color = Line)
                }
            }
            !model.logReady || model.logProjectId != project.id -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            model.logText.isBlank() -> EmptyState("terminal", if (query.isBlank()) "暂无日志" else "没有匹配的记录", modifier = Modifier.weight(1f),
                description = if (query.isBlank()) "运行时的打印和报错会自动记录" else "换个关键词试试",
                actionLabel = if (query.isBlank()) null else "清除搜索", onAction = { query = "" }, showIcon = false)
            else -> {
                val lines = remember(model.logText) { model.logText.lines() }
                LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)) {
                    items(lines) { line ->
                        Text(line, fontSize = 11.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace,
                            color = if (line.contains("error:", ignoreCase = true)) androidx.compose.ui.graphics.Color(0xFFC45360) else Ink)
                    }
                }
            }
        }
    }
    if (clear && project != null) AppDialog({ clear = false }, title = { Text("清空日志？") },
        confirmButton = { DialogAction("删除", { model.clearProjectLogs(project.id); clear = false }) },
        dismissButton = { DialogAction("取消", { clear = false }) })
}
