package io.todu.sview.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 空列表共用轻量占位；键盘或小屏压缩空间时自动收起装饰，不挤占操作区。 */
@Composable
fun EmptyState(icon: String, title: String, modifier: Modifier = Modifier, description: String? = null,
    actionLabel: String? = null, onAction: (() -> Unit)? = null, showIcon: Boolean = true) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val availableHeight = maxHeight
        val compact = availableHeight < 220.dp || maxWidth < 260.dp
        Column(Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = if (availableHeight < 100.dp) 4.dp else 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (showIcon && availableHeight >= 150.dp) {
                Box(Modifier.size(if (compact) 52.dp else 72.dp).background(Panel, RoundedCornerShape(if (compact) 18.dp else 24.dp)), contentAlignment = Alignment.Center) {
                    AppIcon(icon, modifier = Modifier.size(if (compact) 26.dp else 32.dp), tint = Color(0xFF9AA5B8))
                }
                Spacer(Modifier.height(if (compact) 12.dp else 18.dp))
            }
            Text(title, color = Ink.copy(alpha = .76f), fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            if (description != null && availableHeight >= 180.dp) {
                Spacer(Modifier.height(7.dp))
                Text(description, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
            }
            if (actionLabel != null && onAction != null && availableHeight >= 100.dp) {
                Spacer(Modifier.height(if (compact) 12.dp else 18.dp))
                Box(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).background(Panel)
                    .clickable(onClick = onAction).padding(horizontal = 18.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(actionLabel, color = Blue.copy(alpha = .85f), fontSize = 12.sp)
                }
            }
        }
    }
}
