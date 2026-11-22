package io.todu.sview.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.sview.BuildConfig
import io.todu.sview.ui.components.*

/** 独立关于页；折叠屏展开后将品牌区与项目信息并排展示。 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageHeader("关于 S View", onBack)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val expanded = maxWidth >= 720.dp
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (expanded) {
                    Row(Modifier.widthIn(max = 960.dp).fillMaxWidth().padding(top = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
                        AboutBrand(Modifier.weight(1f))
                        AboutDetails(Modifier.weight(1f))
                    }
                } else {
                    AboutBrand(Modifier.fillMaxWidth().padding(vertical = 24.dp))
                    Spacer(Modifier.height(28.dp))
                    AboutDetails(Modifier.widthIn(max = 560.dp).fillMaxWidth())
                }
                Text("io.todu.sview", color = Muted, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 32.dp, bottom = 16.dp))
            }
        }
    }
}

/** 应用标识和版本直接读取构建信息，避免页面与安装版本不一致。 */
@Composable
private fun AboutBrand(modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(112.dp).background(Panel, RoundedCornerShape(32.dp)), contentAlignment = Alignment.Center) {
            SViewMark(Modifier.size(width = 60.dp, height = 56.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("S View", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.height(10.dp))
        Text("版本 ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = Blue,
            modifier = Modifier.background(CardColors.first(), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp))
        Spacer(Modifier.height(20.dp))
        Text("创建、编辑和运行网页项目", fontSize = 14.sp, color = Muted, textAlign = TextAlign.Center)
    }
}

/** 将项目来源与核心开源组件分组，保留原有许可信息。 */
@Composable
private fun AboutDetails(modifier: Modifier) {
    val uriHandler = LocalUriHandler.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Panel).padding(horizontal = 20.dp)) {
            AboutLink("code", "项目源码", "GitHub", { uriHandler.openUri("https://github.com/LinnBenson/S-View") })
            HorizontalDivider(color = Line)
            AboutLink("file-text", "开源协议", "Apache-2.0", { uriHandler.openUri("https://www.apache.org/licenses/LICENSE-2.0") })
        }
        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Panel).padding(20.dp)) {
            Text("开源组件", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(18.dp))
            AboutCredit("GeckoView", "Mozilla Public License 2.0")
            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = Line)
            AboutCredit("Lucide", "ISC License")
        }
    }
}

@Composable
private fun AboutLink(icon: String, title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(icon, modifier = Modifier.size(20.dp), tint = Blue)
        Text(title, Modifier.weight(1f).padding(start = 12.dp), fontSize = 14.sp, color = Ink)
        Text(value, fontSize = 12.sp, color = Muted)
        AppIcon("chevron-right", modifier = Modifier.padding(start = 8.dp).size(16.dp), tint = Muted)
    }
}

@Composable
private fun AboutCredit(name: String, license: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(name, fontSize = 15.sp, color = Ink, fontWeight = FontWeight.Medium)
        Text(license, fontSize = 12.sp, color = Muted)
    }
}
