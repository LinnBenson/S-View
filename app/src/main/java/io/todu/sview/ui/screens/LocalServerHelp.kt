package io.todu.sview.ui.screens

/** 局域网页面的配置与脚本回调共用说明，分别展示聊天和接收入口。 */
internal val localServerInterfaceHelp = listOf(true, false).map { chat ->
    InterfaceHelpEntry(
        name = "sview.network.${if (chat) "chatServer" else "uploadServer"}",
        brief = if (chat) "启动局域网聊天" else "接收局域网文件与图片",
        summary = if (chat) "启动聊天网页，访客可以发送文字、图片和文件。返回后服务持续运行，关闭项目或退出应用时自动停止。"
            else "启动文件传输网页。文件和图片自动保存到设备 Download，需要先允许设备存储访问。关闭项目或退出应用时自动停止。",
        parameters = listOf(
            InterfaceParameter("options.name", "string", false, "网页名称，最多 80 个字符。", "S View"),
            InterfaceParameter("options.server", "string", false, "空字符串选择当前 Wi-Fi IPv4；10.* 选择第一个以 10. 开头的网卡；完整 IPv4 必须属于本机。0.0.0.0 监听所有网卡，返回当前 Wi-Fi 地址。", "''"),
            InterfaceParameter("options.port", "number", false, "监听端口，1024–65535。多个服务需要使用不同端口。", "8080"),
            InterfaceParameter("options.token", "string", false, "访问码，最多 64 个不含空格的可见 ASCII 字符。空字符串不要求输入。", "''"),
            InterfaceParameter("options.maxFileSizeMB", "number", false, "单个文件大小上限，1–512 MB。", "512"),
            InterfaceParameter("options.maxImageSizeMB", "number", false, "单张图片大小上限，1–512 MB。支持常见位图，不支持 SVG。", "512")
        ) + listOf(
            InterfaceParameter("options.onStart", "function(handle)", false, "启动成功后调用一次，参数与 Promise 返回的只读对象相同。先于启动期间排队的事件触发；启动失败不触发。回调不等待异步任务，请直接使用参数中的句柄。"),
            InterfaceParameter("options.onCancel", "function", false, "onCancel(id)：文件或图片传输中断、取消或超时时调用；不会触发 onError，服务器继续运行。上传服务的 id 与进度回调一致，聊天服务的 id 为本次传输标识。"),
            InterfaceParameter("options.onClose", "function", false, "主动关闭或服务停止时调用 onClose()，每个服务只触发一次。启动失败不触发。")
        ) + if (chat) listOf(
            InterfaceParameter("options.onMessage", "function", false, "接收 { id, senderId, type, time, text, name, size, system }。type 为 text、image 或 file；time 为毫秒时间戳。不适用字段为 null。脚本 send 发送的系统消息不触发此回调。"),
            InterfaceParameter("options.onError", "function", false, "运行、磁盘写入或文件校验失败时接收 { message }。启动失败也触发此回调，并拒绝 Promise。")
        ) else listOf(
            InterfaceParameter("options.onText", "function", false, "收到文字时调用 onText(text)。"),
            InterfaceParameter("options.onFile", "function", false, "onFile(id, percent, path)：id 为本次上传的唯一字符串，同一次上传保持不变，重新上传会生成新 ID；接收时 path 为 false；保存成功后为 onFile(id, 100, 'device:/Download/文件名')。"),
            InterfaceParameter("options.onImage", "function", false, "onImage(id, percent, path)：与文件回调相同，独立报告图片接收与保存结果。"),
            InterfaceParameter("options.onError", "function", false, "运行、磁盘写入或文件校验失败时接收 { message }；失败不会报告保存成功。启动失败也触发此回调，并拒绝 Promise。")
        ),
        resultType = "Promise<object>",
        result = "返回只读 { name, url, port, token, close${if (chat) ", send" else ""} }。url 是不含访问码的浏览器地址，token 是启动时传入的访问码。await close() 可重复调用。" +
            if (chat) "await send(text) 广播系统文字并返回消息对象。" else "同名文件自动加序号；close 不删除已经保存到 Download 的文件。",
        failure = "地址不可用、端口被占用、配置无效或缺少存储权限时拒绝 Promise。监听网卡断开或地址改变时停止服务并触发 onError。",
        example = if (chat) """const chat = await sview.network.chatServer({
  name: '局域网聊天', server: '', port: 8080, token: '',
  onMessage(message) {
    console.log(message.senderId, message.type);
    if (message.type === 'text' && message.text === '你好') {
      chat.send('欢迎加入').catch(console.error);
    }
  },
  onStart(handle) { console.log('服务已启动', handle.url); },
  onError(error) { console.error(error.message); },
  onClose() { console.log('服务已关闭'); }
});
console.log(chat.url);
// 用完后：await chat.close();""" else """const upload = await sview.network.uploadServer({
  name: '接收文件', server: '', port: 8081, token: '',
  onText(text) { console.log(text); },
  onFile(id, percent, path) { console.log('文件', id, percent, path); },
  onImage(id, percent, path) { console.log('图片', id, percent, path); },
  onCancel(id) { console.log('上传中断', id); },
  onStart(handle) { console.log('服务已启动', handle.url); },
  onError(error) { console.error(error.message); },
  onClose() { console.log('服务已关闭'); }
});
console.log(upload.url);
// 用完后：await upload.close();""",
        note = if (chat) "聊天附件只保留在当前会话缓存中，关闭后清理。最多保留 10000 条消息与 512 MB 附件，达到上限后拒绝新增。"
            else "进度约每秒回调一次，100 只表示保存已完成。每个服务同时接收一个文件，传输中断或关闭会清理临时文件。",
        category = "网络"
    )
}
