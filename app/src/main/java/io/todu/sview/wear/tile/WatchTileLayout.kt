package io.todu.sview.wear.tile

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
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

    @SuppressLint("RestrictedApi") // 方向常量在当前依赖中标为库内 API，公开的方向设置方法需要这些值。
    fun create(context: Context, projects: List<Project>, token: String, screenSize: Float, fontScale: Float): LayoutElement {
        val size = (screenSize - 8f).coerceAtLeast(120f)
        val thickness = min(54f, size * .28f)
        val root = centeredBox(size)
        if (projects.isNotEmpty()) {
            val angle = 360f / projects.size
            projects.forEachIndexed { index, project ->
                val center = index * angle + angle / 2f
                val click = projectClick(context, project.id, token)
                val semantics = Semantics.Builder().setContentDescription(project.name).build()
                root.addContent(centeredArc(center)
                    .addContent(ArcLine.Builder().setLength(degrees(angle)).setThickness(dp(thickness))
                        .setStrokeCap(STROKE_CAP_BUTT)
                        .setColor(argb(CardColors[project.color.coerceIn(CardColors.indices)].toArgb()))
                        .setModifiers(ArcModifiers.Builder().setClickable(click).setSemantics(semantics).build()).build())
                    .build())
                // 下半圈反向走弧，让字形保持正立；扇区位置始终按顺时针角度定位。
                val clockwise = center <= 90f || center >= 270f
                val textDiameter = size - 12f
                val title = titlePlacement(context, project.name, textDiameter, angle, center, fontScale, clockwise)
                root.addContent(centeredBox(textDiameter)
                    .addContent(centeredArc(title.angle)
                        .addContent(ArcText.Builder().setText(title.text)
                            .setArcDirection(if (clockwise) ARC_DIRECTION_CLOCKWISE else ARC_DIRECTION_COUNTER_CLOCKWISE)
                            .setFontStyle(FontStyle.Builder().setSize(sp(10f)).setColor(argb(INK))
                                .setPreferredFontFamilies(FontStyle.ROBOTO_FONT).setWeight(FONT_WEIGHT_NORMAL).build())
                            .setModifiers(ArcModifiers.Builder().setClickable(click).setSemantics(semantics).build()).build())
                        .build()).build())
                // 图标放在文字内侧，仍沿扇区方向旋转。
                val iconDiameter = size - thickness * .95f
                val icon = Image.Builder().setResourceId(project.icon).setWidth(dp(20f)).setHeight(dp(20f))
                    .setModifiers(Modifiers.Builder().setClickable(click).setSemantics(semantics).build()).build()
                root.addContent(centeredBox(iconDiameter)
                    .addContent(centeredArc(center)
                        .addContent(ArcAdapter.Builder().setRotateContents(true).setContent(icon).build()).build()).build())
            }
        }
        // 扇区铺满圆环，再用等宽径向线分隔，避免角度留白造成外宽内窄。
        if (projects.size > 1) repeat(projects.size) { index ->
            val divider = Box.Builder().setWidth(dp(3f)).setHeight(dp(thickness))
                .setModifiers(Modifiers.Builder().setBackground(Background.Builder().setColor(argb(0xFF000000.toInt())).build()).build()).build()
            root.addContent(centeredArc(index * 360f / projects.size)
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

    /** 所有同心层显式共用中心，不依赖宿主的默认布局方向和对齐方式。 */
    private fun centeredBox(size: Float): Box.Builder = Box.Builder().setWidth(dp(size)).setHeight(dp(size))
        .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER).setVerticalAlignment(VERTICAL_ALIGN_CENTER)

    @SuppressLint("RestrictedApi")
    private fun centeredArc(angle: Float): Arc.Builder = Arc.Builder().setAnchorAngle(degrees(angle))
        .setAnchorType(ARC_ANCHOR_CENTER).setArcDirection(ARC_DIRECTION_CLOCKWISE)

    private data class TitlePlacement(val text: String, val angle: Float)

    /** 用像素测量最终标题，并抵消弧形控件未计入的字形左侧偏移。 */
    private fun titlePlacement(
        context: Context, name: String, diameter: Float, sectorAngle: Float,
        center: Float, fontScale: Float, clockwise: Boolean
    ): TitlePlacement {
        val density = context.resources.displayMetrics.density
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textSize = 10f * fontScale * density
        }
        val metrics = paint.fontMetrics
        val radius = diameter * density / 2f + if (clockwise) metrics.ascent else -metrics.descent
        val available = radius * Math.toRadians((sectorAngle - 12f).toDouble()).toFloat()
        val title = TextUtils.ellipsize(name, paint, available, TextUtils.TruncateAt.END).toString()
        val bounds = Rect()
        paint.getTextBounds(title, 0, title.length, bounds)
        // CurvedTextView 按 bounds.width 居中，却从绘制原点起笔，实际字形中心多出了 bounds.left。
        val bearingAngle = Math.toDegrees(bounds.left / radius.toDouble()).toFloat()
        return TitlePlacement(title, center - if (clockwise) bearingAngle else -bearingAngle)
    }

    private fun projectClick(context: Context, id: String, token: String): Clickable = Clickable.Builder()
        .setId("project-$id")
        .setMinimumClickableWidth(dp(0f)).setMinimumClickableHeight(dp(0f))
        .setOnClick(LaunchAction.Builder().setAndroidActivity(AndroidActivity.Builder()
            .setPackageName(context.packageName).setClassName(WatchTileLaunchActivity::class.java.name)
            .addKeyToExtraMapping("projectId", AndroidStringExtra.Builder().setValue(id).build())
            .addKeyToExtraMapping("token", AndroidStringExtra.Builder().setValue(token).build()).build()).build()).build()
}
