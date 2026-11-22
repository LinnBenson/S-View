package io.todu.sview.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 网格和分类列表共用拖动状态；位移以可见区域坐标计算，不受让位动画干扰。 */
data class ReorderItem(val key: String, val index: Int, val offset: IntOffset, val size: IntSize)

@Stable
class ReorderDragState(
    private val scope: CoroutineScope,
    private val items: () -> List<ReorderItem>,
    private val move: (String, String) -> Unit,
    private val finish: () -> Unit,
    private val canMove: (String, String) -> Boolean,
    private val onStart: () -> Unit
) {
    var dragging by mutableStateOf<String?>(null); private set
    var settling by mutableStateOf<String?>(null); private set
    private var origin by mutableStateOf(Offset.Zero)
    private var delta by mutableStateOf(Offset.Zero)
    private var itemSize = IntSize.Zero
    private var pendingIndex: Int? = null
    private val landing = Animatable(Offset.Zero, Offset.VectorConverter)
    private var landingJob: Job? = null

    val center: Offset get() = origin + delta + Offset(itemSize.width / 2f, itemSize.height / 2f)

    fun start(key: String) {
        if (dragging != null) return
        val item = items().find { it.key == key } ?: return
        landingJob?.cancel()
        settling = null
        dragging = key
        origin = Offset(item.offset.x.toFloat(), item.offset.y.toFloat())
        itemSize = item.size
        delta = Offset.Zero
        pendingIndex = null
        // 每次有效长按只触发一次系统触感，遵循设备的触感设置。
        onStart()
    }

    fun drag(amount: Offset) {
        val key = dragging ?: return
        delta += amount
        val visible = items()
        val current = visible.find { it.key == key } ?: return
        if (pendingIndex != null && current.index != pendingIndex) return
        pendingIndex = null
        val target = visible.firstOrNull {
            it.key != key && canMove(key, it.key) && Rect(it.offset.x.toFloat(), it.offset.y.toFloat(),
                (it.offset.x + it.size.width).toFloat(), (it.offset.y + it.size.height).toFloat()).contains(center)
        } ?: return
        pendingIndex = target.index
        move(key, target.key)
    }

    fun offset(key: String): Offset {
        if (settling == key) return landing.value
        if (dragging != key) return Offset.Zero
        val item = items().find { it.key == key } ?: return delta
        return origin + delta - Offset(item.offset.x.toFloat(), item.offset.y.toFloat())
    }

    /** 松手后独立执行落位动画；磁盘保存只触发一次。 */
    fun end() {
        val key = dragging ?: return
        finish()
        landingJob = scope.launch {
            withFrameNanos { }
            val remaining = offset(key)
            landing.snapTo(remaining)
            settling = key
            dragging = null
            pendingIndex = null
            landing.animateTo(Offset.Zero, spring(dampingRatio = .82f, stiffness = 500f))
            settling = null
        }
    }
}

@Composable
fun rememberReorderDragState(items: () -> List<ReorderItem>, move: (String, String) -> Unit,
    finish: () -> Unit, canMove: (String, String) -> Boolean = { _, _ -> true }): ReorderDragState {
    val scope = rememberCoroutineScope()
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    val currentItems by rememberUpdatedState(items)
    val currentMove by rememberUpdatedState(move)
    val currentFinish by rememberUpdatedState(finish)
    val currentCanMove by rememberUpdatedState(canMove)
    val state = remember(scope) { ReorderDragState(scope, { currentItems() }, { a, b -> currentMove(a, b) },
        { currentFinish() }, { a, b -> currentCanMove(a, b) },
        { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }) }
    DisposableEffect(state) {
        onDispose { if (state.dragging != null) currentFinish() }
    }
    return state
}

/** 仅在拖动期间逐帧检查边缘，静止页面没有滚动计时器。 */
@Composable
fun ReorderAutoScroll(state: ReorderDragState, bounds: () -> Pair<Float, Float>, scroll: suspend (Float) -> Float) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val edge = with(density) { 48.dp.toPx() }
    val step = with(density) { 10.dp.toPx() }
    val currentBounds by rememberUpdatedState(bounds)
    val currentScroll by rememberUpdatedState(scroll)
    LaunchedEffect(state.dragging) {
        if (state.dragging == null) return@LaunchedEffect
        var previous = 0L
        while (state.dragging != null) {
            val frame = withFrameNanos { it }
            val elapsed = if (previous == 0L) 1f else ((frame - previous) / 16_666_667f).coerceIn(.25f, 2f)
            previous = frame
            val (start, end) = currentBounds()
            val y = state.center.y
            val speed = when {
                y < start + edge -> -((start + edge - y) / edge).coerceIn(0f, 1f)
                y > end - edge -> ((y - end + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (speed != 0f && currentScroll(speed * step * elapsed) != 0f) state.drag(Offset.Zero)
        }
    }
}
