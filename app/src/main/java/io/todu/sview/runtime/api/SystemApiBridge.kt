package io.todu.sview.runtime.api

import io.todu.sview.runtime.cleanupResource
import android.content.Context
import android.widget.Toast
import io.todu.sview.data.Project
import org.json.JSONObject
import java.io.Closeable

/** 每个运行窗口独立持有桥接实例，UI 操作取得主线程结果后再回复脚本。 */
class SystemApiBridge(uiContext: Context, private val project: Project, private val foreground: () -> Boolean) : Closeable {
    private val context = uiContext.applicationContext
    private val main = MainThreadCalls()
    private val device = SystemDeviceActions(this.context)
    private val installer = SystemPackageInstaller(this.context, project.id)
    private val navigation = SystemNavigation(uiContext)
    private val info = SystemInfoReader(this.context)
    private val screen = ScreenInfoReader(uiContext)
    private val states = SystemStateReader(this.context)
    private val health = io.todu.sview.runtime.health.HealthInfoReader(this.context)
    private val location = SingleLocationReader(this.context, foreground)
    private val wifiScan = io.todu.sview.runtime.api.scan.WifiScanner(this.context, foreground)
    private val bluetoothScan = io.todu.sview.runtime.api.scan.BluetoothScanner(this.context, foreground)
    private var toast: Toast? = null

    fun invoke(json: JSONObject): JSONObject = try {
        val request = SystemApiRequest.parse(json)
        val result = when (request) {
            SystemApiRequest.HealthInfo -> {
                main.invoke { if (!foreground()) throw io.todu.sview.runtime.api.ApiException("NOT_FOREGROUND", "请在运行窗口位于前台时调用此接口"); JSONObject.NULL }
                health.read()
            }
            is SystemApiRequest.Location -> location.read(request.timeout)
            is SystemApiRequest.Install -> install(request.path)
            is SystemApiRequest.ScanWifi -> wifiScan.read(request.timeout)
            is SystemApiRequest.ScanBluetooth -> bluetoothScan.read(request.timeout)
            else -> main.invoke { execute(request) }
        }
        JSONObject().put("ok", true).put("value", result)
    } catch (error: Exception) {
        ApiErrors.failure(error)
    }

    /** 安装包复制不占用主线程，跳转前再次确认窗口仍在前台。 */
    private fun install(path: String): Any {
        main.invoke { if (!foreground()) throw io.todu.sview.runtime.api.ApiException("NOT_FOREGROUND", "请在运行窗口位于前台时调用此接口"); installer.requireAllowed(); JSONObject.NULL }
        installer.install(path) { file, ensureActive ->
            main.invoke {
                ensureActive()
                if (!foreground()) throw io.todu.sview.runtime.api.ApiException("NOT_FOREGROUND", "请在运行窗口位于前台时调用此接口")
                installer.launch(file)
                JSONObject.NULL
            }
        }
        return JSONObject.NULL
    }

    private fun execute(request: SystemApiRequest): Any {
        if (request !is SystemApiRequest.Info && request !is SystemApiRequest.Notify && request !is SystemApiRequest.RotationSettings &&
            request !is SystemApiRequest.CancelVibration && request !is SystemApiRequest.NetworkState &&
            request !is SystemApiRequest.WifiState && request !is SystemApiRequest.BluetoothState && request !is SystemApiRequest.BatteryInfo) {
            if (!foreground()) throw io.todu.sview.runtime.api.ApiException("NOT_FOREGROUND", "请在运行窗口位于前台时调用此接口")
        }
        return when (request) {
            is SystemApiRequest.Toast -> {
                toast?.cancel()
                toast = Toast.makeText(context, request.message,
                    if (request.duration == "long") Toast.LENGTH_LONG else Toast.LENGTH_SHORT).also { it.show() }
                JSONObject.NULL
            }
            is SystemApiRequest.Notify -> ScriptNotifications.post(context, project, request)
            is SystemApiRequest.Vibrate -> device.vibrate(request.duration)
            is SystemApiRequest.OpenApp -> { navigation.openApp(request.packageName); JSONObject.NULL }
            is SystemApiRequest.OpenLink -> { navigation.openLink(request.url); JSONObject.NULL }
            SystemApiRequest.ReadClipboard -> device.readClipboard()
            is SystemApiRequest.WriteClipboard -> { device.writeClipboard(request.text); JSONObject.NULL }
            is SystemApiRequest.Share -> { navigation.share(request.text, request.title); JSONObject.NULL }
            SystemApiRequest.RotationSettings -> device.rotationSettings()
            SystemApiRequest.CancelVibration -> { device.cancelVibration(); JSONObject.NULL }
            SystemApiRequest.Info -> info.read()
            SystemApiRequest.Screen -> screen.read()
            SystemApiRequest.NetworkState -> states.network()
            SystemApiRequest.WifiState -> states.wifi()
            SystemApiRequest.BluetoothState -> states.bluetooth()
            SystemApiRequest.HealthInfo -> error("健康数据必须异步读取")
            SystemApiRequest.BatteryInfo -> states.battery()
            is SystemApiRequest.ScanWifi, is SystemApiRequest.ScanBluetooth -> error("扫描必须异步执行")
            is SystemApiRequest.Install -> error("安装包准备必须异步执行")
            is SystemApiRequest.Location -> error("定位必须异步执行")
        }
    }

    fun background() { health.cancel(); location.cancel(); device.close(); installer.background(); wifiScan.cancel(); bluetoothScan.cancel() }

    /** 由运行窗口在主线程释放，取消排队调用和尚未结束的短时效果。 */
    override fun close() {
        cleanupResource("健康数据请求") { health.close() }
        cleanupResource("Wi-Fi 扫描") { wifiScan.close() }
        cleanupResource("蓝牙扫描") { bluetoothScan.close() }
        cleanupResource("安装准备") { installer.close() }
        cleanupResource("定位请求") { location.close() }
        cleanupResource("系统调用队列") { main.close() }
        val previousToast = toast
        toast = null
        cleanupResource("提示") { previousToast?.cancel() }
        cleanupResource("震动") { device.close() }
    }
}
