package io.todu.sview.wear

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.sview.data.Project
import io.todu.sview.ui.components.*

/** 勾选顺序就是手表顺序，调整只影响手表，不改变手机项目分类或排序。 */
@Composable
fun WatchConnectionScreen(controller: WatchConnection, projects: List<Project>, onBack: () -> Unit) {
    DisposableEffect(controller) { controller.start(); onDispose { controller.stop() } }
    LaunchedEffect(projects.map { it.id }) { controller.reconcile(projects.map { it.id }.toSet()) }
    var viewingLogs by remember { mutableStateOf(false) }
    if (viewingLogs) {
        WatchLogsScreen(controller.deviceId) { viewingLogs = false }
        return
    }
    var adding by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(setOf<String>()) }
    val list = rememberLazyListState()
    val drag = rememberReorderDragState(items = {
        list.layoutInfo.visibleItemsInfo.mapNotNull { item ->
            val id = item.key as? String
            if (id == null || id !in controller.selected) null else ReorderItem(id, item.index,
                IntOffset(0, item.offset), IntSize(list.layoutInfo.viewportSize.width, item.size))
        }
    }, move = controller::reorder, finish = controller::saveOrder,
        canMove = { _, _ -> !controller.busy })
    ReorderAutoScroll(drag, { list.layoutInfo.viewportStartOffset.toFloat() to list.layoutInfo.viewportEndOffset.toFloat() }, { list.scrollBy(it) })
    Column(Modifier.fillMaxSize()) {
        PageHeader("手表连接", onBack) {
            IconAction("file-spreadsheet", "手表日志", { viewingLogs = true }, tint = Blue,
                enabled = !controller.busy && controller.deviceId != null)
            if (controller.busy) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
            else IconAction("folder-sync", "同步", controller::sync, enabled = controller.devices.isNotEmpty() && drag.dragging == null, tint = Blue)
        }
        LazyColumn(state = list, modifier = Modifier.widthIn(max = 648.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { WatchDeviceCard(controller) }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("手表项目", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Muted)
                    IconAction("plus", "添加项目", { pending = emptySet(); adding = true }, tint = Blue,
                        enabled = !controller.busy && drag.dragging == null)
                }
            }
            val ordered = controller.selected.mapNotNull { id -> projects.find { it.id == id } }
            items(ordered, key = { it.id }) { project ->
                val lifted = drag.dragging == project.id
                val moving = lifted || drag.settling == project.id
                val scale by animateFloatAsState(if (lifted) 1.02f else 1f, label = "手表项目抬起")
                WatchProjectChoice(project, true, !controller.busy && drag.dragging == null,
                    { controller.toggle(project.id) }, removable = true,
                    inTile = project.id in controller.tileProjects, onTile = { controller.toggleTile(project.id) },
                    expanded = project.id in controller.expanded, onExpand = { controller.toggleExpanded(project.id) },
                    modifier = Modifier.animateItem(placementSpec = if (moving) null else spring(dampingRatio = .85f, stiffness = 450f))
                        .zIndex(if (moving) 1f else 0f).graphicsLayer {
                            val offset = drag.offset(project.id)
                            translationY = offset.y
                            scaleX = scale; scaleY = scale
                            shadowElevation = if (lifted) 12.dp.toPx() else 0f
                            shape = RoundedCornerShape(16.dp)
                        }.pointerInput(project.id, controller.busy) {
                            if (!controller.busy) detectDragGesturesAfterLongPress(
                                onDragStart = { drag.start(project.id) },
                                onDrag = { change, amount -> change.consume(); drag.drag(amount) },
                                onDragCancel = drag::end, onDragEnd = drag::end)
                        })
            }
            if (ordered.isEmpty()) item { Text("点击 + 添加项目", Modifier.padding(horizontal = 4.dp, vertical = 12.dp), color = Muted, fontSize = 13.sp) }
        }
    }
    if (adding) AppDialog(onDismissRequest = { adding = false }, title = { Text("添加项目") }, text = {
        val available = projects.filter { it.id !in controller.selected }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (available.isEmpty()) Text("所有项目均已添加", color = Muted)
            available.forEach { project ->
                key(project.id) {
                    WatchProjectChoice(project, project.id in pending, !controller.busy, {
                        pending = if (project.id in pending) pending - project.id else pending + project.id
                    })
                }
            }
        }
    }, confirmButton = {
        DialogAction("添加", {
            controller.addProjects(projects.filter { it.id in pending }.map { it.id })
            adding = false
        }, primary = true)
    }, dismissButton = { DialogAction("取消", { adding = false }) })

}

