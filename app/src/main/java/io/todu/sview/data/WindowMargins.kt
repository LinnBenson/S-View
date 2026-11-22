package io.todu.sview.data

import org.json.JSONObject

/** 四边距保留各自单位；百分比始终相对于扣除边距前的安全显示区域。 */
data class WindowMargins(val top: WindowInset = WindowInset(), val right: WindowInset = WindowInset(),
    val bottom: WindowInset = WindowInset(), val left: WindowInset = WindowInset()) {
    fun toJson(): JSONObject = JSONObject().put("top", top.format()).put("right", right.format())
        .put("bottom", bottom.format()).put("left", left.format())

    data class Resolved(val top: Double, val right: Double, val bottom: Double, val left: Double)

    /** 两侧总边距超出屏幕时同比缩小，至少留出一个 dp，不覆盖用户配置。 */
    fun resolve(width: Double, height: Double, density: Double): Resolved {
        require(width.isFinite() && width >= 0 && height.isFinite() && height >= 0 && density.isFinite() && density > 0)
        val topDp = top.resolve(height, density)
        val bottomDp = bottom.resolve(height, density)
        val leftDp = left.resolve(width, density)
        val rightDp = right.resolve(width, density)
        fun scale(first: Double, second: Double, available: Double): Double =
            if (first + second == 0.0) 1.0 else minOf(1.0, (available - 1.0).coerceAtLeast(0.0) / (first + second))
        val horizontal = scale(leftDp, rightDp, width)
        val vertical = scale(topDp, bottomDp, height)
        return Resolved(topDp * vertical, rightDp * horizontal, bottomDp * vertical, leftDp * horizontal)
    }

    companion object {
        fun fromJson(json: JSONObject): WindowMargins {
            fun inset(name: String): WindowInset = when (val value = json.get(name)) {
                is String -> WindowInset.parse(value)
                is Number -> WindowInset(value.toDouble())
                else -> throw IllegalArgumentException("$name 应为带 dp、px 或 % 的字符串，或 dp 数值")
            }
            return WindowMargins(inset("top"), inset("right"), inset("bottom"), inset("left"))
        }
    }
}
