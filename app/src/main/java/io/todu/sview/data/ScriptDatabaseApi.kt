package io.todu.sview.data

/** 在 JSON 序列化前拒绝会被静默转换或丢弃的值，避免保存后类型发生变化。 */
object ScriptDatabaseApi {
    const val SCRIPT = """{
        async get(key) { checkKey(key); return call('script.db.get', { key }); },
        async set(key, value) {
            checkKey(key);
            if (!['boolean', 'string', 'number'].includes(typeof value) && (value === null || typeof value !== 'object')) {
                throw invalidArgument('值仅支持 boolean、string、number、array、object');
            }
            const encoded = JSON.stringify(value, function(name, item) {
                if (typeof item === 'number' && !Number.isFinite(item)) throw invalidArgument('数字必须为有限值');
                if (['undefined', 'function', 'symbol', 'bigint'].includes(typeof item)) throw invalidArgument('对象和数组仅支持 JSON 数据');
                return item;
            });
            await call('script.db.set', { key, value: JSON.parse(encoded) });
        },
        async remove(key) { checkKey(key); return call('script.db.remove', { key }); },
        getAll() { return call('script.db.getAll', {}); }
    }"""
    const val KEY_CHECK = """function checkKey(key) {
        if (typeof key !== 'string' || !key.trim() || key.length > 128 || /[\u0000-\u001f\u007f-\u009f]/.test(key)) {
            throw invalidArgument('键应为 1 到 128 个字符，不能包含控制字符');
        }
    }"""
}
