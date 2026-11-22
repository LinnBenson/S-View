package io.todu.sview.wear.tile

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.wear.protolayout.ActionBuilders.*
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.*
import androidx.wear.protolayout.LayoutElementBuilders.*
import androidx.wear.protolayout.ModifiersBuilders.*
import io.todu.sview.MainActivity
import io.todu.sview.data.Project
import io.todu.sview.ui.components.CardColors
import io.todu.sview.wear.WatchTileLaunchActivity
import kotlin.math.min
import android.text.TextPaint
import android.text.TextUtils

/** 用原生弧形元素组成磁贴，扇区和文字共用点击动作，不启动 Compose 或网页。 */
internal object WatchTileLayout {
    private const val INK = 0xFF263247.toInt()
    private const val WHITE = 0xFFF0F2F8.toInt()

    fun create(context: Context, projects: List<Project>, token: String, screenSize: Float, fontScale: Float): LayoutElement {
        val size = (screenSize - 8f).coerceAtLeast(120f)
        val thickness = min(54f, size * .28f)
        val root = Box.Builder().setWidth(dp(size)).setHeight(dp(size))
        if (projects.isNotEmpty()) {
            val angle = 360f / projects.size
            projects.forEachIndexed { index, project ->
                val center = index * angle + angle / 2f
                val click = projectClick(context, project.id, token)
                val semantics = Semantics.Builder().setContentDescription(project.name).build()
                root.addContent(Arc.Builder().setAnchorAngle(degrees(center)).setAnchorType(ARC_ANCHOR_CENTER)
                    .addContent(ArcLine.Builder().setLength(degrees(angle)).setThickness(dp(thickness))
                        .setStrokeCap(STROKE_CAP_BUTT)
                        .setColor(argb(CardColors[project.color.coerceIn(CardColors.indices)].toArgb()))
                        .setModifiers(ArcModifiers.Builder().setClickable(click).setSemantics(semantics).build()).build())
                    .build())
                // 文字单独沿外圈弯曲，按可用弧长截短，避免侵入相邻扇区。
                val textDiameter = size - 12f
                val textRadius = textDiameter / 2f - 6f * fontScale
                val textWidth = textRadius * Math.toRadians((angle - 12f).toDouble()).toFloat()
                val paint = TextPaint().apply { textSize = 10f * fontScale }
                val title = TextUtils.ellipsize(project.name, paint, textWidth, TextUtils.TruncateAt.END).toString()
                root.addContent(Box.Builder().setWidth(dp(textDiameter)).setHeight(dp(textDiameter))
                    .addContent(Arc.Builder().setAnchorAngle(degrees(center)).setAnchorType(ARC_ANCHOR_CENTER)
                        .addContent(ArcText.Builder().setText(title)
                            .setFontStyle(FontStyle.Builder().setSize(sp(10f)).setColor(argb(INK)).build())
                            .setModifiers(ArcModifiers.Builder().setClickable(click).setSemantics(semantics).build()).build())
                        .build()).build())
                // 图标放在文字内侧，仍沿扇区方向旋转。
                val iconDiameter = size - thickness * .95f
                val icon = Image.Builder().setResourceId(project.icon).setWidth(dp(20f)).setHeight(dp(20f))
                    .setModifiers(Modifiers.Builder().setClickable(click).setSemantics(semantics).build()).build()
                root.addContent(Box.Builder().setWidth(dp(iconDiameter)).setHeight(dp(iconDiameter))
                    .addContent(Arc.Builder().setAnchorAngle(degrees(center)).setAnchorType(ARC_ANCHOR_CENTER)
                        .addContent(ArcAdapter.Builder().setRotateContents(true).setContent(icon).build()).build()).build())
            }
        }
        // 扇区铺满圆环，再用等宽径向线分隔，避免角度留白造成外宽内窄。
        if (projects.size > 1) repeat(projects.size) { index ->
            val divider = Box.Builder().setWidth(dp(3f)).setHeight(dp(thickness))
                .setModifiers(Modifiers.Builder().setBackground(Background.Builder().setColor(argb(0xFF000000.toInt())).build()).build()).build()
            root.addContent(Arc.Builder().setAnchorAngle(degrees(index * 360f / projects.size))
                .setAnchorType(ARC_ANCHOR_CENTER)
                .addContent(ArcAdapter.Builder().setRotateContents(true).setContent(divider).build()).build())
        }
        val home = Clickable.Builder().setId("home").setOnClick(LaunchAction.Builder().setAndroidActivity(
            AndroidActivity.Builder().setPackageName(context.packageName).setClassName(MainActivity::class.java.name).build()).build()).build()
        val middle = Column.Builder().setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .addContent(Image.Builder().setResourceId(WatchTileResources.BRAND).setWidth(dp(32f)).setHeight(dp(30f)).build())

        if (projects.isEmpty()) middle.addContent(Text.Builder().setText("在手机上选择项目并同步")
            .setMaxLines(2).setFontStyle(FontStyle.Builder().setSize(sp(11f)).setColor(argb(WHITE)).build()).build())
        root.addContent(Box.Builder().setWidth(dp(if (projects.isEmpty()) size * .75f else size - thickness * 2f - 8f))
            .setHeight(dp(if (projects.isEmpty()) size * .7f else size - thickness * 2f - 8f))
            .setModifiers(Modifiers.Builder().setClickable(home).setSemantics(Semantics.Builder().setContentDescription("打开 S View").build()).build()).addContent(middle.build()).build())
        return root.build()
    }

    private fun projectClick(context: Context, id: String, token: String): Clickable = Clickable.Builder()
        .setId("project-$id")
        .setMinimumClickableWidth(dp(0f)).setMinimumClickableHeight(dp(0f))
        .setOnClick(LaunchAction.Builder().setAndroidActivity(AndroidActivity.Builder()
            .setPackageName(context.packageName).setClassName(WatchTileLaunchActivity::class.java.name)
            .addKeyToExtraMapping("projectId", AndroidStringExtra.Builder().setValue(id).build())
            .addKeyToExtraMapping("token", AndroidStringExtra.Builder().setValue(token).build()).build()).build()).build()
}
