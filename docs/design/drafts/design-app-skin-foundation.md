# AppSkin 契约、统一解析与基础组件

顶栏、抽屉与复杂卡片的后续接入见 [AppSkin 槽位说明](design-app-skin-panels.md)。
其余原生组件接入见 [组件迁移说明](design-app-skin-component-migration.md) 与
[覆盖清单](app-skin-component-coverage.md)。
WebView、终端与画布见 [嵌入式渲染器说明](design-app-skin-embedded.md)。
本地资源包与安装管理见 [皮肤资源包 v1](design-skin-packages.md)。
跨页面预览、撤销与资源包版本切换见 [试穿与回滚说明](design-skin-trials-and-rollback.md)。
全路由明暗、大字体、对比度与真机性能基线见 [皮肤验收](design-skin-acceptance.md)。

## 本轮范围

`AppSkin` 提供全局字体/字号、Material 五级形状、分组卡片/按钮/输入框圆角、
基础间距、标准/灵动动效，以及可选配色。`ResolvedAppSkin` 同时提供全局皮肤与既有
`ChatAppearance` 聊天子域；界面通过 `resolveAppSkin` / `rememberAppSkin` 读取同一
解析结果。既有聊天外观接口成为统一解析器的兼容入口。

本轮不替换所有页面的宿主布局，不提供任意代码执行的皮肤脚本，也不新增数据库表。

## 持久化、优先级与回退

配置新增 `DisplaySetting.appSkinId` 和 `appSkinOverrides`，仍由现有 DataStore 保存。
无新增配置时与旧外观一致。优先级为默认全局参数 → 选择的内置/插件皮肤 → 用户覆写。
覆写按字体、形状、间距、动效、配色分组；只在某组有编辑时建立该组覆写，未编辑组
继续随皮肤更新。切换皮肤保留用户覆写；「清除覆写」恢复继承，「恢复默认皮肤」清除
选择与全局覆写。上述操作不改聊天专属配置。

聊天预设和聊天自定义继续遵循原规则，避免选择全局紧凑方案时意外重置气泡/Shader。
聊天字体的「默认」继承全局字体；显式聊天字体优先。代码块等已指定的等宽字体保留。

皮肤提供配色时以该配色为基础；未提供时沿用当前动态/预设/自定义主题。既有用户角色
颜色覆写继续在其后应用，AMOLED 对背景/容器的约束最后应用。系统字体缩放仍由 sp
正常参与，不修改 Density 或系统无障碍设置。

`PluginAppSkinProvider` 使用宿主插件生命周期桥注册，ID 按 owner 命名空间隔离，
插件停用移除贡献；缺失方案回退默认并保留用户覆写和选择 ID，重新启用时恢复解析。
旧句柄关闭不会移除新贡献。保留 `builtin` 命名空间，防止覆盖内置方案。
已有「聊天外观方案」示例插件新增一个全局柔和阅读方案。

## 已接入的组件与设置

- 根 `RikkahubTheme` 应用全局 Typography（普通和强调样式）、Material Shapes 与 MotionScheme。
- `CardGroup` 的内外圆角、分隔间距、表单留白与内容间距；`FormItem` 的基础横向间距。
- 共享数字输入、选择输入、多行编辑器；新增 `AppButton`、`AppOutlinedButton`、
  `AppTextButton`、`AppOutlinedTextField`，保留 Material 的输入、焦点、点击和无障碍行为。
- 用户界面/主题设置页，以及聊天材质/Shader 配置中的基础按钮和输入框；这两个设置页的
  页面留白与分区间距也读取皮肤。

显式传入的组件 shape 优先于皮肤默认值。其他页面直接使用的 Material 按钮或硬编码
形状仍需后续迁移，顶栏/抽屉/复杂工具卡片的独立材质与布局插槽也不属于本轮覆盖范围。

入口为「偏好设置 → 用户界面 → 应用皮肤」。内置默认、紧凑、舒展阅读三个方案。
编辑器使用 `ModalBottomSheet + Column + verticalScroll + imePadding`；参数只改本地
草稿，组件预览在局部 `AppSkinTheme` 中展示。保存才写配置，取消不改变当前应用皮肤。
VM 更新基于最新设置变换，不用旧整对象覆盖并发更新。

## 字体与分享边界

全局字体使用独立的 `skin_font.<uuid>` 文件和临时文件，导入最多 16 MiB，使用 Typeface
验证后原子改名，不替换或删除其他聊天/皮肤引用的字体。目录复用已纳入备份的 `fonts/`。
取消草稿可能留下未引用字体，本轮不做可能误删在用资源的自动回收。

主题外观快照可携带已解析的全局参数，导出不依赖插件 ID；自定义字体本机路径会清空，
并回退系统字体，字体文件打包留待统一皮肤资源包阶段。旧快照不含 `appSkin` 时保持接收端
全局皮肤不变；不使用全局方案/覆写时也维持旧快照形状。完整本机备份仍保留字体文件。

## 验证

JVM 覆盖旧配置兼容、优先级、组继承、插件替换/停用/恢复、数值边界、设置持久化、
主题快照迁移和字体路径剥离、普通/强调 Typography。真机覆盖默认外观像素、热切换时
输入与焦点保留、插件恢复、显式 shape 优先、聊天字体继承、草稿保存/取消、字体导入隔离。

使用常规 debug instrumentation，安装前备份数据库与设置，使用 `adb install -r` 更新，
不清数据或卸载主包。2026-11-09 验证结果：

- 全量 app JVM：3267 项通过、2 项跳过，0 失败或错误；其中新增 AppSkin JVM 测试 7 项。
- 常规 debug 真机：29 项全部通过，含 8 项 AppSkin 测试与既有 21 项外观/材质/Shader 回归。
- debug 主包与测试 APK 构建成功；IME 审计 A/A' 命中均为 0，既有 B/C 告警未增加。
- 数据备份、JVM 汇总与 instrumentation 日志位于忽略的 `app/build/reports/appearance/`。
