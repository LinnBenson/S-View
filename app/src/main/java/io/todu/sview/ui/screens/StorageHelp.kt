package io.todu.sview.ui.screens

/** 文件接口共用路径和失败约定，具体参数保持独立说明。 */
private fun storageEntry(
    method: String, brief: String, summary: String, resultType: String, result: String, example: String,
    extra: List<InterfaceParameter> = emptyList(), note: String? = null
) = InterfaceHelpEntry(
    name = "sview.storage.$method", brief = brief, summary = summary,
    parameters = listOf(InterfaceParameter("path", "string", true,
        "普通路径相对于当前项目资源目录；device:/ 开头相对于设备主共享存储。list、hasDirectory、hasFile 可用空字符串或 / 表示项目根目录，device:/ 表示设备根目录。不能包含 .、.. 或符号链接；设备路径需先在设置 → 权限中授权。")) + extra,
    resultType = resultType, result = result,
    failure = "参数错误、类型不符、权限不足、存储空间不足或文件被占用时 Promise 拒绝；可用 try/catch 读取 error.message。设备目录仍受 Android 访问限制。",
    example = example, note = note, category = "文件"
)

internal val storageInterfaceHelp = listOf(
    InterfaceHelpEntry(
        name = "sview.storage.copy", brief = "复制文件或目录",
        summary = "原生分块复制，支持项目路径与 device:/ 互相复制；目录自动递归，源内容保持不变。",
        parameters = listOf(
            InterfaceParameter("source", "string", true, "来源文件或目录。普通路径相对于当前项目；device:/ 需要设备存储权限。"),
            InterfaceParameter("destination", "string", true, "目标最终路径，文件包含文件名，目录包含目录名。自动创建父目录，不额外嵌套来源目录名。"),
            InterfaceParameter("options.overwrite", "boolean", false, "仅允许覆盖已有普通文件；不合并或覆盖目录。", "false")
        ),
        resultType = "Promise<string>", result = "成功返回目标路径。默认遇到已有目标时报错，不自动改名。",
        failure = "来源不存在、目标冲突、权限不足、空间不足、符号链接、复制到自身或目录子路径时 Promise 拒绝。窗口关闭会取消尚未完成的复制。",
        example = """const path = await sview.storage.copy(
  'device:/Download/photo.jpg', 'images/photo.jpg'
);
await sview.storage.copy('assets', 'device:/Download/assets-backup');
await sview.storage.copy('images/photo.jpg', 'device:/Download/photo.jpg', {
  overwrite: true
});""",
        note = "完整复制到临时位置后再提交。失败清理临时内容，已自动创建的空父目录可能保留；不保证复制期间被其他应用修改的来源文件是一致快照。",
        category = "文件"
    ),
    storageEntry("hasDirectory", "检查目录是否存在", "判断指定路径是否为已存在的目录，不创建或修改内容。", "Promise<boolean>",
        "目录存在返回 true；不存在或实际为文件返回 false。权限不足及非法路径仍会拒绝，不会当作不存在。",
        """const exists = await sview.storage.hasDirectory('cache');
const deviceExists = await sview.storage.hasDirectory('device:/Download');"""),
    storageEntry("hasFile", "检查文件是否存在", "判断指定路径是否为已存在的普通文件，仅检查当前项目资源，不查找全局模板。", "Promise<boolean>",
        "文件存在返回 true；不存在或实际为目录返回 false。权限不足及非法路径仍会拒绝，不会当作不存在。",
        """const exists = await sview.storage.hasFile('data/config.json');
const deviceExists = await sview.storage.hasFile('device:/Download/example.zip');"""),
    storageEntry("createDirectory", "创建目录", "创建指定目录及缺失的父目录；已存在的目录保持不变。", "Promise<boolean>",
        "创建成功或目录已存在返回 true。同名文件已存在则拒绝。", "await sview.storage.createDirectory('data/cache');"),
    storageEntry("deleteDirectory", "递归删除目录", "删除指定目录及其中的所有文件、子目录。禁止删除项目或设备存储根目录。", "Promise<boolean>",
        "删除成功返回 true，目录不存在返回 false。传入文件则拒绝。", "await sview.storage.deleteDirectory('data/cache');",
        note = "删除不可撤销；遇到权限或文件系统错误时可能已经删除部分内容。目录内的符号链接只删除链接，不跟随链接删除目标。"),
    storageEntry("list", "读取目录列表", "列出指定目录的直属文件和子目录，不递归、不包含全局模板；目录在前，同类按名称排序。", "Promise<Array>",
        "每项包含 name（名称）、path（可直接传给其他文件接口的路径）、directory（是否目录）、size（字节数，目录为 0）、lastModified（修改时间，毫秒时间戳）。空目录返回 []。",
        """const entries = await sview.storage.list('');
for (const entry of entries) {
    console.log(entry.name, entry.path, entry.directory, entry.size);
}"""),
    storageEntry("deleteFile", "删除文件", "只删除指定文件，不删除目录。", "Promise<boolean>",
        "成功返回 true，文件不存在返回 false；传入目录则拒绝。", "await sview.storage.deleteFile('data/old.json');"),
    storageEntry("createFile", "创建空文件", "创建零字节文件，自动创建父目录。已有文件不会被清空。", "Promise<boolean>",
        "成功返回 true；目标已存在则拒绝。", "await sview.storage.createFile('data/new.txt');"),
    storageEntry("write", "写入文本", "以 UTF-8 保存文本，文件不存在则创建；默认覆盖，append 为 true 时追加。自动创建父目录。", "Promise<Object>",
        "返回 { path, size }，size 为写入完成后文件的总字节数，包括追加前的内容。",
        """await sview.storage.write('logs/app.txt', '开始运行\n');
await sview.storage.write('logs/app.txt', '运行完成\n', true);""",
        listOf(InterfaceParameter("text", "string", true, "待写入的文本；对象或数组请先 JSON.stringify。"),
            InterfaceParameter("append", "boolean", false, "true 表示追加，false 表示覆盖。", "false")),
        "保存完整后才替换原文件；失败或窗口关闭会清理未提交内容。"),
    storageEntry("save", "保存文件数据", "保存网页文件或二进制内容，自动创建父目录；目标文件已存在时覆盖。", "Promise<Object>",
        "返回 { path, size }，分别为保存路径和最终字节数。",
        """const file = await sview.storage.open('images/photo.png');
await sview.storage.save('device:/Pictures/photo.png', file);

const stream = await sview.storage.open('device:/Videos/demo.mp4', 'stream');
await sview.storage.save('videos/demo.mp4', stream);""",
        listOf(InterfaceParameter("data", "File | Blob | ArrayBuffer | TypedArray | DataView | ReadableStream", true,
            "字节流每块必须为 ArrayBuffer 或其视图。只保存 TypedArray/DataView 的有效范围。文本请使用 write，或自行创建 Blob。")),
        "path 必须包含文件名；不会从数据自动推断目标文件名。正文分块传输，失败保留原文件。流被消费一次后不可再次保存。"),
    storageEntry("open", "打开文件供脚本读取", "默认读取完整内容，返回标准 File；stream 模式返回按需分块读取的字节流，不启动外部应用。", "Promise<File | ReadableStream<Uint8Array>>",
        "File 支持 name、size、type、lastModified、text()、arrayBuffer()、stream()、slice()，可传给 FormData 或 URL.createObjectURL。File 为内容快照，修改后需要 write/save 写回。type 根据文件名推断，未知时为 application/octet-stream。",
        """const file = await sview.storage.open('data/config.json');
const config = JSON.parse(await file.text());
console.log(config);

// img.src 和 a.href 也可直接使用 device:/ 路径。
const image = await sview.storage.open('device:/Pictures/photo.png');
const url = URL.createObjectURL(image);
document.querySelector('img').src = url;
// 图片不再使用时调用 URL.revokeObjectURL(url)。""",
        listOf(InterfaceParameter("type", "'file' | 'stream'", false, "file 返回完整 File，最大 32 MB；更大文件使用 stream。", "'file'")),
        "最多同时保持 8 个读取流、4 个保存任务。流读完自动关闭，不再使用时调用 stream.cancel() 或 reader.cancel()。窗口关闭后所有未完成操作释放。流模式不保证外部程序修改文件时仍得到原始快照。")
)
