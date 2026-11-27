package io.todu.sview.share

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import io.todu.sview.data.Project
import io.todu.sview.data.WorkspaceOwner
import io.todu.sview.runtime.RuntimeAppExit
import io.todu.sview.runtime.RuntimeWindow
import io.todu.sview.ui.components.HomeSViewMark
import io.todu.sview.ui.components.AppIcon
import io.todu.sview.ui.theme.SViewTheme
import io.todu.sview.wear.WatchDevice

/** 系统分享统一选项目，URL 按名字直达；不替换已有运行窗口。 */
class ShareActivity : ComponentActivity() {
    private val workspace by lazy { WorkspaceOwner.model(application) }
    private val receiver by lazy { ViewModelProvider(this)[ShareReceiveModel::class.java] }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        receiver.receive(intent)
        setContent { SViewTheme { Content() } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        io.todu.sview.runtime.background.BackgroundWindow.accept(this, intent)
    }

    @Composable private fun Content() {
        val watch = WatchDevice.isWatch(this)
        val payload = receiver.payload
        val input = receiver.input
        var running by remember { mutableStateOf<Project?>(null) }
        var preparing by remember { mutableStateOf(false) }
        var runError by remember { mutableStateOf<String?>(null) }
        val matches = if (input == null) emptyList() else if (input.scheme)
            workspace.projects.firstOrNull { it.name == input.projectName }?.let(::listOf).orEmpty()
        else workspace.projects.filter { it.shareSource.accepts(input.uris.isNotEmpty()) }

        fun start(project: Project) {
            if (preparing || running != null || payload == null) return
            receiver.selectedId = project.id
            if (RuntimeAppExit.hasForegroundWindows) { runError = "请先关闭正在运行的窗口"; return }
            if (workspace.appExiting || workspace.watchSyncBusy || workspace.clearingCache) { runError = "请等待当前操作完成"; return }
            runError = null
            workspace.error = null
            preparing = true
            workspace.prepareRun(project.id) { ready ->
                preparing = false
                if (!isFinishing && !isDestroyed) {
                    if (RuntimeAppExit.hasWindows) runError = "请先关闭正在运行的窗口"
                    else if (!input!!.scheme && !ready.shareSource.accepts(input.uris.isNotEmpty())) runError = "项目已禁用此类分享"
                    else running = ready
                }
            }
        }
        LaunchedEffect(workspace.ready, payload, matches.map { it.id }, workspace.watchSyncBusy, workspace.clearingCache, workspace.appExiting) {
            if (workspace.ready && payload != null && !workspace.watchSyncBusy && !workspace.clearingCache && !workspace.appExiting) {
                val chosen = matches.firstOrNull { it.id == receiver.selectedId } ?: matches.singleOrNull()
                if (chosen != null) start(chosen)
            }
        }
        LaunchedEffect(workspace.error) {
            if (preparing && workspace.error != null) { runError = workspace.error; preparing = false }
        }
        Surface(Modifier.fillMaxSize(), color = if (watch) Color.Black else Color.White,
            contentColor = if (watch) Color.White else MaterialTheme.colorScheme.onSurface) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (running == null) Column(Modifier.widthIn(max = 840.dp).fillMaxSize()
                    .systemBarsPadding().padding(horizontal = if (watch) 28.dp else 0.dp, vertical = if (watch) 32.dp else 0.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = if (watch) 0.dp else 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (input?.scheme == true) "S View" else "选择脚本", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { finish() }) { AppIcon("x", "关闭", Modifier.size(22.dp)) }
                    }
                    if (!watch) HorizontalDivider(color = io.todu.sview.ui.components.Line)
                    val error = receiver.error ?: runError ?: (if (!workspace.ready) workspace.error else null) ?: if (workspace.ready && payload != null && !workspace.watchSyncBusy && matches.isEmpty())
                        (if (input?.scheme == true) "找不到项目：${input.projectName}" else "没有接收此类分享的项目") else null
                    if (error != null) {
                        Text(error, Modifier.padding(horizontal = if (watch) 0.dp else 20.dp, vertical = 24.dp))
                        if (runError != null && matches.isNotEmpty()) TextButton(onClick = {
                            val selected = matches.firstOrNull { it.id == receiver.selectedId }
                            if (selected != null) start(selected) else { receiver.selectedId = null; runError = null }
                        }) { Text("重试") }
                    } else if (!workspace.ready || payload == null || preparing || workspace.watchSyncBusy) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else LazyVerticalGrid(columns = if (watch) GridCells.Fixed(1) else GridCells.Adaptive(280.dp), modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(horizontal = if (watch) 0.dp else 20.dp, vertical = if (watch) 8.dp else 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(matches, key = { it.id }) { project ->
                            ShareProjectCard(project, compact = watch) { start(project) }
                        }
                    }
                }
                if (running != null && io.todu.sview.runtime.background.BackgroundWindow.active != null) {
                    HomeSViewMark()
                    // 返回工作区时保留分享任务，用户仍可从首页或通知恢复。
                    BackHandler {
                        val home = if (watch) io.todu.sview.wear.WatchActivity::class.java else io.todu.sview.MainActivity::class.java
                        startActivity(android.content.Intent(this@ShareActivity, home).addFlags(
                            android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    }
                }
                running?.let { project ->
                    RuntimeWindow(project, workspace.repository, { workspace.appendLog(project, it) },
                        workspace::cornerRadiusRunProject, workspace::marginRunProject, workspace::locateRunProject,
                        workspace::resizeRunProject, { message ->
                            android.widget.Toast.makeText(this@ShareActivity, message, android.widget.Toast.LENGTH_LONG).show()
                        }, share = payload) {
                        running = null
                        workspace.flushLogWrites()
                        finish()
                    }
                }
            }
        }
    }
}
