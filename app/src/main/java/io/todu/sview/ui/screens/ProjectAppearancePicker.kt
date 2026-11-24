package io.todu.sview.ui.screens

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.sview.ui.components.*

private val colorNames = listOf("浅蓝", "薄荷", "杏色", "淡紫", "浅粉", "银灰", "冰蓝", "青绿", "嫩绿",
    "奶黄", "蜜桃", "玫瑰", "丁香", "靛蓝", "雾蓝", "鼠尾草", "燕麦", "石灰",
    "珊瑚", "桃红", "蜜橘", "橙黄", "金黄", "柠檬", "青柠", "芽绿", "草绿", "翠绿",
    "翡翠", "碧绿", "松绿", "湖绿", "蓝绿", "青蓝", "天蓝", "晴蓝", "海蓝", "钴蓝",
    "鸢尾", "蓝紫", "紫罗兰", "葡萄", "兰紫", "洋红", "杜鹃", "莓粉", "蔷薇", "胭脂",
    "奶橘", "香草", "奶油", "梨黄", "橄榄", "豆绿", "海盐", "水绿", "水蓝", "柔蓝", "长春花", "藤紫",
    "锦葵", "烟粉", "豆沙", "陶粉", "驼色", "亚麻", "苔灰", "桉绿", "青灰", "蓝灰", "紫灰", "暖灰",
    "奶杏", "米黄", "米白", "茶白", "嫩芽", "玉白", "冰川", "晴空", "月蓝", "月紫", "雪紫", "樱紫", "樱粉", "贝粉",
    "焦糖", "麦黄", "竹黄", "竹绿", "玉绿", "海沫", "孔雀蓝", "烟蓝", "牛仔蓝", "灰藤", "浅梅", "木槿", "藕粉", "赤陶")

/** 独立选择面板，避免大量选项撑高项目表单；取消不提交草稿。 */
@Composable
internal fun ProjectAppearancePicker(name: String, icon: String, color: Int, onDismiss: () -> Unit,
    onSelect: (String, Int) -> Unit) {
    var draftIcon by remember { mutableStateOf(icon) }
    var draftColor by remember { mutableIntStateOf(color) }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val categories = remember { listOf("全部") + ProjectIconCatalog.items.map { it.category }.distinct() }
    val pager = rememberPagerState { categories.size }
    val scope = rememberCoroutineScope()
    val tabs = rememberLazyListState()
    LaunchedEffect(pager.currentPage) { tabs.animateScrollToItem(pager.currentPage) }
    AppDialog(onDismissRequest = onDismiss, title = { Text("图标与颜色") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ProjectAppearancePreview(name, draftIcon, draftColor)
            Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp)).padding(4.dp)) {
                listOf("图标", "颜色").forEachIndexed { index, title ->
                    Box(Modifier.weight(1f).height(36.dp).background(if (tab == index) Color.White else Color.Transparent, RoundedCornerShape(7.dp))
                        .selectable(selected = tab == index, role = Role.Tab, onClick = { tab = index }), contentAlignment = Alignment.Center) {
                        Text(title, fontSize = 13.sp, color = if (tab == index) Blue else Muted)
                    }
                }
            }
            if (tab == 0) {
                AppTextField(query, { query = it }, label = { Text("搜索图标") }, singleLine = true)
                androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth(), state = tabs, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(categories.size) { index ->
                        val group = categories[index]
                        Text(group, fontSize = 12.sp, color = if (pager.currentPage == index) Blue else Muted,
                            modifier = Modifier.background(if (pager.currentPage == index) Panel else Color.Transparent, RoundedCornerShape(8.dp))
                                .selectable(pager.currentPage == index, role = Role.Tab, onClick = { scope.launch { pager.animateScrollToPage(index) } }).padding(horizontal = 10.dp, vertical = 8.dp))
                    }
                }
                HorizontalPager(pager, modifier = Modifier.fillMaxWidth().height(280.dp), key = { categories[it] }) { page ->
                    val category = categories[page]
                    val filtered = remember(query, category) {
                        ProjectIconCatalog.items.filter {
                            (category == "全部" || it.category == category) &&
                                (it.name.contains(query.trim(), true) || it.label.contains(query.trim(), true) || it.category.contains(query.trim()))
                        }
                    }
                    if (filtered.isEmpty()) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Text("没有匹配的图标", color = Muted, fontSize = 13.sp)
                        }
                    } else key(query) {
                        LazyVerticalGrid(columns = GridCells.Adaptive(44.dp), modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(filtered, key = { it.name }) { item ->
                                val selected = draftIcon == item.name
                                Box(Modifier.height(44.dp)
                                    .background(if (selected) CardColors[draftColor.coerceIn(CardColors.indices)] else Color.Transparent, RoundedCornerShape(10.dp))
                                    .border(if (selected) 1.dp else 0.dp, if (selected) Blue.copy(alpha = .45f) else Color.Transparent, RoundedCornerShape(10.dp))
                                    .semantics { contentDescription = item.label }
                                    .selectable(selected, role = Role.RadioButton, onClick = { draftIcon = item.name }), contentAlignment = Alignment.Center) {
                                    AppIcon(item.name, modifier = Modifier.size(22.dp), tint = if (selected) Blue else Ink.copy(alpha = .65f))
                                }
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Adaptive(44.dp), modifier = Modifier.fillMaxWidth().height(280.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(CardColors.size) { index ->
                        val selected = draftColor == index
                        Box(Modifier.height(44.dp).semantics { contentDescription = colorNames[index] }
                            .selectable(selected, role = Role.RadioButton, onClick = { draftColor = index }), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(32.dp).background(CardColors[index], CircleShape)
                                .border(if (selected) 2.dp else 0.dp, if (selected) Blue else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                                if (selected) AppIcon("check", modifier = Modifier.size(16.dp), tint = Blue)
                            }
                        }
                    }
                }
            }
        }
    }, dismissButton = { DialogAction("取消", onDismiss) }, confirmButton = {
        DialogAction("保存", { onSelect(draftIcon, draftColor); onDismiss() }, primary = true)
    })
}

/** 表单与选择面板共用预览，颜色和图标与首页卡片一致。 */
@Composable
internal fun ProjectAppearancePreview(name: String, icon: String, color: Int, editable: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
        .background(CardColors[color.coerceIn(CardColors.indices)], RoundedCornerShape(12.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AppIcon(icon, modifier = Modifier.size(24.dp), tint = Ink.copy(alpha = .65f))
        Text(name.ifBlank { "新项目" }, Modifier.weight(1f), color = Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (editable) AppIcon("pencil", "修改图标与颜色", Modifier.size(18.dp), Muted)
    }
}
