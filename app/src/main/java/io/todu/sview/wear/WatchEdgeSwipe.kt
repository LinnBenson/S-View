package io.todu.sview.wear

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.sqrt

/** 仅识别左侧 12dp 边缘区域起手的右滑，返回前给予一次系统触感反馈。 */
fun Modifier.watchEdgeSwipe(round: Boolean, onBack: () -> Unit): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    val currentOnBack = rememberUpdatedState(onBack)
    // 回调更新不重启手势协程，避免网页或系统页面重组打断正在进行的滑动。
    pointerInput(round, haptics) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val radius = size.width / 2f
            val relativeY = down.position.y - size.height / 2f
            val left = if (round) radius - sqrt((radius * radius - relativeY * relativeY).coerceAtLeast(0f)) else 0f
            if (down.position.x < left || down.position.x > left + 12.dp.toPx()) return@awaitEachGesture
            var returned = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                val delta = pointer.position - down.position
                // 纵向滚动或向左拖动一旦明确，就交还给页面，不在途中转成返回。
                if (!returned && (delta.x < -viewConfiguration.touchSlop ||
                        abs(delta.y) > 28.dp.toPx() && abs(delta.y) > abs(delta.x))) break
                if (!returned && pointer.pressed && delta.x > 38.dp.toPx() && delta.x > abs(delta.y) * 1.5f) {
                    returned = true
                    pointer.consume()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    currentOnBack.value()
                }
                if (returned) event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}
