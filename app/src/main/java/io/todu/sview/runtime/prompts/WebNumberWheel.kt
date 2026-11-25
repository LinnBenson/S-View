package io.todu.sview.runtime.prompts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** 只绘制可见数字；停止滚动后回传居中项，不运行持续动画或计时器。 */
@Composable
internal fun NumberWheel(label: String, value: Int, range: IntRange, change: (Int) -> Unit, modifier: Modifier = Modifier, onScrollingChanged: (Boolean) -> Unit) {
    val watch = io.todu.sview.wear.WatchDevice.isWatch(LocalContext.current)
    val rowHeight = if (watch) 22.dp else 36.dp
    val rowPixels = with(LocalDensity.current) { rowHeight.roundToPx() }
    key(range.first, range.last) {
        val state = rememberLazyListState((value - range.first).coerceIn(0, range.last - range.first))
        val currentChange by rememberUpdatedState(change)
        val currentScrollingChanged by rememberUpdatedState(onScrollingChanged)
        val scope = rememberCoroutineScope()
        LaunchedEffect(value) {
            val index = (value - range.first).coerceIn(0, range.last - range.first)
            if (!state.isScrollInProgress && (state.firstVisibleItemIndex != index || state.firstVisibleItemScrollOffset != 0)) {
                state.scrollToItem(index)
            }
        }
        LaunchedEffect(state) {
            snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
                // 先提交停稳值，再解除确认限制，避免提交上一轮数字。
                if (!scrolling) currentChange((range.first + state.firstVisibleItemIndex).coerceIn(range))
                currentScrollingChanged(scrolling)
            }
        }
        DisposableEffect(state, rowPixels) {
            onDispose {
                // 外层列表滚走或切换布局时保存最接近中心的值，不只解除确认限制。
                currentChange(nearestWheelValue(range.first, range.last, state.firstVisibleItemIndex,
                    state.firstVisibleItemScrollOffset, rowPixels))
                currentScrollingChanged(false)
            }
        }
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, modifier = Modifier.padding(bottom = if (watch) 2.dp else 6.dp), fontSize = if (watch) 11.sp else 12.sp, lineHeight = if (watch) 14.sp else 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.fillMaxWidth().height(rowHeight * 3), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(rowHeight)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .12f), RoundedCornerShape(8.dp)))
                LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = rowHeight),
                    flingBehavior = rememberSnapFlingBehavior(state)) {
                    items(range.last - range.first + 1) { index ->
                        val number = range.first + index
                        Box(Modifier.fillMaxWidth().height(rowHeight).clickable {
                            currentScrollingChanged(true)
                            scope.launch {
                                state.animateScrollToItem(index)
                                // 点击当前项可能没有滚动事件，也需要解除限制；取消的旧动画不写回。
                                if (!state.isScrollInProgress) {
                                    currentChange((range.first + state.firstVisibleItemIndex).coerceIn(range))
                                    currentScrollingChanged(false)
                                }
                            }
                        }, contentAlignment = Alignment.Center) {
                            Text(number.toString().padStart(2, '0'), fontSize = if (watch) 17.sp else 20.sp, lineHeight = if (watch) 20.sp else 24.sp,
                                color = if (number == value) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f))
                        }
                    }
                }
            }
        }
    }
}

/** 可见首项可能只剩半格；中途结束滚动时选择更靠近中心的一项，并限制在有效范围内。 */
internal fun nearestWheelValue(minimum: Int, maximum: Int, index: Int, offset: Int, itemSize: Int): Int {
    val next = itemSize > 0 && offset >= (itemSize + 1) / 2
    return (minimum + index + if (next) 1 else 0).coerceIn(minimum, maximum)
}
