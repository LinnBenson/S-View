package io.todu.sview.ui.screens

/** NFC 会话的回调、数据格式和关闭约定。 */
internal val nfcInterfaceHelp = listOf(
    InterfaceHelpEntry(
        name = "sview.system.nfc", brief = "监听 NFC 标签",
        summary = "启动前台 NFC 读取，持续接收标签发现事件。立即返回监听对象，不需要 await。设备需要支持并开启 NFC，项目须在应用内运行；系统悬浮窗口不支持 NFC 监听。",
        parameters = listOf(
            InterfaceParameter("options", "object", false, "回调及读取设置，省略时使用默认设置。", "{}"),
            InterfaceParameter("options.onStart", "function", false, "监听准备就绪时调用。"),
            InterfaceParameter("options.onMessage", "function(tag)", false, "发现标签时调用，接收标签信息及 NDEF 快照。"),
            InterfaceParameter("options.onError", "function(error)", false, "接收 { code, message } 错误对象。"),
            InterfaceParameter("options.onClose", "function(reason)", false, "监听结束时调用一次，reason 为 manual、background、windowClosed 或 error。"),
            InterfaceParameter("options.repeatInterval", "number", false, "同一标签两次回调的最小间隔，0–60000 毫秒的整数；0 不过滤重复。", "1000")
        ),
        resultType = "Object", result = "返回 { close() }。调用 close() 手动停止监听；重复关闭无效。",
        resultFields = listOf(
            InterfaceResultField("tag.id", "string", "标签 ID 的大写十六进制表示，可能为空或变化，不能作为永久身份凭据。"),
            InterfaceResultField("tag.technologies", "string[]", "系统识别的技术名称，例如 NfcA、IsoDep、Ndef。"),
            InterfaceResultField("tag.ndef", "object | null", "发现标签时的 NDEF 快照。未检测到 NDEF 消息时为 null，不代表标签没有其他数据。"),
            InterfaceResultField("tag.ndef.type / maxSize / writable", "string / number / boolean", "NDEF 类型、最大容量（字节）和是否可写；此接口只读取，不写入。"),
            InterfaceResultField("tag.ndef.bytes", "Uint8Array", "完整 NDEF 消息的原始字节。"),
            InterfaceResultField("tag.ndef.records", "object[]", "每条记录含 tnf（数字）及 type、id、payload（Uint8Array）。可识别的文本含 text、language，链接含 uri，MIME 类型含 mimeType；其他记录保留原始数据。")
        ),
        failure = "参数格式错误同步抛出 TypeError。启动失败通过 onError 通知，再触发 onClose('error')。错误代码：UNSUPPORTED（无硬件）、DISABLED（NFC 未开启或已关闭）、NOT_FOREGROUND（不在前台）、ALREADY_LISTENING（已有监听）、START_FAILED（启动失败）、CONNECTION_LOST（事件连接断开）、TAG_LOST（标签离开）、READ_FAILED（读取失败）。标签读取错误可继续等待下一个标签，其余错误结束监听。",
        example = """const reader = sview.system.nfc({
    repeatInterval: 1000,
    onStart() { console.log('请靠近 NFC 标签'); },
    onMessage(tag) {
        console.log(tag.id, tag.technologies);
        for (const record of tag.ndef?.records ?? []) {
            if (record.text !== undefined) console.log(record.text);
            if (record.uri) console.log(record.uri);
        }
    },
    onError(error) { console.error(error.code, error.message); },
    onClose(reason) { console.log('监听结束', reason); }
});
// 需要停止时调用：reader.close();""",
        note = "同一时间只允许一个 NFC 监听，重复启动不会替换原监听。退出前台或关闭运行窗口会停止，返回前台后需重新调用。repeatInterval 只过滤系统重复发现事件，不会定时重复读取；持续贴着同一标签通常只回调一次。请保留返回对象，重新调用 nfc().close() 会创建并关闭另一个监听。"
    )
)
