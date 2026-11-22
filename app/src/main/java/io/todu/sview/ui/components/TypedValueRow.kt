package io.todu.sview.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.sview.data.ScriptVariable
import org.json.JSONObject

/** 数据库与全局变量共用两行结构，类型和值不再分散为三行。 */
@Composable
fun TypedValueRow(entry: ScriptVariable, onEdit: () -> Unit, onDelete: () -> Unit, onToggle: (Boolean) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val preview = remember(entry.type, entry.value) { valuePreview(entry) }
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable(onClick = onEdit).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(entry.name, Modifier.weight(1f, fill = false), color = Ink, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.type, color = Muted, fontSize = 10.sp, lineHeight = 14.sp,
                    modifier = Modifier.background(Panel, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Text(preview, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (entry.type == "boolean") CompactBooleanToggle(entry.value == "true", onToggle)
        Box {
            IconAction("ellipsis", "${entry.name} 的操作", { menu = true }, iconSize = 19.dp)
            ActionMenu(menu, { menu = false }) {
                ActionMenuItem("编辑", "pencil", { menu = false; onEdit() })
                ActionMenuItem("删除", "trash", { menu = false; onDelete() }, destructive = true)
            }
        }
    }
    HorizontalDivider(color = Line)
}

/** 缩小开关视觉尺寸，仍保留 44 dp 点击区和系统开关语义。 */
@Composable
private fun CompactBooleanToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val offset by animateDpAsState(if (checked) 16.dp else 2.dp, label = "布尔开关")
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).toggleable(checked, role = Role.Switch, onValueChange = onChange),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 32.dp, height = 18.dp).background(if (checked) Blue else Color(0xFFCDD2DE), CircleShape)) {
            Box(Modifier.offset(x = offset, y = 2.dp).size(14.dp).background(Color.White, CircleShape))
        }
    }
}

/** 空字符串显式显示引号，JSON 压成单行，预览截断不改变保存值。 */
private fun valuePreview(entry: ScriptVariable): String = runCatching {
    when (entry.type) {
        "string" -> JSONObject.quote(entry.value.take(180))
        "number" -> java.math.BigDecimal(entry.value).stripTrailingZeros().toPlainString().take(200)
        "object", "array" -> entry.jsonValue().toString().take(200)
        else -> entry.value.take(200)
    }
}.getOrDefault(entry.value.take(200))