/** 单设备不展示多余的单选框，多设备时才展开选择。 */
@Composable
private fun WatchDeviceCard(controller: WatchConnection) {
    val connected = controller.devices.isNotEmpty()
    val current = controller.devices.find { it.id == controller.deviceId }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFFF5F6FA)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                AppIcon("watch", tint = if (connected) Blue else Muted, modifier = Modifier.size(26.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(current?.displayName?.replace('_', ' ') ?: "连接手表", fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(if (connected) Color(0xFF57A586) else Muted, CircleShape))
                    Text(controller.message.ifEmpty { if (connected) "已连接" else "未连接" }, Modifier.padding(start = 6.dp), fontSize = 12.sp, color = if (controller.message.isNotEmpty()) Blue else Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            IconAction("refresh-cw", "刷新连接", controller::refresh, modifier = Modifier.size(36.dp), enabled = !controller.busy, iconSize = 20.dp)
        }
        if (controller.busy) {
            Spacer(Modifier.height(12.dp))
            val progress = controller.progress
            if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Blue, trackColor = Line)
            else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = Blue, trackColor = Line)
        }
        if (!connected) Text("配对手表后，在手表上打开 S View", Modifier.padding(top = 12.dp), color = Muted, fontSize = 12.sp)
        if (controller.devices.size > 1) controller.devices.forEach { node ->
            Row(Modifier.fillMaxWidth().clickable(enabled = !controller.busy) { controller.chooseDevice(node.id) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(controller.deviceId == node.id, onClick = { controller.chooseDevice(node.id) }, enabled = !controller.busy)
                Text(node.displayName.replace('_', ' '), fontSize = 13.sp)
            }
        }
    }
}

/** 选择弹窗支持整行勾选，已添加列表在右侧提供扩展与移除操作。 */
@Composable
private fun WatchProjectChoice(project: Project, selected: Boolean, enabled: Boolean, onToggle: () -> Unit, removable: Boolean = false, expanded: Boolean = false, onExpand: () -> Unit = {}, inTile: Boolean = false, onTile: () -> Unit = {}, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Row(modifier.fillMaxWidth().clip(shape).background(if (selected) Color(0xFFF7F8FC) else Color.White)
        .border(1.dp, if (selected) Color(0xFFE9EDF6) else Line, shape)
        .then(if (removable) Modifier else Modifier.toggleable(selected, enabled = enabled, role = Role.Checkbox) { onToggle() }).padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(CardColors[project.color.coerceIn(CardColors.indices)]), contentAlignment = Alignment.Center) {
            AppIcon(project.icon, modifier = Modifier.size(22.dp))
        }
        Text(project.name, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
        if (removable) {
            IconAction("circle-fading-play", if (inTile) "从磁贴移除" else "显示在磁贴", onTile,
                enabled = enabled, tint = if (inTile) Blue else Muted.copy(alpha = .5f), iconSize = 20.dp)
            IconAction("square-stop", if (expanded) "关闭扩展" else "开启扩展", onExpand,
                enabled = enabled, tint = if (expanded) Blue else Muted.copy(alpha = .5f), iconSize = 20.dp)
            IconAction("x", "移除${project.name}", onToggle, enabled = enabled, iconSize = 18.dp)
        }
        else Box(Modifier.padding(6.dp).size(22.dp).background(if (selected) Blue else Color(0xFFF0F3FA), CircleShape), contentAlignment = Alignment.Center) {
            AppIcon(if (selected) "check" else "plus", tint = if (selected) Color.White else Muted, modifier = Modifier.size(14.dp))
        }
    }
}
