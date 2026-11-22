package io.todu.sview.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.sview.data.ResourceFile
import io.todu.sview.data.ScriptVariable
import io.todu.sview.data.WorkspaceModel
import io.todu.sview.ui.components.*
import io.todu.sview.ui.editor.CodeEditor
import io.todu.sview.ui.editor.SyntaxHighlighter

/** 宽屏保留资源栏，窄屏用标签切换；编辑状态由 ViewModel 保留。 */
@Composable
fun EditorScreen(model: WorkspaceModel, wide: Boolean, templates: Boolean, fontSize: Int, onBack: () -> Unit,
    onRun: () -> Unit, onSettings: () -> Unit, onImport: () -> Unit, onImage: (ResourceFile) -> Unit) {
    var tab by remember(templates, model.projectId) { mutableStateOf(if (templates) "资源" else "代码") }
    var menu by remember { mutableStateOf(false) }
    var console by remember { mutableStateOf(false) }
    var help by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // 仅收起代码页的辅助区域，不重建编辑器，保留光标、滚动和撤销历史。
    val editingWithKeyboard = tab == "代码" && !help && WindowInsets.ime.getBottom(LocalDensity.current) > 0
    fun open(entry: ResourceFile) {
        val extension = entry.path.substringAfterLast('.').lowercase()
        if (extension in listOf("png", "jpg", "jpeg", "webp", "gif")) onImage(entry)
        else if (extension in listOf("js", "mjs", "html", "htm", "css", "json", "txt", "md", "svg", "xml", "csv") || !entry.path.contains('.')) {
            model.openFile(entry.path); tab = "代码"
        } else model.error = "此文件暂不支持查看"
    }
    Column(Modifier.fillMaxSize()) {
        if (!editingWithKeyboard) PageHeader(if (templates) "模板文件" else model.project?.name ?: "项目",
            onBack = { if (templates && tab == "代码") tab = "资源" else onBack() }) {
            if (templates) {
                if (tab == "代码") IconAction("save", "保存模板", model::saveManually)
                else IconAction("import", "导入模板", onImport)
            } else {
                IconAction("circle-help", "系统接口帮助", { focusManager.clearFocus(); help = true })
                IconAction("play", "运行项目", onRun)
                Box {
                    IconAction("ellipsis", "项目操作", { menu = true })
                    ActionMenu(menu, { menu = false }) {
                        ActionMenuItem("项目设置", "settings-2", { menu = false; onSettings() })
                        ActionMenuItem("保存", "save", { menu = false; model.saveManually() })
                    }
                }
            }
        }
        if (!editingWithKeyboard && !wide && !templates) Row(Modifier.height(52.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            (if (templates) listOf("代码", "资源") else listOf("代码", "资源", "引用项", "全局变量")).forEach { label ->
                Column(Modifier.width(if (label == "全局变量") 104.dp else 84.dp).fillMaxHeight().clickable {
                    if (label == "代码" && model.filePath != "main.js") model.openFile("main.js")
                    tab = label
                }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(label, fontSize = 14.sp, color = if (tab == label) Blue else Muted) }
                    if (tab == label) Box(Modifier.width(36.dp).height(2.dp).background(Blue))
                }
            }
        }
        Row(Modifier.weight(1f)) {
            if (wide && !templates) Column(Modifier.width(230.dp).fillMaxHeight()) {
                Box(Modifier.weight(1f)) { ResourceScreen(model, ::open, onImport, compact = true) }
                TextButton({ model.openFile("main.js"); tab = "代码" }, Modifier.fillMaxWidth()) { Text("代码", color = if (tab == "代码") Blue else Muted) }
                TextButton({ tab = "引用项" }, Modifier.fillMaxWidth()) { Text("引用项", color = if (tab == "引用项") Blue else Muted) }
                if (!templates) TextButton({ tab = "全局变量" }, Modifier.fillMaxWidth().padding(12.dp)) { Text("全局变量", color = if (tab == "全局变量") Blue else Muted) }
                HorizontalDivider(color = Line)
                TextButton({ tab = "资源" }, Modifier.fillMaxWidth()) { Text("资源管理") }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (tab) {
                    "资源" -> ResourceScreen(model, ::open, onImport)
                    "引用项" -> ReferencesScreen(model)
                    "全局变量" -> VariablesScreen(model)
                    else -> Column(Modifier.fillMaxSize()) {
                        if (!editingWithKeyboard) {
                            Row(Modifier.fillMaxWidth().height(44.dp).background(Color(0xFF18202E)).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                AppIcon("file-code", modifier = Modifier.size(16.dp), tint = Color(0xFF95A2B8))
                                Text(model.filePath.substringAfterLast('/'), Modifier.weight(1f).padding(start = 12.dp), color = Color(0xFFB8C8F6), fontSize = 12.sp)
                                Text(if (model.saved) "已保存" else "保存中", color = Color(0xFF95A2B8), fontSize = 10.sp)
                            }
                            HorizontalDivider(color = Color(0xFF2A3546))
                        }
                        if (model.fileReady) CodeEditor(model.code, model::changeCode, Modifier.weight(1f).fillMaxWidth(), model.editorKey, fontSize, SyntaxHighlighter.language(model.filePath))
                        else Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF18202E)), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        if (!editingWithKeyboard) {
                            HorizontalDivider(color = Line)
                            Row(Modifier.fillMaxWidth().height(44.dp).clickable { console = !console }.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                AppIcon("terminal", modifier = Modifier.size(18.dp), tint = Muted)
                                Text("控制台", Modifier.weight(1f).padding(start = 12.dp), color = Muted, fontSize = 12.sp)
                                AppIcon("chevron-down", modifier = Modifier.size(16.dp), tint = Muted)
                            }
                            if (console) Column(Modifier.height(140.dp).fillMaxWidth().background(Panel).padding(horizontal = 16.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(model::clearLogs) { Text("清空", fontSize = 11.sp) } }
                                LazyColumn { items(model.logs) { Text(it, fontSize = 11.sp, color = Ink) } }
                            }
                        }
                    }
                }
            }
        }
    }
    if (help) SystemInterfacesDialog { help = false }
}

@Composable
private fun VariablesScreen(model: WorkspaceModel) {
    var dialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ScriptVariable?>(null) }
    Column(Modifier.widthIn(max = 640.dp).fillMaxSize().padding(horizontal = 24.dp)) {
        SectionToolbar("全局变量") {
            IconAction("plus", "添加变量", { editing = null; dialog = true })
        }
        if (model.project?.variables.isNullOrEmpty()) {
            EmptyState("code", "还没有变量", modifier = Modifier.weight(1f), actionLabel = "添加变量",
                onAction = { editing = null; dialog = true })
        } else LazyColumn {
            items(model.project?.variables ?: emptyList(), key = { it.name }) { variable ->
                TypedValueRow(variable,
                    onEdit = { editing = variable; dialog = true },
                    onDelete = { model.project?.let { model.updateProject(it.copy(variables = it.variables.filterNot { value -> value.name == variable.name })) } },
                    onToggle = { model.putVariable(variable.copy(value = it.toString()), variable.name) })
            }
        }
    }
    if (dialog) VariableDialog(editing, { dialog = false }) { model.putVariable(it, editing?.name) }
}
