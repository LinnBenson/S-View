package io.todu.sview.data

/** 先提交当前页面尚未发送的 console 批次，再等待原生日志写入屏障。 */
internal object ScriptLogApi {
    const val SCRIPT = """
        async function readProjectLog(projectName) {
            if (projectName !== undefined && (typeof projectName !== 'string' || !projectName.trim())) {
                throw invalidArgument('项目名称应为非空字符串');
            }
            await new Promise(resolve => dispatchEvent(new CustomEvent('__sview_flush_logs', { detail: resolve })));
            return call('script.log', projectName === undefined ? {} : { name: projectName });
        }
    """
}
