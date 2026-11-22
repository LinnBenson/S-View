package io.todu.sview.ui.screens

/** 设备能力的参数和示例与原生接口保持一致。 */
internal val deviceInterfaceHelp = listOf(
    InterfaceHelpEntry(
        name = "sview.system.install",
        brief = "打开 APK 安装界面",
        summary = "将指定 APK 交给 Android 系统安装器，由用户确认安装。普通路径相对于当前项目资源目录，device:/ 路径相对于设备主共享存储。",
        parameters = listOf(InterfaceParameter("path", "string", true, "APK 文件路径，最长 4096 个字符。例如 packages/app.apk 或 device:/Download/app.apk。必须包含文件名；不支持目录、URL、APKS/XAPK 或多个拆分包。")),
        resultType = "Promise<void>",
        result = "无返回数据，仅表示系统安装界面已打开，不表示安装成功。用户取消、签名冲突、版本不兼容等结果由系统安装界面提示。",
        failure = "路径无效、文件缺失或非 APK、权限未允许、空间不足、窗口不在前台或设备没有安装器时 Promise 拒绝。",
        example = """try {
    await sview.system.install('packages/app.apk');
    // 设备存储中的安装包也可使用：
    // await sview.system.install('device:/Download/app.apk');
} catch (error) {
    console.error(error.message);
}""",
        note = "先在设置 → 权限中允许安装应用；device:/ 路径还需要设备存储权限。准备期间退出前台会取消。源文件不会被修改或删除；安装器读取独立临时副本，副本在后续安装时清理超过一天的文件。APK 的有效性、兼容性和最终安装结果以系统判断为准。"
    ),
    InterfaceHelpEntry(
        name = "sview.system.vibrate",
        brief = "触发一次系统震动",
        summary = "触发一次系统震动" ,
        parameters = listOf(InterfaceParameter("duration", "number", false, "必须为 1–10000 的整数。传入 20 优先尝试线性马达的短促点击触感；其他值表示普通震动时长（毫秒），省略时为 50 毫秒。", "50")),
        resultType = "Promise<boolean>",
        result = "返回 boolean：true 表示已提交震动请求，false 表示没有震动硬件。传入 20 由系统适配触感，不保证设备具有线性马达；不支持时回退为普通短震动。实际效果受硬件及系统设置影响；窗口关闭或进入后台会停止震动。",
        failure = "参数无效、所需权限未允许、设备不支持或运行连接不可用时，Promise 会拒绝。需要交互的操作只能在运行窗口位于前台时调用。",
        example = "(async () => {\n  try {\n    await sview.system.vibrate(20);\n  } catch (error) {\n    console.error(error.message);\n  }\n})();",
        category = "系统"
    ),
    InterfaceHelpEntry(
        name = "sview.system.cancelVibration",
        brief = "停止本应用发起的震动",
        summary = "停止本应用发起的震动" ,
        parameters = listOf(),
        resultType = "Promise<void>",
        result = "无返回数据；不会停止其他应用的震动。",
        failure = "参数无效、所需权限未允许、设备不支持或运行连接不可用时，Promise 会拒绝。需要交互的操作只能在运行窗口位于前台时调用。",
        example = "(async () => {\n  try {\n    await sview.system.cancelVibration();\n  } catch (error) {\n    console.error(error.message);\n  }\n})();",
        category = "系统"
    ),
    InterfaceHelpEntry(
        name = "sview.system.openApp",
        brief = "通过包名打开应用",
        summary = "通过包名打开应用" ,
        parameters = listOf(InterfaceParameter("packageName", "string", true, "应用完整包名，最长 255 个字符。")),
        resultType = "Promise<void>",
        result = "无返回数据，表示打开请求已提交，不代表目标应用已加载完成。",
        failure = "参数无效、所需权限未允许、设备不支持或运行连接不可用时，Promise 会拒绝。需要交互的操作只能在运行窗口位于前台时调用。",
        example = "(async () => {\n  try {\n    await sview.system.openApp('com.android.settings');\n  } catch (error) {\n    console.error(error.message);\n  }\n})();",
        category = "系统"
    ),
    InterfaceHelpEntry(
        name = "sview.system.openLink",
        brief = "打开网页、地图或应用链接",
        summary = "打开网页、地图或应用链接" ,
        parameters = listOf(InterfaceParameter("url", "string", true, "完整链接，最长 4096 个字符。支持 https、http、geo 和已安装应用的链接协议；不支持 javascript、data、file、content、intent。")),
        resultType = "Promise<void>",
        result = "无返回数据；由系统选择能够处理链接的应用，没有可用应用时拒绝。",
        failure = "参数无效、所需权限未允许、设备不支持或运行连接不可用时，Promise 会拒绝。需要交互的操作只能在运行窗口位于前台时调用。",
        example = "(async () => {\n  try {\n    await sview.system.openLink('geo:0,0?q=Singapore');\n  } catch (error) {\n    console.error(error.message);\n  }\n})();",
        category = "系统"
    ),
    InterfaceHelpEntry(
        name = "sview.system.readClipboard",
        brief = "读取剪切板中的文字",
        summary = "读取剪切板中的文字" ,
        parameters = listOf(),
        resultType = "Promise<string | null>",
        result = "返回 string 或 null；没有文字或系统不允许读取时为 null。只读取文字，不读取剪切板文件；超过 65536 个字符时拒绝。",
        failure = "参数无效、所需权限未允许、设备不支持或运行连接不可用时，Promise 会拒绝。需要交互的操作只能在运行窗口位于前台时调用。",
        example = "(async () => {\n  try {\n    const text = await sview.system.readClipboard();\n    console.log(text);\n  } catch (error) {\n    console.error(error.message);\n  }\n})();",
        category = "系统"
    ),
    InterfaceHelpEntry(
        name = "sview.system.writeClipboard",
        brief = "写入剪切板文字",
        summary = "写入剪切板文字" ,
        parameters = listOf(InterfaceParameter("text", "string", true, "要复制的文字，最多 8192 个字符；允许空字符串。")),
        resultType = "Promise<void>",
        result = "无返回数据，表示已写入系统剪切板。",
        failure = "参数无效、所需权限未允许、设备不支持或运行连接不可用时，Promise 会拒绝。需要交互的操作只能在运行窗口位于前台时调用。",
        example = "(async () => {\n  try {\n    await sview.system.writeClipboard('你好');\n  } catch (error) {\n    console.error(error.message);\n  }\n})();",
        category = "系统"
    ),
    InterfaceHelpEntry(
        name = "sview.system.share",
        brief = "打开系统分享面板",
        summary = "打开系统分享面板" ,
        parameters = listOf(InterfaceParameter("text", "string", true, "分享文字，1–8192 个字符，不可为空白。"), InterfaceParameter("title", "string", false, "可选标题，最多 120 个字符。", "''")),
        resultType = "Promise<void>",
        result = "无返回数据，表示分享面板已打开，不表示内容已经发送；接收方由你选择。",
        failure = "参数无效、所需权限未允许、设备不支持或运行连接不可用时，Promise 会拒绝。需要交互的操作只能在运行窗口位于前台时调用。",
        example = "(async () => {\n  try {\n    await sview.system.share('分享的内容', '标题');\n  } catch (error) {\n    console.error(error.message);\n  }\n})();",
        category = "系统"
    ),
    InterfaceHelpEntry(
        name = "sview.system.getRotationSettings",
        brief = "读取屏幕旋转设置",
        summary = "读取屏幕旋转设置" ,
        parameters = listOf(),
        resultType = "Promise<object>",
        result = "返回对象：autoRotate（boolean）是否开启自动旋转；lockedRotation（number | null）保存的锁定方向，0、90、180 或 270 度，无法读取时为 null。这是系统设置，不是当前物理朝向。",
        failure = "参数无效、所需权限未允许、设备不支持或运行连接不可用时，Promise 会拒绝。需要交互的操作只能在运行窗口位于前台时调用。",
        example = "(async () => {\n  try {\n    const settings = await sview.system.getRotationSettings();\n    console.log(settings.autoRotate, settings.lockedRotation);\n  } catch (error) {\n    console.error(error.message);\n  }\n})();",
        category = "系统"
    ),
    InterfaceHelpEntry(
        name = "sview.system.getNetworkState",
        brief = "读取当前网络状态",
        summary = "读取当前网络状态" ,
        parameters = listOf(),
        resultType = "Promise<object>",
        result = "返回对象：connected（boolean）有活动网络；type（string）为 wifi、cellular、ethernet、bluetooth、vpn、other 或 none；internetAvailable（boolean）通过系统联网验证；metered（boolean）是否按流量计费。仅返回调用时的状态，不持续监听。",
        failure = "参数无效、所需权限未允许、设备不支持或运行连接不可用时，Promise 会拒绝。需要交互的操作只能在运行窗口位于前台时调用。",
        example = "(async () => {\n  try {\n    console.log(await sview.system.getNetworkState());\n  } catch (error) {\n    console.error(error.message);\n  }\n})();",
        category = "系统"
    ),
    InterfaceHelpEntry(
        name = "sview.system.getWifiState",
        brief = "读取 Wi-Fi 开关和连接状态",
        summary = "读取 Wi-Fi 开关和连接状态" ,
        parameters = listOf(),
        resultType = "Promise<object>",
        result = "返回对象：supported、enabled、connected 均为 boolean；state 为 enabled、disabled、enabling、disabling、unknown 或 unavailable。connected 表示当前默认网络使用 Wi-Fi；不扫描热点，不读取密码或 Wi-Fi 名称。",
        failure = "参数无效、所需权限未允许、设备不支持或运行连接不可用时，Promise 会拒绝。需要交互的操作只能在运行窗口位于前台时调用。",
        example = "(async () => {\n  try {\n    console.log(await sview.system.getWifiState());\n  } catch (error) {\n    console.error(error.message);\n  }\n})();",
        category = "系统"
    ),
    InterfaceHelpEntry(
        name = "sview.system.getBluetoothState",
        brief = "读取蓝牙开关状态",
        summary = "读取蓝牙开关状态" ,
        parameters = listOf(),
        resultType = "Promise<object>",
        result = "返回对象：supported、enabled 为 boolean；state 为 on、off、turning_on、turning_off、unknown 或 unavailable。Android 12 及以上需在设置 → 权限中允许附近设备；不扫描或连接设备。",
        failure = "参数无效、所需权限未允许、设备不支持或运行连接不可用时，Promise 会拒绝。需要交互的操作只能在运行窗口位于前台时调用。",
        example = "(async () => {\n  try {\n    console.log(await sview.system.getBluetoothState());\n  } catch (error) {\n    console.error(error.message);\n  }\n})();",
        category = "系统"
    ),
    InterfaceHelpEntry(
        name = "sview.system.getLocation",
        brief = "获取一次当前位置信息",
        summary = "获取一次当前位置信息" ,
        parameters = listOf(InterfaceParameter("timeout", "number", false, "等待上限，单位毫秒；1000–30000 的整数。", "15000")),
        resultType = "Promise<object>",
        result = "返回对象：latitude、longitude 为纬度和经度；accuracy 为米，altitude 为海拔米数，缺失时为 null；timestamp 为 Unix 毫秒时间。需允许位置权限并开启位置服务。优先使用网络定位，可能返回一分钟内的缓存；同一窗口只允许一个定位请求，超时、关闭或进入后台会取消。",
        failure = "参数无效、所需权限未允许、设备不支持或运行连接不可用时，Promise 会拒绝。需要交互的操作只能在运行窗口位于前台时调用。",
        example = "(async () => {\n  try {\n    const location = await sview.system.getLocation();\n    console.log(location.latitude, location.longitude);\n  } catch (error) {\n    console.error(error.message);\n  }\n})();",
        category = "系统"
    ),
    InterfaceHelpEntry(
        name = "sview.system.getHealthInfo",
        brief = "读取本机健康记录",
        summary = "手机从 Health Connect 读取已保存记录；手表独立读取 Health Services 提供的被动数据，不连接手机，不启动主动测量。请先在设置 → 权限 → 健康数据授权。",
        parameters = listOf(),
        resultType = "Promise<object>",
        result = "两端固定返回 source、providerStatus、timestamp、timeZone、dayStart、historyStart，以及 steps、distance、calories、heartRate、oxygenSaturation、sleep。每项含 value、unit、status、startTime、endTime；时间为 Unix 毫秒，缺失值为 null。今日步数/距离/总热量单位为 count/m/kcal；最近心率/血氧单位为 bpm/%；sleep 为最近一条已结束睡眠记录的起止时长（分钟，包含记录内清醒阶段）。手机最近记录查询范围为 30 天。status 为 available、no_data、permission_denied、unsupported 或 unavailable；providerStatus 为 available、unsupported、update_required 或 unavailable。手表血氧和睡眠历史标为 unsupported，其他项按设备支持与系统是否提供数据返回，不保证与三星健康首页数值一致。",
        failure = "窗口不在前台、同一窗口重复读取，或读取被退出/后台化取消时 Promise 拒绝。数据缺失与未授权通过各字段状态返回。读取通常在 15 秒内完成，手表注销监听可能额外等待 2 秒。",
        example = "(async () => {\n  const health = await sview.system.getHealthInfo();\n  console.log(health);\n  if (health.steps.status === 'available') {\n    console.log('今日步数：', health.steps.value);\n  }\n})();",
        category = "系统"
    ),
    InterfaceHelpEntry(
        name = "sview.system.getBatteryInfo",
        brief = "读取电量与充电状态",
        summary = "读取电量与充电状态" ,
        parameters = listOf(),
        resultType = "Promise<object>",
        result = "返回对象：level 为 0–100 的电量百分比，未知时为 null；charging、powerSaveMode 为 boolean；powerSource 为 battery、ac、usb、wireless、dock 或 unknown。仅查询一次，不持续监听。",
        failure = "参数无效、所需权限未允许、设备不支持或运行连接不可用时，Promise 会拒绝。需要交互的操作只能在运行窗口位于前台时调用。",
        example = "(async () => {\n  try {\n    console.log(await sview.system.getBatteryInfo());\n  } catch (error) {\n    console.error(error.message);\n  }\n})();",
        category = "系统"
    )
)
