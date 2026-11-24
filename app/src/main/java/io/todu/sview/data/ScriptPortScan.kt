package io.todu.sview.data

/** 扫描回调在网页执行，结果逐批消费；句柄创建不等待解析或连接。 */
internal object ScriptPortScan {
    const val SCRIPT = """
        const portScans = new Map();
        function scanPorts(options) {
            const id = crypto.randomUUID();
            let ended = false, closing = false, closePromise, ready;
            let resolveResult, rejectResult, resolveEnd;
            let completed = 0, openCount = 0, lastProgress = 0, info;
            const openPorts = [], counts = { open: 0, closed: 0, timeout: 0, unreachable: 0, error: 0 };
            const begin = performance.now();
            const result = new Promise((resolve, reject) => { resolveResult = resolve; rejectResult = reject; });
            const end = new Promise(resolve => { resolveEnd = resolve; });
            // 回调模式无需读取 result；保留原 Promise 的拒绝语义供 await 使用。
            result.catch(() => {});
            const callback = (name, value) => {
                try {
                    if (typeof options?.[name] === 'function') {
                        invokeInterfaceCallback(options[name], [value], 'scanPorts.' + name, true);
                    }
                } catch (error) { logInterfaceError(error, 'scanPorts.' + name); }
            };
            const progress = force => {
                const now = performance.now();
                if (force || now - lastProgress >= 200) {
                    lastProgress = now;
                    callback('onProgress', { completed, total: info?.total ?? 0, openCount });
                }
            };
            const finish = (summary, error) => {
                if (ended) return;
                ended = true;
                portScans.delete(task);
                completed = summary.completed;
                openCount = summary.counts.open;
                info = { ...info, total: summary.total };
                if (error) {
                    error.summary = summary;
                    callback('onError', error);
                }
                progress(true);
                callback('onClose', summary);
                if (error) rejectResult(error); else resolveResult(summary);
                resolveEnd();
            };
            const task = Object.freeze({
                result,
                close() {
                    if (ended) return Promise.resolve();
                    if (closePromise) return closePromise;
                    closing = true;
                    closePromise = (async () => {
                        await ready;
                        if (!ended) await call('network.scanPorts.close', { id });
                        await end;
                    })();
                    closePromise.catch(() => {});
                    return closePromise;
                }
            });
            portScans.set(task, () => ready.then(() => call('network.scanPorts.close', { id, discard: true })).catch(() => {}));
            // 延后开始，确保所有回调均发生在调用方取得句柄之后。
            ready = Promise.resolve().then(async () => {
                if (!options || typeof options !== 'object' || Array.isArray(options)) throw invalidArgument('扫描参数必须是对象');
                for (const name of ['onStart', 'onResult', 'onProgress', 'onError', 'onClose']) {
                    if (options[name] !== undefined && typeof options[name] !== 'function') throw invalidArgument(name + ' 必须是函数');
                }
                await call('network.scanPorts', {
                    id, host: options.host, ports: options.ports,
                    timeout: options.timeout, concurrency: options.concurrency
                });
            });
            (async () => {
                try {
                    await ready;
                    if (closing) await call('network.scanPorts.close', { id });
                    while (!ended) {
                        const batch = await call('network.scanPorts.next', { id });
                        for (const event of batch.events) {
                            if (event.kind === 'start') { info = { ...event.value }; callback('onStart', event.value); }
                            else if (event.kind === 'result') {
                                completed++;
                                counts[event.value.state]++;
                                if (event.value.state === 'open') { openCount++; openPorts.push(event.value.port); }
                                callback('onResult', event.value);
                            }
                        }
                        if (batch.summary) {
                            const data = batch.summary.error;
                            finish(batch.summary, data ? Object.assign(new Error(data.message), { code: data.code }) : null);
                        } else if (info) progress(false);
                    }
                } catch (caught) {
                    const error = caught instanceof Error ? caught : new Error(String(caught));
                    error.code ||= 'BRIDGE_ERROR';
                    // 通道失败也尝试停止原生任务；失败记录只能汇总到网页已收到的位置。
                    try { await call('network.scanPorts.close', { id, discard: true }); } catch (_) { }
                    finish({ host: typeof options?.host === 'string' ? options.host : null,
                        address: info?.address ?? null, protocol: 'tcp', reason: 'error',
                        total: info?.total ?? 0, completed, counts: { ...counts },
                        openPorts: openPorts.sort((a, b) => a - b), durationMs: Math.round(performance.now() - begin),
                        error: { code: error.code, message: error.message } }, error);
                }
            })();
            return task;
        }
        addEventListener('pagehide', () => {
            for (const stop of portScans.values()) stop();
        });
    """
}
