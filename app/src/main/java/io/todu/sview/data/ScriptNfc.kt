package io.todu.sview.data

/** 回调仅存在于网页；通过现有事件连接接收原生 NFC 消息。 */
object ScriptNfc {
    const val SCRIPT = """
        let activeNfc = null;
        let nfcRequests = Promise.resolve();
        function callNfc(method, args) {
            const result = nfcRequests.then(() => call(method, args));
            nfcRequests = result.catch(() => {});
            return result;
        }
        function closeNfcWindow() {
            if (activeNfc) activeNfc.finish('windowClosed');
        }
        function nfc(options = {}) {
            if (!options || typeof options !== 'object' || Array.isArray(options)) throw invalidArgument('nfc 需要回调对象');
            const handlers = {};
            for (const name of ['onStart', 'onMessage', 'onError', 'onClose']) {
                if (options[name] !== undefined && typeof options[name] !== 'function') throw invalidArgument(name + ' 应为函数');
                handlers[name] = options[name];
            }
            const repeatInterval = options.repeatInterval === undefined ? 1000 : options.repeatInterval;
            if (!Number.isInteger(repeatInterval) || repeatInterval < 0 || repeatInterval > 60000) throw invalidArgument('repeatInterval 应为 0–60000 的整数');
            const id = crypto.randomUUID();
            let closed = false, started = false, requested = false, source;
            const pending = [];
            function invoke(name, value) {
                const callback = handlers[name];
                if (!callback) {
                    if (name === 'onError') reportInterfaceError(value, 'system.nfc', 'interface');
                    return;
                }
                if (name === 'onError') markInterfaceErrorHandled(value);
                invokeInterfaceCallback(callback, [value], 'system.nfc.' + name, false, name === 'onClose');
            }
            function stopNative() {
                return callNfc('system.nfc.close', { id }).catch(error => invoke('onError', error));
            }
            function finish(reason) {
                if (closed) return;
                closed = true;
                pending.length = 0;
                source?.removeEventListener('nfc', receive);
                source?.removeEventListener('error', disconnected);
                if (activeNfc === state) activeNfc = null;
                if (requested) void stopNative();
                invoke('onClose', reason);
            }
            function fail(code, message) {
                if (closed) return;
                invoke('onError', { code, message });
                finish('error');
            }
            function deliver(event) {
                if (closed) return;
                if (event.type === 'close') { finish(event.value); return; }
                if (event.type === 'error') { invoke('onError', event.value); return; }
                if (event.type === 'message') {
                    const tag = event.value;
                    if (tag.ndef) {
                        tag.ndef.bytes = new Uint8Array(tag.ndef.bytes);
                        for (const record of tag.ndef.records) {
                            for (const key of ['type', 'id', 'payload']) record[key] = new Uint8Array(record[key]);
                        }
                    }
                    invoke('onMessage', tag);
                }
            }
            function receive(event) {
                if (closed) return;
                try {
                    const data = JSON.parse(event.data);
                    if (data.id !== id) return;
                    // 原生事件可能先于启动响应到达，确保 onStart 先执行。
                    if (!started) { if (pending.length < 128) pending.push(data); }
                    else deliver(data);
                } catch (error) { fail('READ_FAILED', error.message); }
            }
            function disconnected() { fail('CONNECTION_LOST', 'NFC 事件连接已断开'); }
            const state = { finish };
            const handle = Object.freeze({ close() { finish('manual'); } });
            if (activeNfc) {
                queueMicrotask(() => fail('ALREADY_LISTENING', '当前窗口已有 NFC 监听'));
                return handle;
            }
            if (ended) {
                queueMicrotask(() => fail('NOT_FOREGROUND', '运行窗口正在关闭'));
                return handle;
            }
            activeNfc = state;
            void (async () => {
                try {
                    source = ensureWindowEvents();
                    source.addEventListener('nfc', receive);
                    source.addEventListener('error', disconnected);
                    await waitWindowMonitor(null);
                    if (closed) return;
                    requested = true;
                    const result = await callNfc('system.nfc.start', { id, repeatInterval });
                    // close() 可发生在启动请求期间，响应后再停一次以覆盖请求乱序。
                    if (closed) { await stopNative(); return; }
                    if (!result.started) { fail(result.code, result.message); return; }
                    started = true;
                    invoke('onStart');
                    for (const event of pending.splice(0)) deliver(event);
                } catch (error) { fail(error.code || 'START_FAILED', error.message || '无法启动 NFC'); }
            })();
            return handle;
        }
        addEventListener('pagehide', closeNfcWindow);
    """
}
