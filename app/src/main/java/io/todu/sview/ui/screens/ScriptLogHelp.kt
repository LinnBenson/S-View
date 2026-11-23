package io.todu.sview.ui.screens

/** 日志读取的名称匹配、返回范围和错误码与运行接口保持一致。 */
internal val scriptLogHelp = listOf(InterfaceHelpEntry(
    name = "sview.script.log", brief = "读取项目日志",
    summary = "不传项目名称时读取当前项目日志；传入名称时精确匹配当前设备上的项目。",
    parameters = listOf(InterfaceParameter("projectName", "string", false, "项目完整名称，区分大小写，不忽略首尾空格。省略时读取当前项目。")),
    resultType = "Promise<string>",
    result = "返回当前保留的完整日志文本，包含时间和日志级别；没有日志时返回空字符串。读取前提交当前页面待发送的日志，并等待应用已接收的日志写入完成。",
    failure = "项目不存在：PROJECT_NOT_FOUND；名称重复：PROJECT_NAME_AMBIGUOUS；名称为空白或类型错误：INVALID_ARGUMENT。失败时 Promise 拒绝，可读取 error.code 与 error.message。",
    example = """const current = await sview.script.log();
const other = await sview.script.log('天气');""",
    note = "不传名称按当前项目 ID 读取，即使重名也不会读错。日志沿用现有轮转规则：超过 2 MiB 时保留最近约 1 MiB，已轮转删除的内容无法恢复。手表只读取手表上的项目日志。",
    category = "脚本"
))
