package io.todu.sview.ui.screens

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.sview.ui.components.*

/** 首级只展示接口列表，详情返回后保留原分类。 */
@Composable
fun SystemInterfacesScreen(onBack: () -> Unit, onDismiss: (() -> Unit)? = null) {
    val categories = remember { listOf("系统", "网络", "交互", "文件", "脚本", "工具") }
    val pager = rememberPagerState { categories.size }
    val scope = rememberCoroutineScope()
    val tabs = rememberLazyListState()
    LaunchedEffect(pager.currentPage) { tabs.animateScrollToItem(pager.currentPage) }
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    fun closeSearch() { searching = false; query = ""; focusManager.clearFocus() }
    val selected = interfaceHelp.find { it.name == selectedName }
    BackHandler(enabled = selected != null || searching) {
        if (selected != null) selectedName = null else closeSearch()
    }
    LaunchedEffect(searching, selectedName) {
        if (searching && selectedName == null) focusRequester.requestFocus()
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(selected?.shortName ?: "系统接口",
            if (selected == null) onBack else ({ selectedName = null })) {
            if (selected == null) IconAction(if (searching && onDismiss == null) "x" else "search",
                if (searching) "关闭搜索" else "搜索接口", { if (searching) closeSearch() else searching = true }, tint = if (searching) Blue else Muted)
            if (onDismiss != null) IconAction("x", "关闭帮助", onDismiss)
        }
        if (selected != null) {
            key(selected.name) {
                Column(Modifier.weight(1f).widthIn(max = 648.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
                    .verticalScroll(rememberScrollState()).padding(24.dp)) {
                    InterfaceHelp(selected)
                }
            }
        } else {
            if (searching) AppTextField(query, { query = it },
                Modifier.widthIn(max = 648.dp).align(Alignment.CenterHorizontally).padding(horizontal = 24.dp, vertical = 12.dp).focusRequester(focusRequester),
                label = { Text("搜索接口名称或用途") }, singleLine = true)
            androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), state = tabs) {
                items(categories.size) { index ->
                    val label = categories[index]
                    Column(Modifier.width(72.dp).height(48.dp).clickable { scope.launch { pager.animateScrollToPage(index) } }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(label, color = if (pager.currentPage == index) Blue else Muted, fontSize = 14.sp)
                        }
                        if (pager.currentPage == index) Box(Modifier.width(28.dp).height(2.dp).background(Blue))
                    }
                }
            }
            HorizontalDivider(color = Line)
            HorizontalPager(pager, modifier = Modifier.weight(1f).fillMaxWidth(), key = { categories[it] }) { page ->
                val category = categories[page]
                val filtered = remember(category, query) {
                    interfaceHelp.filter { it.category == category }.filter {
                        val keyword = query.trim()
                        it.name.contains(keyword, ignoreCase = true) || it.brief.contains(keyword, ignoreCase = true) ||
                            it.summary.contains(keyword, ignoreCase = true) || it.methods.any { method ->
                                method.name.contains(keyword, ignoreCase = true) || method.brief.contains(keyword, ignoreCase = true) ||
                                    method.summary.contains(keyword, ignoreCase = true)
                            }
                    }
                }
                if (filtered.isEmpty()) EmptyState(if (query.isBlank()) "code" else "search",
                    if (query.isBlank()) "暂无接口" else "没有匹配的接口", Modifier.fillMaxSize(),
                    actionLabel = if (query.isBlank()) null else "清空搜索", onAction = if (query.isBlank()) null else ({ query = "" }))
                else LazyColumn(Modifier.widthIn(max = 648.dp).fillMaxSize().padding(horizontal = 20.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)) {
                    item(key = "namespace") {
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(if (category == "工具") "此类工具由 global.js 提供" else filtered.first().namespace, Modifier.weight(1f),
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Muted)
                            Text("${filtered.size} 项", fontSize = 11.sp, color = Muted)
                        }
                    }
                    items(filtered, key = { it.name }) { entry ->
                        InterfaceListRow(entry) { focusManager.clearFocus(); selectedName = entry.name }
                    }
                }
            }
        }
    }
}

