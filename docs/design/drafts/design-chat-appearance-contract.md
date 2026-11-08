# 聊天外观统一契约

本地实现说明；不涉及远端发布。

## 范围

统一聊天正文的排版、消息布局与双侧气泡配置。设置页与源码级原生插件都提供
`ChatAppearance`，由同一解析入口和 `ChatBubbleSurface` 消费。现有全局 M3 色板、
头像框、思维链容器、顶栏与玻璃背景仍使用原来的独立配置。

气泡支持无底色、纯色、渐变、内置图案（圆点/网格/斜纹）、颗粒噪声和玻璃。
正文支持渐变文字。消息布局通过头部、正文、操作/附加信息插槽替换渲染器。
材质统一扩展气泡契约与共享渲染器，设置页、聊天页、图片导出共用同一实现。

## 配置与兼容

- `ChatTypography`：字体族、本机字体文件、字号倍率。
- `ChatLayout`：布局渲染器、头部显隐、用户消息对齐、正文宽度、消息内部元素间距、
  列表边距与消息间距。
- `ChatBubble`：底色开关、透明度、可选颜色、圆角、内边距；双侧独立。
- `DisplaySetting.toChatAppearance()` 把旧字段投影为统一配置，不做破坏性迁移。
- 新增布局、颜色、几何参数与助手透明度差异存于 `chatAppearanceDetails`。
  字体、共享透明度与助手气泡开关仍以旧字段为持久化权威，不重复保存。
- `withChatAppearance()` 是统一写入口，保留不相关设置并切回自定义。
- 数值在消费前处理非有限值与越界；非法色值回退主题色。
- 未指定气泡颜色时保留已有主题角色颜色，包括用户气泡的角色覆写。

## 插件配置入口

原生插件实现 `PluginChatAppearanceProvider`，返回 `ChatAppearancePreset` 列表。
参考可运行的 `BuiltinChatAppearancePlugin`：默认关闭，在插件中心启用后，进入
偏好设置 → 用户界面 → 聊天外观选择“紧凑”或“阅读”。启用本身不自动替换外观。

桥接器随插件启用注册贡献，资源句柄随停用、卸载或启用失败自动撤销。
方案 ID 为 `插件ID:方案ID`，不同插件可使用相同本地方案 ID。
注册表可观察；旧句柄不能移除后来替换的贡献。

选中插件方案后，渲染时解析该配置；用户自定义配置保持原样。
插件停用或方案不存在时回退自定义，保留选择 ID，重新可用时恢复。
用户编辑任一统一外观字段时，把当前有效外观物化为自定义，再应用该字段修改。
读改写基于 SettingsStore 最新状态，避免快速修改不同字段时相互覆盖。

当前接口供编译进应用的原生插件使用，未开放 JSON 规则或 JS 沙箱外观接口。
`PluginChatAppearanceProvider.chatLayouts` 可提供 `ChatLayoutRenderer`，与方案使用
相同的插件生命周期与注册入口。渲染器接收 `ChatLayoutContext` 和宿主提供的
`ChatLayoutSlots(header, body, footer)`；警告与引用前置区仍由宿主管理。
渲染器只负责布局，消息数据处理、工具确认、消息操作与编辑弹层继续由宿主管理。

内置标准、侧栏、卡片三种布局；内置外观插件还演示了真正由插件贡献的居中卡片布局。
布局 ID 使用 `插件ID:局部ID`，内置布局保留 `builtin.*`；插件停用或布局缺失时回退
标准布局，保留选择，恢复后自动生效。配置型方案与可执行布局均由生命周期资源句柄撤销。

## 导出

图片导出与屏显共用气泡宿主、字体选择、正文缩放与消息布局；保留导出自身的页面排版。
HTML 导出不在本轮范围。

外观快照导出当前解析后的配置，不携带插件选择 ID 或本机字体路径。
旧快照不含新字段时，保留当前额外配置；新快照可以携带额外配置。
快照中的自定义布局引用需要接收设备安装相应原生插件，否则使用标准布局；快照不包含可执行代码。

## 验证

`ChatAppearanceTest` 覆盖旧配置读取、持久化往返、数值容错、命名空间与旧句柄、
真实 PluginManager 启停与卸载、插件方案回退及快照可移植性。
结合现有主题单测验证角色覆写与旧快照兼容。视觉与交互仍需真机验收。

第一批：新增 `BubbleMaterialsTest` 4 项，主题相关 JVM 测试共 30 项通过；
`ChatAppearanceRenderingTest` 提供渐变像素、无色透底、纹理稳定性测试。
隔离测试包能安装到当前 Android 15 设备，但系统在 instrumentation 启动前报
`Unable to find instrumentation info`；Gradle 产生的 0 测试报告不计为通过。
本批未修改设备隐私/包管理配置，也未覆盖用户现有应用。

第二批：正文渐变配置进入排版契约、主题快照及字体编辑 sheet。
BetterText v0.1.5 的 text 模块未消费 `TextStyle.brush`，因此只将该模块以保留 MIT
许可与原包名的 `bettertext-text` 本地兼容模块替换。原版测量、选择、链接逻辑保留；
绘制时按文字尺寸设置 shader，显式前景色 span 清除 shader，绘制后恢复 paint。
Markdown、LaTeX 与 highlight 仍使用原来的 Maven 模块。

玻璃气泡通过列表的字体/外观 Provider 接收背景专用 HazeState，绝不采样消息列表。
API 33+ 使用真实玻璃；31–32 使用背景模糊；更低版本、背景源缺失或后台 gate 关闭时
使用静态透光效果。设置预览与图片导出使用静态效果，不把静态效果声称为背景折射。
不透明度为 0 时不启用背景效果。新增 `TypographyAppearanceTest` 验证字体渐变的
分享/持久化与 SDK 降级策略，渲染测试同时覆盖字体渐变、显式文字色与关闭渐变。

第三批：`ChatLayoutTest` 覆盖新布局参数的分享/持久化、非法尺寸归一、缺失布局回退、
命名空间与旧句柄保护、重复 ID 的原子拒绝，以及真实 PluginManager 对配置与渲染器的
成对启停。渲染测试增加多段正文的顺序和操作回调，防止插槽重构导致正文堆叠或操作丢失。
设置页的所有新多字段编辑器均为 `ModalBottomSheet + Column + verticalScroll + imePadding`。

最终验证：全量 app JVM 单测 3213 项，3211 通过、2 跳过、0 失败；Debug 主 APK 与
AndroidTest APK 构建通过；IME 审计 A=0、A'=0。

Android 15 隔离包的调试预览已实际启动并截图核验：文字红蓝渐变与显式绿色 span
同时可见；无色、纯色、渐变、圆点图案、噪声、真实背景采样玻璃均正常绘制；标准、
侧栏、卡片布局的多段正文与附加信息顺序正常。调试入口为 `SketchTestHostActivity`
的 `appearancePreview=true` Intent extra，仅 debug 包包含；截图生成于
`app/build/reports/appearance/device-preview.png`，不提交构建产物。
这项视觉核查不计为 instrumentation 自动化通过；前述测试启动限制仍然保留。
测试使用独立 applicationId，验证后移除临时 APK，未覆盖用户现有应用。
