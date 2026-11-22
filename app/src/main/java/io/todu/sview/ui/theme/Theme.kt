package io.todu.sview.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 固定设计稿配色；代码编辑器有独立深色调色板。 */
@Composable
fun SViewTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = Color(0xFF3869E8), background = Color.White, surface = Color.White,
        onSurface = Color(0xFF192338), onBackground = Color(0xFF192338),
        surfaceVariant = Color(0xFFF6F7FB), outlineVariant = Color(0xFFE8EBF2)
    ), typography = Typography, content = content)
}
