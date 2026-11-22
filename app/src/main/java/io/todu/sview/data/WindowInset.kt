package io.todu.sview.data

import java.math.BigDecimal

/** 带单位的单边外边距，零值合法；事件和备份保留单位。 */
data class WindowInset(val value: Double = 0.0, val unit: WindowDimension.Unit = WindowDimension.Unit.DP) {
    init { require(value.isFinite() && value >= 0 && value <= Float.MAX_VALUE) { "边距应为非负有限数值" } }

    fun format(): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString() + unit.suffix

    fun resolve(available: Double, density: Double): Double = when (unit) {
        WindowDimension.Unit.DP -> value
        WindowDimension.Unit.PX -> value / density
        WindowDimension.Unit.PERCENT -> available * (value / 100.0)
    }

    companion object {
        fun parse(input: String): WindowInset {
            val (value, unit) = WindowDimension.parseValue(input)
            return WindowInset(value, unit)
        }
    }
}
