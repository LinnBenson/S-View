# S View

**在 Android 手机上编写网页工具，也让它们在 Wear OS 手表上运行。**

S View 把 JavaScript 编辑器、项目资源管理和网页运行窗口放在一个应用里。用 HTML、CSS 和 JavaScript 构建界面，通过 `sview` 接口访问设备能力；既可以在手机上直接编辑，也可以连接电脑浏览器继续开发。

[下载安装包](https://github.com/LinnBenson/S-View/releases) · [源代码](https://github.com/LinnBenson/S-View) · [Apache-2.0](LICENSE)

## 可以用它做什么

- **编写和运行网页项目**：支持真实 DOM、HTML、CSS 和 JavaScript，运行内核为 GeckoView。
- **管理独立工作区**：每个项目分别保存代码、资源、全局变量和窗口配置，支持分类、置顶、排序及 ZIP 导入导出。
- **复用公共模板**：多个项目共用入口、样式和脚本，也可以用项目内同名文件单独覆盖。
- **调用 Android 能力**：读写文件、发送网络请求、接收分享、预览图片，以及使用通知、定位、蓝牙、NFC 等接口。
- **在电脑上编辑**：通过局域网网页管理项目文件、模板、项目变量和共享数据库，搜索 Lucide 图标并复制名称。
- **在手表上使用**：同步选定项目到 Wear OS，使用适配圆屏的项目列表和环形磁贴入口。

## 安装

最低要求为 Android 8.0（API 26）。手机与手表使用同一套应用，根据设备支持的 ABI 选择安装包：

| 文件 | 架构 |
| --- | --- |
| `S-View-<版本号>-arm64-v8a.apk` | 64 位 ARM |
| `S-View-<版本号>-armeabi-v7a.apk` | 32 位 ARM |
| `SHA256SUMS.txt` | 安装包 SHA-256 校验值 |

大多数较新的手机使用 ARM64；手表请按实际架构选择，不要仅凭设备类型判断。Wear OS 同步功能需要手机与手表完成系统配对，并且两端均安装 S View。

安装 APK 时，Android 可能要求允许当前文件管理器或浏览器安装应用。更新时使用相同签名的安装包覆盖安装；卸载会删除应用私有目录中的项目和数据，请先备份。

## 第一个项目

1. 打开 S View，点击右上角的添加按钮创建项目。
2. 编辑 `main.js`，写入下面的内容并保存。
3. 返回项目列表，点击项目卡片运行。

```javascript
const app = document.querySelector('#app');

app.innerHTML = `
  <div style="padding: 24px; text-align: center">
    <h2>你好，S View</h2>
    <button id="count">点击了 0 次</button>
  </div>
`;

let count = 0;
document.querySelector('#count').addEventListener('click', (event) => {
  count += 1;
  event.currentTarget.textContent = `点击了 ${count} 次`;
});
```

默认入口是 `/index.html`，其中包含 `<main id="app"></main>`，并加载 `/main.js`。可以继续添加 CSS、图片或其他脚本，通过项目资源路径引用。

在项目设置中调整名称、图标、配色，以及窗口尺寸、位置、边距、圆角和遮罩透明度。运行窗口可见时保持亮屏，关闭项目或退到后台后恢复系统熄屏行为，仍可按电源键主动锁屏。

## 模板、变量与资源

资源按“当前项目优先、全局模板其次”的顺序解析。项目中的同名文件只影响当前项目，不会修改公共模板。

「设置 → 模板文件」用于管理全局模板。内置入口、公共样式和公共脚本，以及 jQuery、Lucide、Alpine.js 库，可供项目引用。系统模板允许编辑，但不能删除；更新应用不会覆盖已有模板修改。Alpine.js 可在项目的「引用 → 快速添加」中选择，默认延后加载（defer），无需联网。

入口模板中的两个占位符用于插入项目配置：

```html
<!-- sview:variables -->
<!-- sview:references -->
```

变量占位符应放在引用项之前。自行编写入口时，如果移除对应占位符，该部分内容就不会自动插入。

项目全局变量支持文本、数字、布尔值、数组和对象；数组与对象使用 JSON 编辑。这些变量属于项目配置，与浏览器的 `localStorage`、应用共享数据库是不同的数据。

## 局域网编辑器

在手机打开「设置 → 局域网编辑器」，设置端口和访问密码，启动服务。电脑连接同一局域网后，在浏览器打开手机显示的地址并登录。

网页提供四个入口：

| 页面 | 用途 |
| --- | --- |
| 项目 | 管理项目、编辑代码与资源、修改窗口设置、导出项目 |
| 模板文件 | 编辑所有项目共用的模板和资源 |
| 共享数据库 | 查看、搜索、新增、编辑和删除应用共享变量 |
| 图标库 | 连续预览内置 Lucide 图标，搜索并点击复制名称，不分页 |

项目编辑器右上角可管理当前项目的全局变量。点击“运行”会先保存，再在手机上运行项目；网页本身不提供运行预览。

使用时请保持手机应用在前台，离开应用会停止服务。连接使用 HTTP，应只在可信网络中开启。另一端修改工作区后，旧网页会拒绝覆盖保存；请先下载需要保留的未保存文件，再刷新页面。

网页单文件传输上限为 16 MB，文本直接编辑上限为 2 MB，项目 ZIP 导出上限为 64 MB。较大的项目可在手机端导出。

## 桌面小部件与手表磁贴

### 手机桌面

在系统小部件列表中添加「项目快捷方式」，选择需要显示的项目。小部件可随桌面调整大小，最多配置 16 个项目位置，具体尺寸和重新配置入口取决于桌面应用。显示数量与桌面占格不符时，在小部件配置页把「当前占格」设为实际列数和行数后保存；例如一行占两格，设置为 2 列、1 行。在另一尺寸下再次校准可进一步修正格间距。更换桌面网格或折叠状态后，可分别校准。

已授予悬浮窗权限时，点击快捷方式直接打开悬浮运行窗口；未授权时，进入 S View 后运行项目。悬浮窗口沿用项目的窗口配置，锁屏、撤销权限或结束应用会停止运行。

### Wear OS

在手机「设置 → 手表连接」中选择需要同步的项目并调整顺序，然后点击同步。

**同步会替换手表工作区。** 手表上的项目资源、模板和共享数据库以手机同步内容为准，手表本地修改不会反向合并；未选中的旧项目会移除，网页缓存会清除。同步中断时保留原工作区。

每个项目可以分别启用：

- **扩展显示**：同步到手表时，将窗口宽高改为 100%、四边距改为 0dp，手机原配置不变。
- **磁贴入口**：在环形磁贴中显示项目，默认关闭，最多选择 8 个。

同步后，在手表系统中添加 S View 磁贴。点击扇区运行项目，关闭后回到系统；点击中央 Logo 打开应用。手表运行界面可从左侧边缘右滑返回，项目网页自身仍需为圆形屏幕设计布局。

手表日志保存在手表本地，通过手机手表连接页的日志入口查看。读取时需要两端连接，清除缓存会删除日志。同步包上限为 512 MiB、20000 个文件和目录条目。

## 系统接口

脚本通过全局 `sview` 对象调用原生能力。**完整参数、返回值和可复制示例，以应用内「设置 → 系统接口」为准。**

| 命名空间 | 主要能力 |
| --- | --- |
| `sview.system` | 设备和屏幕信息、通知、震动、定位、蓝牙、Wi-Fi、NFC、健康记录等 |
| `sview.interaction` | 运行窗口控制、图片预览、运行事件监听 |
| `sview.storage` | 文件与目录读写、复制、流式处理 |
| `sview.network` | 原生网络请求、本地热点、聊天及上传服务器 |
| `sview.script` | 共享数据库、接收分享内容等 |

需要权限的能力，请先在「设置 → 权限」中授权。设备硬件、Android 版本和系统服务会影响接口可用性，手机支持的能力不一定在手表上可用。

### 文件与设备路径

普通路径相对于当前项目；`device:/` 指向设备主共享存储，需要设备存储权限，系统保护目录仍受 Android 限制。

```javascript
await sview.storage.write('data/message.txt', '你好');
const file = await sview.storage.open('data/message.txt');
console.log(await file.text());

await sview.storage.copy(
  'data/message.txt',
  'device:/Download/message.txt'
);
```

`open()` 返回标准 `File`，普通读取上限为 32 MB；大文件可使用流式读取。复制默认不覆盖同名目标，是否覆盖可通过参数指定。

图片和链接可以直接使用设备路径：

```html
<img src="device:/Download/photo.jpg">
<a href="device:/Download/document.pdf">打开文档</a>
```

这项映射适用于 `img.src` 和 `a.href`，不包含 `srcset`、CSS 或其他标签。图片也可通过 `sview.interaction.preview(path)` 全屏预览，支持缩放和拖动，双击复位、单击关闭；该接口接收路径，不接收网络 URL。

### 网络与本地服务

`sview.network.request(url)` 使用原生网络请求，不受网页 CORS 限制；普通 `fetch`、`XMLHttpRequest` 和 `$.ajax` 仍遵循浏览器规则。HTTPS 请求保留证书校验。

`hotspot()` 创建供局域网设备通信的本地热点，不共享移动网络。`chatServer()` 提供聊天网页，`uploadServer()` 提供文件接收网页。文件和图片上传默认上限均为 512 MB，可调低。三个接口均可在配置对象中传入 `onError(error)` 和 `onClose()`，分别处理错误和关闭通知。

`server({ home, onRequest, ... })` 可创建自定义 HTTP 服务器。`home` 支持项目路径或 `device:/Download/home.html` 等设备路径，设备路径需要允许设备存储访问。`/` 返回指定 HTML，其他路径优先返回项目文件，找不到文件时交给 `onRequest(request)`，通过标准 `Response` 返回文字、JSON 或 HTML。设备首页的同目录资源不会自动公开。支持 `onError`、`onClose`，默认端口 8080、超时 30 秒、正文上限 16 MiB、并发连接数 4。静态文件只接受 GET/HEAD；动态响应完整接收后发送，不支持 WebSocket、HTTPS 或持续推送。项目文件可通过对应路径访问，不应在其中存放私密资料。


上传服务将完成的文件保存到设备 `Download`，同名文件自动改名。聊天附件只保留在会话缓存中，关闭服务后清理。项目关闭或应用退出时会释放相关服务；已经保存到 `Download` 的文件不会随服务关闭而删除。

### 分享与 URL 启动

手机项目设置中的“共享来源”支持禁用、全部、文本、文件，默认禁用。启用后，其他应用可以分享给 S View；多个项目符合类型时选择目标，只有一个时直接运行。

```javascript
const share = await sview.script.getShare();
console.log(share.title, share.text);
for (const file of share.files) {
  console.log(file.name, file.size);
}
```

文件以标准 `File[]` 返回。手机系统分享最多接收 16 个文件、合计 32 MB；本次运行结束后清理临时副本。普通启动时返回空文本和空文件数组。

手机和手表均支持以下 URL：

```text
sview://task?name=项目名&share=传递参数
```

查询参数需要 URL 编码。按列表顺序运行第一个同名项目，`getShare()` 返回的 `title` 为 `URL Schemes`、`text` 为 `share` 参数。URL 启动不受“共享来源”限制；手表不接收 Android 系统分享。

### 健康记录

`sview.system.getHealthInfo()` 读取已有记录，不启动主动健康测量。手机使用 Health Connect，手表独立读取本机 Health Services 数据。两端返回同一结构，但缺失或不支持的数据会标明状态，不会用 0 代替。

手表暂不支持血氧和睡眠历史，其他数据也取决于设备和系统提供的记录，不保证与厂商健康应用首页一致。

## 数据保存与备份

| 数据 | 保存和清理方式 |
| --- | --- |
| 代码、资源、项目配置 | 保存在应用私有目录，可通过项目 ZIP 导出 |
| 项目全局变量 | 随项目配置保存，并包含在项目导出中 |
| `localStorage`、IndexedDB、持久 Cookie | 按项目隔离，清除缓存或删除项目时清理，不包含在 ZIP 中 |
| 共享数据库 | 所有项目共用，清除缓存或删除项目不会清空，不包含在单个项目 ZIP 中 |
| 运行日志 | 按项目保存，清除缓存或删除项目时清理 |
| `device:/` 中的文件 | 保存在设备共享存储，需由用户或脚本管理 |

项目菜单中的“导出”生成 ZIP，通过首页导入按钮还原。重复导入会创建独立项目。全局模板随导出包保存快照，导入后成为项目内文件，不覆盖设备上的公共模板；网络引用只保留地址，不打包远程内容。项目导入包最多包含 10000 个条目，解压总大小不超过 512 MB，配置文件不超过 1 MB。

设置中的“清除缓存”会清理网页数据、Cookie、运行产物、日志，以及项目资源根目录下的 `cache` 文件夹。其他资源、配置和模板保留。卸载应用会删除应用私有数据，重要项目请另行保存。

共享数据库可在设置、局域网编辑器或脚本中管理：

```javascript
await sview.script.db.set('preferences', { sound: true });
const preferences = await sview.script.db.get('preferences');
```

数据库最多保存 1000 个键，键名不超过 128 个字符，单值上限为 48 KB，总值大小上限为 2 MB。

## 从源码构建

使用 Android Studio 打开仓库根目录，并安装构建配置要求的 Android SDK。当前配置为 `compileSdk 37.2`、`targetSdk 36`、`minSdk 26`。

```sh
./gradlew :app:assembleRelease
```

构建生成 ARM64 和 ARMv7 两个未签名 Release APK。可使用项目提供的脚本签名并打包：

```sh
python3 scripts/package_release.py \
  --version <版本号> \
  --sdk <Android-SDK目录> \
  --keystore <密钥库路径> \
  --password-file <密码文件路径> \
  --alias <密钥别名> \
  --output <输出目录>
```

版本号应与 `app/build.gradle.kts` 一致。密钥库、密码文件与本机 SDK 配置不应提交到仓库。自行签名的安装包不能直接覆盖不同签名的已安装版本。

实现说明见 [架构文档](docs/architecture/)。

## 开源许可

S View 以 [Apache License 2.0](LICENSE) 发布。第三方组件及资源仍遵循各自的许可：

| 组件 | 许可证 |
| --- | --- |
| GeckoView | Mozilla Public License 2.0 |
| Lucide | ISC |
| jQuery | MIT |
| Alpine.js 及其 Vue 响应式模块 | MIT |
| JetBrains Mono | SIL Open Font License |
| OkHttp、Okio、Prism4j | Apache License 2.0 |

随附许可文件位于 [`app/src/main/assets/licenses/`](app/src/main/assets/licenses/)；Lucide 图标资源另附 [许可证](app/src/main/assets/lucide/LICENSE)。GeckoView 的源码及构建信息可参阅 [Mozilla 项目文档](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/)。
