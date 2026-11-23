package io.todu.sview.ui.screens

/** 首页和静态资源优先，动态请求使用网页标准 Request/Response。 */
internal val customServerInterfaceHelp = InterfaceHelpEntry(
    name = "sview.network.server",
    brief = "创建自定义 HTTP 服务器",
    summary = "访问 / 返回 home 指定的 HTML。其他路径先查找项目文件，找不到时调用 onRequest；未配置回调则返回 404。页面使用原始文件，不注入 sview 系统接口。",
    parameters = listOf(
        InterfaceParameter("options.home", "string", true, "已有的 HTML 文件路径，例如 /home.html 或 device:/Download/home.html。device:/ 需要允许设备存储访问；普通路径从项目根目录解析，不接受外部网址。"),
        InterfaceParameter("options.server", "string", false, "空字符串选择当前 Wi-Fi IPv4；10.* 按前缀选择本机网卡；完整 IPv4 必须属于本机。0.0.0.0 监听所有网卡。", "''"),
        InterfaceParameter("options.port", "number", false, "监听端口，1024–65535；不同服务使用不同端口。", "8080"),
        InterfaceParameter("options.onRequest", "function", false, "接收标准 Request，返回 Response 或 Promise<Response>。可读取 url、method、headers，并使用 text()、json()、formData()、blob()、arrayBuffer() 读取正文。request.signal 在超时或服务关闭时取消。"),
        InterfaceParameter("options.onError", "function", false, "接收 { message, code?, requestId? }。启动失败同时拒绝 Promise；单个请求失败不会关闭服务器。"),
        InterfaceParameter("options.onClose", "function", false, "主动关闭或网络失效时调用一次；启动失败不触发。"),
        InterfaceParameter("options.timeout", "number", false, "请求超时毫秒数，1000–300000。等待动态响应超时返回 504；连接超过期限会断开。", "30000"),
        InterfaceParameter("options.maxBodySizeMB", "number", false, "请求正文上限，1–512 MiB，超过返回 413。手表上建议保持较小值。", "16"),
        InterfaceParameter("options.maxConcurrentRequests", "number", false, "同时处理的连接数，1–16，包含静态文件；超过返回 503。", "4")
    ),
    resultType = "Promise<object>",
    result = "返回只读 { url, server, port, close }。server 为实际监听地址；url 为可访问地址，监听所有网卡时取当前 Wi-Fi 地址。await close() 停止接收并终止未完成请求，可重复调用。",
    failure = "首页不存在、无法读取、缺少设备存储权限、配置无效或端口占用时启动失败。onRequest 抛错或返回值无效时返回 500，并触发 onError；客户端不会收到脚本错误详情。网络断开时停止服务。",
    example = """// 先在项目中创建 home.html。
const server = await sview.network.server({
  home: '/home.html', // 也可用 'device:/Download/home.html'
  server: '',
  port: 8080,
  async onRequest(request) {
    const { pathname } = new URL(request.url);
    if (pathname === '/api/info') {
      return Response.json({ name: 'S View' });
    }
    if (pathname === '/about') {
      return new Response('<!doctype html><h1>关于</h1>', {
        headers: { 'Content-Type': 'text/html; charset=utf-8' }
      });
    }
    return new Response('未找到', { status: 404 });
  },
  onError(error) { console.error(error.message); },
  onClose() { console.log('服务器已关闭'); }
});
console.log(server.url);
// 用完后：await server.close();""",
    note = "Request、Response 是全局类，无需导入。静态文件只接受 GET/HEAD；动态请求支持 GET、HEAD、POST、PUT、PATCH、DELETE、OPTIONS。项目文件会通过对应路径公开，请勿放入私密资料。/ 下的资源路径仍以项目根目录为准，与 home 所在目录无关；设备首页的同目录文件不会自动公开。动态响应先接收完整正文再发送，不支持持续推送、WebSocket 或 HTTPS。每个项目最多创建四个自定义服务器，项目关闭后自动停止。",
    category = "网络"
)
