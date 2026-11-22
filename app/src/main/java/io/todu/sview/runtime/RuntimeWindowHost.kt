package io.todu.sview.runtime

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.todu.sview.wear.watchEdgeSwipe

/** 有遮罩时使用模态窗口；完全透明时只让网页窗口占用触摸区域。 */
@Composable
internal fun RuntimeWindowHost(
    transparency: Double,
    onDismiss: () -> Unit,
    onBackdropClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val round = androidx.compose.ui.platform.LocalConfiguration.current.isScreenRound
    if (io.todu.sview.wear.WatchDevice.isWatch(context)) {
        BackHandler(onBack = onDismiss)
        Box(Modifier.fillMaxSize().watchEdgeSwipe(round, onDismiss)) {
            // 手表在当前窗口内运行，没有 Dialog 的系统 dim 层，需要显式绘制项目遮罩。
            if (transparency < 100.0) Box(Modifier.matchParentSize()
                .background(Color.Black.copy(alpha = (1.0 - transparency / 100.0).toFloat().coerceIn(0f, 1f)))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClickLabel = "关闭运行窗口", onClick = onBackdropClick))
            KeepScreenOnContent(content)
        }
    } else if (io.todu.sview.runtime.overlay.LocalRuntimeOverlay.current != null) {
        KeepScreenOnContent(content)
    } else if (transparency == 100.0) {
        BackHandler(onBack = onDismiss)
        // 不给全屏容器添加触摸处理，窗口外由下层工作区接收事件。
        Box(Modifier.fillMaxSize()) { KeepScreenOnContent(content) }
    } else {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        )) {
            val window = (LocalView.current.parent as DialogWindowProvider).window
            SideEffect {
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setDimAmount((1.0 - transparency / 100.0).toFloat())
            }
            Box(Modifier.fillMaxSize()) {
                // 单独放在网页下层，网页内容的点击不会冒泡为遮罩点击。
                Box(Modifier.matchParentSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "关闭运行窗口",
                    onClick = onBackdropClick,
                ))
                KeepScreenOnContent(content)
            }
        }
    }
}

/** 跟随实际承载视图保持亮屏；关闭项目时恢复此前状态，不修改系统超时设置。 */
@Composable
private fun KeepScreenOnContent(content: @Composable () -> Unit) {
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    content()
}
