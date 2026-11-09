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
