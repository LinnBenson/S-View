package io.todu.sview.ui.screens

/** 文本工具使用本机计算，返回值可直接用于脚本和网页。 */
internal val scriptTextHelp = listOf(
    InterfaceHelpEntry(
        name = "sview.script.code",
        brief = "同步读取并替换文本模板",
        summary = "先查找全局模板，再查找项目资源；读取 UTF-8 原文，不执行代码，不注入运行脚本。",
        parameters = listOf(
            InterfaceParameter("file", "string", true, "本地文件路径，如 /templates/card.html 或 templates/card.html。相对于资源根目录，不支持网络地址和设备文件地址。"),
            InterfaceParameter("replace", "object | array", false, "默认 {}。支持嵌套对象和多维数组，例如 {{a.title}}、{{items.0.name}}、{{matrix.0.1}}。")
        ),
        resultType = "string",
        result = "同步返回替换后的文本，无需 await；两处均找不到文件时返回空字符串。仅替换字符串和数字，其余值或不存在的路径保留原占位符。",
        failure = "参数或路径非法、路径越界、目标为目录、文件读取失败或运行连接不可用时直接抛出错误。",
        example = """// /templates/card.html：<h2>{{title}}</h2><p>{{rows.0.1}}</p>
const html = sview.script.code('/templates/card.html', {
  title: '专注计时', rows: [['分钟', 25]]
});
content('main#app', `<div>${'$'}{html}</div>`);""",
        note = "全局同名文件优先。占位符两端可留空格，只读取对象自身属性，不执行表达式；替换只进行一轮，原样插入且不转义 HTML。适合小型文本模板，避免在动画或高频刷新中反复同步读取。",
        category = "脚本",
    ),
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
