package io.todu.sview.ui.screens

/** 帮助正文按段落与示例组织，新增条目不需要修改页面布局。 */
internal data class UsageHelpEntry(val id: String, val title: String, val brief: String, val sections: List<UsageHelpSection>)
internal data class UsageHelpSection(val title: String, val text: String, val code: String? = null, val language: String = "JavaScript")

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
    )),
    UsageHelpEntry("message-box", "信息盒子", "用图标、标题和正文展示提示信息", listOf(
        UsageHelpSection("HTML 示例", "先加载 global.css 和 Lucide 图标库，再将以下内容放入页面。", """<div box>
    <div icon>
        <i data-lucide="circle-x" style="stroke: rgb( var( --r5 ) );"></i>
    </div>
    <p title>发生错误</p>
    <p text>系统运行时发生了一些错误</p>
</div>""", language = "HTML"),
        UsageHelpSection("显示图标", "添加 HTML 后调用 lucide.createIcons()。如果使用 global.js 中的 content() 填充内容，会自动生成图标。"),
    )),
    UsageHelpEntry("card", "卡片组件", "在卡片中展示标题、内容和操作按钮", listOf(
        UsageHelpSection("HTML 示例", "加载 global.css 和 Lucide 图标库后使用。按钮的点击操作需自行绑定。", """<div card>
    <div title>卡片标题</div>
    <div>
        卡片内容
    </div>
    <button class="top8" button icon><i data-lucide="house"></i>返回首页</button>
</div>""", language = "HTML"),
        UsageHelpSection("显示图标", "添加 HTML 后调用 lucide.createIcons()。使用 global.js 中的 content() 填充内容时，会自动生成图标。"),
    )),
    UsageHelpEntry("item", "item 元素", "在同一项中展示左侧文字和右侧操作", listOf(
        UsageHelpSection("HTML 示例", "加载 global.css 和 Lucide 图标库后使用。按钮的点击操作需自行绑定。", """<div item>
    <p class="more">项目左侧文字</p>
    <div right>
        <button button icon r4><i data-lucide="chevron-right"></i></button>
    </div>
</div>""", language = "HTML"),
        UsageHelpSection("显示图标", "添加 HTML 后调用 lucide.createIcons()。使用 global.js 中的 content() 填充内容时，会自动生成图标。"),
    )),
    UsageHelpEntry("loader", "加载组件", "显示加载动画", listOf(
        UsageHelpSection("HTML 示例", "加载 global.css 后使用。加载结束后移除或隐藏此元素。", """<div loader></div>""", language = "HTML"),
    )),
    UsageHelpEntry("flex", "间隔布局", "让子元素之间保持间隔", listOf(
        UsageHelpSection("HTML 示例", "加载 global.css 和 Lucide 图标库后使用。", """<div flex>
    <button button icon><i data-lucide="house"></i></button>
    <button button icon><i data-lucide="house"></i></button>
    <button button icon><i data-lucide="house"></i></button>
</div>""", language = "HTML"),
        UsageHelpSection("显示图标", "添加 HTML 后调用 lucide.createIcons()。使用 global.js 中的 content() 填充内容时，会自动生成图标。"),
    )),
    UsageHelpEntry("row", "行平分布局", "子元素两端对齐，均匀分配间距，保留各自宽度", listOf(
        UsageHelpSection("HTML 示例", "加载 global.css 和 Lucide 图标库后使用。", """<div row>
    <button button icon><i data-lucide="house"></i></button>
    <button button icon><i data-lucide="house"></i></button>
    <button button icon><i data-lucide="house"></i></button>
</div>""", language = "HTML"),
        UsageHelpSection("显示图标", "添加 HTML 后调用 lucide.createIcons()。使用 global.js 中的 content() 填充内容时，会自动生成图标。"),
    )),
)
