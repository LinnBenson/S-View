package io.todu.sview.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.sview.data.HeadReference
import io.todu.sview.data.ReferenceCatalog
import io.todu.sview.data.WorkspaceModel
import io.todu.sview.ui.components.*

/** 引用项与资源、变量使用相同的列表和编辑弹窗。 */
@Composable
fun ReferencesScreen(model: WorkspaceModel) {
    var quickAdd by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<HeadReference?>(null) }
    var removing by remember { mutableStateOf<HeadReference?>(null) }
    val references = model.project?.references.orEmpty()
    val projectId = model.projectId
    val list = rememberLazyListState()
    val drag = rememberReorderDragState(items = {
        list.layoutInfo.visibleItemsInfo.map { ReorderItem(it.key as String, it.index,
            IntOffset(0, it.offset), IntSize(list.layoutInfo.viewportSize.width, it.size)) }
    }, move = { from, to -> projectId?.let { model.reorderReference(it, from, to) } },
        finish = { model.saveProjectOrder() })
    ReorderAutoScroll(drag,
        { list.layoutInfo.viewportStartOffset.toFloat() to list.layoutInfo.viewportEndOffset.toFloat() },
        { list.scrollBy(it) })
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        SectionToolbar("引用项") {
            IconAction("folders", "快速添加", { quickAdd = true })
            IconAction("plus", "添加引用", { editing = null; visible = true })
        }
        if (references.isEmpty()) EmptyState("file-code", "还没有引用项", modifier = Modifier.weight(1f),
            actionLabel = "添加引用", onAction = { editing = null; visible = true })
        else LazyColumn(state = list, modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(references, key = { it.id }) { reference ->
                var menu by remember { mutableStateOf(false) }
                val lifted = drag.dragging == reference.id
                val moving = lifted || drag.settling == reference.id
                val scale by animateFloatAsState(if (lifted) 1.02f else 1f, label = "引用抬起")
                Column(Modifier.animateItem(placementSpec = if (moving) null else spring(dampingRatio = .85f, stiffness = 450f))
                    .zIndex(if (moving) 1f else 0f).graphicsLayer {
                        val offset = drag.offset(reference.id)
                        translationX = offset.x; translationY = offset.y
                        scaleX = scale; scaleY = scale
                        shadowElevation = 0f
                    }.background(if (lifted) Panel else Color.White, RoundedCornerShape(12.dp))
                    .pointerInput(reference.id) {
                        detectDragGesturesAfterLongPress(onDragStart = { drag.start(reference.id) },
                            onDrag = { change, amount -> change.consume(); drag.drag(amount) },
                            onDragCancel = drag::end, onDragEnd = drag::end)
                    }) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable { editing = reference; visible = true },
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Panel), contentAlignment = Alignment.Center) {
                            AppIcon(if (reference.type == "js") "file-code" else "palette", modifier = Modifier.size(18.dp), tint = Muted)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp)) {
                            Text(reference.source, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${reference.type.uppercase()} · ${HeadReference.modes(reference.type)[reference.mode].orEmpty()}", color = Muted, fontSize = 11.sp)
                        }
                        Box {
                            IconAction("ellipsis", "引用操作", { menu = true }, iconSize = 18.dp)
                            ActionMenu(menu, { menu = false }) {
                                ActionMenuItem("编辑", "pencil", { menu = false; editing = reference; visible = true })
                                ActionMenuItem("删除", "trash", { menu = false; removing = reference }, destructive = true)
                            }
                        }
                    }
                    HorizontalDivider(color = Line)
                }
            }
        }
    }
    if (quickAdd) AppDialog({ quickAdd = false }, title = { Text("快速添加") }, text = {
        Column {
            ReferenceCatalog.items.forEach { preset ->
                val added = references.any { it.source == preset.source }
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !added) {
                        editing = preset.createReference()
                        quickAdd = false
                        visible = true
                    }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Panel), contentAlignment = Alignment.Center) {
                        AppIcon("file-code", modifier = Modifier.size(18.dp), tint = Muted)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(preset.name, fontSize = 14.sp)
                        Text(if (preset.version.isBlank()) "全局模板" else "${preset.version} · 本地", fontSize = 11.sp, color = Muted)
                    }
                    AppIcon(if (added) "check" else "plus", if (added) "已添加" else "选择引用", Modifier.size(18.dp), tint = if (added) Muted else Blue)
                }
                HorizontalDivider(color = Line)
            }
        }
    }, confirmButton = { DialogAction("关闭", { quickAdd = false }) })
    if (visible) ReferenceDialog(editing, { visible = false }, references.none { it.id == editing?.id }) { reference ->
        model.project?.let { project ->
            val updated = if (project.references.any { it.id == reference.id }) project.references.map { if (it.id == reference.id) reference else it }
                else project.references + reference
            model.updateProject(project.copy(references = updated))
        }
    }
    removing?.let { reference ->
        AppDialog({ removing = null }, title = { Text("删除引用？") },
            confirmButton = { DialogAction("删除", {
                model.project?.let { model.updateProject(it.copy(references = it.references.filterNot { item -> item.id == reference.id })) }
                removing = null
            }) }, dismissButton = { DialogAction("取消", { removing = null }) })
    }
}

/** 类型切换重置加载方式，避免把 JS 的属性带入 CSS。 */
@Composable
private fun ReferenceDialog(reference: HeadReference?, onDismiss: () -> Unit, isNew: Boolean, onSave: (HeadReference) -> Unit) {
    var source by remember { mutableStateOf(reference?.source.orEmpty()) }
    var type by remember { mutableStateOf(reference?.type ?: "js") }
    var mode by remember { mutableStateOf(reference?.mode ?: "default") }
    var error by remember { mutableStateOf<String?>(null) }
    AppDialog(onDismiss, title = { Text(if (isNew) "添加引用" else "编辑引用") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ReferenceChoices(linkedMapOf("js" to "JavaScript", "css" to "CSS"), type) { type = it; mode = "default" }
            AppTextField(source, { source = it; error = null }, label = { Text("地址") }, singleLine = true)
            Text("加载方式", fontSize = 12.sp, color = Muted)
            ReferenceChoices(HeadReference.modes(type), mode) { mode = it }
            error?.let { Text(it, color = androidx.compose.ui.graphics.Color(0xFFC45360), fontSize = 12.sp) }
        }
    }, confirmButton = { DialogAction("保存", {
        try {
            val value = (reference ?: HeadReference(source = "")).copy(source = source.trim(), type = type, mode = mode)
            value.validate()
            onSave(value)
            onDismiss()
        } catch (exception: IllegalArgumentException) { error = exception.message }
    }, primary = true) }, dismissButton = { DialogAction("取消", onDismiss) })
}

/** 两列选项保留稳定点击面积，窄屏也不压缩文字。 */
@Composable
private fun ReferenceChoices(options: Map<String, String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (key, label) ->
                    val active = selected == key
                    Row(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (active) Blue.copy(alpha = .09f) else Panel).clickable { onSelect(key) }
                        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), color = if (active) Blue else Muted, fontSize = 13.sp)
                        if (active) AppIcon("check", modifier = Modifier.size(15.dp), tint = Blue)
                    }
                }
            }
        }
    }
}
