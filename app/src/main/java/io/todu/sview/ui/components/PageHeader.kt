package io.todu.sview.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 所有页面共用标头与分隔线，避免各页累加留白或重复绘制边线。 */
@Composable
fun PageHeader(title: String, onBack: (() -> Unit)? = null, showDivider: Boolean = true,
    titleContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconAction("arrow-left", "返回", onBack)
                Spacer(Modifier.width(4.dp))
            } else Spacer(Modifier.width(12.dp))
            if (titleContent != null) Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { titleContent() }
            else Text(title, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium,
                color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            actions()
        }
        if (showDivider) HorizontalDivider(color = Line)
    }
}
