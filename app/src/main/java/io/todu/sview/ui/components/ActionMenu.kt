package io.todu.sview.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** 菜单只借用 Popup 定位；颜色、行高、图标与阴影不使用 Material 默认菜单样式。 */
@Composable
fun ActionMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (!expanded) return
    val density = LocalDensity.current
    val margin = with(density) { 8.dp.roundToPx() }
    val position = remember(margin) { MenuPosition(margin) }
    val configuration = LocalConfiguration.current
    val width = minOf(184.dp, (configuration.screenWidthDp - 32).coerceAtLeast(120).dp)
    val maxHeight = (configuration.screenHeightDp - 48).coerceAtLeast(100).dp
    Popup(popupPositionProvider = position, onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)) {
        val shape = RoundedCornerShape(14.dp)
        Column(Modifier.padding(8.dp).width(width).heightIn(max = maxHeight)
            .shadow(8.dp, shape, ambientColor = Ink.copy(alpha = .06f), spotColor = Ink.copy(alpha = .10f))
            .clip(shape).background(Color.White).border(1.dp, Line, shape)
            .verticalScroll(rememberScrollState()).padding(6.dp), content = content)
    }
}

/** 所有操作共用 44 dp 点击行，删除操作以细分隔线和柔和红色区分。 */
@Composable
fun ActionMenuItem(label: String, icon: String, onClick: () -> Unit, destructive: Boolean = false) {
    val color = if (destructive) Color(0xFFB2545E) else Ink
    if (destructive) Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).height(1.dp).background(Line))
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AppIcon(icon, modifier = Modifier.size(18.dp), tint = if (destructive) color else Color(0xFF626C7C))
        Text(label, color = color, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
    }
}

/** 优先贴齐按钮右缘并向下展开；靠近窗口边缘时自动翻转或收进可见范围。 */
private class MenuPosition(private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val preferredX = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width + margin else anchorBounds.left - margin
        val below = anchorBounds.bottom - margin
        val preferredY = if (below + popupContentSize.height <= windowSize.height - margin) below else anchorBounds.top - popupContentSize.height + margin
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
        return IntOffset(preferredX.coerceIn(margin, maxX), preferredY.coerceIn(margin, maxY))
    }
}
