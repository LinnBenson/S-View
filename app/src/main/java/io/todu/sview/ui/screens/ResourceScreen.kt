package io.todu.sview.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.sview.data.ReferenceCatalog
import io.todu.sview.data.ResourceFile
import io.todu.sview.data.WorkspaceModel
import io.todu.sview.ui.components.*

/** 模板与项目资源合并显示；编辑模板时在明确的全局入口操作。 */
@Composable
fun ResourceScreen(model: WorkspaceModel, onOpen: (ResourceFile) -> Unit, onImport: () -> Unit, compact: Boolean = false) {
    val templates = model.projectId == null
    var createFolder by remember { mutableStateOf<Boolean?>(null) }
    var name by remember { mutableStateOf("") }
    var remove by remember { mutableStateOf<ResourceFile?>(null) }
    var restore by remember { mutableStateOf<ResourceFile?>(null) }
    var restoreAll by remember { mutableStateOf(false) }
    var systemExpanded by rememberSaveable { mutableStateOf(false) }
    val showSystemGroup = templates && model.directory.isEmpty()
    val (systemFiles, otherFiles) = remember(model.files, showSystemGroup) {
        model.files.partition { showSystemGroup && !it.directory && it.path in ReferenceCatalog.systemTemplateNames }
    }
    val visibleFiles = if (showSystemGroup) {
        if (systemExpanded) systemFiles + otherFiles else otherFiles
    } else model.files
    Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 12.dp else 24.dp)) {
        if (!templates || model.directory.isNotEmpty()) {
            SectionToolbar(if (model.directory.isEmpty()) "资源" else model.directory.substringAfterLast('/'), leading = {
                if (model.directory.isNotEmpty()) IconAction("arrow-left", "上一级", { model.refresh(model.directory.substringBeforeLast('/', "")) })
            }) {
                if (!templates) {
                    IconAction("file-plus", "新建文件", { name = ""; createFolder = false })
                    if (!compact) {
                        IconAction("folder-plus", "新建目录", { name = ""; createFolder = true })
                        IconAction("import", "导入资源", onImport)
                    } else {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconAction("ellipsis", "资源操作", { menu = true })
                            ActionMenu(menu, { menu = false }) {
                                ActionMenuItem("新建目录", "folder-plus", { menu = false; name = ""; createFolder = true })
                                ActionMenuItem("导入资源", "import", { menu = false; onImport() })
                            }
                        }
                    }
                }
            }
        } else Spacer(Modifier.height(8.dp))
        // 分组标题放在滚动列表外，折叠后不创建系统文件行。
        if (showSystemGroup) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 48.dp).clickable { systemExpanded = !systemExpanded },
                    verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(if (systemExpanded) "chevron-down" else "chevron-right", modifier = Modifier.size(20.dp))
                    Text("系统模板", modifier = Modifier.padding(start = 8.dp), fontSize = if (compact) 13.sp else 15.sp)
                }
                IconAction("undo-2", "还原全部系统模板", { restoreAll = true }, enabled = !model.restoringTemplate)
            }
        }
        if (model.files.isEmpty() && !showSystemGroup) {
            EmptyState("folder", if (templates) "暂无模板" else "文件夹为空", modifier = Modifier.weight(1f),
                actionLabel = if (templates) "导入模板" else "新建文件",
                onAction = if (templates) onImport else ({ name = ""; createFolder = false }))
        } else LazyColumn(Modifier.weight(1f)) {
            items(visibleFiles, key = { it.path }) { entry ->
                Row(Modifier.fillMaxWidth().height(if (compact) 48.dp else 68.dp).clickable {
                    if (entry.directory) model.refresh(entry.path) else onOpen(entry)
                }, verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(if (entry.directory) "folder" else if (entry.path.endsWith(".txt")) "file-text" else "file-code", modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(entry.path.substringAfterLast('/'), fontSize = if (compact) 13.sp else 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (entry.global) Text("全局模板", color = Muted, fontSize = 10.sp)
                        else if (!compact && !entry.directory) Text(if (entry.size < 1024) "${entry.size} B" else "${entry.size / 1024} KB", color = Muted, fontSize = 11.sp)
                    }
                    if (templates && !entry.directory && entry.path in ReferenceCatalog.systemTemplateNames) {
                        IconAction("undo-2", "还原 ${entry.path}", { restore = entry }, enabled = !model.restoringTemplate)
                    } else if (!compact) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconAction("ellipsis", "${entry.path}菜单", { menu = true })
                            ActionMenu(menu, { menu = false }) {
                                if (!entry.directory) ActionMenuItem(if (entry.global) "覆盖为项目文件" else "编辑", "pencil", { menu = false; onOpen(entry) })
                                if (!entry.global) ActionMenuItem("删除", "trash", { menu = false; remove = entry }, destructive = true)
                            }
                        }
                    }
                }
                if (!compact) HorizontalDivider(color = Line)
            }
        }
    }
    createFolder?.let { folder -> AppDialog(onDismissRequest = { createFolder = null }, title = { Text(if (folder) "新建目录" else "新建文件") },
        text = { AppTextField(name, { name = it }, label = { Text("名称") }, singleLine = true) },
        confirmButton = { DialogAction("创建", { if (name.isNotBlank()) { model.createFile(name.trim(), folder); createFolder = null } }, primary = true) },
        dismissButton = { DialogAction("取消", { createFolder = null }) }) }
    remove?.let { entry -> AppDialog(onDismissRequest = { remove = null }, title = { Text("删除 ${entry.path.substringAfterLast('/')}？") },
        confirmButton = { DialogAction("删除", { model.deleteFile(entry); remove = null }) },
        dismissButton = { DialogAction("取消", { remove = null }) }) }
    if (restoreAll) AppDialog(onDismissRequest = { restoreAll = false }, title = { Text("还原全部系统模板？") },
        text = { Text("系统模板将恢复为内置版本，已有修改将丢失。自建模板不受影响。") },
        confirmButton = { DialogAction("还原", { model.restoreAllTemplates(); restoreAll = false }, primary = true) },
        dismissButton = { DialogAction("取消", { restoreAll = false }) })
    restore?.let { entry -> AppDialog(onDismissRequest = { restore = null }, title = { Text("还原 ${entry.path}？") },
        text = { Text("替换为当前安装包内置版本，此文件的修改将丢失。") },
        confirmButton = { DialogAction("还原", { model.restoreTemplate(entry); restore = null }, primary = true) },
        dismissButton = { DialogAction("取消", { restore = null }) }) }
}
