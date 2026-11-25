
## 原生网络桥

`sview.network.request` 的 JS 封装单独放在 assets/runtime/network.js，由运行入口同步加载。网络帮助归属「网络」分类。创建任务的 JSON 只包含地址、请求头和长度等元数据，文件与流通过带会话认证、精确 Origin 和自定义请求头校验的本地 POST 通道传输；手机每块 64 KiB、手表每块 256 KiB，原生最大接收 256 KiB。四个原生任务各有一个上传和下载握手通道，未消费的块不会继续积压，取消不排在阻塞的读写任务后面。

OkHttp 在原生执行网络请求，正常校验 HTTPS，不依赖目标站点的 CORS。Cookie 使用独立项目目录持久化；显式 Cookie 只转发到初始来源，跨来源重定向不携带。网络缓存目录随项目删除或清缓存清理。下载先写网络缓存临时文件，完整结束后发布到项目资源目录，不覆盖已有文件。进程直接被杀死留下的临时文件在下次初始化网络客户端时清理。

运行窗口释放资源服务时同步取消全部网络任务、握手通道、超时任务、连接池及后台线程。JS 页面离开时也尝试取消；原生关闭仍是最终保障。流需消费或取消，超时覆盖流消费。自动响应实际解析 JSON，isJson() 不依赖服务端声明的 MIME；二进制、流和下载文件不为判断 JSON 而额外全量读取。

创建任务时传入响应模式。非流式响应填满固定缓冲区后交付，最后一块允许不足；stream 保持即时交付，文件下载仍直接落盘。进度按交付块更新，不保证固定频率。isFile() 根据附件声明、文件 MIME 或下载落盘结果判断文件，不额外读取响应内容。

## 窗口位置

`Project.location` 使用 `WindowLocation` 的固定标识，默认 `center`。项目配置和 ZIP 备份共用 `ProjectJson`，位置随配置保存与还原。运行窗口在安全显示区域内按七种对齐方式摆放，左右使用物理方向，不随界面文字方向反转。

`interaction.location(position, persist)` 默认只修改运行状态；持久化复用项目保存队列，只更新位置字段，避免覆盖其他配置。位置实际改变时通过现有事件连接发送 `onLocation({ location })`。注册监听后立即调用位置接口，会先等待事件连接建立，避免遗漏第一次事件。

`interaction.removeClose()` 只隐藏当前运行框的原生悬浮关闭按钮，不操作网页 DOM，不写入配置。重新运行恢复按钮；返回关闭和脚本关闭继续使用现有生命周期与资源清理入口。

## 圆角与遮罩

项目保存 `cornerRadius`（dp，默认 20）和 `backdropTransparency`（百分比，默认 40）。默认透明度对应原先 Material 主题的 `backgroundDimAmount=0.6`。运行窗口将透明度换算成 Android 的遮罩不透明度；100% 透明不暗化底层页面，0% 完全遮黑，不改变网页自身背景或内容透明度。

圆角为非负数，显示时限制为当前窗口短边的一半，同时应用于 Compose 裁切与 GeckoView 背景轮廓。两个字段均由 ProjectJson 保存并随项目 ZIP 导出还原，圆角可通过 sview.interaction.cornerRadius(value, persist = false) 修改，value 为非负有限 dp 数值。


## 窗口边距

上下百分比以安全区域高度为基准，左右以宽度为基准，均在扣除边距前计算；px 按当前屏幕密度转换，数值输入按 dp 解释。

`WindowMargins` 保存 top、right、bottom、left 四个带 dp、px 或 % 单位的非负值，默认均为 0。边距相对安全显示区域计算，窗口在剩余区域对齐，百分比宽高也以剩余区域为基准。两侧总边距过大时同比缩小显示值并保留至少 1dp，配置原值不变。左右边距使用物理方向。

`interaction.margin({ top, right, bottom, left }, persist=false)` 复用配置保存队列，持久化只更新边距。`monitor.onMargin` 接收相同结构且保留单位的字符串配置，配置变化才推送，重复值不推送；实际内容尺寸变化仍由 onResize 通知。四边距随 ProjectJson 和 ZIP 导出还原，未增加定时轮询。

## 图片预览

`interaction.preview(path)` 复用 StoragePaths 的项目范围、设备授权和符号链接检查。ImagePreviewController 在接口线程限尺寸解码，主线程创建独立全屏 Dialog；后台或运行窗口关闭时递增代次并清理预览，迟到结果不会重新打开窗口。悬浮运行使用已有悬浮窗授权，不改变项目原窗口配置。每个会话仅允许一个正在解码的请求，成功重复调用替换已显示的预览。

PreviewImageView 使用原生 Canvas 与系统缩放、双击手势。手机基准为 contain，手表基准为 cover；下限为基准一半与完整显示比例中的较小值，圆屏按图片四角位于椭圆内计算。缩放围绕双指中心，位移限制在图片边界，双击和视口尺寸变化复位。只在手势或尺寸变化时重绘。图片长边限制 2048 像素，Android 28 起由 ImageDecoder 处理方向，旧系统采样后按 EXIF 修正。

