package io.todu.sview.share

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.sview.data.Project
import io.todu.sview.ui.components.*

/** 分享选择使用中性色卡片，项目颜色集中在图标，长名称和折叠屏共用布局。 */
@Composable
internal fun ShareProjectCard(project: Project, compact: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        color = Panel, border = BorderStroke(1.dp, Line)) {
        Row(Modifier.heightIn(min = if (compact) 56.dp else 84.dp).padding(if (compact) 10.dp else 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp)) {
            Box(Modifier.size(if (compact) 30.dp else 48.dp).background(CardColors[project.color.coerceIn(CardColors.indices)], RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center) {
                AppIcon(project.icon, modifier = Modifier.size(if (compact) 20.dp else 25.dp), tint = Ink.copy(alpha = .7f))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(project.name, color = Ink, fontSize = if (compact) 13.sp else 16.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!compact && project.category.isNotBlank()) Text(project.category, color = Muted, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!compact) AppIcon("chevron-right", modifier = Modifier.size(18.dp), tint = Muted)
        }
    }
}
