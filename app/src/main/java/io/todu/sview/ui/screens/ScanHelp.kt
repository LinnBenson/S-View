package io.todu.sview.ui.screens

/** 单次无线扫描的权限、返回字段和系统限制。 */
internal val scanInterfaceHelp = listOf(
    InterfaceHelpEntry(
        name = "sview.system.scanBluetooth", brief = "扫描附近蓝牙设备",
        summary = "扫描设备支持的经典蓝牙和 BLE，按地址去重并按信号强度从高到低返回。不会配对、连接或混入未被本次发现的已配对设备。",
        parameters = listOf(InterfaceParameter("timeout", "number", false, "扫描时长，1000–30000 的整数，单位毫秒。仅支持经典蓝牙时可能在系统发现结束后提前返回。", "12000")),
        resultType = "Promise<Array>", result = "完成后返回设备数组，没有发现设备返回 []。",
        resultFields = listOf(
            InterfaceResultField("name", "string | null", "设备名称；未广播或无法取得时为 null。"),
            InterfaceResultField("address", "string", "本次发现的蓝牙地址；BLE 随机地址可能变化，不能作为永久身份。"),
            InterfaceResultField("rssi", "number | null", "最近一次接收信号强度，单位 dBm；未知时为 null。通常越接近 0 越强。"),
            InterfaceResultField("type", "string", "classic、le、dual 或 unknown。"),
            InterfaceResultField("paired", "boolean", "该设备是否已与本机配对。"),
            InterfaceResultField("sources", "string[]", "本次发现来源：classic、ble，或两者。")
        ),
        failure = "蓝牙未开启、设备不支持、权限不足、系统正在发现设备、扫描启动失败或窗口退出前台时 Promise 拒绝。",
        example = """try {
    const devices = await sview.system.scanBluetooth();
    for (const device of devices) {
        console.log(device.name, device.address, device.rssi);
    }
} catch (error) {
    console.error(error.message);
}""",
        note = "需允许精确位置并开启系统位置服务；Android 12 及以上还需允许附近设备。只能发现处于可发现状态的经典设备或正在广播的 BLE 设备，不能保证列出所有附近设备。不要连续高频调用，系统可能限流。"
    ),
    InterfaceHelpEntry(
        name = "sview.system.scanWifi", brief = "扫描附近 Wi-Fi",
        summary = "请求一次 Wi-Fi 扫描，等待系统刷新后返回本次请求开始以来观察到的热点；按 BSSID 去重并按信号强度从高到低排列。不会连接或更改 Wi-Fi 设置。",
        parameters = listOf(InterfaceParameter("timeout", "number", false, "最长等待时间，1000–30000 的整数，单位毫秒。", "15000")),
        resultType = "Promise<Array>", result = "成功返回热点数组，没有本次可用结果返回 []。同名但 BSSID 不同的热点分别保留。",
        resultFields = listOf(
            InterfaceResultField("ssid", "string", "热点名称；隐藏名称可能为空，非 UTF-8 名称可能以十六进制显示。"),
            InterfaceResultField("bssid", "string", "热点的 BSSID。"),
            InterfaceResultField("rssi", "number", "信号强度，单位 dBm。"),
            InterfaceResultField("frequency", "number", "频率，单位 MHz，例如 2412、5180。"),
            InterfaceResultField("capabilities", "string", "系统提供的能力和安全方式字符串，例如 [WPA2-PSK-CCMP][ESS]。")
        ),
        failure = "Wi-Fi 未开启、权限不足、位置服务未开启、系统限流、刷新失败、超时或窗口退出前台时 Promise 拒绝，不会退回旧缓存。",
        example = """try {
    const networks = await sview.system.scanWifi();
    for (const network of networks) {
        console.log(network.ssid, network.bssid, network.rssi);
    }
} catch (error) {
    console.error(error.message);
}""",
        note = "需允许精确位置并开启系统位置服务。Android 限制主动扫描频率，建议由用户操作触发，避免轮询；扫描完成广播也可能来自系统或其他应用的刷新。两个扫描接口都只在运行窗口前台工作，退到后台或关闭窗口会释放监听和蓝牙扫描；已交给系统的 Wi-Fi 扫描无法主动停止。"
    )
)
