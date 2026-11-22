package io.todu.sview.ui.screens

internal val scriptShareHelp = listOf(InterfaceHelpEntry(
    name = "sview.script.getShare", brief = "获取本次启动传入的内容",
    summary = "获取 Android 分享或 URL Schemes 传入的内容。首次调用返回标准 File 对象，之后重复调用复用同一份结果。",
    parameters = emptyList(), resultType = "Promise<object>",
    result = "返回 { text, title, files }，text 和 title 为字符串，files 为 File[]。普通启动两端均返回 { text: '', title: '', files: [] }，不会返回 null。",
    failure = "分享文件读取失败时 Promise 拒绝。系统分享最多 16 个文件、总计 32 MB，超限会在运行前提示，不截断文件。",
    example = """const share = await sview.script.getShare();
console.log(share.title, share.text);
for (const file of share.files) {
  console.log(file.name, file.type, file.size);
  await sview.storage.save('received/' + file.name, file);
}

// 手机、手表都可从外部启动：
// sview://task?name=项目名&share=传递参数
// name 和 share 请分别使用 encodeURIComponent 编码。""",
    note = "手机项目设置的共享来源控制 Android 分享接收；只有一个匹配项目时直接运行，多个时选择。手表不接收系统分享。URL Schemes 不受共享来源限制，按项目列表顺序运行首个同名项目，title 固定为 URL Schemes，share 作为 text。已有运行窗口时需先关闭。临时文件随本次运行结束清理，异常残留纳入清除缓存；需保留时自行 save。",
    category = "脚本",
    resultFields = listOf(
        InterfaceResultField("text", "string", "分享文字或 URL 的 share 参数；没有时为空字符串。"),
        InterfaceResultField("title", "string", "分享方提供的标题；URL 启动固定为 URL Schemes。"),
        InterfaceResultField("files", "File[]", "标准 File，支持 FormData、Blob URL、text、arrayBuffer、stream 以及 storage.save。没有文件时为空数组。")
    )
))
