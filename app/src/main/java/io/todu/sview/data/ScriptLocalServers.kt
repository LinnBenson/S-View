package io.todu.sview.data

/** 回调通过窗口已有的事件连接传递，文件内容不经过 JavaScript 桥。 */
object ScriptLocalServers {
    const val SCRIPT = """
        const localServers = new Map();
        let localServerListening = false;
        function dispatchLocalServer(event) {
            const item = JSON.parse(event.data), record = localServers.get(item.id);
            if (!record) return;
            if (record.starting) { record.events.push(item); return; }
            deliverLocalServerEvent(item, record);
        }
        // 先移除记录，再调用用户代码，避免重复通知关闭。
        function deliverLocalServerEvent(item, record) {
            if (record.closed) return;
            if (item.kind === 'request') { handleCustomServerRequest(item.id, record, item.value); return; }
            if (item.kind === 'request-end') {
                record.requests?.get(item.value.requestId)?.abort();
                record.requests?.delete(item.value.requestId);
                return;
            }
            if (item.kind === 'closed') { record.closed = true; localServers.delete(item.id); cancelCustomServerRequests(record); }
            invokeLocalServerCallback(record, item.kind, item.value);
        }
        function invokeLocalServerCallback(record, kind, value, fallback = true) {
            const names = { message: 'onMessage', text: 'onText', file: 'onFile', image: 'onImage', error: 'onError', closed: 'onClose' };
            const callback = record.options[names[kind]];
            if (!callback) {
                if (kind === 'error' && fallback) reportInterfaceError(value, 'network.' + (record.kind || 'server'), 'interface');
                return;
            }
            if (kind === 'error') markInterfaceErrorHandled(value);
            const args = (kind === 'file' || kind === 'image') ? [value.percent, value.path] : kind === 'closed' ? [] : [value];
            invokeInterfaceCallback(callback, args, 'network.' + (record.kind || 'server') + '.' + names[kind], false, kind === 'closed');
        }
        async function startLocalServer(kind, options = {}) {
            if (!options || typeof options !== 'object' || Array.isArray(options)) throw invalidArgument('需要传入配置对象');
            for (const key of ['onMessage', 'onText', 'onFile', 'onImage', 'onError', 'onClose', 'onRequest']) {
                if (options[key] !== undefined && typeof options[key] !== 'function') throw invalidArgument(key + ' 应为函数');
            }
            for (const key of ['port', 'maxFileSizeMB', 'maxImageSizeMB', 'timeout', 'maxBodySizeMB', 'maxConcurrentRequests']) {
                if (options[key] !== undefined && !Number.isInteger(options[key])) throw invalidArgument(key + ' 应为整数');
            }
            for (const key of ['name', 'server', 'token', 'home']) {
                if (options[key] !== undefined && typeof options[key] !== 'string') throw invalidArgument(key + ' 应为字符串');
            }
            if (kind === 'server' && (typeof options.home !== 'string' || !options.home.trim())) throw invalidArgument('home 应为 HTML 文件路径');
            const source = ensureWindowEvents();
            if (!localServerListening) { source.addEventListener('local-server', dispatchLocalServer); localServerListening = true; }
            const id = crypto.randomUUID(), record = { kind, options, closed: false, starting: true, events: [], requests: new Map() };
            localServers.set(id, record);
            try {
                await waitWindowMonitor();
                const args = { id };
                for (const key of ['name', 'server', 'port', 'token', 'maxFileSizeMB', 'maxImageSizeMB', 'home', 'timeout', 'maxBodySizeMB', 'maxConcurrentRequests']) if (options[key] !== undefined) args[key] = options[key];
                if (kind === 'server') args.hasHandler = typeof options.onRequest === 'function';
                const value = await call('network.' + kind, args);
                let closing;
                const closeMethod = kind === 'hotspot' ? 'network.hotspot.close' : kind === 'server' ? 'network.server.close' : 'network.localServer.close';
                const handle = { ...(kind === 'hotspot' ? { name: value.name, password: value.password }
                    : kind === 'server' ? { url: value.url, server: value.server, port: value.port }
                    : { name: value.name, url: value.url, port: value.port, token: value.token }),
                    close() {
                        if (record.closed) return Promise.resolve();
                        if (!closing) closing = call(closeMethod, { id }).then(() => {
                            deliverLocalServerEvent({ id, kind: 'closed' }, record);
                        }).catch(error => { closing = undefined; throw error; });
                        return closing;
                    }
                };
                if (kind === 'chatServer') handle.send = text => {
                    if (typeof text !== 'string') return Promise.reject(invalidArgument('send 需要传入字符串'));
                    if (record.closed) return Promise.reject(Object.assign(new Error('服务已关闭'), { code: 'INVALID_STATE' }));
                    return call('network.localServer.send', { id, text });
                };
                record.starting = false;
                for (const item of record.events) deliverLocalServerEvent(item, record);
                record.events = [];
                return Object.freeze(handle);
            } catch (error) { localServers.delete(id); invokeLocalServerCallback(record, 'error', error, false); throw error; }
        }
    """
}
