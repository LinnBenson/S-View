package io.todu.sview.data

import java.math.BigDecimal

/** 保留用户填写的单位与数值，屏幕变化只重新计算显示尺寸，不改写配置。 */
data class WindowDimension(val value: Double, val unit: Unit = Unit.DP) {
    enum class Unit(val suffix: String) { DP("dp"), PX("px"), PERCENT("%") }

    init { require(value.isFinite() && value > 0) { "尺寸应为正数" } }

    fun format(): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString() + unit.suffix

    /** 百分比以当前可用宽度或高度为基准，超出范围的输入只在渲染时截取。 */
    fun resolveDp(availableDp: Double, density: Double): Float {
        require(availableDp.isFinite() && availableDp >= 0 && density.isFinite() && density > 0)
        val requested = when (unit) {
            Unit.DP -> value
            Unit.PX -> value / density
            Unit.PERCENT -> availableDp * (value / 100.0)
        }
        return requested.coerceIn(minOf(1.0 / density, availableDp), availableDp).toFloat()
    }

    companion object {
        private val pattern = Regex("^(\\d+(?:\\.\\d*)?|\\.\\d+)\\s*(dp|px|%)$", RegexOption.IGNORE_CASE)

        /** 尺寸必须包含单位，保存和编辑使用同一种格式。 */
        fun parse(input: String): WindowDimension {
            val (value, unit) = parseValue(input)
            return WindowDimension(value, unit)
        }

        /** 宽高与边距共用单位解析，各自校验是否允许零。 */
        internal fun parseValue(input: String): Pair<Double, Unit> {
            val match = pattern.matchEntire(input.trim()) ?: throw IllegalArgumentException("请输入尺寸，如 320dp、600px 或 80%")
            val value = match.groupValues[1].toDoubleOrNull() ?: throw IllegalArgumentException("尺寸无效")
            val unit = when (match.groupValues[2].lowercase()) {
                "px" -> Unit.PX
                "%" -> Unit.PERCENT
                else -> Unit.DP
            }
            return value to unit
        }
    }
}
