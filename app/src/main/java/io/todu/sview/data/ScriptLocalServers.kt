package io.todu.sview.data

/** 回调通过窗口已有的事件连接传递，文件内容不经过 JavaScript 桥。 */
object ScriptLocalServers {
    const val SCRIPT = """
        const localServers = new Map();
        let localServerListening = false;
        function dispatchLocalServer(event) {
            const item = JSON.parse(event.data), record = localServers.get(item.id);
            if (!record) return;
            if (item.kind === 'closed') { record.closed = true; localServers.delete(item.id); return; }
            const names = { message: 'onMessage', text: 'onText', file: 'onFile', image: 'onImage', error: 'onError' };
            const callback = record.options[names[item.kind]];
            if (!callback) return;
            try {
                const value = item.value;
                const result = (item.kind === 'file' || item.kind === 'image') ? callback(value.percent, value.path) : callback(value);
                Promise.resolve(result).catch(error => console.error('network server:', error));
            } catch (error) { console.error('network server:', error); }
        }
        async function startLocalServer(kind, options = {}) {
            if (!options || typeof options !== 'object' || Array.isArray(options)) throw invalidArgument('需要传入配置对象');
            for (const key of ['onMessage', 'onText', 'onFile', 'onImage', 'onError']) {
                if (options[key] !== undefined && typeof options[key] !== 'function') throw invalidArgument(key + ' 应为函数');
            }
            for (const key of ['port', 'maxFileSizeMB', 'maxImageSizeMB']) {
                if (options[key] !== undefined && !Number.isInteger(options[key])) throw invalidArgument(key + ' 应为整数');
            }
            for (const key of ['name', 'server', 'token']) {
                if (options[key] !== undefined && typeof options[key] !== 'string') throw invalidArgument(key + ' 应为字符串');
            }
            const source = ensureWindowEvents();
            if (!localServerListening) { source.addEventListener('local-server', dispatchLocalServer); localServerListening = true; }
            await waitWindowMonitor();
            const id = crypto.randomUUID(), record = { options, closed: false };
            localServers.set(id, record);
            try {
                const args = { id };
                for (const key of ['name', 'server', 'port', 'token', 'maxFileSizeMB', 'maxImageSizeMB']) if (options[key] !== undefined) args[key] = options[key];
                const value = await call('network.' + kind, args);
                let closing;
                const handle = { name: value.name, url: value.url, port: value.port, token: value.token,
                    close() {
                        if (record.closed) return Promise.resolve();
                        if (!closing) closing = call('network.localServer.close', { id }).then(() => {
                            record.closed = true; localServers.delete(id);
                        }).catch(error => { closing = undefined; throw error; });
                        return closing;
                    }
                };
                if (kind === 'chatServer') handle.send = text => {
                    if (typeof text !== 'string') return Promise.reject(invalidArgument('send 需要传入字符串'));
                    if (record.closed) return Promise.reject(Object.assign(new Error('服务已关闭'), { code: 'INVALID_STATE' }));
                    return call('network.localServer.send', { id, text });
                };
                return Object.freeze(handle);
            } catch (error) { localServers.delete(id); throw error; }
        }
    """
}
