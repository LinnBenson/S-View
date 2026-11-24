package io.todu.sview.data

/** 复用窗口事件连接；启动确认、提前关闭和异常均只结束当前监听一次。 */
object ScriptNotifyMonitor {
    const val SCRIPT = """
        let activeNotifyMonitor = null;
        let notifyMonitorRequests = Promise.resolve();
        function callNotifyMonitor(method, id) {
            const result = notifyMonitorRequests.then(() => call('system.notifyMonitor.' + method, { id }));
            notifyMonitorRequests = result.catch(() => {});
            return result;
        }
        function closeNotifyMonitorWindow() { activeNotifyMonitor?.finish('windowClosed'); }
        function notifyMonitor(options = {}) {
            if (!options || typeof options !== 'object' || Array.isArray(options)) throw invalidArgument('notifyMonitor 需要回调对象');
            const handlers = {};
            for (const name of ['onStart', 'onMessage', 'onError', 'onClose']) {
                if (options[name] !== undefined && typeof options[name] !== 'function') throw invalidArgument(name + ' 应为函数');
                handlers[name] = options[name];
            }
            const id = crypto.randomUUID();
            let closed = false, requested = false, started = false, source;
            function invoke(name, value) {
                const callback = handlers[name];
                if (!callback) {
                    if (name === 'onError') reportInterfaceError(value, 'system.notifyMonitor', 'interface');
                    return;
                }
                if (name === 'onError') markInterfaceErrorHandled(value);
                invokeInterfaceCallback(callback, name === 'onStart' ? [] : [value], 'system.notifyMonitor.' + name, false, name === 'onClose');
            }
            function finish(reason) {
                if (closed) return;
                closed = true;
                source?.removeEventListener('notify-monitor', receive);
                source?.removeEventListener('error', disconnected);
                if (activeNotifyMonitor === state) activeNotifyMonitor = null;
                if (requested) void callNotifyMonitor('close', id).catch(error => {
                    if (reason !== 'windowClosed') invoke('onError', error);
                });
                invoke('onClose', reason);
            }
            function fail(error) { if (!closed) { invoke('onError', error); finish('error'); } }
            function receive(event) {
                if (closed) return;
                try {
                    const data = JSON.parse(event.data);
                    if (data.id !== id) return;
                    if (data.type === 'start' && !started) { started = true; invoke('onStart'); }
                    else if (data.type === 'message' && started) invoke('onMessage', data.value);
                    else if (data.type === 'error') fail(data.value);
                    else if (data.type === 'close') finish(data.value);
                } catch (error) { fail({ code: 'READ_FAILED', message: error.message }); }
            }
            function disconnected() { fail({ code: 'CONNECTION_LOST', message: '通知事件连接已断开' }); }
            const state = { finish };
            const handle = Object.freeze({ close() { finish('manual'); } });
            if (activeNotifyMonitor || ended) {
                const error = { code: activeNotifyMonitor ? 'ALREADY_LISTENING' : 'WINDOW_CLOSED', message: activeNotifyMonitor ? '当前窗口已有通知监听' : '运行窗口已关闭' };
                queueMicrotask(() => fail(error));
                return handle;
            }
            activeNotifyMonitor = state;
            void (async () => {
                try {
                    source = ensureWindowEvents();
                    source.addEventListener('notify-monitor', receive);
                    source.addEventListener('error', disconnected);
                    await waitWindowMonitor(null);
                    if (closed) return;
                    requested = true;
                    await callNotifyMonitor('start', id);
                } catch (error) { fail(error); }
            })();
            return handle;
        }
        addEventListener('pagehide', closeNotifyMonitorWindow);
    """
}
