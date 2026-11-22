package io.todu.sview.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.todu.sview.R

val Ink = Color(0xFF192338)
val Muted = Color(0xFF7B8496)
val Blue = Color(0xFF3869E8)
val Line = Color(0xFFE8EBF2)
val Panel = Color(0xFFF6F7FB)
val CardColors = listOf(0xFFEAF0FF, 0xFFE7F1EC, 0xFFF8EDE4, 0xFFEEEAF6, 0xFFF7E7EB, 0xFFE9EDF1,
    0xFFE2F1F7, 0xFFE2F3EF, 0xFFEDF3DC, 0xFFF8F2D9, 0xFFF9E5D8, 0xFFF5DFE1,
    0xFFF1E3F3, 0xFFE3E5F7, 0xFFDDEAF3, 0xFFE4ECE4, 0xFFF0E9DF, 0xFFE4E6EB,
    0xFFE8BFBF, 0xFFE8C7BF, 0xFFE8CFBF, 0xFFE8D8BF, 0xFFE8E0BF, 0xFFE8E8BF, 0xFFE0E8BF, 0xFFD8E8BF, 0xFFCFE8BF, 0xFFC7E8BF, 0xFFBFE8BF, 0xFFBFE8C7, 0xFFBFE8CF, 0xFFBFE8D8, 0xFFBFE8E0, 0xFFBFE8E8, 0xFFBFE0E8, 0xFFBFD8E8, 0xFFBFCFE8, 0xFFBFC7E8, 0xFFBFBFE8, 0xFFC7BFE8, 0xFFCFBFE8, 0xFFD8BFE8, 0xFFE0BFE8, 0xFFE8BFE8, 0xFFE8BFE0, 0xFFE8BFD8, 0xFFE8BFCF, 0xFFE8BFC7).map { Color(it) }

/** 使用 Lucide 原始路径生成的矢量资源，避免运行时解析 SVG。 */
@Composable
fun AppIcon(name: String, description: String? = null, modifier: Modifier = Modifier.size(22.dp), tint: Color = Color(0xFF626C7C)) {
    val resource = when (name) {
        "circle-fading-play" -> R.drawable.lucide_circle_fading_play
        "folder-sync" -> R.drawable.lucide_folder_sync
        "square-stop" -> R.drawable.lucide_square_stop
        "file-spreadsheet" -> R.drawable.lucide_file_spreadsheet
        "refresh-cw" -> R.drawable.lucide_refresh_cw
        "indent-increase" -> R.drawable.lucide_indent_increase
        "clock" -> R.drawable.lucide_clock
        "timer" -> R.drawable.lucide_timer
        "calendar" -> R.drawable.lucide_calendar
        "check-check" -> R.drawable.lucide_check_check
        "list-todo" -> R.drawable.lucide_list_todo
        "notebook-pen" -> R.drawable.lucide_notebook_pen
        "calculator" -> R.drawable.lucide_calculator
        "chart-no-axes-combined" -> R.drawable.lucide_chart_no_axes_combined
        "wallet" -> R.drawable.lucide_wallet
        "heart" -> R.drawable.lucide_heart
        "star" -> R.drawable.lucide_star
        "bookmark" -> R.drawable.lucide_bookmark
        "lightbulb" -> R.drawable.lucide_lightbulb
        "zap" -> R.drawable.lucide_zap
        "cloud" -> R.drawable.lucide_cloud
        "sun" -> R.drawable.lucide_sun
        "moon" -> R.drawable.lucide_moon
        "headphones" -> R.drawable.lucide_headphones
        "camera" -> R.drawable.lucide_camera
        "globe" -> R.drawable.lucide_globe
        "map-pin" -> R.drawable.lucide_map_pin
        "gamepad-2" -> R.drawable.lucide_gamepad_2
        "coffee" -> R.drawable.lucide_coffee
        "shopping-cart" -> R.drawable.lucide_shopping_cart
        "plus" -> R.drawable.lucide_plus
        "search" -> R.drawable.lucide_search
        "copy" -> R.drawable.lucide_copy
        "circle-help" -> R.drawable.lucide_circle_help
        "info" -> R.drawable.lucide_info
        "arrow-left" -> R.drawable.lucide_arrow_left
        "chevron-right" -> R.drawable.lucide_chevron_right
        "chevron-down" -> R.drawable.lucide_chevron_down
        "play" -> R.drawable.lucide_play
        "folder" -> R.drawable.lucide_folder
        "folders" -> R.drawable.lucide_folders
        "ellipsis" -> R.drawable.lucide_ellipsis
        "layout-grid" -> R.drawable.lucide_layout_grid
        "settings-2" -> R.drawable.lucide_settings_2
        "import" -> R.drawable.lucide_import
        "file-code" -> R.drawable.lucide_file_code
        "file-plus" -> R.drawable.lucide_file_plus
        "folder-plus" -> R.drawable.lucide_folder_plus
        "upload" -> R.drawable.lucide_upload
        "pencil" -> R.drawable.lucide_pencil
        "check" -> R.drawable.lucide_check
        "terminal" -> R.drawable.lucide_terminal
        "save" -> R.drawable.lucide_save
        "x" -> R.drawable.lucide_x
        "shield" -> R.drawable.lucide_shield
        "sliders-horizontal" -> R.drawable.lucide_sliders_horizontal
        "palette" -> R.drawable.lucide_palette
        "undo-2" -> R.drawable.lucide_undo_2
        "redo-2" -> R.drawable.lucide_redo_2
        "file-image" -> R.drawable.lucide_file_image
        "file-text" -> R.drawable.lucide_file_text
        "pin" -> R.drawable.lucide_pin
        "trash" -> R.drawable.lucide_trash
        else -> ProjectIconCatalog.byName[name]?.resource ?: R.drawable.lucide_code
    }
    Icon(painterResource(resource), description, modifier, tint)
}

/** 点击区保持 44 dp，图标本身维持轻量的 22 dp。 */
@Composable
fun IconAction(name: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Color(0xFF626C7C), enabled: Boolean = true, iconSize: androidx.compose.ui.unit.Dp = 22.dp) {
    IconButton(onClick, modifier.size(44.dp), enabled = enabled) { AppIcon(name, label, modifier = Modifier.size(iconSize), tint = if (enabled) tint else tint.copy(alpha = .35f)) }
}
