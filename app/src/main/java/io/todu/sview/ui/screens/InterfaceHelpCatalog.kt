package io.todu.sview.ui.screens

/** 接口说明集中保存，列表和详情使用同一份内容。 */
internal data class InterfaceHelpEntry(
    val name: String,
    val brief: String,
    val summary: String,
    val parameters: List<InterfaceParameter>,
    val resultType: String,
    val result: String,
    val failure: String,
    val example: String,
    val note: String? = null,
    val category: String = "系统",
    val methods: List<InterfaceHelpEntry> = emptyList(),
    val resultFields: List<InterfaceResultField> = emptyList()
)

/** 返回对象字段与输入参数分开描述。 */
internal data class InterfaceResultField(val name: String, val type: String, val description: String)

/** 参数的类型、必填状态和默认值独立展示，避免混在说明段落里。 */
internal data class InterfaceParameter(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String,
    val defaultValue: String? = null
)

internal val systemInterfaceHelp = listOf(
    InterfaceHelpEntry(
        name = "sview.system.toast",
        brief = "显示短暂的系统提示",
        summary = "显示短暂的系统提示，适合保存成功、操作完成等即时反馈。无需通知权限。",
        parameters = listOf(
            InterfaceParameter("message", "string", true, "提示文字，1–2000 个字符，不可为空白。"),
            InterfaceParameter("duration", "string", false, "short 为短提示，long 为长提示；具体时长由系统决定。", "'short'")
        ),
        resultType = "Promise<void>",
        result = "await 完成后的值为 undefined，不返回数据；表示提示请求已接收。",
        failure = "参数无效或运行连接不可用时，Promise 会拒绝。",
        example = """(async () => {
  try {
    await sview.system.toast('保存成功');
  } catch (error) {
    console.error(error.message);
  }
})();"""
    ),
    InterfaceHelpEntry(
        name = "sview.system.notify",
        brief = "发送系统通知",
        summary = "向系统通知栏发送通知，适合任务完成等提醒。通知显示项目名称，点击后打开 S View。",
        parameters = listOf(
            InterfaceParameter("title", "string", true, "通知标题，1–120 个字符，不可为空白。"),
            InterfaceParameter("message", "string", true, "通知正文，1–2000 个字符，不可为空白。")
        ),
        resultType = "Promise<number>",
        result = "await 完成后得到通知编号（number），表示通知已提交给系统。",
        failure = "参数无效、通知未获允许或运行连接不可用时，Promise 会拒绝。",
        example = """(async () => {
  try {
    const id = await sview.system.notify(
      '任务完成', '文件已处理完毕'
    );
    console.log('通知编号', id);
  } catch (error) {
    console.error(error.message);
  }
})();""",
        note = "先在设置 → 权限中允许通知。声音和显示方式由系统通知设置决定。"
    ),
)

internal val interfaceHelp = systemInterfaceHelp + deviceInterfaceHelp + interactionInterfaceHelp + networkInterfaceHelp + toolInterfaceHelp + scriptDatabaseHelp + scriptShareHelp + scriptLogHelp + scriptTextHelp + scriptDeviceHelp + storageInterfaceHelp + scanInterfaceHelp + infoInterfaceHelp + nfcInterfaceHelp + notifyMonitorHelp

/** 分类标题只显示 sview 的一级命名空间，子模块留在方法名前。 */
internal val InterfaceHelpEntry.namespace: String
    get() = name.split('.').take(2).joinToString(".")

internal val InterfaceHelpEntry.shortName: String
    get() = if (name.startsWith("sview.")) name.removePrefix("$namespace.") else name
