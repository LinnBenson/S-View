package io.todu.sview.widget

import kotlin.math.abs
import kotlin.math.roundToInt

/** 用用户确认的尺寸与占格数校准单个方向；两点可同时确定格距与桌面边距。 */
internal object WidgetGridAxis {
    data class Sample(val size: Float, val span: Int)

    fun resolve(size: Float, samples: List<Sample>, fallbackPitch: Float, padding: Float): Int {
        if (!size.isFinite() || size <= 0) return 1
        val ordered = samples.sortedBy { it.size }
        ordered.firstOrNull { abs(it.size - size) <= 2f }?.let { return it.span }
        if (ordered.isEmpty()) return ((size + padding) / fallbackPitch).roundToInt().coerceIn(1, 4)
        if (ordered.size == 1) {
            val anchor = ordered.single()
            val pitch = ((anchor.size + padding) / anchor.span).coerceAtLeast(1f)
            return (anchor.span + (size - anchor.size) / pitch).roundToInt().coerceIn(1, 4)
        }
        // 选取包围当前尺寸的两个校准点，超出范围时使用最近的一段格距。
        val upper = ordered.indexOfFirst { it.size >= size }.let { if (it < 0) ordered.lastIndex else it.coerceAtLeast(1) }
        val first = ordered[upper - 1]
        val second = ordered[upper]
        val span = first.span + (size - first.size) * (second.span - first.span) / (second.size - first.size)
        return span.roundToInt().coerceIn(1, 4)
    }

    /** 同一占格数的尺寸明显变化表示桌面网格已改变，旧格距不能继续参与计算。 */
    fun calibrate(samples: List<Sample>, size: Float, span: Int): List<Sample> {
        require(size.isFinite() && size > 0 && span in 1..4)
        val changed = samples.any { it.span == span && abs(it.size - size) > 2f }
        val retained = if (changed) emptyList() else samples.filter {
            abs(it.size - size) > 2f && (it.size - size) * (it.span - span) > 0
        }
        return (retained + Sample(size, span)).sortedBy { it.size }
    }
}
