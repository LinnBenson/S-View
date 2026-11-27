package io.todu.sview.data

/** 公共命名空间由运行环境注入，项目无需导入脚本；后续接口继续扩展此对象。 */
object ScriptApi {
    const val SCRIPT = """(() => {
        const invalidArgument = message => Object.assign(new TypeError(message), { code: 'INVALID_ARGUMENT' });
        const endpoint = new URL('/__sview_api', location.href).href;
        async function call(method, args) {
            const response = await fetch(endpoint, {
                method: 'POST', credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json', 'X-SView-Request': '1' },
                body: JSON.stringify({ method, args })
            });
            if (!response.ok) throw Object.assign(new Error('系统接口请求失败：' + response.status), { code: response.status === 503 ? 'BUSY' : response.status === 413 ? 'PAYLOAD_TOO_LARGE' : 'BRIDGE_ERROR' });
            const result = await response.json();
            if (!result.ok) throw Object.assign(new Error(result.error), { code: result.code || 'INTERNAL_ERROR' });
            return result.value;
        }
        ${ScriptCode.SCRIPT}
        ${ScriptErrorHandling.SCRIPT}
        ${ScriptLogApi.SCRIPT}
        ${ScriptMonitor.SCRIPT}
        ${ScriptNfc.SCRIPT}
        ${ScriptNotifyMonitor.SCRIPT}
        ${ScriptCustomServer.SCRIPT}
        ${ScriptLocalServers.SCRIPT}
        ${ScriptPortScan.SCRIPT}
        ${ScriptDeviceResources.SCRIPT}
        ${ScriptShare.SCRIPT}
        ${ScriptDatabaseApi.KEY_CHECK}
        const sview = {
            system: {
                nfc,
                notifyMonitor,
                getInfo() { return call('system.getInfo', {}); },
                getScreen() { return call('system.getScreen', {}); },
                async toast(message, duration = 'short') {
                    await call('system.toast', { message, duration });
                },
                notify(title, message) { return call('system.notify', { title, message }); },
                vibrate(duration = 50) { return call('system.vibrate', { duration }); },
                async install(path) { await call('system.install', { path }); },
                async openApp(packageName) { await call('system.openApp', { packageName }); },
                async openLink(url) { await call('system.openLink', { url }); },
                readClipboard() { return call('system.readClipboard', {}); },
                async writeClipboard(text) { await call('system.writeClipboard', { text }); },
                async share(text, title = '') { await call('system.share', { text, title }); },
                getRotationSettings() { return call('system.getRotationSettings', {}); },
                async cancelVibration() { await call('system.cancelVibration', {}); },
                getNetworkState() { return call('system.getNetworkState', {}); },
                scanWifi(timeout = 15000) { return call('system.scanWifi', { timeout }); },
                scanBluetooth(timeout = 12000) { return call('system.scanBluetooth', { timeout }); },
                getWifiState() { return call('system.getWifiState', {}); },
                getBluetoothState() { return call('system.getBluetoothState', {}); },
                getLocation(timeout = 15000) { return call('system.getLocation', { timeout }); },
                getHealthInfo() { return call('system.getHealthInfo', {}); },
                getBatteryInfo() { return call('system.getBatteryInfo', {}); }
            },
            network: {
                scanPorts,
                interface() { return call('network.interface', {}); },
                server: options => startLocalServer('server', options),
                chatServer: options => startLocalServer('chatServer', options),
                uploadServer: options => startLocalServer('uploadServer', options),
                hotspot: options => startLocalServer('hotspot', options)
            }, storage: {},
            interaction: {
                monitor,
                background(enabled, dynamic = false) { return call('interaction.background', { enabled, dynamic }); },
                async closeThis() { await call('interaction.closeThis', {}); },
                async close() { await call('interaction.close', {}); },
                async location(location, persist = false) { await waitWindowMonitor('onLocation'); await call('interaction.location', { location, persist }); },
                async margin(margins, persist = false) { await waitWindowMonitor('onMargin'); await call('interaction.margin', { margins, persist }); },
                async cornerRadius(cornerRadius, persist = false) { await waitWindowMonitor('onCornerRadius'); await call('interaction.cornerRadius', { cornerRadius, persist }); },
                async preview(path) { await call('interaction.preview', { path }); },
                async removeClose() { await call('interaction.removeClose', {}); },
                async resize(width, height, persist = false) { await call('interaction.resize', { width, height, persist }); }
            },
            script: {
                code: readScriptCode,
                isWatch() { return call('script.isWatch', {}); },
                md5(text) { return call('script.md5', { text }); },
                qrcode(text) { return call('script.qrcode', { text }); },
                log: readProjectLog, getShare: getSharedContent, db: ${ScriptDatabaseApi.SCRIPT} }, tools: {}
        };
        Object.defineProperty(globalThis, 'sview', { value: sview, writable: false });
    })();"""
}
