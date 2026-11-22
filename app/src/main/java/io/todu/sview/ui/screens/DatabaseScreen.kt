package io.todu.sview.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.sview.data.ScriptVariable
import io.todu.sview.data.WorkspaceModel
import io.todu.sview.ui.components.*

/** 全部项目共用的键值数据；编辑沿用变量弹窗，数组和对象使用 JSON 编辑器。 */
@Composable
fun DatabaseScreen(model: WorkspaceModel, onBack: () -> Unit) {
    var dialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ScriptVariable?>(null) }
    var deleting by remember { mutableStateOf<ScriptVariable?>(null) }
    LaunchedEffect(model) { model.watchDatabase() }
    Column(Modifier.fillMaxSize()) {
        PageHeader("数据库", onBack) {
            IconAction("plus", "添加数据", { editing = null; dialog = true }, enabled = model.databaseReady)
        }
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().weight(1f).align(Alignment.CenterHorizontally).padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("所有项目共享", Modifier.weight(1f), color = Muted, fontSize = 12.sp)
                if (model.databaseReady) Text("${model.databaseEntries.size} 项", color = Muted, fontSize = 12.sp)
            }
            if (!model.databaseReady) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else if (model.databaseEntries.isEmpty()) EmptyState("database", "还没有数据", modifier = Modifier.weight(1f),
                actionLabel = "添加数据", onAction = { editing = null; dialog = true })
            else LazyColumn(Modifier.weight(1f)) {
                items(model.databaseEntries, key = { it.name }) { entry ->
                    TypedValueRow(entry,
                        onEdit = { editing = entry; dialog = true },
                        onDelete = { deleting = entry },
                        onToggle = { model.saveDatabase(entry.copy(value = it.toString()), entry) })
                }
            }
        }
    }
    if (dialog) VariableDialog(editing, { dialog = false }, database = true) { model.saveDatabase(it, editing) }
    deleting?.let { entry ->
        AppDialog(onDismissRequest = { deleting = null }, title = { Text("删除数据") }, text = { Text("确定删除「${entry.name}」？") },
            dismissButton = { DialogAction("取消", { deleting = null }) },
            confirmButton = { DialogAction("删除", { model.removeDatabase(entry); deleting = null }) })
    }
}
