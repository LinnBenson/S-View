package io.todu.sview.runtime.api.scan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.io.Closeable

/** 只请求一次系统 Wi-Fi 扫描；不轮询、不把刷新失败时的缓存冒充新结果。 */
internal class WifiScanner(private val context: Context, foreground: () -> Boolean) : Closeable {
    private val scan = SingleRadioScan("Wi-Fi", foreground)

    @Suppress("DEPRECATION") // startScan 仍是公开 API 中主动请求扫描的入口。
    fun read(timeout: Long): JSONArray = scan.read(timeout) { session, finish ->
        check(context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)) { "设备不支持 Wi-Fi" }
        ScanPermissions.location(context)
        val manager = checkNotNull(context.getSystemService(WifiManager::class.java)) { "设备不支持 Wi-Fi" }
        check(manager.isWifiEnabled) { "请先开启 Wi-Fi" }
        val started = SystemClock.elapsedRealtime() * 1000
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (session.result.isDone) return
                if (intent.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                    if (intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1) == WifiManager.WIFI_STATE_DISABLED) {
                        finish(Result.failure(IllegalStateException("Wi-Fi 已关闭")))
                    }
                    return
                }
                if (intent.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
                finish(runCatching {
                    check(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)) { "Wi-Fi 扫描未刷新，可能受系统限流，请稍后重试" }
                    ScanPermissions.location(context)
                    val entries = manager.scanResults.map { item ->
                        val ssid = if (Build.VERSION.SDK_INT >= 33) item.wifiSsid?.toString().orEmpty().removeSurrounding("\"") else item.SSID.orEmpty()
                        WifiScanResults.Entry(ssid, item.BSSID.orEmpty(), item.level, item.frequency, item.capabilities.orEmpty(), item.timestamp)
                    }
                    WifiScanResults.toJson(entries, started)
                })
            }
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION).apply { addAction(WifiManager.WIFI_STATE_CHANGED_ACTION) }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        session.cleanup.add { context.unregisterReceiver(receiver) }
        check(manager.startScan()) { "Wi-Fi 扫描未启动，可能受系统限流，请稍后重试" }
    }

    fun cancel() = scan.cancel()
    override fun close() = scan.close()
}
