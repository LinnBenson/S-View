package io.todu.sview.wear.tile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import androidx.wear.protolayout.ResourceBuilders.*
import io.todu.sview.BuildConfig
import io.todu.sview.R
import io.todu.sview.data.Project
import io.todu.sview.ui.components.ProjectIconCatalog
import java.nio.ByteBuffer

/** 将少量矢量图标转换为磁贴支持的 ARGB 位图；资源版本携带图标集合，避免同步期间串用两代资源。 */
internal object WatchTileResources {
    const val BRAND = "s-view-brand"

    fun version(projects: List<Project>): String = BuildConfig.VERSION_NAME + ":" +
        (listOf(BRAND) + projects.map { it.icon }).distinct().joinToString(",")

    fun create(context: Context, version: String): Resources {
        val names = version.substringAfter(':', BRAND).split(',').distinct().take(9)
        return Resources.Builder().setVersion(version).apply {
            names.forEach { name ->
                val resource = if (name == BRAND) R.drawable.s_view_mark
                    else ProjectIconCatalog.byName[name]?.resource ?: R.drawable.lucide_code
                addIdToImageMapping(name, image(context, resource, name == BRAND))
            }
        }.build()
    }

    private fun image(context: Context, resource: Int, brand: Boolean): ImageResource {
        val bitmap = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
        val bytes = try {
            val drawable = requireNotNull(ContextCompat.getDrawable(context, resource)).mutate()
            drawable.setTint(if (brand) 0xFFF0F2F8.toInt() else 0xFF263247.toInt())
            drawable.setBounds(0, 0, 72, 72)
            drawable.draw(Canvas(bitmap))
            // 明确传入像素格式与尺寸，避免系统磁贴对压缩图片的格式识别差异。
            ByteBuffer.allocate(bitmap.byteCount).also(bitmap::copyPixelsToBuffer).array()
        } finally { bitmap.recycle() }
        return ImageResource.Builder().setInlineResource(InlineImageResource.Builder().setData(bytes)
            .setWidthPx(72).setHeightPx(72).setFormat(IMAGE_FORMAT_ARGB_8888).build()).build()
    }
}
