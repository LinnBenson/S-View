package io.todu.sview.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.todu.sview.data.WorkspaceModel
import io.todu.sview.ui.components.*

/** 分类增删与排序集中在一页，删除时只调整归属，不删除项目文件。 */
@Composable
fun CategoriesScreen(model: WorkspaceModel, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) }
    var form by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val counts = remember(model.projects) { model.projects.groupingBy { it.category }.eachCount() }
    val drag = rememberReorderDragState(items = {
        list.layoutInfo.visibleItemsInfo.map { ReorderItem(it.key as String, it.index,
            IntOffset(0, it.offset), IntSize(list.layoutInfo.viewportSize.width, it.size)) }
    }, move = model::reorderCategory, finish = model::saveCategoryOrder)
    ReorderAutoScroll(drag, { list.layoutInfo.viewportStartOffset.toFloat() to list.layoutInfo.viewportEndOffset.toFloat() }, { list.scrollBy(it) })
    Column(Modifier.fillMaxSize()) {
        PageHeader("分类", onBack) {
            IconAction("plus", "新增分类", { editing = null; name = ""; error = null; form = true })
        }
        if (model.categories.isEmpty()) {
            EmptyState("folders", "还没有分类", modifier = Modifier.weight(1f), actionLabel = "新增分类",
                onAction = { editing = null; name = ""; error = null; form = true })
        } else LazyColumn(state = list, modifier = Modifier.widthIn(max = 648.dp).fillMaxWidth().weight(1f)
            .align(Alignment.CenterHorizontally).padding(horizontal = 24.dp), contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(model.categories, key = { it }) { category ->
                val lifted = drag.dragging == category
                val moving = lifted || drag.settling == category
                val scale by animateFloatAsState(if (lifted) 1.02f else 1f, label = "分类抬起")
                Row(Modifier.animateItem(placementSpec = if (moving) null else spring(dampingRatio = .85f, stiffness = 450f))
                    .zIndex(if (moving) 1f else 0f).graphicsLayer {
                        val offset = drag.offset(category)
                        translationX = offset.x; translationY = offset.y
                        scaleX = scale; scaleY = scale
                        shape = RoundedCornerShape(12.dp)
                    }.fillMaxWidth().height(60.dp).background(if (lifted) Panel else Color.White, RoundedCornerShape(12.dp))
                    .pointerInput(category) {
                        detectDragGesturesAfterLongPress(onDragStart = { drag.start(category) },
                            onDrag = { change, amount -> change.consume(); drag.drag(amount) },
                            onDragCancel = drag::end, onDragEnd = drag::end)
                    }.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon("folder", modifier = Modifier.size(20.dp), tint = Muted)
                    Text(category, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    Text("${counts[category] ?: 0}", color = Muted, fontSize = 12.sp)
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconAction("ellipsis", "$category 菜单", { menu = true }, tint = Muted.copy(alpha = .65f), iconSize = 18.dp)
                            ActionMenu(menu, { menu = false }) {
                                ActionMenuItem("重命名", "pencil", { menu = false; editing = category; name = category; error = null; form = true })
                                ActionMenuItem("删除", "trash", { menu = false; removing = category }, destructive = true)
                            }
                        }
                }
            }
        }
    }
    if (form) AppDialog(onDismissRequest = { form = false }, title = { Text(if (editing == null) "新增分类" else "重命名分类") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTextField(name, { name = it; error = null }, label = { Text("名称") }, singleLine = true)
            error?.let { Text(it, color = Color(0xFFB2545E), fontSize = 12.sp) }
        }
    }, confirmButton = { DialogAction("保存", {
        try { model.saveCategory(name, editing); form = false } catch (exception: IllegalArgumentException) { error = exception.message }
    }, primary = true) }, dismissButton = { DialogAction("取消", { form = false }) })
    removing?.let { category -> AppDialog(onDismissRequest = { removing = null }, title = { Text("删除$category？") },
        text = { Text("项目会保留在“全部”中。") }, confirmButton = { DialogAction("删除", { model.deleteCategory(category); removing = null }) },
        dismissButton = { DialogAction("取消", { removing = null }) }) }
}
