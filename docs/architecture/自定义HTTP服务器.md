# 自定义 HTTP 服务器

`sview.network.server(options)` 必填 home，必须指向真实存在且可读取的 HTML 文件，支持项目路径和 device:/ 路径。设备首页通过 StoragePaths 和 ProjectRepository.deviceStorageRoot 复用设备存储授权、挂载与路径校验，启动及每次读取首页时重新校验；不公开设备首页所在目录。`/`（含查询参数）只读取该首页；其他路径按项目根目录查找真实文件，未命中才触发 onRequest。静态文件仅允许 GET/HEAD，不列目录，不加载全局模板，不注入运行时接口。路径解码保留加号，规范路径校验阻止越界与指向项目外的符号链接。

ScriptLocalServers 复用窗口事件连接和生命周期；ScriptCustomServer 构建标准 Request 并接收标准 Response。事件只包含请求元数据。请求体写入独立缓存文件，网页通过经过会话与 Origin 验证的 /__sview_server/ 通道读取；响应分块回传，每块最多 256 KiB，原生接收完后发送。文件响应直接从项目文件分块发送，避免 Base64 和大字符串复制。首版不提供持续流式响应、WebSocket 或 HTTPS。

CustomServerApiBridge 负责创建、关闭与内部正文通道；CustomHttpSession 负责资源路由、网络失效和请求生命周期；CustomHttpExchange 管理单个请求的缓存与响应完成状态。LanHttpTransport 与聊天、上传服务共用，自定义配置开放常用 HTTP 方法和响应头，保留精确 Host 校验；跨域许可由脚本返回的 CORS 头决定。Content-Length、Transfer-Encoding 和逐跳响应头由传输层管理。

默认监听当前 Wi-Fi IPv4、端口 8080；地址规则复用 LanAddress。默认请求期限 30000 ms、正文上限 16 MiB、并发连接上限 4；可分别配置 1000–300000 ms、1–512 MiB、1–16。正文支持 Content-Length 和 chunked，请求期限覆盖读取头部、正文、等待脚本和发送响应；每个连接只有一个期限任务，空闲服务不轮询。动态处理超时返回 504，正文超限返回 413，并发超限返回 503，脚本错误返回不含堆栈的 500。

启动失败通知 onError 并拒绝 Promise；单个请求失败不关闭整个服务。等待请求完成或超时后清理缓存并通知网页释放状态；超时和服务器关闭会取消 request.signal。网络地址失效、主动关闭和窗口销毁都释放端口、连接和临时文件，onClose 最多通知一次。网页已销毁或进程被终止时无法保证回调。取消是协作式的，不能强行中断用户脚本中的同步循环。
