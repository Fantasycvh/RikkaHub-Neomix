# AppSkin 组件迁移

## 统一基础控件

六类基础控件（Button、OutlinedButton、TextButton、FilledTonalButton、OutlinedTextField、
TextField）通过 `AppSkinComponents` 统一提供视觉默认值。迁移使用 import alias，保留各页面
的调用结构、回调和编辑宿主；显式 shape / ButtonShapes 优先。输入框支持 String、
TextFieldValue、TextFieldState，透传转换器、滚动状态、布局和 IME 回调。

Filled TextField 保留 Material 的方形底边。媒体创作页原有按钮按压形变继续存在；默认保持
原尺寸形变，选择按钮圆角覆写时使用该圆角及其 70% 的按压圆角。

第一批迁移 141 个文件、767 处调用。启动恢复屏使用不依赖设置加载的 GateTheme，保留
原生控件；统一封装内部的 Material 委托也不属于遗漏。

验证：完整 app JVM 测试 3274 通过、2 跳过；debug 与 androidTest 构建通过；真机新增
5 项测试通过，覆盖默认像素、附着/上方标签、输入转换和 IME、选区/组合区、按钮语义。
测试使用 install -r，先备份调试版数据，不卸载或清空。

## 自定义宿主与覆盖清单

逐项覆盖和例外清单见 [组件覆盖清单](app-skin-component-coverage.md)。

第二批将 91 处常规固定圆角接入统一缩放函数，并继续迁移胶囊操作、选择器、分段按钮、
半圆角编辑容器、历史条目和素材缩略图。弹窗、错误卡片、权限卡片、图表外框、
消息附件、引用、工具详情、代码预览及主题设置里的外框均从同一形状入口读取。
引用条、波形端点、进度条端点等结构标记保留原几何；独立槽位/聊天子域/插件显式覆写仍优先。

`AppSkinShapes.scaledCornerRadius` 在 0/4/8/12/16/28dp 参考点间插值；大于 28dp 按
extraLarge 比例缩放，上限 64dp。默认配置精确保留 6/10/18/20/24/32/48dp 等宿主圆角。
实现不在逐帧调用中分配参考点列表。Material beta02 额外的 largeIncreased(20)、
extraLargeIncreased(32)、extraExtraLarge(48) 也通过同一映射生成，不再固定在库默认值。

紧凑文字、代码、diff、工具摘要、工作区文件编辑器等原先显式写 sp 的界面通过
`appSkinTextSize` 读取全局字号比例。指定的代码等宽字体保留，em/Unspecified 不重复缩放，
已有 Material/chat 计算后的字号也不再额外乘比例。Emoji 图形和预览样例字号保持独立。
输入框封装同时对齐 beta02 的 Inside/Cutout 默认标签位置与相应内容间距，兼容 Above。

审计补查了完全限定的 Material 调用，额外迁移 6 处。最终应用源码中 813 处六类基础
控件调用走 AppSkin，4 处启动恢复调用保留原生；33 处圆角构造逐项登记理由。
这些数字仅描述受审计的入口，不能等同于整个应用的换肤完成率。

## 防回归与验证

- `python -B scripts/audit_app_skin.py` 检查原生调用、别名、完全限定调用和直接圆角构造；
  新绕过、数量增加和过期例外都会失败。`--write-report` 刷新本地覆盖清单草稿。
- `python -B scripts/test_audit_app_skin.py` 的 5 个回归用例覆盖别名/完全限定绕过、
  字符串/注释误报、嵌套形状定位和统一封装委托。
- IME 审计现在同时识别 AppTextField/AppOutlinedTextField；A=0、A'=0。
  B=4、C=33 为现有宿主及启发式告警，本轮没有新增表单宿主。
- 最终 JVM 测试 3275 通过、2 跳过；debug 和 androidTest 构建通过。
- 最终真机 69 项全部通过，包括新增的 10 项基础控件/宿主测试及既有 59 项材质、
  皮肤、抽屉手势和导航测试；默认像素、实时换肤、弹窗返回关闭、菜单选择、分段按钮
  语义/触控高度、代码和 diff 字号缩放均有覆盖。首轮对照夹具误放了一个额外输入框，
  已修正后完整复跑通过。

## 尚未覆盖的协议边界

WebView、终端 AndroidView、草稿纸画布、公式和 SVG 的内部内容主题不属于本轮原生
组件迁移。图表数据标记、语义状态色、头像裁切以及宿主结构/触控尺寸也不能统一按
装饰参数替换。未知插件渲染器仍可提供显式外观；完整资源包管理、整皮肤试穿撤销、
所有页面的大字体/对比度/性能验收另行推进。