/** 命名空间在列表顶部统一展示，复制仍使用完整名称。 */
@Composable
private fun InterfaceListRow(entry: InterfaceHelpEntry, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp).padding(end = 4.dp)) {
            InterfaceName(entry, entry.shortName)
            Text(entry.brief, fontSize = 11.sp, lineHeight = 16.sp, color = Muted,
                modifier = Modifier.padding(top = 3.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        CopyAction(entry.name, "复制 ${entry.name}", tint = Muted.copy(alpha = .75f))
        AppIcon("chevron-right", modifier = Modifier.size(14.dp), tint = Muted.copy(alpha = .6f))
    }
    HorizontalDivider(color = Line.copy(alpha = .65f))
}

/** 返回类型决定标记，列表、详情和链式方法共用同一显示规则。 */
@Composable
private fun InterfaceName(entry: InterfaceHelpEntry, name: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(name, Modifier.weight(1f, fill = false), fontFamily = FontFamily.Monospace,
            fontSize = 14.sp, lineHeight = 19.sp, color = Ink, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        if (entry.resultType.trim().let { it == "Promise" || it.startsWith("Promise<") }) {
            Text("Promise", color = Blue, fontSize = 9.sp, lineHeight = 12.sp,
                modifier = Modifier.background(Blue.copy(alpha = .08f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun InterfaceHelp(entry: InterfaceHelpEntry) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        InterfaceName(entry, entry.name, Modifier.weight(1f), maxLines = 2)
        CopyAction(entry.name, "复制接口名称")
    }
    Text(entry.summary, color = Muted, fontSize = 13.sp, lineHeight = 21.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
    if (entry.methods.isNotEmpty()) {
        HelpLabel("请求方法")
        entry.methods.forEach { method ->
            key(method.name) {
                var expanded by rememberSaveable { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        InterfaceName(method, method.name.substringAfterLast('.'))
                        Text(method.brief, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
                    }
                    AppIcon("chevron-down", modifier = Modifier.size(18.dp).rotate(if (expanded) 180f else 0f), tint = Muted)
                }
                if (expanded) Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) { InterfaceHelp(method) }
                HorizontalDivider(color = Line)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    HelpLabel("传入参数")
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp)) {
        if (entry.parameters.isEmpty()) Text("无需传入参数", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
        entry.parameters.forEachIndexed { index, parameter ->
            if (index > 0) HorizontalDivider(color = Line)
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(parameter.name, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Ink)
                    Text(if (parameter.required) "必填" else "可选", color = if (parameter.required) Blue else Muted, fontSize = 11.sp)
                }
                Text(parameter.type, color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 5.dp))
                Text(parameter.description, fontSize = 13.sp, lineHeight = 21.sp, color = Ink.copy(alpha = .8f),
                    modifier = Modifier.padding(top = 8.dp))
                parameter.defaultValue?.let {
                    Text("默认值：$it", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
    Spacer(Modifier.height(24.dp))
    HelpLabel("返回结果")
    Column(Modifier.fillMaxWidth().background(Color(0xFFF0F4FC), RoundedCornerShape(14.dp)).padding(16.dp)) {
        Text(entry.resultType, color = Blue, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        Text(entry.result, fontSize = 13.sp, lineHeight = 21.sp, color = Ink.copy(alpha = .8f), modifier = Modifier.padding(top = 10.dp))
        entry.resultFields.forEach { field ->
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Line)
            Text(field.name, fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Ink)
            Text(field.type, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Blue,
                modifier = Modifier.padding(top = 4.dp))
            Text(field.description, fontSize = 13.sp, lineHeight = 21.sp, color = Muted,
                modifier = Modifier.padding(top = 8.dp))
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Line)
        Text("调用失败", fontSize = 12.sp, color = Muted)
        Text(entry.failure + " 可用 try / catch 或 .catch() 处理。", fontSize = 12.sp, lineHeight = 20.sp,
            color = Muted, modifier = Modifier.padding(top = 5.dp))
    }
    Spacer(Modifier.height(24.dp))
    HelpLabel("调用示例")
    Column(Modifier.fillMaxWidth().background(Color(0xFF18202E), RoundedCornerShape(14.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon("code", modifier = Modifier.size(16.dp), tint = Color(0xFF95A2B8))
            Text("JavaScript", Modifier.weight(1f).padding(start = 8.dp), color = Color(0xFF95A2B8), fontSize = 11.sp)
            CopyAction(entry.example, "复制示例代码", tint = Color(0xFF95A2B8))
        }
        HorizontalDivider(color = Color(0xFF2A3546))
        SelectionContainer {
            Text(entry.example, Modifier.padding(16.dp).horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 21.sp, color = Color(0xFFB8C8F6))
        }
    }
    Text("可直接放入 main.js。无需等待结果时，可省略 await，并使用 .catch() 处理错误。",
        color = Muted, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 10.dp))
    entry.note?.let { Text(it, color = Muted, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 12.dp)) }
}

@Composable
private fun HelpLabel(label: String) {
    Text(label, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 10.dp))
}
