package io.todu.sview.ui.screens

/** 文本工具使用本机计算，返回值可直接用于脚本和网页。 */
internal val scriptTextHelp = listOf(
    InterfaceHelpEntry(
        name = "sview.script.md5",
        brief = "计算文本 MD5",
        summary = "按 UTF-8 编码计算文本的 MD5，不添加换行，不改变空白。",
        parameters = listOf(InterfaceParameter("text", "string", true, "待计算的文本，最多 8192 个 UTF-16 代码单元，允许空字符串。")),
        resultType = "Promise<string>",
        result = "返回 32 位小写十六进制 MD5 字符串。",
        failure = "参数不是字符串、内容过长或接口连接不可用时拒绝 Promise。",
        example = """const digest = await sview.script.md5('hello');
console.log(digest); // 5d41402abc4b2a76b9719d911017c592""",
        note = "用于内容摘要，不适合保存密码或验证数据未被恶意篡改。",
        category = "脚本",
    ),
    InterfaceHelpEntry(
        name = "sview.script.qrcode",
        brief = "生成二维码图片",
        summary = "在本机生成黑白 PNG 二维码，使用 UTF-8 编码、M 级纠错和四模块白边。",
        parameters = listOf(InterfaceParameter("text", "string", true, "非空文本，UTF-8 编码最多 2000 字节。")),
        resultType = "Promise<string>",
        result = "返回 data:image/png;base64, 开头的图片地址，尺寸至少为 256 × 256 像素，长内容会自动增大，可直接赋给 img.src。",
        failure = "参数不是字符串、为空、超过容量或生成失败时拒绝 Promise。",
        example = """const image = document.createElement('img');
image.src = await sview.script.qrcode('https://todu.io');
document.body.appendChild(image);""",
        note = "不请求网络，不创建资源文件。显示时保留白边，避免将图片缩得过小。",
        category = "脚本",
    ),
)
