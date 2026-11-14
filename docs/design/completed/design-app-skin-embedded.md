# AppSkin：WebView、终端与画布

## 配置与入口

`AppSkin.embedded` 包含 web、terminal、canvas 三个子配置，作为一组参与默认值 →
皮肤/插件 → 用户覆写的统一解析、设置持久化与主题分享。旧配置缺少该字段时读取默认值，
没有 Room schema 变更。颜色仅接受不透明 `#RRGGBB`，非法值在解析/导入时回退；
编辑器保留未完成输入并禁用保存，不逐字符改写输入。

设置路径：偏好设置 → 用户界面 → 应用皮肤 → 编辑全局样式 → 网页、终端和画布。
子编辑器使用常驻 Column + verticalScroll + imePadding；应用到草稿后，仍需保存外层
全局样式。取消子编辑器或外层编辑器均不写入全局配置。

| 配置 | 默认行为 | 可覆写项 |
| --- | --- | --- |
| web | 已声明支持的自有 HTML 跟随应用 | 启用、底色、文字、强调色；字体/字号继承全局 |
| terminal | 旧黑底白字，JetBrains Mono | 跟随应用配色、文字/底色/光标、8–24sp；叠加全局和系统字号 |
| canvas | 白色新画纸，工作区继承应用 | 工作区、选择框、新画纸底色 |

只指定自定义底色时，网页/终端默认文字自动选择黑或白；显式文字覆写优先。
自定义画布工作区同样提供对比文字；深色新画纸初始使用白色画笔。

## 自有 HTML 协议

`WebContent.Data.appOwned` 显式开启主题。`rememberWebViewState(data=…)` 也识别
`<meta name="rikka-app-skin" content="v1">`，因此缓存后在完整预览页中仍可声明支持。
这只允许外观变量，不新增 JavaScript interface 或任何 WebView 权限。

主题在固定 id 的 style 节点中更新，不重新 loadData/loadUrl，不替换 DOM。提供：

- `--rikka-background`、`--rikka-foreground`、`--rikka-accent`
- `--rikka-font-family`、`--rikka-font-size`
- `rikka-skin-change` 事件，detail.enabled 表示启用状态

默认元素规则使用 `:where` 的低优先级，作者明确写的 CSS 和 SVG 颜色优先。
关掉主题时移除该 style 节点。CSS 通过 JSON 编码传入脚本；不把本机字体路径透露给页面，
自定义字体暂时回退到通用 sans-serif，serif/monospace 对应全局字体类型。
字号同时使用全局比例与系统 fontScale。

只对声明支持的 HTML 文档、且页面回调 URL 与其基准地址相同的页面应用；页面跳转到
不同 URL 后不再注入。JS 执行时再次核对 document.URL，防止导航切换期间的旧回调。
HTTP 根地址缺少路径与 `/` 视作同一地址，其他路径与查询参数仍须匹配。
真机发现 loadDataWithBaseURL 下 getUrl() 可返回 about:blank，而实际 DOM 地址是基准
地址，因此采用页面回调中的文档地址判断。复用 AndroidView 时客户端随 WebViewState
更新，避免新 HTML 状态继续收到旧状态的回调。

Mermaid 使用已解析的配色/字号/通用字体与可选强调色生成图表，渲染参数变化时重新生成
图表；其 PNG 导出使用同一底色。通用 HTML 热更新不重载文档。SVG 预览外框声明支持
协议，但 SVG 内部显式颜色不改写。任意远程网页和未声明支持的 HTML 保留自身主题。

## 终端默认颜色与协议优先级

TerminalColors 保留公开静态 COLOR_SCHEME 以维持 terminal-view AAR 的接口兼容；
新皮肤只更新各会话的本地默认前景/背景/光标，不修改全局色表或 ANSI 0–255 色。
通过独立标记记录 OSC 显式设置，连“显式值恰好等于旧默认色”的情况也不覆盖。
OSC 110/111/112 和终端 reset 回到当前会话最新的皮肤默认色。

程序真彩色、SGR 属性和屏幕缓冲区不改变。更新字号时重用原 TerminalView、Session 和
Emulator；允许正常列宽重排，不重启 shell。隐藏标签在重新挂接时读取当前皮肤。
首帧没有 shell 输出时也会在 TerminalView 首次布局后应用颜色，避免等到第一段输出才着色。
未开启跟随应用配色时，终端页面继续使用旧暗色环境；开启后使用应用当前明暗模式。

## 画纸是文档内容

工作区和选择框颜色实时读取皮肤。新画纸底色只在创建 SketchState 时捕获一次，随后
屏幕绘制与 PNG/JPEG 导出共用 state.paperColor。换肤、重组、清空笔画或调整视口不改写
该值；下一张新画纸才使用新初值。底图和已选画笔颜色保持文档自己的值。
公式、TikZ 和已有 SVG 的显式图形颜色仍遵循原内容协议，未做像素反色或颜色替换。

## 验证与剩余范围

应用 JVM 3279 通过、2 跳过；terminal JVM 148 全部通过。包含旧 JSON/分享/覆写回退、
非法颜色与字号边界、HTML 地址隔离，以及会话隔离、OSC reset、等值显式覆写、真彩色
与文本缓冲不变的测试。debug 和 androidTest 构建通过。

真机完整回归 90 项全部通过：本轮新增 9 项（HTML 热更新/作者 CSS/导航隔离/状态替换、
Mermaid 字体与 PNG 导出、真实 TerminalView 会话与 OSC 保留、画纸捕获/导出、编辑器
输入和草稿取消），并覆盖原有 69 项皮肤/材质/导航及 12 项画板状态/导出测试。
最后对齐设置页终端预览的字体/字号后，相关 10 项真机测试再次全部通过。

完整字体资源在 HTML 中加载、任意远程站点的主题适配、ANSI 调色板编辑、对已有图形内容
重新着色、皮肤资源包生命周期与全页面可访问性验收仍不在本次范围。
