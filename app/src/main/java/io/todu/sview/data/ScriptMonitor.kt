package io.todu.sview.data

/** 回调保留在网页中；原生只推送生命周期，不尝试序列化 JavaScript 函数。 */
object ScriptMonitor {
    const val SCRIPT = """
        let callbacks = {}, monitorVersion = 0, readyDelivered = false;
        let nativeReady = false, pageReady = document.readyState === 'complete', ended = false, events;
        let resizeFrame = 0, lastWidth = innerWidth, lastHeight = innerHeight;
        const eventUrl = new URL('/__sview_events', location.href).href;
        const ackUrl = new URL('/__sview_events_ack', location.href).href;
        function invokeMonitor(name, value) {
            const callback = callbacks[name];
            if (!callback) return;
            const args = ['onError', 'onResize', 'onLocation', 'onMargin', 'onCornerRadius'].includes(name) ? [value] : [];
            invokeInterfaceCallback(callback, args, 'monitor.' + name, name === 'onError', name === 'onClose');
        }

        function notifyReady() {
            if (!nativeReady || !pageReady || ended || readyDelivered || !callbacks.onReady) return;
            const version = monitorVersion;
            queueMicrotask(() => {
                if (version !== monitorVersion || ended || readyDelivered) return;
                readyDelivered = true;
                invokeMonitor('onReady');
            });
        }
        function notifyError(value, closing = false) {
            if (!ended || closing) invokeMonitor('onError', value);
        }
        function notifyClose() {
            if (ended) return;
            ended = true;
            closeNfcWindow();
            closeNotifyMonitorWindow();
            for (const record of localServers.values()) cancelCustomServerRequests(record);
            if (resizeFrame) cancelAnimationFrame(resizeFrame);
            if (events) events.close();
            invokeMonitor('onClose');
            // 同步回调中的日志先提交，再确认关闭；原生仍有超时上限。
            new Promise(resolve => dispatchEvent(new CustomEvent('__sview_flush_logs', { detail: resolve })))
                .then(() => fetch(ackUrl, {
                    method: 'POST', credentials: 'same-origin', keepalive: true,
                    headers: { 'Content-Type': 'application/json', 'X-SView-Request': '1' }, body: '{}'
                })).catch(() => {});
        }
        // 对外保持同一个事件目标，重连不丢失 NFC 和服务注册的回调。
        let eventSource, reconnectTimer, reconnectStarted = 0, reconnectDelay = 250;
        let lastEventId = 0, eventPaused = false;
        const eventNames = ['ready', 'runtime-error', 'location', 'margin', 'cornerRadius', 'close', 'nfc', 'notify-monitor', 'local-server'];
        function stopWindowEvents() {
            eventPaused = true;
            clearTimeout(reconnectTimer); reconnectTimer = undefined;
            eventSource?.close(); eventSource = undefined;
        }
        function connectWindowEvents() {
            if (ended || eventPaused || eventSource || reconnectTimer) return;
            const source = new EventSource(eventUrl + '?after=' + lastEventId);
            eventSource = source;
            source.addEventListener('open', () => {
                if (eventSource !== source) return;
                reconnectStarted = 0; reconnectDelay = 250;
                events.dispatchEvent(new Event('open'));
            });
            for (const name of eventNames) source.addEventListener(name, event => {
                if (eventSource !== source || ended) return;
                const id = Number(event.lastEventId);
                if (id > 0) {
                    if (id <= lastEventId) return;
                    lastEventId = id;
                }
                events.dispatchEvent(new MessageEvent(name, { data: event.data, lastEventId: event.lastEventId }));
            });
            source.addEventListener('error', () => {
                if (eventSource !== source || ended || eventPaused) return;
                source.close(); eventSource = undefined;
                if (!reconnectStarted) reconnectStarted = performance.now();
                if (performance.now() - reconnectStarted >= 15000) {
                    reconnectStarted = 0;
                    events.dispatchEvent(new Event('error'));
                    notifyError({ type: 'connection', code: 'CONNECTION_LOST', message: '窗口事件连接无法恢复' });
                    return;
                }
                reconnectTimer = setTimeout(() => { reconnectTimer = undefined; connectWindowEvents(); }, reconnectDelay);
                reconnectDelay = Math.min(2000, reconnectDelay * 2);
            });
        }
        async function waitWindowMonitor(name) {
            if (name && !callbacks[name]) return;
            const source = ensureWindowEvents();
            if (source.readyState === EventSource.OPEN) return;
            await new Promise((resolve, reject) => {
                const finish = error => {
                    clearTimeout(timer);
                    source.removeEventListener('open', opened);
                    source.removeEventListener('error', failed);
                    if (error) reject(error); else resolve();
                };
                const opened = () => finish();
                const failed = () => finish(Object.assign(new Error('窗口事件连接不可用'), { code: 'CONNECTION_LOST' }));
                const timer = setTimeout(failed, 17000);
                source.addEventListener('open', opened, { once: true });
                source.addEventListener('error', failed, { once: true });
            });
        }
        function ensureWindowEvents() {
            if (!events) {
                events = new EventTarget();
                Object.defineProperty(events, 'readyState', { get: () => eventSource?.readyState ?? EventSource.CONNECTING });
                events.close = stopWindowEvents;
                events.addEventListener('ready', () => { nativeReady = true; notifyReady(); });
                events.addEventListener('runtime-error', event => {
                    try { const value = JSON.parse(event.data); console.error(value.message); notifyError(value); } catch (error) { console.error(error); }
                });
                for (const [event, callback] of [['location', 'onLocation'], ['margin', 'onMargin'], ['cornerRadius', 'onCornerRadius']]) {
                    events.addEventListener(event, value => { if (!ended) invokeMonitor(callback, JSON.parse(value.data)); });
                }
                events.addEventListener('close', notifyClose);
            }
            connectWindowEvents();
            return events;
        }
        function resumeWindowEvents() {
            if (!ended && events) { eventPaused = false; connectWindowEvents(); }
        }
        addEventListener('online', resumeWindowEvents);
        addEventListener('pageshow', resumeWindowEvents);
        document.addEventListener('visibilitychange', () => { if (!document.hidden) resumeWindowEvents(); });
        function monitor(options) {
            if (!options || typeof options !== 'object' || Array.isArray(options)) throw invalidArgument('monitor 需要传入回调对象');
            if (ended) throw new Error('运行窗口正在关闭');
            const next = {};
            for (const [name, callback] of Object.entries(options)) {
                if (!['onReady', 'onError', 'onClose', 'onResize', 'onLocation', 'onMargin', 'onCornerRadius'].includes(name)) throw invalidArgument('不支持的事件：' + name);
                if (callback !== undefined && typeof callback !== 'function') throw invalidArgument(name + ' 应为函数');
                if (callback) next[name] = callback;
            }
            callbacks = next;
            lastWidth = innerWidth; lastHeight = innerHeight;
            monitorVersion++;
            readyDelivered = false;
            ensureWindowEvents();
            notifyReady();
        }
        addEventListener('load', () => { pageReady = true; notifyReady(); }, { once: true });
        addEventListener('error', event => {
            if (event.target && event.target !== globalThis && (event.target.src || event.target.href)) {
                notifyError({ type: 'resource', message: '资源加载失败', filename: event.target.src || event.target.href });
            } else {
                if (interfaceErrorHandled(event.error)) return;
                const value = describeRuntimeError(event.error ?? event.message, 'error');
                value.message = event.message || value.message;
                Object.assign(value, { filename: event.filename || '', line: event.lineno || 0, column: event.colno || 0 });
                notifyError(value);
            }
        }, true);
        addEventListener('unhandledrejection', event => {
            if (interfaceErrorHandled(event.reason)) return;
            notifyError(describeRuntimeError(event.reason, 'unhandledrejection'));
        });
        addEventListener('resize', () => {
            if (ended || !callbacks.onResize || resizeFrame) return;
            resizeFrame = requestAnimationFrame(() => {
                resizeFrame = 0;
                const width = innerWidth, height = innerHeight;
                if (width === lastWidth && height === lastHeight) return;
                lastWidth = width; lastHeight = height;
                if (nativeReady && pageReady && !ended) invokeMonitor('onResize', { width, height });
            });
        });
        addEventListener('pagehide', () => {
            if (events) events.close();
            if (resizeFrame) cancelAnimationFrame(resizeFrame);
        });
    """
}
