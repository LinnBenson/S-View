package io.todu.sview.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.sview.data.WindowDimension
import io.todu.sview.data.Project
import io.todu.sview.data.ScriptVariable
import io.todu.sview.ui.components.*
import io.todu.sview.ui.editor.CodeEditor

/** 尺寸带单位保存；允许超过屏幕的值，实际显示范围由运行窗口计算。 */
@Composable
fun ProjectDialog(project: Project?, categories: List<String>, onDismiss: () -> Unit, onSave: (Project) -> Unit) {
    var name by remember { mutableStateOf(project?.name ?: "") }
    var category by remember { mutableStateOf(project?.category ?: "") }
    val phone = !io.todu.sview.wear.WatchDevice.isWatch(androidx.compose.ui.platform.LocalContext.current)
    var shareSource by remember { mutableStateOf(project?.shareSource ?: io.todu.sview.data.ShareSource.DISABLED) }
    var choosingShare by remember { mutableStateOf(false) }
    var choosingCategory by remember { mutableStateOf(false) }
    var color by remember { mutableIntStateOf(project?.color ?: 0) }
    var icon by remember { mutableStateOf(project?.icon ?: "code") }
    var choosingAppearance by remember { mutableStateOf(false) }
    var location by remember { mutableStateOf(project?.location ?: io.todu.sview.data.WindowLocation.CENTER) }
    var choosingLocation by remember { mutableStateOf(false) }
    var backdropTransparency by remember { mutableStateOf((project?.backdropTransparency ?: 40.0).toString().removeSuffix(".0")) }
    val marginDrafts = remember { listOf(
        project?.margins?.top ?: io.todu.sview.data.WindowInset(), project?.margins?.bottom ?: io.todu.sview.data.WindowInset(),
        project?.margins?.left ?: io.todu.sview.data.WindowInset(), project?.margins?.right ?: io.todu.sview.data.WindowInset()).map { it.format() }.toMutableStateList() }
    var cornerRadius by remember { mutableStateOf((project?.cornerRadius ?: 20.0).toString().removeSuffix(".0")) }
    var width by remember { mutableStateOf((project?.width ?: WindowDimension(360.0)).format()) }
    var height by remember { mutableStateOf((project?.height ?: WindowDimension(80.0, WindowDimension.Unit.PERCENT)).format()) }
    var error by remember { mutableStateOf<String?>(null) }
    AppDialog(onDismissRequest = onDismiss, title = { Text(if (project == null) "创建项目" else "项目设置") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AppTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
            ProjectChoiceRow("分类", category.ifBlank { "未分类" }) { choosingCategory = true }
            if (phone) ProjectChoiceRow("共享来源", shareSource.label) { choosingShare = true }
            Text("图标与颜色", fontSize = 12.sp, color = Muted)
            Box(Modifier.fillMaxWidth().clickable { choosingAppearance = true }) {
                ProjectAppearancePreview(name, icon, color, editable = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTextField(width, { width = it }, Modifier.weight(1f), label = { Text("宽度") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                AppTextField(height, { height = it }, Modifier.weight(1f), label = { Text("高度") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
            }
            ProjectChoiceRow("窗口位置", location.label) { choosingLocation = true }
            listOf(0 to 1, 2 to 3).forEach { (first, second) ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(first, second).forEach { index ->
                        AppTextField(marginDrafts[index], { marginDrafts[index] = it }, Modifier.weight(1f),
                            label = { Text(listOf("上边距", "下边距", "左边距", "右边距")[index]) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTextField(cornerRadius, { cornerRadius = it }, Modifier.weight(1f), label = { Text("圆角（dp）") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                AppTextField(backdropTransparency, { backdropTransparency = it }, Modifier.weight(1f), label = { Text("遮罩透明度（%）") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { DialogAction(if (project == null) "创建" else "保存", {
        try {
            require(name.isNotBlank()) { "请输入项目名称" }
            require(category.isBlank() || category in categories) { "请选择现有分类" }
            val marginValues = marginDrafts.map { io.todu.sview.data.WindowInset.parse(it) }
            val margins = io.todu.sview.data.WindowMargins(marginValues[0], marginValues[3], marginValues[1], marginValues[2])
            val transparency = backdropTransparency.toDoubleOrNull() ?: throw IllegalArgumentException("请输入有效透明度")
            val radius = cornerRadius.toDoubleOrNull() ?: throw IllegalArgumentException("请输入有效圆角值")
            val w = WindowDimension.parse(width)
            val h = WindowDimension.parse(height)
            onSave((project ?: Project(name = name)).copy(name = name.trim(), category = category.trim(), color = color, icon = icon, width = w, height = h, location = location, cornerRadius = radius, backdropTransparency = transparency, margins = margins, shareSource = shareSource))
            onDismiss()
        } catch (exception: IllegalArgumentException) { error = exception.message }

    }, primary = true) }, dismissButton = { DialogAction("取消", onDismiss) })
    if (choosingCategory) ProjectChoiceDialog("分类", listOf("" to "未分类") + categories.filter { it.isNotBlank() }.distinct().map { it to it },
        category, { choosingCategory = false }) { category = it; choosingCategory = false }
    if (choosingShare) ProjectChoiceDialog("共享来源", io.todu.sview.data.ShareSource.entries.map { it.value to it.label },
        shareSource.value, { choosingShare = false }) { shareSource = io.todu.sview.data.ShareSource.parse(it); choosingShare = false }
    if (choosingLocation) ProjectChoiceDialog("窗口位置", io.todu.sview.data.WindowLocation.entries.map { it.value to it.label },
        location.value, { choosingLocation = false }) { location = io.todu.sview.data.WindowLocation.parse(it); choosingLocation = false }
    if (choosingAppearance) ProjectAppearancePicker(name, icon, color, { choosingAppearance = false }) { selectedIcon, selectedColor ->
        icon = selectedIcon
        color = selectedColor
    }

}

/** 分类与位置共用选择入口，长名称省略显示，保留完整选项值。 */
@Composable
private fun ProjectChoiceRow(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp)
        Spacer(Modifier.width(12.dp))
        Text(value, Modifier.weight(1f), color = Muted, fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        Spacer(Modifier.width(8.dp))
        AppIcon("chevron-right", modifier = Modifier.size(18.dp))
    }
}

/** 选中后立即返回设置页；分类维护仍由分类管理页面负责。 */
@Composable
private fun ProjectChoiceDialog(title: String, options: List<Pair<String, String>>, selected: String,
    onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    AppDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column {
            options.forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().clickable { onSelect(value) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), color = if (selected == value) Blue else MaterialTheme.colorScheme.onSurface)
                    if (selected == value) AppIcon("check", tint = Blue)
                }
            }
        }
    }, confirmButton = {})
}

/** 四种类型使用紧凑分段选择；数组和对象数组沿用代码编辑器。 */
@Composable
fun VariableDialog(variable: ScriptVariable?, onDismiss: () -> Unit, database: Boolean = false, onSave: (ScriptVariable) -> Unit) {
    var name by remember { mutableStateOf(variable?.name ?: "") }
    var type by remember { mutableStateOf(variable?.type ?: "string") }
    val drafts = remember { mutableStateMapOf("boolean" to "false", "string" to "", "number" to "0", "array" to "[]", "object" to "{}").apply { variable?.let { put(it.type, it.value) } } }
    var error by remember { mutableStateOf<String?>(null) }
    AppDialog(onDismissRequest = onDismiss, maxWidth = if (type in setOf("array", "object")) 620.dp else 440.dp,
        title = { Text(if (database) { if (variable == null) "添加数据" else "编辑数据" } else if (variable == null) "添加变量" else "编辑变量") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AppTextField(name, { name = it }, label = { Text(if (database) "键" else "变量名") }, singleLine = true)
                BoxWithConstraints(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp)).padding(4.dp)) {
                    val columns = if (maxWidth < 250.dp) 2 else 3
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ScriptVariable.TYPES.chunked(columns).forEach { group ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                group.forEach { item ->
                                    Box(Modifier.weight(1f).height(36.dp).background(if (item == type) Color.White else Color.Transparent, RoundedCornerShape(7.dp))
                                        .border(if (item == type) 1.dp else 0.dp, if (item == type) Blue.copy(alpha = .25f) else Color.Transparent, RoundedCornerShape(7.dp))
                                        .clickable { type = item; error = null }, contentAlignment = Alignment.Center) {
                                        Text(item, fontSize = 12.sp, color = if (item == type) Blue else Muted)
                                    }
                                }
                            }
                        }
                    }
                }
                when (type) {
                    "boolean" -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(drafts[type] ?: "false", modifier = Modifier.weight(1f), fontSize = 14.sp)
                        Switch(drafts[type] == "true", { drafts[type] = it.toString() })
                    }
                    "array", "object" -> CodeEditor(drafts[type] ?: "", { drafts[type] = it }, Modifier.fillMaxWidth().height(260.dp), identity = type, language = "json")
                    else -> AppTextField(drafts[type] ?: "", { drafts[type] = it }, label = { Text("值") },
                        keyboardOptions = KeyboardOptions(keyboardType = if (type == "number") KeyboardType.Decimal else KeyboardType.Text))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        }, dismissButton = { DialogAction("取消", onDismiss) }, confirmButton = {
            DialogAction("保存", {
                try {
                    val next = ScriptVariable(if (database) name else name.trim(), type, drafts[type] ?: "")
                    if (database) io.todu.sview.data.DatabaseValue.normalized(next)
                    else try { next.jsonValue() } catch (_: Exception) { throw IllegalArgumentException(when (type) { "array" -> "请输入有效的 JSON 数组"; "object" -> "请输入有效的 JSON 对象"; else -> "请输入有效的值" }) }
                    onSave(next)
                    onDismiss()
                } catch (exception: Exception) { error = exception.message ?: "变量无效" }
            }, primary = true)
        })
}
