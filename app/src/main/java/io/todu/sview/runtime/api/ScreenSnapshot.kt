package io.todu.sview.runtime.api

import org.json.JSONObject

/** 将原生像素转换成带有明确单位的屏幕快照，尺寸与安全区域使用同一次读取。 */
internal object ScreenSnapshot {
    fun size(width: Int, height: Int, density: Float): JSONObject {
        require(width >= 0 && height >= 0 && density.isFinite() && density > 0) { "屏幕尺寸不可用" }
        return JSONObject().put("width", width).put("height", height)
            .put("widthDp", width / density.toDouble()).put("heightDp", height / density.toDouble())
    }

    fun window(width: Int, height: Int, density: Float, left: Int, top: Int, right: Int, bottom: Int): JSONObject =
        size(width, height, density)
            .put("insets", JSONObject().put("left", left).put("top", top).put("right", right).put("bottom", bottom))
            .put("contentWidth", (width - left - right).coerceAtLeast(0))
            .put("contentHeight", (height - top - bottom).coerceAtLeast(0))

    fun orientation(width: Int, height: Int) = when {
        width > height -> "landscape"
        width < height -> "portrait"
        else -> "square"
    }
}
