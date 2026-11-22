package io.todu.sview.runtime.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.todu.sview.ui.components.PageHeader
import io.todu.sview.ui.theme.SViewTheme

/** Health Connect 授权页要求提供可查看的数据用途说明。 */
class HealthPermissionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SViewTheme {
                Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                    Column {
                        PageHeader("健康数据", { finish() })
                        Column(Modifier.widthIn(max = 648.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
                            .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("S View 允许项目脚本读取今日步数、距离、总热量，以及最近 30 天的心率、血氧和睡眠记录。")
                            Text("只有获得你授权的类型才能读取。应用不修改健康记录，不主动发起测量，也不在后台持续采集。")
                            Text("读取结果会交给当前运行的项目。脚本可以处理、保存或通过网络发送这些数据，请只运行你信任的项目。")
                            Text("你可以随时在系统健康数据权限设置中撤销授权。")
                        }
                    }
                }
            }
        }
    }
}
