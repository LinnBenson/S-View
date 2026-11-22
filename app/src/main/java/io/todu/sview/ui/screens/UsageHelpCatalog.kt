package io.todu.sview.ui.screens

/** 帮助正文按段落与示例组织，新增条目不需要修改页面布局。 */
internal data class UsageHelpEntry(val id: String, val title: String, val brief: String, val sections: List<UsageHelpSection>)
internal data class UsageHelpSection(val title: String, val text: String, val code: String? = null)

internal val usageHelpEntries = listOf(
    UsageHelpEntry("url-schemes", "URL Schemes", "从其他应用启动项目并传入文本", listOf(
        UsageHelpSection("启动链接", "手机和手表均支持。可在支持打开自定义链接的浏览器、快捷方式或自动化应用中调用。", "sview://task?name=项目名&share=传递参数"),
        UsageHelpSection("参数", "name：必填，填写完整项目名，按项目列表顺序运行第一个同名项目。\n\nshare：可选，传入文本；省略时为空字符串。不能通过此参数传入文件。\n\nname 最多 1024 个字符，share 最多 262144 个字符；调用方可能有更短的链接长度限制。"),
        UsageHelpSection("生成链接", "name 和 share 分别使用 encodeURIComponent 编码，避免中文、空格、&、# 等字符影响参数解析。不要对整条链接编码。", """const name = '专注计时';
const text = '开始 25 分钟';
const url = 'sview://task?name=' + encodeURIComponent(name)
  + '&share=' + encodeURIComponent(text);
console.log(url);"""),
        UsageHelpSection("接收内容", "在目标项目的 main.js 中读取。本次启动的 title 固定为 URL Schemes，text 为解码后的 share 文本，files 为空数组。重复调用会返回同一份内容。", """const share = await sview.script.getShare();
console.log(share.title); // URL Schemes
console.log(share.text);  // 开始 25 分钟
console.log(share.files); // []"""),
        UsageHelpSection("使用注意", "项目必须已存在于当前设备。URL Schemes 不受项目“共享来源”设置限制。已有项目运行窗口时，请先关闭再调用。\n\n普通启动时 getShare() 返回 { text: '', title: '', files: [] }。其他应用也能构造启动链接，脚本应检查传入内容后再执行操作。")
    ))
)
