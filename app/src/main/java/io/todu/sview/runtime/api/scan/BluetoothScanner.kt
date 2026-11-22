package io.todu.sview.runtime.api.scan

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.io.Closeable

/** 有界的经典蓝牙与 BLE 扫描，不读取离线配对列表，也不进行配对或连接。 */
internal class BluetoothScanner(private val context: Context, foreground: () -> Boolean) : Closeable {
    private val scan = SingleRadioScan("蓝牙", foreground)
    private val handler = Handler(Looper.getMainLooper())

    fun read(timeout: Long): JSONArray = scan.read(timeout) { session, finish ->
        if (Build.VERSION.SDK_INT >= 31) {
            ScanPermissions.require(context, Manifest.permission.BLUETOOTH_SCAN, "请在设置 → 权限中允许附近设备")
            ScanPermissions.require(context, Manifest.permission.BLUETOOTH_CONNECT, "请在设置 → 权限中允许附近设备")
        }
        ScanPermissions.location(context)
        val adapter = checkNotNull(context.getSystemService(BluetoothManager::class.java)?.adapter) { "设备不支持蓝牙" }
        check(adapter.isEnabled) { "请先开启蓝牙" }
        check(!adapter.isDiscovering) { "系统正在搜索蓝牙设备，请稍后重试" }
        val results = BluetoothScanResults()
        session.timeout = { results.toJson() }
        val classic = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        val ble = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        check(classic || ble) { "设备不支持蓝牙扫描" }
        var discovering = false
        fun collect(device: BluetoothDevice, name: String?, rssi: Int?, source: String) {
            val type = when (device.type) {
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
                BluetoothDevice.DEVICE_TYPE_LE -> "le"
                BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
                else -> "unknown"
            }
            results.add(device.address, name ?: device.name, rssi, type, device.bondState == BluetoothDevice.BOND_BONDED, source)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (session.result.isDone) return
                try {
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            if (!discovering) return
                            val device = deviceExtra(intent) ?: return
                            val rssi = if (intent.hasExtra(BluetoothDevice.EXTRA_RSSI)) intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, 0).toInt() else null
                            collect(device, intent.getStringExtra(BluetoothDevice.EXTRA_NAME), rssi, "classic")
                        }
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                            if (!discovering) return
                            discovering = false
                            if (!ble) finish(Result.success(results.toJson()))
                        }
                        BluetoothAdapter.ACTION_STATE_CHANGED -> {
                            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) {
                                finish(Result.failure(IllegalStateException("蓝牙已关闭或正在切换状态")))
                            }
                        }
                    }
                } catch (error: Exception) { finish(Result.failure(error)) }
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            if (classic) { addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) }
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        session.cleanup.add { context.unregisterReceiver(receiver) }
        if (classic) {
            session.cleanup.add { if (discovering) { discovering = false; adapter.cancelDiscovery() } }
            check(adapter.startDiscovery()) { "蓝牙扫描未启动，请稍后重试" }
            discovering = true
        }
        if (ble) {
            val scanner = checkNotNull(adapter.bluetoothLeScanner) { "BLE 扫描不可用" }
            val callback = object : ScanCallback() {
                private fun receive(items: List<ScanResult>) {
                    handler.post {
                        if (!session.result.isDone) try {
                            items.forEach { collect(it.device, it.scanRecord?.deviceName, it.rssi, "ble") }
                        } catch (error: Exception) { finish(Result.failure(error)) }
                    }
                }
                override fun onScanResult(callbackType: Int, result: ScanResult) = receive(listOf(result))
                override fun onBatchScanResults(results: MutableList<ScanResult>) = receive(results)
                override fun onScanFailed(errorCode: Int) {
                    handler.post { if (!session.result.isDone) finish(Result.failure(IllegalStateException("BLE 扫描失败（$errorCode），请稍后重试"))) }
                }
            }
            session.cleanup.add { scanner.stopScan(callback) }
            // 单次前台扫描采用平衡功耗，结束立即停止，不维持后台发现。
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), callback)
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceExtra(intent: Intent): BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    fun cancel() = scan.cancel()
    override fun close() = scan.close()
}
