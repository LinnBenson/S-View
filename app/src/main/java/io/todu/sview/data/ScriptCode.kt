package io.todu.sview.data

/** 同步读取本地原文；替换留在脚本端，保留数字语义并避免序列化整个数据对象。 */
object ScriptCode {
    const val SCRIPT = """
        function readScriptCode(file, replace = {}) {
            if (typeof file !== 'string' || !file.trim()) throw invalidArgument('file 必须是非空本地文件路径');
            if (replace === null || typeof replace !== 'object') throw invalidArgument('replace 必须是对象或数组');
            // 仅访问当前运行会话的本机接口，不将 file 当作请求地址。
            const request = new XMLHttpRequest();
            request.open('POST', endpoint, false);
            request.setRequestHeader('Content-Type', 'application/json');
            request.setRequestHeader('X-SView-Request', '1');
            request.send(JSON.stringify({ method: 'script.code', args: { file } }));
            if (request.status !== 200) throw Object.assign(new Error('读取模板失败：' + request.status), { code: 'BRIDGE_ERROR' });
            const result = JSON.parse(request.responseText);
            if (!result.ok) throw Object.assign(new Error(result.error), { code: result.code || 'IO_ERROR' });
            // 回调返回的文本不参与下一轮匹配，也不会解释其中的替换符号。
            return result.value.replace(/\{\{([^{}]*)\}\}/g, (placeholder, expression) => {
                const path = expression.trim().split('.');
                let value = replace;
                for (const key of path) {
                    if (!key || value === null || typeof value !== 'object' ||
                        !Object.prototype.hasOwnProperty.call(value, key)) return placeholder;
                    value = value[key];
                }
                return typeof value === 'string' || typeof value === 'number' ? String(value) : placeholder;
            });
        }
    """
}
