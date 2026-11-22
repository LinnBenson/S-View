package io.todu.sview.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.todu.sview.R

/** 首页与宽屏侧栏共用的品牌标记，以轮廓实际高度控制视觉大小。 */
@Composable
fun SViewMark(modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    Icon(painterResource(R.drawable.s_view_mark), contentDescription = "S View",
        modifier = modifier.size(width = 30.dp, height = 28.dp), tint = tint)
}
