package io.todu.sview.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.sview.ui.components.*

/** 列表与详情共用页面入口，系统返回键优先回到条目列表。 */
@Composable
fun UsageHelpScreen(onBack: () -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = usageHelpEntries.find { it.id == selectedId }
    BackHandler(selected != null) { selectedId = null }
    Column(Modifier.fillMaxSize()) {
        PageHeader(selected?.title ?: "使用帮助", if (selected == null) onBack else ({ selectedId = null }))
        LazyColumn(
            Modifier.weight(1f).widthIn(max = 648.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(24.dp)
        ) {
            if (selected == null) {
                items(usageHelpEntries, key = { it.id }) { entry ->
                    Row(Modifier.fillMaxWidth().clickable { selectedId = entry.id }.padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(entry.title, fontSize = 15.sp, color = Ink)
                            Text(entry.brief, fontSize = 12.sp, lineHeight = 18.sp, color = Muted,
                                modifier = Modifier.padding(top = 6.dp))
                        }
                        AppIcon("chevron-right", modifier = Modifier.size(16.dp), tint = Muted)
                    }
                    HorizontalDivider(color = Line)
                }
            } else {
                item(key = selected.id + "-intro") {
                    Text(selected.brief, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 24.dp))
                }
                items(selected.sections, key = { selected.id + it.title }) { section -> UsageHelpSectionView(section) }
            }
        }
    }
}

/** 文本可选取，链接和示例另提供一键复制。 */
@Composable
private fun UsageHelpSectionView(section: UsageHelpSection) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(section.title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        SelectionContainer {
            Text(section.text, color = Muted, fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 10.dp))
        }
        section.code?.let { code ->
            Column(Modifier.padding(top = 12.dp).fillMaxWidth().background(Color(0xFF18202E), RoundedCornerShape(14.dp))) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (code.startsWith("sview://")) "链接" else "JavaScript", Modifier.weight(1f), color = Color(0xFF95A2B8), fontSize = 11.sp)
                    CopyAction(code, "复制${section.title}", tint = Color(0xFF95A2B8))
                }
                HorizontalDivider(color = Color(0xFF2A3546))
                SelectionContainer {
                    Text(code, Modifier.horizontalScroll(rememberScrollState()).padding(16.dp), fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp, lineHeight = 21.sp, color = Color(0xFFB8C8F6))
                }
            }
        }
    }
}
