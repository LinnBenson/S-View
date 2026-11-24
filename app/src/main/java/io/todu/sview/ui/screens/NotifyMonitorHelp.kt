package io.todu.sview.ui.screens

/** 通知监听的数据结构、授权和运行窗口生命周期。 */
internal val notifyMonitorHelp = listOf(
    InterfaceHelpEntry(
        name = "sview.system.notifyMonitor", brief = "监听系统通知",
        summary = "监听启动后新增、更新和移除的通知。立即返回监听对象，无需 await；先在设置 → 权限中授予通知使用权。",
        parameters = listOf(
            InterfaceParameter("options", "object", false, "监听回调。", "{}"),
            InterfaceParameter("options.onStart", "function()", false, "系统监听连接就绪后调用一次。"),
            InterfaceParameter("options.onMessage", "function(notification)", false, "接收通知快照。"),
            InterfaceParameter("options.onError", "function(error)", false, "接收包含 code、message 的错误对象，随后结束监听。"),
            InterfaceParameter("options.onClose", "function(reason)", false, "结束时调用一次，reason 为 manual、windowClosed 或 error。")
        ),
        resultType = "Object", result = "返回 { close() }。调用 close() 停止监听，重复关闭无效。",
        resultFields = listOf(
            InterfaceResultField("notification.type", "string", "posted 表示新增或更新，removed 表示移除；同一通知更新沿用 key。"),
            InterfaceResultField("notification.key / packageName / id / tag", "string / string / number / string|null", "通知唯一键、来源包名、通知编号和标签。"),
            InterfaceResultField("notification.title / text / bigText / subText", "string", "标题、正文、展开正文和副标题，每项最多 4096 字符；没有内容时为空字符串。"),
            InterfaceResultField("notification.textLines", "string[]", "多行文本，最多 16 项，每项最多 4096 字符。"),
            InterfaceResultField("notification.postTime", "number", "通知发布时间，Unix 毫秒时间戳。"),
            InterfaceResultField("notification.channelId / category / groupKey", "string|null / string|null / string", "通知渠道、类别和分组键。"),
            InterfaceResultField("notification.ongoing / clearable", "boolean", "是否持续通知、是否可被用户清除。接口本身不清除通知。")
        ),
        failure = "参数错误同步抛出 TypeError。监听失败触发 onError，再触发 onClose('error')。常见代码：PERMISSION_DENIED、ALREADY_LISTENING、START_TIMEOUT、DISCONNECTED、READ_FAILED、CONNECTION_LOST。",
        example = """const listener = sview.system.notifyMonitor({
    onStart() { console.log('通知监听已启动'); },
    onMessage(notification) {
        console.log(notification.type, notification.packageName,
            notification.title, notification.text);
    },
    onError(error) { console.error(error.code, error.message); },
    onClose(reason) { console.log('通知监听已停止', reason); }
});

// 不再需要时停止；运行窗口关闭时也会自动停止。
// listener.close();""",
        note = "每个运行窗口只允许一个监听，后台继续接收，窗口结束运行时停止。未启动时不读取通知，不补发历史通知，不保存通知到文件。系统休眠可能延迟回调；事件缓存有上限。系统可能隐藏敏感正文、限制工作资料或部分设备的通知访问，不能保证读到全部内容。通知使用权与发送通知权限独立。手机和手表权限页均提供入口；显示“无授权入口”时，当前系统没有提供对应设置页面。"
    )
)
