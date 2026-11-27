package io.todu.sview.ui.screens

/** 运行窗口操作；尺寸默认仅在当前会话生效。 */
internal val interactionInterfaceHelp = listOf(
    InterfaceHelpEntry(
        name = "sview.interaction.background", brief = "收起或恢复当前运行窗口",
        summary = "收起后保留页面和脚本，点绿色 S 标志或运行通知恢复，点停止结束本次运行。",
        parameters = listOf(
            InterfaceParameter("enabled", "boolean", true, "true 收起，false 恢复。"),
            InterfaceParameter("dynamic", "boolean", false, "收起时请求系统实时展示，默认 false。设备或设置不支持时显示普通通知。")
        ),
        resultType = "Promise<boolean>", result = "已收起或恢复时返回 true；应用在后台时请求恢复返回 false，回到应用后自动显示。",
        failure = "参数错误、后台运行通知未开启或服务启动失败时，Promise 拒绝。",
        example = "await sview.interaction.background(true, true);\nsetTimeout(() => sview.interaction.background(false), 5000);",
        note = "收起时不触发关闭回调。再次运行同一项目或启动其他项目，会先通知后台脚本关闭，再开始新的一次运行。从应用首页按返回会退出应用并结束后台脚本。宿主销毁或进程退出也会结束本次运行。",
        category = "交互"
    ),
    InterfaceHelpEntry(
        name = "sview.interaction.closeThis", brief = "结束运行并退出 S View",
        summary = "关闭 S View 的运行窗口并退出应用，手机、手表和悬浮运行均支持。退出前通知脚本关闭，提交待保存内容和日志，停止悬浮运行、连接与局域网编辑器。",
        parameters = emptyList(), resultType = "Promise<void>",
        result = "无返回数据。接口响应发出后开始退出；这是终止操作，不应依赖调用后的代码继续执行。",
        failure = "运行连接不可用时 Promise 拒绝；退出收尾失败时保留应用并提示错误。",
        example = "sview.interaction.closeThis();",
        note = "与 close() 仅关闭当前脚本窗口不同，此接口退出整个 S View。onClose 仅等待同步收尾，不等待异步任务。",
        category = "交互"
    ),
    InterfaceHelpEntry(
        name = "sview.interaction.preview", brief = "全屏预览图片",
        summary = "按路径打开图片。手机初始完整显示，手表初始居中铺满，超出部分裁切。双指缩放，单指拖动，双击恢复初始大小和位置；单击图片返回脚本，双击不会关闭。",
        parameters = listOf(InterfaceParameter("path", "string", true, "项目资源路径，如 /images/photo.jpg，或设备路径 device:/Download/photo.jpg。不接受 URL。")),
        resultType = "Promise<void>", result = "图片成功显示后完成；关闭预览不会关闭脚本。重复调用替换当前预览。",
        failure = "路径无效、图片无法解码、设备存储未授权、窗口不在前台或图片正在加载时，Promise 拒绝。",
        example = "sview.interaction.preview('device:/Download/sview-preview-test.png')\n  .catch(error => console.error(error.message));",
        note = "初始适配大小为 100%，最大 300%。最小为 50% 与完整显示所需比例中的较小值，圆屏按四角可见计算。支持系统可解码的位图格式，动图显示首帧；解码长边限制为 2048 像素。设备路径需先允许设备存储访问。",
        category = "交互"
    ),
    InterfaceHelpEntry(
        name = "sview.interaction.monitor",
        brief = "监听运行窗口事件",
        summary = "注册页面就绪、运行错误、窗口关闭、尺寸、位置、边距和圆角变化回调。回调函数直接保留在当前页面，不需要 await。",
        parameters = listOf(
            InterfaceParameter("options", "object", true, "事件回调对象。以下七项均可省略；再次调用会替换整组回调。"),
            InterfaceParameter("options.onReady", "() => void", false, "页面 load 完成且窗口首帧就绪后触发一次，不等待业务中的异步请求。就绪后再注册，会异步补发一次。"),
            InterfaceParameter("options.onError", "(error) => void", false, "接收注册后的未捕获 JavaScript 异常、未处理的 Promise 拒绝和资源或页面加载错误。error 包含 type、message，可包含 code、source、filename、line、column、stack；行列从 1 开始，未知为 0。接口专用 onError 或 catch 已处理的原始错误不再转发。console.error 打印本身不会触发此事件。"),
            InterfaceParameter("options.onClose", "() => void", false, "正常关闭窗口前触发一次，可同步保存 localStorage 或打印日志。关闭按钮、遮罩点击、返回关闭、interaction.close 和 interaction.closeThis 均会触发；回调不能阻止关闭，也不会等待异步任务。系统强杀、崩溃或页面尚未建立监听连接时不能保证执行。"),
            InterfaceParameter("options.onCornerRadius", "({ cornerRadius }) => void", false, "圆角配置实际改变时触发，cornerRadius 为非负 number，单位 dp。相同值不重复通知，注册时不补发；显示圆角最多为窗口短边的一半。"),
            InterfaceParameter("options.onMargin", "({ top, right, bottom, left }) => void", false, "边距配置实际改变时触发，四边均为保留单位的字符串，如 12dp、24px、5%。相同边距不重复通知，注册时不补发；超出屏幕时实际显示边距可能缩小。"),
            InterfaceParameter("options.onLocation", "({ location }) => void", false, "位置实际改变时触发，location 为位置标识；相同位置不重复通知，注册时不补发。"),
            InterfaceParameter("options.onResize", "({ width, height }) => void", false, "页面就绪后的实际内容区域尺寸变化时触发，宽高为 CSS 像素 number。同一帧内合并变化，相同尺寸不重复通知；注册时不会立即触发。")
        ),
        resultType = "void",
        result = "同步注册，无返回值。onReady、onClose 无参数；onResize 接收宽高对象；onLocation 接收位置对象；onMargin 接收四边距对象；onCornerRadius 接收圆角对象；onError 的 type 为 error、unhandledrejection、resource、load、connection、interface 或 callback。",
        failure = "参数不是对象、事件名不支持或回调不是函数时同步抛出异常。其他回调内部未捕获异常会转给 onError；onError 自身异常只写日志，不递归调用。",
        example = """sview.interaction.monitor({
  onReady() {
    console.log('窗口已就绪');
  },
  onError(error) {
    console.error(error.type, error.message);
  },
  onClose() {
    localStorage.setItem('lastClosed', String(Date.now()));
  },
  onResize({ width, height }) {
    console.log('窗口大小', width, height);
  },
  onCornerRadius({ cornerRadius }) {
    console.log('窗口圆角', cornerRadius);
  }
});""",
        category = "交互"
    ),
    InterfaceHelpEntry(
        name = "sview.interaction.close",
        brief = "终止脚本并关闭当前运行窗口",
        summary = "终止脚本并关闭当前运行窗口" ,
        parameters = listOf(),
        resultType = "Promise<void>",
        result = "无返回数据。关闭动画结束后会销毁运行会话及其资源；这是终止操作，不应依赖调用后的代码继续执行。",
        failure = "参数无效、所需权限未允许、设备不支持或运行连接不可用时，Promise 会拒绝。需要交互的操作只能在运行窗口位于前台时调用。",
        example = "(async () => {\n  try {\n    await sview.interaction.close();\n  } catch (error) {\n    console.error(error.message);\n  }\n})();",
        category = "交互"
    ),
    InterfaceHelpEntry(
        name = "sview.interaction.resize",
        brief = "修改当前运行窗口大小",
        summary = "修改当前运行窗口大小" ,
        parameters = listOf(InterfaceParameter("width", "string", true, "宽度，正数并带 dp、px 或 % 单位，例如 360dp、800px、90%。"), InterfaceParameter("height", "string", true, "高度，正数并带 dp、px 或 % 单位。百分比相对于可用屏幕尺寸。"), InterfaceParameter("persist", "boolean", false, "true 同时写入项目配置，下次运行沿用；false 只影响本次运行。", "false")),
        resultType = "Promise<void>",
        result = "无返回数据。窗口实际大小由系统限制在屏幕范围内，不限制保存的输入值；persist 为 true 时，配置保存成功才完成。",
        failure = "参数无效、所需权限未允许、设备不支持或运行连接不可用时，Promise 会拒绝。需要交互的操作只能在运行窗口位于前台时调用。",
        example = "(async () => {\n  try {\n    await sview.interaction.resize('360dp', '80%');\n    // 同时保存到项目配置\n    await sview.interaction.resize('90%', '80%', true);\n  } catch (error) {\n    console.error(error.message);\n  }\n})();",
        category = "交互"
    )
,
    InterfaceHelpEntry(
        name = "sview.interaction.location", brief = "修改运行窗口位置",
        summary = "调整窗口在屏幕可用区域中的位置，默认完全居中。",
        parameters = listOf(
            InterfaceParameter("location", "string", true, "center 完全居中；top 上居中；bottom 下居中；topLeft 左上角；topRight 右上角；bottomLeft 左下角；bottomRight 右下角。"),
            InterfaceParameter("persist", "boolean", false, "true 同时保存到项目配置并参与备份；false 仅本次生效。", "false")
        ), resultType = "Promise<void>", result = "无返回数据；持久化时等待保存成功。实际变化触发 onLocation({ location })，相同位置不重复通知。",
        failure = "位置不支持、persist 不是布尔值、保存失败或运行连接不可用时拒绝。",
        example = """sview.interaction.monitor({
    onLocation({ location }) {
        console.log('窗口位置', location);
    }
});
(async () => {
    await sview.interaction.location('topRight');
    await sview.interaction.location('center', true);
})();""", category = "交互"
    ),
    InterfaceHelpEntry(
        name = "sview.interaction.removeClose", brief = "移除运行窗口关闭按钮",
        summary = "隐藏右上角悬浮关闭按钮，仅影响本次运行，重复调用有效。不会删除网页自己的 DOM 按钮。",
        parameters = emptyList(), resultType = "Promise<void>", result = "无返回数据。下次运行恢复按钮；系统返回键和 interaction.close() 仍可关闭。",
        failure = "运行连接不可用或窗口已关闭时拒绝。",
        example = """(async () => {
    await sview.interaction.removeClose();
})();""", category = "交互"
    ),
    InterfaceHelpEntry(
        name = "sview.interaction.margin", brief = "修改运行窗口四边距",
        summary = "设置窗口与屏幕安全显示区域之间的外边距。默认四边均为 0dp，窗口在扣除边距后的区域按所选位置对齐，百分比宽高以此区域为基准。",
        parameters = listOf(
            InterfaceParameter("margins", "object", true, "完整四边距对象，top、right、bottom、left 均必填，值支持非负带单位字符串 dp、px、%；number 按 dp 解释。上下百分比相对安全区域高度，左右相对安全区域宽度，均在扣除边距前计算。"),
            InterfaceParameter("persist", "boolean", false, "true 同时保存项目配置并参与导出还原；false 仅本次运行有效。", "false")
        ), resultType = "Promise<void>",
        result = "无返回数据；持久化时等待配置保存成功。实际配置变化触发 onMargin({ top, right, bottom, left })，相同值不重复触发。两侧边距过大时按比例缩小显示边距，保留至少 1dp 内容区域，不修改保存值。内容尺寸变化时仍会触发 onResize。",
        failure = "缺少边距、负数、非法单位、无效 persist 或配置保存失败时拒绝；窗口关闭后不可继续调用。",
        example = """sview.interaction.monitor({
    onMargin({ top, right, bottom, left }) {
        console.log('边距', top, right, bottom, left);
    }
});
(async () => {
    await sview.interaction.margin({ top: '5%', right: '24px', bottom: '16dp', left: '24px' });
    // 第二个参数为 true 时写入项目配置。
    await sview.interaction.margin({ top: '0dp', right: '0dp', bottom: '0dp', left: '0dp' }, true);
})();""", category = "交互"
    ),
    InterfaceHelpEntry(
        name = "sview.interaction.cornerRadius", brief = "修改当前运行窗口圆角",
        summary = "设置窗口圆角半径，0 表示直角，默认仅本次运行生效。",
        parameters = listOf(
            InterfaceParameter("cornerRadius", "number", true, "非负有限数值，单位 dp，最大为 3.4028234663852886e38。显示时不超过窗口短边的一半，保存值不受此限制。"),
            InterfaceParameter("persist", "boolean", false, "true 同时写入项目配置，下次运行及导出还原沿用；false 仅本次生效。", "false")
        ), resultType = "Promise<void>",
        result = "无返回数据；持久化时等待保存成功再更新窗口。配置实际变化触发 onCornerRadius({ cornerRadius })，相同值不重复通知。",
        failure = "圆角不是非负有限数值、persist 不是布尔值、保存失败或运行连接不可用时拒绝。",
        example = """sview.interaction.monitor({
    onCornerRadius({ cornerRadius }) {
        console.log('窗口圆角', cornerRadius);
    }
});
(async () => {
    await sview.interaction.cornerRadius(0);
    // 保存为 20dp，下次运行沿用。
    await sview.interaction.cornerRadius(20, true);
})();""", category = "交互"
    )
)
