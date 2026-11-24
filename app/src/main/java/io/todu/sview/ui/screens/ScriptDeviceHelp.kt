package io.todu.sview.ui.screens

/** 脚本运行设备的类型查询。 */
internal val scriptDeviceHelp = listOf(
    InterfaceHelpEntry(
        name = "sview.script.isWatch",
        brief = "判断是否为手表",
        summary = "判断当前运行脚本的设备是否为手表，不判断已连接或配对的设备。",
        parameters = emptyList(),
        resultType = "Promise<boolean>",
        result = "手表返回 true，手机及其他设备返回 false。",
        failure = "设备类型读取失败或接口连接不可用时拒绝 Promise。",
        example = """const isWatch = await sview.script.isWatch();
document.body.classList.toggle('watch', isWatch);""",
        note = "无需权限或联网。根据系统设备类型判断，不依赖屏幕尺寸和形状。",
        category = "脚本",
    ),
)
