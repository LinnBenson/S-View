package io.todu.sview.data

/** Request/Response 保留网页标准语义，正文分块通过本地通道交给原生服务器。 */
object ScriptCustomServer {
    const val SCRIPT = """
        async function customServerTransfer(id, requestId, operation, body, signal) {
            const url = new URL('/__sview_server/' + id + '/' + requestId + '/' + operation, location.href);
            const response = await fetch(url, {
                method: 'POST', credentials: 'same-origin', signal,
                headers: { 'X-SView-Request': '1', 'Content-Type': 'application/octet-stream' },
                body: body === undefined ? '' : body
            });
            if (!response.ok) {
                const value = await response.json().catch(() => ({}));
                throw new Error(value.error || '服务器请求通道不可用');
            }
            return response;
        }
        // 超时或关闭时释放等待；用户的异步工作通过 request.signal 感知取消。
        function waitCustomServerResponse(callback, request, signal) {
            return new Promise((resolve, reject) => {
                const abort = () => reject(signal.reason || new DOMException('请求已取消', 'AbortError'));
                signal.addEventListener('abort', abort, { once: true });
                if (signal.aborted) { abort(); return; }
                Promise.resolve().then(() => callback(request)).then(resolve, reject)
                    .finally(() => signal.removeEventListener('abort', abort));
            });
        }
        function cancelCustomServerRequests(record) {
            if (!record.requests) return;
            for (const controller of record.requests.values()) controller.abort();
            record.requests.clear();
        }
        // 浏览器构造 Request 时会过滤 Host/Cookie，供服务器读取的头部保留客户端原值。
        function customServerRequestHeaders(request, headers) {
            const values = new Headers(headers);
            Object.defineProperty(request, 'headers', { value: values, enumerable: true });
            Object.defineProperty(request, 'clone', { value() {
                return customServerRequestHeaders(Request.prototype.clone.call(this), this.headers);
            } });
            return request;
        }
        async function handleCustomServerRequest(id, record, value) {
            const requestId = value.requestId;
            if (record.requests.has(requestId)) return;
            const controller = new AbortController(), signal = controller.signal;
            record.requests.set(requestId, controller);
            let reader;
            const cancelReader = () => { if (reader) reader.cancel().catch(() => {}); };
            signal.addEventListener('abort', cancelReader, { once: true });
            try {
                const init = { method: value.method, headers: value.headers, signal };
                if (value.hasBody) {
                    const body = await customServerTransfer(id, requestId, 'body', undefined, signal);
                    init.body = (await body.blob()).slice();
                }
                const request = customServerRequestHeaders(new Request(value.url, init), value.headers);
                const response = await waitCustomServerResponse(record.options.onRequest, request, signal);
                if (!(response instanceof Response) || response.status < 200 || response.status > 599 || response.bodyUsed) {
                    throw new TypeError('onRequest 必须返回未读取的 Response');
                }
                const headers = Array.from(response.headers.entries());
                if (typeof response.headers.getSetCookie === 'function') {
                    const cookies = response.headers.getSetCookie();
                    if (cookies.length) {
                        for (let index = headers.length - 1; index >= 0; index--) if (headers[index][0] === 'set-cookie') headers.splice(index, 1);
                        for (const cookie of cookies) headers.push(['set-cookie', cookie]);
                    }
                }
                await customServerTransfer(id, requestId, 'begin', JSON.stringify({ status: response.status, headers }), signal);
                if (response.body) {
                    reader = response.body.getReader();
                    if (signal.aborted) cancelReader();
                    while (!signal.aborted) {
                        const chunk = await reader.read();
                        if (chunk.done) break;
                        if (!(chunk.value instanceof Uint8Array)) throw new TypeError('响应流必须产生 Uint8Array');
                        for (let offset = 0; offset < chunk.value.byteLength; offset += 262144) {
                            await customServerTransfer(id, requestId, 'chunk', chunk.value.subarray(offset, offset + 262144), signal);
                        }
                    }
                }
                await customServerTransfer(id, requestId, 'end', undefined, signal);
            } catch (error) {
                if (!signal.aborted && !record.closed) {
                    try {
                        await customServerTransfer(id, requestId, 'fail', JSON.stringify({ message: String(error.message || error).slice(0, 2000) }), signal);
                    } catch (failure) {
                        if (!signal.aborted) invokeLocalServerCallback(record, 'error', failure);
                    }
                }
            } finally {
                signal.removeEventListener('abort', cancelReader);
                if (reader) { reader.cancel().catch(() => {}); reader.releaseLock(); }
                record.requests.delete(requestId);
            }
        }
    """
}
