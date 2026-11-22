/** 文件接口保持网页原生数据类型，正文按设备选择有界分块传输。 */
(() => {
    'use strict';
    const invalidArgument = message => Object.assign(new TypeError(message), { code: 'INVALID_ARGUMENT' });
    const CHUNK = __SVIEW_TRANSFER_CHUNK__;
    const active = new Set();
    const readers = new Set();
    let closed = false;

    function pathValue(path) {
        if (typeof path !== 'string') throw invalidArgument('路径必须是字符串');
        return path;
    }

    async function bridge(operation, body, keepalive = false) {
        if (closed && !keepalive) throw Object.assign(new Error('运行窗口已关闭'), { code: 'CANCELLED' });
        const response = await fetch('/__sview_storage/' + operation, {
            method: 'POST', credentials: 'same-origin',
            headers: { 'X-SView-Request': '1' }, body, keepalive
        });
        if (!response.ok) {
            const result = await response.json().catch(() => ({}));
            throw Object.assign(new Error(result.error || '文件操作失败：' + response.status), { code: result.code || 'BRIDGE_ERROR' });
        }
        return response;
    }

    async function command(operation, args) {
        const response = await bridge(operation, JSON.stringify(args));
        return (await response.json()).value;
    }

    async function release(id) {
        active.delete(id);
        await bridge('cancel', JSON.stringify({ id }), true).catch(() => {});
    }

    /** 页面销毁过程中 start 仍可能返回句柄，收到后立即回收。 */
    async function start(operation, args) {
        const result = await command(operation, args);
        if (closed) { await release(result.id); throw Object.assign(new Error('运行窗口已关闭'), { code: 'CANCELLED' }); }
        active.add(result.id);
        return result;
    }

    function readStream(id) {
        let ended = false;
        return new ReadableStream({
            async pull(controller) {
                try {
                    const response = await bridge('read/' + id);
                    const bytes = new Uint8Array(await response.arrayBuffer());
                    if (ended) return;
                    if (bytes.length) controller.enqueue(bytes);
                    else { ended = true; active.delete(id); controller.close(); }
                } catch (error) {
                    if (!ended) { ended = true; controller.error(error); }
                    await release(id);
                }
            },
            async cancel() { ended = true; await release(id); }
        }, { highWaterMark: 0 });
    }

    function byteView(value) {
        if (value instanceof ArrayBuffer) return new Uint8Array(value);
        if (ArrayBuffer.isView(value)) return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
        throw invalidArgument('数据块必须是 ArrayBuffer 或 TypedArray');
    }

    /** 只消费有效视图范围，避免保存 TypedArray 之外的底层缓冲区内容。 */
    async function* chunks(data) {
        if (data instanceof Blob) data = data.stream();
        if (data instanceof ReadableStream) {
            const reader = data.getReader();
            readers.add(reader);
            let finished = false;
            try {
                while (true) {
                    if (closed) throw Object.assign(new Error('运行窗口已关闭'), { code: 'CANCELLED' });
                    const next = await reader.read();
                    if (next.done) { finished = true; break; }
                    const bytes = byteView(next.value);
                    for (let offset = 0; offset < bytes.length; offset += CHUNK) yield bytes.subarray(offset, offset + CHUNK);
                }
            } finally {
                readers.delete(reader);
                if (!finished) { try { Promise.resolve(reader.cancel()).catch(() => {}); } catch (_) { } }
                reader.releaseLock();
            }
        } else {
            const bytes = byteView(data);
            for (let offset = 0; offset < bytes.length; offset += CHUNK) yield bytes.subarray(offset, offset + CHUNK);
        }
    }

    async function save(path, data, append = false) {
        pathValue(path);
        if (!(data instanceof Blob || data instanceof ReadableStream || data instanceof ArrayBuffer || ArrayBuffer.isView(data))) {
            throw invalidArgument('保存数据必须是 File、Blob、ArrayBuffer、TypedArray 或字节流');
        }
        const { id } = await start('writeStart', { path, append });
        try {
            for await (const bytes of chunks(data)) await bridge('write/' + id, bytes);
            const result = await command('commit', { id });
            active.delete(id);
            return result;
        } catch (error) { await release(id); throw error; }
    }

    async function open(path, type = 'file') {
        pathValue(path);
        if (!['file', 'stream'].includes(type)) throw invalidArgument('打开类型只能是 file 或 stream');
        const metadata = await start('readStart', { path, stream: type === 'stream' });
        const stream = readStream(metadata.id);
        if (type === 'stream') return stream;
        // 默认 File 是完整内容快照；超过限额的文件改用按需读取的 stream。
        const reader = stream.getReader();
        const parts = [];
        let size = 0;
        try {
            while (true) {
                const next = await reader.read();
                if (next.done) break;
                size += next.value.byteLength;
                if (size > 32 * 1024 * 1024) throw new Error("文件超过 32 MB，请使用 open(path, 'stream')");
                parts.push(next.value);
            }
            return new File(parts, metadata.name, { type: metadata.type, lastModified: metadata.lastModified });
        } catch (error) { await reader.cancel().catch(() => {}); throw error; }
        finally { reader.releaseLock(); }
    }

    const storage = { open,
        async copy(source, destination, options = {}) {
            pathValue(source); pathValue(destination);
            if (!options || typeof options !== 'object' || Array.isArray(options)) throw invalidArgument('复制选项必须是对象');
            if (Object.keys(options).some(key => key !== 'overwrite')) throw invalidArgument('复制仅支持 overwrite 选项');
            const overwrite = options.overwrite === undefined ? false : options.overwrite;
            if (typeof overwrite !== 'boolean') throw invalidArgument('overwrite 必须是 boolean');
            return command('copy', { source, destination, overwrite });
        }, save: (path, data) => save(path, data),
        write(path, text, append = false) {
            if (typeof text !== 'string' || typeof append !== 'boolean') return Promise.reject(invalidArgument('文本必须是字符串，追加参数必须是 boolean'));
            return save(path, new Blob([text], { type: 'text/plain;charset=utf-8' }), append);
        }
    };
    for (const method of ['createDirectory', 'deleteDirectory', 'hasDirectory', 'hasFile', 'list', 'deleteFile', 'createFile']) {
        storage[method] = async path => command(method, { path: pathValue(path) });
    }
    Object.assign(sview.storage, storage);
    addEventListener('pagehide', () => {
        closed = true;
        for (const reader of readers) {
            try { Promise.resolve(reader.cancel()).catch(() => {}); } catch (_) { }
        }
        for (const id of active) release(id);
    });
    addEventListener('pageshow', event => { if (event.persisted) closed = false; });
})();
