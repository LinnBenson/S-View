package io.todu.sview.ui.screens

/** TCP 扫描的参数、事件顺序和结果边界。 */
internal val portScanHelp = listOf(
    InterfaceHelpEntry(
        name = "sview.network.scanPorts",
        brief = "扫描 TCP 端口",
        summary = "扫描指定主机的 TCP 端口，实时返回结果。创建任务后立即启动，可随时使用 close() 停止。",
        parameters = listOf(
            InterfaceParameter("options.host", "string", true, "单个 IPv4、IPv6 地址或域名，不接受 URL。IPv6 不加方括号；域名只解析一次，扫描系统返回的第一个地址。"),
            InterfaceParameter("options.ports", "number[] | string", true, "端口数组或逗号分隔的范围，如 [22, 80, 443] 或 '22,80,8000-8100'。范围为 1～65535，自动去重。"),
            InterfaceParameter("options.timeout", "number", false, "单个端口连接超时，1～60000 毫秒，不含排队和地址解析时间。", "1000"),
            InterfaceParameter("options.concurrency", "number", false, "并发上限为 1～32；实际值不超过端口数量。多个项目共享最多 32 个连接。", "手机 16，手表 4"),
            InterfaceParameter("options.onStart", "function(info)", false, "地址解析完成、开始连接前调用一次；info 包含 host、address、total、concurrency。启动失败不调用。"),
            InterfaceParameter("options.onResult", "function(result)", false, "每个已完成端口返回 address、port、protocol（tcp）、state、elapsedMs，按完成顺序调用。state 为 open、closed、timeout、unreachable 或 error；error 另含 code、message。"),
            InterfaceParameter("options.onProgress", "function(progress)", false, "返回 completed、total、openCount，过程进度最多每 200 毫秒一次，结束前补发最终进度。"),
            InterfaceParameter("options.onError", "function(error)", false, "任务无法继续时调用一次，返回带 code、message、summary 的 Error。端口超时、拒绝连接或不可达不触发此回调。"),
            InterfaceParameter("options.onClose", "function(summary)", false, "正常完成、主动关闭或失败后调用一次。summary.reason 为 completed、closed 或 error。之后不再触发该任务的回调。")
        ),
        resultType = "{ result: Promise<object>, close: function }",
        result = "立即返回任务句柄。await task.result 获取汇总：host、address、protocol、reason、total、completed、openPorts、counts、durationMs。openPorts 升序排列，counts 按五种端口状态计数。主动关闭返回部分汇总，未完成端口不计入统计。await task.close() 停止扫描并等待资源释放和结束通知，可以重复调用。无需读取 result 也可以只用回调。",
        failure = "参数无效、DNS 解析失败、权限错误或通道故障时调用 onError，随后 onClose，result 同时拒绝；错误的 summary 保留部分结果。主动 close 不触发 onError。回调异常仅记日志，不改变任务结果。",
        example = """const task = sview.network.scanPorts({
  host: '192.168.1.10',
  ports: '22,80,443,8000-8100',
  timeout: 1000,
  concurrency: 8,
  onStart(info) { console.log('开始扫描', info.address, info.total); },
  onResult(result) {
    if (result.state === 'open') console.log('开放端口', result.port);
  },
  onProgress(progress) { console.log(progress.completed, progress.total); },
  onError(error) { console.error(error.code, error.message); },
  onClose(summary) { console.log('扫描结束', summary.reason, summary.openPorts); }
});
// 可将 task.close() 绑定到停止按钮。
try {
  const summary = await task.result;
  console.log(summary);
} catch (error) {
  console.log(error.summary);
}""",
        note = "只建立 TCP 连接，不发送业务数据，不扫描 UDP 或自动发现网段。超时不能证明端口关闭，开放也不代表业务服务正常。每个窗口最多一个任务，全应用最多四个；DNS 最多等待 10 秒，不自动重试。结果有界缓存，网页长时间不读取时停止。页面离开和窗口关闭会释放任务，网页销毁后无法保证回调执行。",
        category = "网络"
    )
)
