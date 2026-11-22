package io.todu.sview.ui.screens

/** 共享数据库说明与运行时接口保持一致，JSON 类型在传输时自动还原。 */
internal val scriptDatabaseHelp = listOf(
    InterfaceHelpEntry(
        name = "sview.script.db.get", brief = "读取共享数据库中的值",
        summary = "按键读取持久化数据。对象和数组会自动还原，字符串保持原样。所有项目共用同一份数据库。",
        parameters = listOf(InterfaceParameter("key", "string", true, "1–128 个字符，不能为空白或包含控制字符。")),
        resultType = "Promise<boolean | string | number | array | object | null>",
        result = "返回保存的原始类型；键不存在时返回 null。",
        failure = "键无效或读取失败时，Promise 会拒绝。",
        example = """const preferences = await sview.script.db.get('preferences');
console.log(preferences?.theme);""",
        note = "此数据库独立于项目变量、localStorage 和浏览器缓存。", category = "脚本"
    ),
    InterfaceHelpEntry(
        name = "sview.script.db.set", brief = "保存共享数据库中的值",
        summary = "保存键值数据，同名键会被覆盖。自动序列化对象和数组，重启应用后数据仍保留。",
        parameters = listOf(
            InterfaceParameter("key", "string", true, "1–128 个字符，不能为空白或包含控制字符。"),
            InterfaceParameter("value", "boolean | string | number | array | object", true, "数字必须有限；对象和数组须可序列化为 JSON，不支持循环引用、undefined、函数、Symbol 或 BigInt。")
        ),
        resultType = "Promise<void>", result = "写入完成后返回 undefined。",
        failure = "类型无效、超出存储限制或写入失败时，Promise 会拒绝。",
        example = """await sview.script.db.set('preferences', { theme: 'dark', enabled: true });
await sview.script.db.set('tasks', ['阅读', '运动']);
const preferences = await sview.script.db.get('preferences');
console.log(preferences.theme);
await sview.script.db.set('text', '{"a":1}');
console.log(typeof await sview.script.db.get('text')); // string""",
        note = "最多 1000 个键，单个值 48 KB，全部值合计 2 MB。清理缓存、删除项目不会删除数据库；卸载应用会删除本机数据。", category = "脚本"
    ),
    InterfaceHelpEntry(
        name = "sview.script.db.remove", brief = "删除共享数据库中的键",
        summary = "删除指定键及其值，其他项目会立即读到删除后的结果。",
        parameters = listOf(InterfaceParameter("key", "string", true, "要删除的键。")),
        resultType = "Promise<boolean>", result = "删除成功返回 true；键原本不存在返回 false。",
        failure = "键无效或删除失败时，Promise 会拒绝。",
        example = "const removed = await sview.script.db.remove('preferences');\nconsole.log(removed);", category = "脚本"
    ),
    InterfaceHelpEntry(
        name = "sview.script.db.getAll", brief = "读取共享数据库的全部键值",
        summary = "返回以数据库键为属性的对象，每项保留原始类型，嵌套对象和数组自动还原。",
        parameters = emptyList(), resultType = "Promise<object>", result = "返回全部键值；数据库为空时返回 {}。",
        failure = "读取失败时，Promise 会拒绝。",
        example = """const data = await sview.script.db.getAll();
for (const [key, value] of Object.entries(data)) {
    console.log(key, value);
}""", category = "脚本"
    )
)
