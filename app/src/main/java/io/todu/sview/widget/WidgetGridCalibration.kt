package io.todu.sview.widget

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.todu.sview.ui.components.IconAction
import io.todu.sview.ui.components.Muted

/** 占格数由用户对照桌面确认，与下面的项目顺序分开保存。 */
@Composable
internal fun WidgetGridCalibration(columns: Int, rows: Int, enabled: Boolean, onColumns: (Int) -> Unit, onRows: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text("当前占格", color = Muted)
        GridCount("列", columns, enabled, onColumns)
        GridCount("行", rows, enabled, onRows)
    }
}

@Composable
private fun GridCount(label: String, value: Int, enabled: Boolean, change: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        IconAction("minus", "减少${label}数", { change(value - 1) }, enabled = enabled && value > 1)
        Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) { Text(value.toString()) }
        IconAction("plus", "增加${label}数", { change(value + 1) }, enabled = enabled && value < 4)
    }
}
