package io.todu.sview.data

import org.json.JSONObject

/** 保存时生成运行入口；变量和日志桥先于用户引用执行。 */
object RuntimeDocument {
    const val REVISION = 31

    fun render(project: Project, source: String, isWatch: Boolean, entry: Boolean = true): String {
        // 在浏览器解析前替换属性标记，源模板保持原样，跨设备同步后重新生成。
        val deviceSource = source.replace(ReferenceTemplate.IS_WATCH_PLACEHOLDER, if (isWatch) "iswatch" else "")
        val variables = JSONObject()
        project.variables.forEach { variables.put(it.name, it.jsonValue()) }
        val data = variables.toString().replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        val injection = "<script src=\"/__sview_runtime.js?v=$REVISION\"></script>"
        val variablesScript = if (project.variables.isEmpty()) "" else "<script>Object.assign(globalThis,$data);</script>"
        val html = if (entry) ReferenceTemplate.render(deviceSource, project.references)
            .replace(ReferenceTemplate.VARIABLES_PLACEHOLDER, variablesScript) else deviceSource
        val head = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(html)
        return if (head == null) injection + html else html.substring(0, head.range.last + 1) + injection + html.substring(head.range.last + 1)
    }

    /** 公共桥接脚本只生成一份，页面仍同步加载，保证用户脚本执行前接口就绪。 */
    fun bootstrap() = "$CONSOLE_SCRIPT\n${ScriptApi.SCRIPT}"

    private const val CONSOLE_SCRIPT = """(() => {
            let queue = [], queuedCharacters = 0, timer, pending;
            // Gecko 的堆栈不包含错误消息；分别读取，避免自定义 getter 让日志再次抛错。
            function formatError(value) {
                let name = 'Error', message = '', stack = '';
                try { name = String(value.name || name); } catch (_) { }
                try { message = String(value.message ?? ''); } catch (_) { }
                try { stack = typeof value.stack === 'string' ? value.stack : ''; } catch (_) { }
                const title = message ? name + ': ' + message : name;
                if (!stack) return title;
                return stack.split('\n', 1)[0] === title ? stack : title + '\n' + stack;
            }
            function format(value) {
                if (value instanceof Error) return formatError(value);
                try { return typeof value === 'object' ? JSON.stringify(value) : String(value); }
                catch { return String(value); }
            }
            function flush() {
                clearTimeout(timer); timer = null;
                if (pending) return pending;
                if (!queue.length) return Promise.resolve();
                // 每次最多 16000 个 UTF-16 字符，UTF-8 编码后也不会超过服务端 64 KB。
                pending = (async () => {
                    while (queue.length) {
                        const batch = [];
                        let size = 0;
                        while (queue.length && size + queue[0].length + batch.length <= 16000) {
                            const line = queue.shift();
                            queuedCharacters -= line.length;
                            size += line.length;
                            batch.push(line);
                        }
                        try { await fetch('/__sview_console', { method: 'POST', body: batch.join('\n'), keepalive: true }); }
                        catch { }
                    }
                })().finally(() => { pending = null; if (queue.length) return flush(); });
                return pending;
            }
            function send(value) {
                const line = String(value).slice(0, 16000);
                // 持续大量打印时仅保留最近的待写内容，最多一个网络请求，避免堆积 Promise。
                while (queue.length && queuedCharacters + line.length > 64000) queuedCharacters -= queue.shift().length;
                queue.push(line); queuedCharacters += line.length;
                if (queue.length >= 32) flush();
                else if (!timer && !pending) timer = setTimeout(flush, 100);
            }
            for (const level of ['log', 'debug', 'info', 'warn', 'error']) {
                const original = console[level].bind(console);
                console[level] = (...args) => {
                    original(...args);
                    let line = level + ': ';
                    for (let i = 0; i < args.length && line.length < 16000; i++) {
                        if (i) line += ' ';
                        line += String(format(args[i]) ?? '').slice(0, 16000 - line.length);
                    }
                    send(line);
                };
            }
            addEventListener('error', event => {
                if (event.error) send('error: ' + format(event.error));
                else if (event.message) send('error: ' + event.message + ' (' + event.filename + ':' + event.lineno + ':' + event.colno + ')');
                else if (event.target && (event.target.src || event.target.href)) send('error: 资源加载失败 ' + (event.target.src || event.target.href));
            }, true);
            addEventListener('unhandledrejection', event => send('error: ' + format(event.reason)));
            addEventListener('__sview_flush_logs', event => {
                if (typeof event.detail === 'function') flush().then(event.detail, event.detail);
            });
            addEventListener('pagehide', flush);
            addEventListener('visibilitychange', () => { if (document.hidden) flush(); });
        })();"""

}
