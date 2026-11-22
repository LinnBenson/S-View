package io.todu.sview.wear

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.sview.BuildConfig
import io.todu.sview.ui.components.SViewMark

/** 图标固定在表盘中心，名称与版本固定在底部安全区域，避免列表边缘缩放改变位置。 */
@Composable
fun WatchAboutScreen() {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Text("关于应用", Modifier.align(Alignment.TopCenter).padding(top = maxHeight * .16f),
            fontSize = 16.sp, textAlign = TextAlign.Center)
        SViewMark(Modifier.size(32.dp).align(Alignment.Center), tint = Color(0xFFF0F2F8))
        // 文字区收窄，给圆屏下缘留出安全距离。
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).fillMaxWidth(.6f),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("S View", fontSize = 14.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
            Text(BuildConfig.VERSION_NAME, fontSize = 10.sp, lineHeight = 14.sp,
                color = Color(0xFFA6AFC1), textAlign = TextAlign.Center)
        }
    }
}
