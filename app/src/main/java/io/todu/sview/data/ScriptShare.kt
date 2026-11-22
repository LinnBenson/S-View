package io.todu.sview.data

/** 第一次调用创建标准 File 快照，随后复用同一份内容；未通过分享启动时返回空对象结构。 */
object ScriptShare {
    const val SCRIPT = """
        let sharedContent;
        async function readSharedResource(path) {
            const response = await fetch(path, { credentials: 'same-origin', cache: 'no-store' });
            if (!response.ok) throw new Error('无法读取分享内容');
            return response;
        }
        function getSharedContent() {
            if (!sharedContent) sharedContent = (async () => {
                const metadata = await (await readSharedResource('/__sview_share')).json();
                const files = [];
                let total = 0;
                for (let index = 0; index < metadata.files.length; index++) {
                    const item = metadata.files[index];
                    total += item.size;
                    if (total > 32 * 1024 * 1024) throw new Error('分享文件总大小不能超过 32 MB');
                    const data = await (await readSharedResource('/__sview_share/' + index)).arrayBuffer();
                    if (data.byteLength !== item.size) throw new Error('分享文件内容不完整');
                    files.push(new File([data], item.name, { type: item.type, lastModified: item.lastModified }));
                }
                return Object.freeze({ text: metadata.text, title: metadata.title, files: Object.freeze(files) });
            })().catch(error => { sharedContent = undefined; throw error; });
            return sharedContent;
        }
    """
}
