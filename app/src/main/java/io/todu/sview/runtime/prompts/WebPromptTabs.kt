package io.todu.sview.runtime.prompts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

/** 文件来源和日期时间共用分段选择，当前项有明确选中状态，仍保留点击语义。 */
@Composable
internal fun PromptTabs(labels: List<String>, selected: Int, enabled: Boolean = true, compact: Boolean = false, select: (Int) -> Unit) {
    val small = compact && io.todu.sview.wear.WatchDevice.isWatch(LocalContext.current)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(Modifier.fillMaxWidth(if (small) .76f else 1f).clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(if (small) 1.dp else 3.dp)) {
            labels.forEachIndexed { index, label ->
                val active = selected == index
                Box(Modifier.weight(1f).heightIn(min = if (small) 24.dp else 36.dp).clip(RoundedCornerShape(22.dp))
                    .background(if (active) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(active, enabled = enabled, role = Role.Tab, onClick = { select(index) }), contentAlignment = Alignment.Center) {
                    Text(label, fontSize = if (small) 11.sp else 14.sp, color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
