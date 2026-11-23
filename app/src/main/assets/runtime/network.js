/** 原生网络的网页封装：只传递元数据和有界二进制块，不把文件编码成 Base64。 */
(() => {
    'use strict';
    const invalidArgument = message => Object.assign(new TypeError(message), { code: 'INVALID_ARGUMENT' });
    const CHUNK = __SVIEW_TRANSFER_CHUNK__;
    const active = new Set();
    const waiting = new Set();
    let transmitting = 0;
    // 自动排队而不是拒绝正常的并发调用，限制同时传输以控制手表端占用。
    function acquire(signal) {
        return new Promise((resolve, reject) => {
            const item = {
                start() {
                    signal.removeEventListener('abort', abort);
                    transmitting++;
                    let released = false;
                    resolve(() => {
                        if (released) return;
                        released = true;
                        transmitting--;
                        const next = waiting.values().next().value;
                        if (next) { waiting.delete(next); next.start(); }
                    });
                }
            };
            function abort() {
                waiting.delete(item);
                reject(error('请求已取消', 'CANCELLED'));
            }
            if (signal.aborted) { abort(); return; }
            if (transmitting < 4) item.start();
            else { waiting.add(item); signal.addEventListener('abort', abort, { once: true }); }
        });
    }
    const encoder = new TextEncoder();
    const error = (message, code) => Object.assign(new Error(message), { code });

    async function bridge(path, body = new Uint8Array(), signal, keepalive = false) {
        const response = await fetch('/__sview_network/' + path, {
            method: 'POST', credentials: 'same-origin',
            headers: { 'X-SView-Request': '1' }, body, signal, keepalive
        });
        if (!response.ok) {
            const result = await response.json().catch(() => ({}));
            throw error(result.error || '原生网络连接失败', result.code || 'BRIDGE_ERROR');
        }
        return response;
    }

    /** 将 TypedArray 限定到其实际视图范围，避免上传底层缓冲区的其他数据。 */
    function bytes(value) {
        if (value instanceof ArrayBuffer) return new Uint8Array(value);
        if (ArrayBuffer.isView(value)) return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
        throw invalidArgument('流只能产生 ArrayBuffer 或 TypedArray 数据块');
    }

    /** 根据服务器文件声明判断，不预读流，也不把 URL 后缀当作文件证据。 */
    function fileResponse(headers) {
        const disposition = headers['content-disposition'] || '';
        if (/^\s*attachment(?:\s*;|\s*$)/i.test(disposition) || /(?:^|;)\s*filename\*?\s*=/i.test(disposition)) return true;
        const type = (headers['content-type'] || '').split(';', 1)[0].trim().toLowerCase();
        if (/^(image|audio|video|font)\//.test(type)) return true;
        return ['application/octet-stream', 'application/pdf', 'application/zip',
            'application/gzip', 'application/x-gzip', 'application/x-tar',
            'application/x-7z-compressed', 'application/vnd.rar', 'application/x-rar-compressed',
            'application/msword', 'application/vnd.ms-excel', 'application/vnd.ms-powerpoint',
            'application/rtf', 'application/wasm', 'application/vnd.android.package-archive'].includes(type) ||
            type.startsWith('application/vnd.openxmlformats-officedocument.') ||
            type.startsWith('application/vnd.oasis.opendocument.');
    }

    function request(address) {
        const url = new URL(address, location.href);
        if (!['http:', 'https:'].includes(url.protocol)) throw invalidArgument('仅支持 HTTP 和 HTTPS');
        const config = { url: url.href, method: 'GET', headers: {}, cookies: true, timeout: 15000 };
        const headers = new Headers();
        const controller = new AbortController();
        const readers = new Set();
        const files = [];
        let body, responseType = 'auto', uploadCallback, downloadCallback;
        let id, promise, cancelled = false, automaticType = false, finished = false, deadline, releaseSlot, streamFailure;
        let cancellationError = error('请求已取消', 'CANCELLED');

        function editable() {
            if (promise) throw new Error('请求已发送，不能修改配置');
        }
        function ensureActive() {
            if (cancelled) throw cancellationError;
        }
        function notify(callback, loaded, total) {
            if (!callback) return;
            callback({ loaded, total: total >= 0 ? total : null,
                percent: total > 0 ? Math.min(100, loaded / total * 100) : total === 0 ? 100 : null });
        }
        async function release() {
            if (finished) return;
            finished = true;
            active.delete(builder);
            clearTimeout(deadline);
            for (const reader of readers) {
                // 用户自定义流的 cancel 可能永不完成，不能让它拖住原生资源释放。
                try { Promise.resolve(reader.cancel()).catch(() => {}); } catch (_) { }
            }
            readers.clear();
            // 已结束的构建器仍可能被脚本保存，释放它不再需要的上传数据与回调。
            body = undefined;
            files.length = 0;
            uploadCallback = downloadCallback = undefined;
            if (id) await bridge(id + '/cancel', undefined, undefined, true).catch(() => {});
            releaseSlot?.();
            releaseSlot = undefined;
        }
        async function* streamChunks(stream) {
            const reader = stream.getReader();
            readers.add(reader);
            let ended = false;
            try {
                while (true) {
                    ensureActive();
                    const item = await reader.read();
                    if (item.done) { ended = true; break; }
                    const value = bytes(item.value);
                    for (let offset = 0; offset < value.byteLength; offset += CHUNK) {
                        yield value.subarray(offset, offset + CHUNK);
                    }
                }
            } finally {
                readers.delete(reader);
                if (!ended) { try { Promise.resolve(reader.cancel()).catch(() => {}); } catch (_) { } }
                reader.releaseLock();
            }
        }
        async function* valueChunks(value) {
            if (value instanceof Blob) { yield* streamChunks(value.stream()); return; }
            if (value instanceof ReadableStream) { yield* streamChunks(value); return; }
            if (typeof value === 'string') {
                // 分段编码并复用缓冲区，encodeInto 不会拆开 UTF-16 代理对。
                const buffer = new Uint8Array(CHUNK);
                let offset = 0;
                while (offset < value.length) {
                    let end = Math.min(value.length, offset + CHUNK);
                    const last = value.charCodeAt(end - 1);
                    if (end < value.length && last >= 0xD800 && last <= 0xDBFF) end++;
                    const { read, written } = encoder.encodeInto(value.slice(offset, end), buffer);
                    offset += read;
                    yield buffer.subarray(0, written);
                }
                return;
            }
            const data = bytes(value);
            for (let offset = 0; offset < data.byteLength; offset += CHUNK) yield data.subarray(offset, offset + CHUNK);
        }
        /** 计算 UTF-8 长度不创建整份编码副本，孤立代理字符与 TextEncoder 一样按替换字符计数。 */
        function utf8Size(value) {
            let size = 0;
            for (let i = 0; i < value.length; i++) {
                const c = value.charCodeAt(i);
                if (c < 0x80) size++;
                else if (c < 0x800) size += 2;
                else if (c >= 0xD800 && c <= 0xDBFF && value.charCodeAt(i + 1) >= 0xDC00 && value.charCodeAt(i + 1) <= 0xDFFF) { size += 4; i++; }
                else size += 3;
            }
            return size;
        }
        function sizeOf(value) {
            if (value instanceof Blob) return value.size;
            if (value instanceof ReadableStream) return -1;
            return typeof value === 'string' ? utf8Size(value) : bytes(value).byteLength;
        }
        function quoted(value) {
            return String(value).replace(/\r/g, '%0D').replace(/\n/g, '%0A').replace(/"/g, '%22');
        }

        /** FormData 逐项输出，保留同名字段、文件名和类型；项目文件也按流读取。 */
        function multipart(form) {
            const boundary = 'sview-' + Array.from(crypto.getRandomValues(new Uint8Array(18)), n => n.toString(16).padStart(2, '0')).join('');
            headers.set('Content-Type', 'multipart/form-data; boundary=' + boundary);
            const parts = [];
            for (const [name, value] of form.entries()) {
                const file = value instanceof Blob;
                const disposition = 'Content-Disposition: form-data; name="' + quoted(name) + '"' +
                    (file ? '; filename="' + quoted(value.name || 'blob') + '"' : '');
                const type = file ? '\r\nContent-Type: ' + (value.type || 'application/octet-stream').replace(/[\r\n]/g, '') : '';
                parts.push({ header: '--' + boundary + '\r\n' + disposition + type + '\r\n\r\n', value });
            }
            for (const file of files) {
                parts.push({ header: '--' + boundary + '\r\nContent-Disposition: form-data; name="' + quoted(file.field) +
                    '"; filename="' + quoted(file.name) + '"\r\nContent-Type: ' + file.type + '\r\n\r\n', file });
            }
            const ending = '--' + boundary + '--\r\n';
            let size = encoder.encode(ending).length;
            for (const part of parts) {
                if (part.file) { size = -1; break; }
                size += encoder.encode(part.header).length + sizeOf(part.value) + 2;
            }
            async function* chunks() {
                for (const part of parts) {
                    yield* valueChunks(part.header);
                    if (part.file) {
                        const response = await bridge('file', JSON.stringify({ path: part.file.path }), controller.signal);
                        yield* streamChunks(response.body);
                    } else yield* valueChunks(part.value);
                    yield encoder.encode('\r\n');
                }
                yield* valueChunks(ending);
            }
            return { size, chunks: chunks() };
        }

        function prepare() {
            if (body instanceof FormData || files.length) {
                if (body !== undefined && !(body instanceof FormData)) throw invalidArgument('项目文件请与 form() 一起使用');
                return multipart(body || new FormData());
            }
            if (body === undefined || body === null) return null;
            let value = body;
            if (value instanceof URLSearchParams) {
                value = value.toString();
                if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/x-www-form-urlencoded;charset=UTF-8');
            } else if (Object.prototype.toString.call(value) === '[object Object]' || Array.isArray(value)) {
                value = JSON.stringify(value);
                if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/json;charset=UTF-8');
            } else if (value instanceof Blob && value.type && !headers.has('Content-Type')) headers.set('Content-Type', value.type);
            return { size: sizeOf(value), chunks: valueChunks(value) };
        }

        /** 消费原生下载块时再申请下一块；取消 ReadableStream 会取消原生请求。 */
        function responseStream() {
            return new ReadableStream({
                async pull(target) {
                    try {
                        ensureActive();
                        const response = await bridge(id + '/read', undefined, controller.signal);
                        const loaded = Number(response.headers.get('X-SView-Loaded'));
                        const total = Number(response.headers.get('X-SView-Total'));
                        const end = response.headers.get('X-SView-End') === 'true';
                        const data = new Uint8Array(await response.arrayBuffer());
                        notify(downloadCallback, loaded, total);
                        if (end) { id = undefined; target.close(); await release(); }
                        else if (data.length) target.enqueue(data);
                    } catch (failure) {
                        // Gecko 的 Response 消费流时可能替换异常，保留原始失败原因。
                        streamFailure = cancelled ? cancellationError : failure;
                        target.error(streamFailure);
                        await release();
                    }
                },
                cancel() { return builder.cancel(); }
            }, { highWaterMark: 0 });
        }

        async function execute() {
            ensureActive();
            active.add(builder);
            if (config.timeout > 0) deadline = setTimeout(() => stop(error('请求超时', 'TIMEOUT')), config.timeout);
            try {
                releaseSlot = await acquire(controller.signal);
                ensureActive();
                const prepared = prepare();
                if (prepared && ['GET', 'HEAD'].includes(config.method)) throw invalidArgument('GET 和 HEAD 不能设置请求体');
                config.url = url.href;
                config.headers = Object.fromEntries(headers.entries());
                config.hasBody = Boolean(prepared);
                config.size = prepared ? prepared.size : 0;
                // 创建请求不能因网页取消而丢失返回的任务编号；取得编号后再立即清理。
                const created = await bridge('create', JSON.stringify(config));
                id = (await created.json()).id;
                ensureActive();
                if (prepared) {
                    for await (const chunk of prepared.chunks) {
                        ensureActive();
                        const response = await bridge(id + '/upload', chunk, controller.signal);
                        const progress = await response.json();
                        notify(uploadCallback, progress.loaded, progress.total);
                    }
                    await bridge(id + '/end', undefined, controller.signal);
                }
                const metadata = await (await bridge(id + '/meta', undefined, controller.signal)).json();
                const successful = metadata.status >= 200 && metadata.status < 300;
                const result = { status: metadata.status, headers: metadata.headers, url: metadata.url,
                    data: null, isJson: () => false, isFifle: () => fileResponse(metadata.headers) };
                if (config.download && successful) {
                    while (true) {
                        const response = await bridge(id + '/read', undefined, controller.signal);
                        const loaded = Number(response.headers.get('X-SView-Loaded'));
                        notify(downloadCallback, loaded, Number(response.headers.get('X-SView-Total')));
                        await response.arrayBuffer();
                        if (response.headers.get('X-SView-End') === 'true') {
                            result.isFifle = () => true;
                            result.path = metadata.downloadPath || config.download;
                            result.size = loaded;
                            break;
                        }
                    }
                    id = undefined;
                    await release();
                    return result;
                }
                const stream = responseStream();
                if (responseType === 'stream' && successful) {
                    result.data = stream;
                    return result;
                }
                const content = new Response(stream, { headers: metadata.headers });
                if (responseType === 'blob' && successful) result.data = await content.blob();
                else if (responseType === 'arrayBuffer' && successful) result.data = await content.arrayBuffer();
                else {
                    const text = await content.text();
                    if (responseType === 'text') {
                        // 显式文本不提前创建整棵 JSON；需要判断时再解析，结果仍与旧接口相同。
                        let valid;
                        result.isJson = () => {
                            if (valid === undefined) { try { JSON.parse(text); valid = true; } catch (_) { valid = false; } }
                            return valid;
                        };
                        result.data = text;
                    } else {
                        let parsed, valid = false;
                        try { parsed = JSON.parse(text); valid = true; } catch (_) { }
                        result.isJson = () => valid;
                        result.data = valid ? parsed : text;
                        if (successful && responseType === 'json' && !valid) {
                            throw Object.assign(error('响应不是合法 JSON', 'INVALID_JSON'), { response: result, status: result.status });
                        }
                    }
                }
                if (!successful) throw Object.assign(error('HTTP ' + metadata.status, 'HTTP_ERROR'), { status: metadata.status, response: result });
                return result;
            } catch (failure) {
                await release();
                if (cancelled) throw cancellationError;
                throw streamFailure || failure;
            }
        }

        async function stop(reason) {
            if (finished || cancelled) return;
            cancelled = true;
            cancellationError = reason;
            controller.abort();
            for (const reader of readers) {
                try { Promise.resolve(reader.cancel()).catch(() => {}); } catch (_) { }
            }
            // create 返回前不能丢弃任务编号，execute 收到编号后会完成原生取消。
            if (id || !promise) await release();
        }

        const builder = {
            method(value) {
                editable();
                if (typeof value !== 'string' || !/^[a-z]+$/i.test(value)) throw invalidArgument('请求方法无效');
                config.method = value.toUpperCase(); return builder;
            },
            query(values) {
                editable();
                for (const [key, value] of Object.entries(values)) {
                    url.searchParams.delete(key);
                    for (const item of Array.isArray(value) ? value : [value]) if (item != null) url.searchParams.append(key, String(item));
                }
                return builder;
            },
            header(name, value) {
                editable();
                const changes = new Headers(typeof name === 'string' ? [[name, value]] : name);
                changes.forEach((value, name) => headers.set(name, value));
                if (changes.has('Content-Type')) automaticType = false;
                return builder;
            },
            headers(values) { return builder.header(values); },
            ua(value) { return builder.header('User-Agent', value); },
            cookie(value = true) {
                editable();
                if (typeof value === 'boolean') {
                    config.cookies = value;
                    if (!value) headers.delete('Cookie');
                } else if (typeof value === 'string') headers.set('Cookie', value);
                else if (value && Object.prototype.toString.call(value) === '[object Object]') {
                    const parts = Object.entries(value).map(([name, content]) => {
                        if (/[;=\s]/.test(name) || /[;\r\n]/.test(String(content))) throw invalidArgument('Cookie 名称或值无效');
                        return name + '=' + String(content);
                    });
                    headers.set('Cookie', parts.join('; '));
                } else throw invalidArgument('Cookie 必须是布尔值、字符串或对象');
                return builder;
            },
            data(value) {
                editable();
                body = value;
                if (automaticType) headers.delete('Content-Type');
                automaticType = false;
                return builder;
            },
            body(value) { return builder.data(value); },
            json(value) {
                const text = JSON.stringify(value);
                if (text === undefined) throw invalidArgument('数据不能转为 JSON');
                builder.data(text).header('Content-Type', 'application/json;charset=UTF-8');
                automaticType = true; return builder;
            },
            form(value) {
                editable();
                if (value instanceof FormData) return builder.data(value);
                if (typeof HTMLFormElement !== 'undefined' && value instanceof HTMLFormElement) return builder.data(new FormData(value));
                if (!value || Object.prototype.toString.call(value) !== '[object Object]') throw invalidArgument('表单格式无效');
                if (files.length || Object.values(value).some(item => item instanceof Blob || (Array.isArray(item) && item.some(x => x instanceof Blob)))) {
                    const form = new FormData();
                    for (const [key, item] of Object.entries(value)) for (const entry of Array.isArray(item) ? item : [item]) if (entry != null) form.append(key, entry);
                    return builder.data(form);
                }
                const form = new URLSearchParams();
                for (const [key, item] of Object.entries(value)) for (const entry of Array.isArray(item) ? item : [item]) if (entry != null) form.append(key, String(entry));
                return builder.data(form);
            },
            file(field, path, name = path.split('/').pop(), type = 'application/octet-stream') {
                editable();
                if (typeof field !== 'string' || typeof path !== 'string' || /[\r\n]/.test(type)) throw invalidArgument('文件参数无效');
                if (body instanceof URLSearchParams) {
                    const form = new FormData(); for (const [key, value] of body) form.append(key, value); body = form;
                }
                files.push({ field, path, name, type }); return builder;
            },
            response(value) {
                editable();
                if (!['auto', 'json', 'text', 'blob', 'arrayBuffer', 'stream'].includes(value)) throw invalidArgument('响应格式无效');
                responseType = value; return builder;
            },
            download(path) {
                editable();
                if (typeof path !== 'string' || !path.trim()) throw invalidArgument('下载路径无效');
                config.download = path; return builder;
            },
            timeout(value) {
                editable();
                if (!Number.isInteger(value) || value < 0 || value > 2147483647) throw invalidArgument('超时必须是非负整数毫秒数');
                config.timeout = value; return builder;
            },
            onUploadProgress(callback) {
                editable(); if (typeof callback !== 'function') throw invalidArgument('进度回调必须是函数');
                uploadCallback = callback; return builder;
            },
            onDownloadProgress(callback) {
                editable(); if (typeof callback !== 'function') throw invalidArgument('进度回调必须是函数');
                downloadCallback = callback; return builder;
            },
            cancel() { return stop(error('请求已取消', 'CANCELLED')); },
            send() {
                if (!promise) promise = Promise.resolve().then(execute);
                return promise;
            }
        };
        return builder;
    }
    addEventListener('pagehide', () => { for (const task of active) task.cancel(); });
    sview.network.request = request;
})();
