package io.todu.sview.ui.screens

/** 原生请求及其链式方法，所有示例均使用同一个 sview.network 入口。 */
internal val networkInterfaceHelp = listOf(
    InterfaceHelpEntry(
        name = "sview.network.hotspot",
        brief = "启动本地 Wi-Fi 热点",
        summary = "创建只用于设备间局域网通信的热点，不共享移动网络。必须在运行窗口前台启动，名称和密码由系统生成。",
        parameters = listOf(),
        resultType = "Promise<object>",
        result = "启动成功后返回 { name, password, close }。name 和 password 为只读字符串，开放热点的 password 为空字符串。await close() 释放本项目的热点预约，可重复调用；旧对象不会关闭后来创建的热点。项目关闭、应用退出或进程结束时自动释放，无须脚本手动调用。切到后台不等同于关闭项目，但系统仍可能停止热点。",
        failure = "设备不支持、权限未允许、系统网络模式冲突、已有项目占用、启动超过 30 秒或项目提前关闭时 Promise 拒绝。Android 13 及以上需允许附近 Wi-Fi 设备，旧版本需精确位置权限并开启位置服务。",
        example = """const hotspot = await sview.network.hotspot();
console.log(hotspot.name, hotspot.password);
// 通信结束后可提前释放；项目关闭时也会自动释放。
await hotspot.close();""",
        note = "同一时间只允许一个项目申请。系统可能让多个应用共享本地热点，close 只释放 S View 的预约，其他应用仍持有时热点可能继续运行。启动超时或项目提前退出后，迟到的成功回调会立即关闭预约。",
        category = "网络"
    ),
    InterfaceHelpEntry(
        name = "sview.network.request",
        brief = "创建原生 HTTP 请求",
        summary = "链式配置请求，send() 才发送。由原生客户端访问 HTTP/HTTPS、公网或局域网，不受网页 CORS 限制。HTTPS 正常校验证书。",
        parameters = listOf(
            InterfaceParameter("url", "string | URL", true, "请求地址。相对地址以当前页面为基准。"),
            InterfaceParameter("method(value)", "string", false, "设置 HTTP 方法；GET、HEAD 不接受请求体。", "'GET'"),
            InterfaceParameter("query(values)", "object", false, "查询参数，同名键覆盖；数组生成重复键，null/undefined 删除对应键。"),
            InterfaceParameter("header(name, value) / header(values)", "string, string | object", false, "设置单个或多个请求头；headers(values) 为别名。长度、传输分帧和连接头由客户端管理。"),
            InterfaceParameter("ua(value)", "string", false, "自定义 User-Agent。"),
            InterfaceParameter("cookie(value)", "boolean | string | object", false, "true 自动携带并保存当前项目 Cookie，false 禁用自动 Cookie 并清除显式 Cookie。字符串或对象设置本次 Cookie，同名值优先；跨来源重定向不会转发显式 Cookie。", "true"),
            InterfaceParameter("timeout(ms)", "number", false, "整次请求及流消费的超时毫秒数，0 关闭超时。", "15000"),
            InterfaceParameter("response(type)", "string", false, "auto 自动识别 JSON，否则返回文本；json 要求合法 JSON；text 保持文本；blob、arrayBuffer 返回二进制；stream 返回 ReadableStream。", "'auto'")
        ),
        resultType = "RequestBuilder",
        result = "返回链式构建器。配置方法返回自身；同一实例 send() 只发送一次。Cookie 与网页 Cookie 分开，按项目保留，清除缓存或删除项目时清除。",
        failure = "配置无效时抛错。send() 失败可通过 error.code、error.message 处理，HTTP 错误另有 error.status 和 error.response。",
        example = """const result = await sview.network
    .request('https://example.com/api/items')
    .query({ page: 1 })
    .ua('SView/1.0')
    .header('Accept', 'application/json')
    .cookie({ session: 'abc123' })
    .send();
console.log(result.status, result.data);
console.log(result.isJson());""",
        note = "普通网页 fetch/XHR 仍遵循浏览器规则。原生请求不绕过系统网络权限、证书校验或服务端认证。同一页面同时传输四个请求，其余自动排队；流需要消费完成或主动取消。",
        category = "网络"
    ),
    InterfaceHelpEntry(
        name = "sview.network.request().data",
        brief = "发送 JSON、表单、文件或数据流",
        summary = "兼容 JS 获取的 FormData、File、Blob、ArrayBuffer、TypedArray 和 ReadableStream。上传流逐块传给原生，不把整个文件转成 Base64。",
        parameters = listOf(
            InterfaceParameter("data(value)", "object | array | string | FormData | Blob | ArrayBuffer | TypedArray | ReadableStream", true, "对象和数组按 JSON 发送；FormData 按 multipart；URLSearchParams 按 URL 编码表单。其他类型按原始字节发送，body(value) 为别名。"),
            InterfaceParameter("json(value)", "可 JSON 序列化的值", false, "强制 JSON 编码并设置 Content-Type，可发送 null、数字和布尔值。"),
            InterfaceParameter("form(values)", "object | FormData | HTMLFormElement", false, "普通对象按 URL 编码；含文件、使用 file() 或传入 FormData 时按 multipart。同名字段与文件名保留。"),
            InterfaceParameter("file(field, path, name?, type?)", "string, string, string?, string?", false, "添加项目资源文件，可多次调用。path 相对于项目资源目录，不能越界；默认文件名取路径末尾，类型为 application/octet-stream。"),
            InterfaceParameter("onUploadProgress(callback)", "({ loaded, total, percent }) => void", false, "已发送字节数、总字节数、0–100 百分比。未知总大小时 total/percent 为 null。回调同步抛错会取消请求。")
        ),
        resultType = "RequestBuilder",
        result = "返回自身。TypedArray 仅上传视图对应字节。ReadableStream 必须产生二进制块；流不能自动重放，307/308 等需要重放请求体的重定向可能直接返回该 HTTP 状态。",
        failure = "不合法的数据、文件越界、文件不存在或读取流失败会拒绝请求。窗口关闭时取消上传，并释放读取器。",
        example = """const form = new FormData(
    document.querySelector('#uploadForm')
);
const result = await sview.network
    .request('https://example.com/upload')
    .method('POST')
    .data(form)
    .onUploadProgress(({ loaded, percent }) => {
        console.log(loaded, percent);
    })
    .send();

// 文件选择框得到的 File 也可直接传入 data(file)。
// 原始文件流：data(file.stream())。
// 项目文件：form({ title: '图片' }).file('image', 'assets/photo.jpg')。""",
        category = "网络"
    ),
    InterfaceHelpEntry(
        name = "sview.network.request().send",
        brief = "读取结果、JSON 或下载文件",
        summary = "默认返回 { status, headers, url, data, isJson, isFifle }。isJson() 实际检查响应文本是否为合法 JSON，不依赖 Content-Type。合法对象和数组自动转换为 JS 对象和数组，不额外包裹。",
        parameters = listOf(
            InterfaceParameter("send()", "无参数", false, "返回 Promise；重复调用返回同一 Promise。"),
            InterfaceParameter("download(path)", "string", false, "普通路径保存到项目目录；device:/ 开头保存到设备主共享存储，需先在设置 → 权限中允许设备存储访问。单级路径、已有目录或以 / 结尾的路径自动采用响应头文件名，其次 URL 文件名，无可用名称时使用 download。多级新目录请以 / 结尾。可写 device:/Download/a.zip 指定文件。目录名区分大小写，不覆盖已有文件；失败或取消会清理临时文件。系统保护目录仍不可写。"),
            InterfaceParameter("onDownloadProgress(callback)", "({ loaded, total, percent }) => void", false, "每块数据交付或落盘后报告进度，单位为字节；未知总大小时 total/percent 为 null。")
        ),
        resultFields = listOf(
            InterfaceResultField("status", "number", "最终响应的 HTTP 状态码。send() 正常完成只返回 2xx；非 2xx 通过 error.response 查看。"),
            InterfaceResultField("headers", "Record<string, string>", "最终响应头，键名均为小写；同名值合并为字符串。缺少的头为 undefined，例如 result.headers['content-type']。"),
            InterfaceResultField("url", "string", "最终响应地址，包含重定向后的地址。"),
            InterfaceResultField("data", "依 response(type) 而定", "auto：合法 JSON 转为对象、数组或对应的原始值，否则为字符串。json：解析后的 JSON 值，不合法则拒绝。text：始终为字符串。blob：Blob。arrayBuffer：ArrayBuffer。stream：ReadableStream<Uint8Array>。download(path) 成功时为 null。空正文在 auto/text 中是空字符串，在 json 中拒绝。"),
            InterfaceResultField("isJson()", "() => boolean", "无参数。auto/json/text 实际解析完整正文判断，合法 JSON 返回 true，空正文或非法 JSON 返回 false。对象、数组、null、数字、布尔值和 JSON 字符串均为合法 JSON。blob、arrayBuffer、stream、落盘下载不预读正文，返回 false。"),
            InterfaceResultField("isFifle()", "() => boolean", "无参数，方法名为 isFifle。download(path) 成功落盘返回 true；其他模式依据响应头中的 attachment、filename 或常见文件 MIME 判断，例如图片、音视频、字体、PDF、压缩包、Office 文档、APK。普通 JSON/文本且没有附件声明返回 false。缺失或错误的响应头可能无法准确识别；不根据 URL 后缀猜测，不读取或校验文件内容。选择 blob/stream 本身不代表文件。判断不会改变 data 类型，文本附件可能同时满足 isJson() 和 isFifle()。"),
            InterfaceResultField("path", "string | undefined", "仅 download(path) 成功时存在。项目下载返回传入路径，设备下载返回包含实际文件名的 device:/ 路径。设备路径可直接赋给 img.src 或 a.href，由运行环境映射图片或打开文件；其他网页 URL 属性不会自动转换。"),
            InterfaceResultField("size", "number | undefined", "仅 download(path) 成功时存在，表示实际落盘字节数，0 表示空文件。其他模式没有此字段；可按需读取 Blob.size 或 ArrayBuffer.byteLength。")
        ),
        resultType = "Promise<NetworkResult>",
        result = "默认 data 是解析后的 JSON 值或原始文本；text 模式保持文本但仍可判断 isJson()。stream 模式 data 为 ReadableStream。文件下载返回 path、size，data 为 null。headers 的键为小写，同名响应头合并显示。",
        failure = "HTTP 非 2xx 拒绝，error.status 为状态码，error.response 保留 status、headers、url、data、isJson() 和 isFifle()；没有落盘文件。连接失败等未取得响应的错误通常没有 response。TIMEOUT 为超时，CANCELLED 为取消，NETWORK_ERROR 为连接或传输错误，INVALID_JSON 为强制 JSON 解析失败。",
        example = """const result = await sview.network
    .request('https://example.com/api/items')
    .send();
if (result.isJson()) {
    console.log(result.data);
}

const file = await sview.network
    .request('https://example.com/image.jpg')
    .download('downloads/image.jpg')
    .onDownloadProgress(({ percent }) => {
        console.log(percent);
    })
    .send();
console.log(file.isFifle()); // 成功落盘为 true
console.log(file.path, file.size);""",
        note = "大文件建议使用 download() 或 response('stream')。auto、json、text、blob 和 arrayBuffer 最终需要在 JS 中保留完整结果。流返回后仍可发生超时或网络错误，读取流时也需要捕获异常。",
        category = "网络"
    ),
    InterfaceHelpEntry(
        name = "sview.network.request().cancel",
        brief = "取消请求与流传输",
        summary = "取消未完成请求、上传读取器和下载流，并释放原生连接。窗口关闭时自动取消该窗口的全部原生请求。",
        parameters = listOf(InterfaceParameter("cancel()", "无参数", false, "发送前调用也有效；已完成请求调用无副作用。")),
        resultType = "Promise<void>",
        result = "完成清理，无返回值。原请求或后续流读取以 CANCELLED 拒绝。",
        failure = "取消后的实例不能重新发送，需要重新创建请求。",
        example = """const task = sview.network
    .request('https://example.com/large.bin')
    .response('stream');
cancelButton.onclick = () => task.cancel();
try {
    const result = await task.send();
    const reader = result.data.getReader();
    try {
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            console.log(value.byteLength);
        }
    } finally {
        await reader.cancel();
        reader.releaseLock();
    }
} catch (error) {
    console.error(error.code, error.message);
}""",
        category = "网络"
    )
).plus(localServerInterfaceHelp).let { entries ->
    // 按完整名称归属链式方法，独立接口的位置变化不能改变 request 的分组。
    val request = entries.single { it.name == "sview.network.request" }
    val methods = entries.filter { it.name.startsWith("sview.network.request().") }
    listOf(request.copy(methods = methods)) + entries.filter {
        it.name != request.name && !it.name.startsWith("sview.network.request().")
    }
}
