package io.todu.sview.data

/** 异常在网页内归一化；弱引用只标记已由专用回调处理的原始错误，不改变 Promise 的拒绝结果。 */
internal object ScriptErrorHandling {
    const val SCRIPT = """
        const handledInterfaceErrors = new WeakSet();
        function isErrorObject(value) {
            return value !== null && (typeof value === 'object' || typeof value === 'function');
        }
        function markInterfaceErrorHandled(error) {
            if (isErrorObject(error)) handledInterfaceErrors.add(error);
        }
        function interfaceErrorHandled(error) {
            return isErrorObject(error) && handledInterfaceErrors.has(error);
        }
        function describeRuntimeError(error, type, source) {
            const value = { type, message: '脚本运行错误', stack: '' };
            // 用户可以抛出任意值，甚至带有会抛错的 getter，不能让错误处理再次失败。
            try { value.message = String(error?.message ?? error); } catch (_) { }
            try { if (typeof error?.stack === 'string') value.stack = error.stack; } catch (_) { }
            try { if (typeof error?.code === 'string') value.code = error.code; } catch (_) { }
            if (source) value.source = source;
            return value;
        }
        function logInterfaceError(error, source) {
            try { console.error(source + ':', error); } catch (_) { }
        }
        function reportInterfaceError(error, source, type = 'callback', closing = false) {
            logInterfaceError(error, source);
            notifyError(describeRuntimeError(error, type, source), closing);
        }
        function invokeInterfaceCallback(callback, args, source, errorHandler = false, closing = false) {
            const failed = error => {
                // monitor.onError 自身失败只记日志；其他回调的新异常仍交给 monitor。
                if (errorHandler) logInterfaceError(error, source);
                else reportInterfaceError(error, source, 'callback', closing);
            };
            try {
                const result = callback(...args);
                if (result && typeof result.then === 'function') Promise.resolve(result).catch(failed);
            } catch (error) { failed(error); }
        }
    """
}
