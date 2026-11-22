package io.todu.sview.wear

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.sview.ui.components.*
import kotlinx.coroutines.CancellationException

private data class WatchLogProject(val id: String, val name: String, val size: Long)

/** 手机按需查看手表日志，退出即释放正文；手表界面不增加日志页面。 */
@Composable
fun WatchLogsScreen(node: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    var projects by remember(node) { mutableStateOf(emptyList<WatchLogProject>()) }
    var selected by remember(node) { mutableStateOf<WatchLogProject?>(null) }
    var text by remember(node) { mutableStateOf("") }
    var error by remember(node) { mutableStateOf("") }
    var loading by remember(node) { mutableStateOf(true) }
    var revision by remember { mutableIntStateOf(0) }
    fun back() { if (selected != null) selected = null else onBack() }
    BackHandler { back() }
    LaunchedEffect(node, selected, revision) {
        loading = true
        error = ""
        text = ""
        try {
            check(node != null) { "请先连接手表" }
            val result = WatchLogsProtocol.fetch(context, node, selected?.id)
            if (selected == null) {
                val entries = result.getJSONArray("projects")
                projects = List(entries.length()) { index ->
                    val entry = entries.getJSONObject(index)
                    WatchLogProject(entry.getString("id"), entry.getString("name"), entry.getLong("size"))
                }
            } else text = result.getString("text")
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = failure.message ?: "无法读取日志，请检查手表连接"
        } finally { loading = false }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(selected?.name ?: "手表日志", ::back) {
            IconAction("refresh-cw", "刷新日志", { revision++ }, enabled = !loading, tint = Blue)
        }
        Box(Modifier.weight(1f).widthIn(max = 840.dp).fillMaxWidth().align(Alignment.CenterHorizontally)) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error.isNotEmpty() -> EmptyState("terminal", "无法读取日志", description = error,
                    actionLabel = "重试", onAction = { revision++ }, showIcon = false)
                selected == null -> {
                    if (projects.isEmpty()) EmptyState("terminal", "手表暂无项目", showIcon = false)
                    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp)) {
                        items(projects, key = { it.id }) { project ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { selected = project },
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(project.name, Modifier.weight(1f).padding(end = 16.dp), fontSize = 14.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(Formatter.formatShortFileSize(context, project.size), color = Muted, fontSize = 12.sp)
                            }
                            HorizontalDivider(color = Line)
                        }
                    }
                }
                text.isBlank() -> EmptyState("terminal", "暂无日志", showIcon = false)
                else -> {
                    val lines = remember(text) { text.lines() }
                    SelectionContainer {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
                            items(lines) { line ->
                                Text(line, fontSize = 11.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace, color = Ink)
                            }
                        }
                    }
                }
            }
        }
    }
}
