package io.todu.sview.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.PositionIndicatorState
import androidx.wear.compose.material.PositionIndicatorVisibility

/** 沿用 Wear OS 原生绘制与位移动画，可滚动时保持可见，不在停手后自动隐藏。 */
@Composable
internal fun WatchPositionIndicator(list: ScalingLazyListState) {
    val state = remember(list) { WatchPositionState(list) }
    // 原生组件内部已按全屏定位，外加 align 或 matchParentSize 会干扰其边界容器。
    PositionIndicator(state = state, indicatorHeight = 60.dp, indicatorWidth = 3.dp,
        paddingHorizontal = 3.dp, color = Color.White,
        background = Color(0xFF45474B))
}

/** 根据缩放后的首尾条目计算可见比例，部分进入屏幕的条目也参与连续位移。 */
private class WatchPositionState(private val list: ScalingLazyListState) : PositionIndicatorState {
    private fun visibleRange(): Pair<Float, Float> {
        val layout = list.layoutInfo
        val items = layout.visibleItemsInfo
        if (items.isEmpty()) return 0f to 0f
        val first = items.first()
        val last = items.last()
        val halfViewport = layout.viewportSize.height / 2f
        val centered = layout.anchorType == ScalingLazyListAnchorType.ItemCenter
        val firstTop = first.offset - if (centered) first.size / 2f else 0f
        val lastTop = last.offset - if (centered) last.size / 2f else 0f
        val start = first.index + ((-halfViewport - firstTop) / first.size.coerceAtLeast(1)).coerceIn(0f, 1f)
        val end = last.index + ((halfViewport - lastTop) / last.size.coerceAtLeast(1)).coerceIn(0f, 1f)
        return start to end
    }

    override val positionFraction: Float
        get() {
            if (!list.canScrollBackward) return 0f
            if (!list.canScrollForward) return 1f
            val (start, end) = visibleRange()
            return (start / (start + list.layoutInfo.totalItemsCount - end).coerceAtLeast(.001f)).coerceIn(0f, 1f)
        }

    override fun sizeFraction(scrollableContainerSizePx: Float): Float {
        val (start, end) = visibleRange()
        return ((end - start) / list.layoutInfo.totalItemsCount.coerceAtLeast(1)).coerceIn(.1f, .9f)
    }

    override fun visibility(scrollableContainerSizePx: Float) =
        if (list.canScrollBackward || list.canScrollForward) PositionIndicatorVisibility.Show
        else PositionIndicatorVisibility.Hide
}