图片预览不显示关闭按钮。单击图片区域经 onSingleTapConfirmed 确认后关闭，双击仅复位；黑边、拖动与双指缩放不触发关闭。视图移除时取消手势识别器的待处理事件。

## 脚本退出应用

`interaction.closeThis()` 与 close 一样先发送接口响应，再启动关闭。RuntimeAppExit 在主线程注册各运行窗口的关闭与释放等待函数，使用独立于页面的协程完成退出。WorkspaceModel.prepareAppExit 禁止新运行，等待同步、缓存和归档结束，通知所有窗口正常关闭，等待资源释放，再复用保存队列与日志屏障完成落盘。随后停止悬浮服务、移除本应用任务、清理 ViewModel，并复用 AppProcessExit 关闭引擎及同 UID 的本应用辅助进程。手表原有主动退出复用相同的准备过程。重复调用不会启动多个退出流程。

## 运行亮屏

RuntimeWindowHost 在实际承载内容的 LocalView 上设置 keepScreenOn，覆盖 Activity、Dialog 与悬浮运行分支；组合移除时恢复原值。图片预览的独立 Dialog 使用 FLAG_KEEP_SCREEN_ON，关闭窗口即释放。由可见窗口向系统申请保持亮屏，不使用 WakeLock、周期唤醒或修改系统熄屏设置。

脚本可通过 `await sview.script.isWatch()` 判断当前运行设备是否为手表，返回布尔值。原生端复用 `WatchDevice.isWatch()` 的系统设备类型识别，不按屏幕尺寸或形状推断，也不判断配对设备；调用无需额外权限。

入口模板及其他运行 HTML 页面支持 `<!-- sview:iswatch -->` 占位符。在 `<html lang="zh-CN" <!-- sview:iswatch -->>` 中使用时，手表替换为不带引号的 `iswatch`，其他设备替换为空字符串；CSS 可用 `html[iswatch]` 选择手表。替换发生在运行页面生成时，不修改源模板；设备类型参与入口缓存签名，跨设备同步后按目标设备生成。已有自定义模板需要自行添加占位符，升级不会覆盖用户模板。

默认 `global.js` 提供同步工具 `getAttr(selector, name)`，读取首个匹配元素的属性，元素或属性缺失时返回 `null`，空属性返回空字符串。`isWatch()` 根据根元素是否存在 `iswatch` 属性返回布尔值，读取的是页面标记；原生设备判断仍可调用 `await sview.script.isWatch()`。已有全局脚本需自行合并或还原模板，升级不覆盖自定义内容。

默认 `global.js` 的 `randomId()` 同步返回八位随机字符串，首位为字母，后七位从大小写字母和数字中随机选择，不保证三类字符都出现。使用浏览器安全随机数与拒绝采样，不承诺跨调用唯一；工具页提供调用说明。

## 通知监听

sview.system.notifyMonitor 使用当前运行窗口的事件连接传递 start、message、error、close，不另建轮询线程。通知使用权由系统设置授予，与 POST_NOTIFICATIONS 独立。NotificationMonitorService 只在有订阅时转换有界文本快照，无订阅时请求系统解绑；不读取历史通知、不写入通知文件。系统连接超时为 15 秒，断开或撤销权限会结束监听。后台不注销订阅，窗口资源释放时同步注销；脚本 close 和页面退出也会清理事件回调。共享窗口事件历史最多 512 条或 2 MiB，系统冻结页面时可能延迟交付，超过保留范围沿用 EVENT_GAP 错误。

## 网页媒体授权

WebMediaPermissions 接入实际 GeckoSession.PermissionDelegate，仅接受 RECORD_AUDIO、CAMERA 以及当前 ProjectServer 来源的麦克风和摄像头请求。Android 权限由手机和手表设置页统一申请，网页请求同步核验，不保存授权回调，也不创建线程或长期任务；屏幕采集和其他权限不放行。设备顺序沿用 Gecko 对 constraints 的筛选结果。

窗口关闭先将授权代理标记为关闭、清空 permissionDelegate，再走原有 BrowserEngine.closeSession；GeckoSession.close 销毁原生网页窗口并释放其媒体采集。崩溃、系统终止和启动失败复用同一资源释放路径。不以网页 pagehide 或 JS Track.stop 成功作为释放前提。硬件 feature 均为可选，避免无摄像头的手表被过滤。

### 同步文本模板

`sview.script.code(file, replace = {})` 使用同步 XMLHttpRequest 调用当前会话的 `/__sview_api`，沿用 Cookie、Origin 与自定义请求头校验。请求只携带本地路径，不访问远端地址，也不序列化替换对象。原生接口在独立接口线程读取 UTF-8 文件，避免依赖正在等待响应的页面线程。

`ScriptCodeApiBridge` 复用目录边界校验，按全局模板、项目资源顺序读取原文，不调用运行文件生成逻辑。每次读取当前文件，不保留模板缓存，修改后下次调用即可生效。缺失返回空字符串，目录、非法路径与读取失败返回结构化错误，脚本端同步抛出。占位符由 `ScriptCode` 在 JavaScript 侧单轮替换，逐级检查自身属性，仅插入字符串和数字。
