package io.todu.sview.ui.screens

/** 基本信息只返回公开属性，屏幕字段明确区分 px、dp 与应用窗口。 */
internal val infoInterfaceHelp = listOf(
    InterfaceHelpEntry(
        name = "sview.system.getInfo", brief = "读取设备与系统基本信息",
        summary = "获取设备型号、Android 版本、支持的架构、语言、时区和当前内存信息，无需额外权限。",
        parameters = emptyList(), resultType = "Promise<Object>", result = "返回当前设备与系统信息对象。",
        resultFields = listOf(
            InterfaceResultField("manufacturer / brand", "string", "设备制造商和品牌。"),
            InterfaceResultField("model", "string", "设备型号。"),
            InterfaceResultField("device / product / hardware", "string", "系统报告的设备代号、产品名称和硬件平台名称。"),
            InterfaceResultField("androidVersion", "string", "Android 版本，例如 16。"),
            InterfaceResultField("sdkVersion", "number", "Android API 级别，例如 36。"),
            InterfaceResultField("securityPatch", "string", "系统报告的安全补丁日期，未提供时可能为空。"),
            InterfaceResultField("supportedAbis", "string[]", "设备支持的 ABI，按系统偏好排列，例如 arm64-v8a、armeabi-v7a。"),
            InterfaceResultField("is64BitProcess", "boolean", "当前 S View 进程是否为 64 位，不等同于设备是否支持 64 位。"),
            InterfaceResultField("deviceType", "string", "watch（手表）、tv（电视）、automotive（车载）或 handheld（手机、平板等手持设备）。"),
            InterfaceResultField("locale / timeZone", "string", "当前应用配置的首选语言标签和系统时区 ID，例如 zh-CN、Asia/Shanghai。"),
            InterfaceResultField("memory", "object | null", "total 为系统可用的总 RAM，available 为当前可用 RAM，单位均为字节；low 表示系统是否报告低内存。无法读取时为 null。")
        ),
        failure = "系统服务或运行连接不可用时 Promise 拒绝。",
        example = """const info = await sview.system.getInfo();
console.log(info.brand, info.model, info.androidVersion);
console.log(info.supportedAbis);
if (info.memory) console.log(info.memory.available / 1024 / 1024, 'MB');""",
        note = "不包含 IMEI、序列号或 Android ID。内存是系统 RAM，不是应用剩余堆空间或磁盘容量。"
    ),
    InterfaceHelpEntry(
        name = "sview.system.getScreen", brief = "读取当前屏幕信息",
        summary = "读取应用所在显示器的尺寸、密度、旋转方向和当前应用窗口。每次调用重新读取，适用于折叠、展开、旋转及分屏后重新计算布局，无需额外权限。",
        parameters = emptyList(), resultType = "Promise<Object>", result = "返回屏幕与应用窗口的当前快照。",
        resultFields = listOf(
            InterfaceResultField("width / height", "number", "当前显示区域允许应用占用的最大宽高，单位 px，含系统栏。Android 10 及以下为系统报告的完整显示区域；不是硬件面板的标称物理分辨率。"),
            InterfaceResultField("widthDp / heightDp", "number", "最大显示区域宽高换算成 dp，可有小数。"),
            InterfaceResultField("density / densityDpi", "number", "px 与 dp 的换算密度及对应逻辑 DPI。不是测量得到的面板物理 DPI。"),
            InterfaceResultField("fontScale", "number", "系统字体缩放设置；不能用于精确推算非线性字体缩放后的文字像素大小。"),
            InterfaceResultField("rotation", "number", "相对设备自然方向的旋转角度：0、90、180、270。"),
            InterfaceResultField("orientation", "string", "根据最大显示区域宽高得出 portrait、landscape 或 square。"),
            InterfaceResultField("refreshRate / displayId", "number", "系统报告的当前刷新率（Hz）及显示器编号；刷新率不是网页实际帧率。"),
            InterfaceResultField("isRound", "boolean", "系统是否将当前屏幕标记为圆形。"),
            InterfaceResultField("isInMultiWindowMode", "boolean", "应用是否处于多窗口模式。"),
            InterfaceResultField("window", "object", "当前 S View 宿主窗口，包含 width、height（px）、widthDp、heightDp。应用内为应用窗口，悬浮运行时使用悬浮宿主的系统窗口信息；不是网页视口尺寸。Android 10 及以下的悬浮模式按显示区域返回。"),
            InterfaceResultField("window.insets", "object", "系统栏和刘海区域占用的 left、top、right、bottom，单位 px；忽略系统栏当前可见性，不包括键盘。"),
            InterfaceResultField("window.contentWidth / contentHeight", "number", "窗口减去上述边距后的宽高，单位 px。圆形屏幕角落、折痕或铰链不由此字段描述。")
        ),
        failure = "窗口不在前台、无法取得 Activity 或显示器、运行连接不可用时 Promise 拒绝。",
        example = """const screen = await sview.system.getScreen();
console.log(screen.width, screen.height, 'px');
console.log(screen.widthDp, screen.heightDp, 'dp');
console.log(screen.orientation, screen.isRound);
console.log(screen.window);""",
        note = "如果需要当前项目网页的可用尺寸，请使用 window.innerWidth、window.innerHeight。此接口不持续监听尺寸变化；布局变化后可再次调用。"
    )
)
