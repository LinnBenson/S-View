package io.todu.sview.ui.screens

/** 网页标准媒体接口的授权、示例和窗口释放约定。 */
internal val webMediaHelp = listOf(
    InterfaceHelpEntry(
        name = "navigator.mediaDevices.getUserMedia", brief = "网页使用麦克风和相机",
        summary = "使用浏览器标准接口采集音频或视频。先在手机或手表的设置 → 权限中允许麦克风、相机；启动采集时运行窗口须位于前台。只允许当前项目本地页面使用，外部网页不授权。",
        parameters = listOf(
            InterfaceParameter("constraints", "object", true, "标准媒体约束，例如 { audio: true } 或 { video: true, audio: true }。"),
            InterfaceParameter("constraints.audio", "boolean | object", false, "是否采集音频，也可设置设备、回声消除等标准约束。", "false"),
            InterfaceParameter("constraints.video", "boolean | object", false, "是否采集视频，也可设置镜头朝向、尺寸等标准约束。", "false")
        ),
        resultType = "Promise<MediaStream>", result = "返回媒体流。可赋给 video.srcObject 预览，或传给 MediaRecorder 录制；保存文件需通过现有文件接口另行处理。",
        failure = "权限未授予或页面不被允许时拒绝；无对应设备、设备被占用、系统隐私开关关闭或约束不支持时，也可能拒绝。错误名称由浏览器返回，例如 NotAllowedError、NotFoundError、NotReadableError、OverconstrainedError。",
        example = """const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
let recorder;
try {
    recorder = new MediaRecorder(stream);
    const chunks = [];
    recorder.ondataavailable = event => {
        if (event.data.size) chunks.push(event.data);
    };
    recorder.onstop = () => {
        stream.getTracks().forEach(track => track.stop());
        const blob = new Blob(chunks, { type: recorder.mimeType });
        console.log('录音字节数', blob.size);
    };
    recorder.onerror = () => stream.getTracks().forEach(track => track.stop());
    recorder.start();
} catch (error) {
    stream.getTracks().forEach(track => track.stop());
    throw error;
}
// 结束录音：recorder.stop();
// 仅释放采集：stream.getTracks().forEach(track => track.stop());""",
        note = "recorder.stop() 结束编码，不等于释放麦克风，仍须停止媒体轨道。窗口关闭、页面崩溃或被系统终止时，原生会销毁网页会话释放采集，不依赖脚本执行清理；未保存的录音可能丢失。没有硬件时授权也无法采集。后台录制受系统限制，不提供后台采集服务。录制格式取决于浏览器支持，可用 MediaRecorder.isTypeSupported() 检查。"
    )
)
