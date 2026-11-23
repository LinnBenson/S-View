package io.todu.sview.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.todu.sview.data.WorkspaceOwner
import io.todu.sview.ui.components.*
import io.todu.sview.ui.theme.SViewTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 配置由桌面启动，只有保存成功才向桌面返回完成。 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider?.className != ProjectWidget::class.java.name) { finish(); return }
        val initialGrid = WidgetGridSizing.current(this, id)
        enableEdgeToEdge()
        val model = WorkspaceOwner.model(application)
        setContent { SViewTheme {
            var selected by rememberSaveable { mutableStateOf(ArrayList(WidgetStore.read(this, id))) }
            var gridColumns by rememberSaveable { mutableIntStateOf(initialGrid.first) }
            var gridRows by rememberSaveable { mutableIntStateOf(initialGrid.second) }
            var choosing by rememberSaveable { mutableStateOf<Int?>(null) }
            var saving by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val round = LocalConfiguration.current.isScreenRound
            Column(Modifier.fillMaxSize().safeDrawingPadding().then(if (round) Modifier.padding(horizontal = 24.dp, vertical = 20.dp) else Modifier)) {
                PageHeader("项目快捷方式", { finish() }) {
                    IconAction("check", "保存", {
                        if (!saving) {
                            saving = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        WidgetGridSizing.calibrate(this@WidgetConfigActivity, id, gridColumns, gridRows)
                                        WidgetStore.save(this@WidgetConfigActivity, id, selected)
                                        ProjectWidget.update(this@WidgetConfigActivity, intArrayOf(id))
                                    }
                                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                                    finish()
                                } catch (error: Exception) {
                                    android.widget.Toast.makeText(this@WidgetConfigActivity, error.message ?: "保存失败", android.widget.Toast.LENGTH_LONG).show()
                                    saving = false
                                }
                            }
                        }
                    }, enabled = model.ready && !saving)
                }
                Text("按顺序显示，数量随小部件大小调整", color = Muted, modifier = Modifier.padding(20.dp))
                LazyColumn(Modifier.weight(1f).widthIn(max = 600.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(horizontal = 20.dp)) {
                    item {
                        WidgetGridCalibration(gridColumns, gridRows, !saving, { gridColumns = it }, { gridRows = it })
                    }
                    items(WidgetStore.SLOTS) { index ->
                        val project = model.projects.find { it.id == selected[index] }
                        Row(Modifier.fillMaxWidth().clickable { choosing = index }.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("${index + 1}", color = Muted)
                            AppIcon(project?.icon ?: "plus")
                            Text(project?.name ?: "选择项目", Modifier.weight(1f))
                            AppIcon("chevron-right")
                        }
                    }
                }
            }
            choosing?.let { slot -> AppDialog(onDismissRequest = { choosing = null }, title = { Text("选择项目") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        item { Text("留空", Modifier.fillMaxWidth().clickable {
                            selected = ArrayList(selected).also { it[slot] = "" }; choosing = null
                        }.padding(16.dp), color = Muted) }
                        items(model.projects, key = { it.id }) { project ->
                            Row(Modifier.fillMaxWidth().clickable {
                                selected = ArrayList(selected).also { it[slot] = project.id }; choosing = null
                            }.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AppIcon(project.icon); Text(project.name)
                            }
                        }
                    }
                }, confirmButton = { DialogAction("取消", { choosing = null }) }) }
        } }
    }
}
