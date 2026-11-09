# 气泡内置 Shader 模板与参数

## 范围与入口

在既有气泡图层编辑器中添加「内置 Shader」图层，提供极光、流纹、虹彩、水波四种
固定程序。可编辑三色、尺度（32–512 dp）、旋转（0–360°）、对比度（0–200%）、
扭曲（0–200%）与相位（0–100%），可重置当前模板的默认参数。

本轮渲染静态画面，相位用于手动选取画面，没有持续运行的动画时钟。模板切换保留
当前参数，重置按钮恢复该模板的配色与参数。仍使用最多四层的原有栈，支持启停、
排序、透明度与混合；玻璃底层可与 Shader 图层组合。

## 统一配置与输入

`BubbleLayer.kind = SHADER`，参数存于 `BubbleShaderStyle`；旧配置未携带该字段时
使用默认值，旧材质路径保持原样。设置页、插件 typed preset、主题快照、材质包及
备份共用同一配置类型，不需要数据库迁移，也没有新增外部图片资源。

参数契约固定，不保存或执行用户源代码。数值拒绝非有限值并限制范围，颜色规范化为
三个 `#RRGGBB` 值。编辑器保留未完成的输入，预览使用规范化副本；颜色无效时禁用
保存和导出，取消不会提交配置。字段继续位于常驻的可滚动 sheet 中。

## 渲染与性能

Android 13 / API 33 起使用 AGSL `RuntimeShader`，通过 uniform 更新参数。
每个挂载图层持有一个程序实例，拖动参数不会重新编译程序。程序编译或参数更新失败时
回退到栅格后端，软件 Canvas 与低版本设备直接使用栅格后端。

四种场都是周期函数。软件后端按相同公式生成 128×128 的不透明色块，
`BitmapShader(REPEAT)` 使用尺度、旋转及全局偏移绘制；缓存最多 16 块（约 1 MiB），
不主动回收仍可能被其他气泡使用的 Bitmap。尺度和旋转不计入色块缓存键，避免仅调整
变换时重新生成图片。GPU 与软件结果允许低分辨率双线性采样带来的小幅差异。

坐标沿用懒加载气泡片段的共享坐标，既不预先测量整条长消息，也不依赖最终消息高度。
每段绘制相同程序和相位；图片导出使用固定相位的对应材质。整体透明度与图层混合
继续由原来的隔离绘制组处理，不改变正文的布局、选择和点击。

叠加回归同时修正 Android 10 / API 29 起半透明正片叠底的 alpha：使用
`BlendMode.MULTIPLY`，避免旧 `PorterDuff.MULTIPLY` 同时乘低目标 alpha。API 26–28
保留旧的 PorterDuff 兼容合成，因此半透明正片叠底的透明度结果与 API 29+ 有区别；
不透明正片叠底及其他已有模式不受此调整影响。参见官方
[BlendMode 定义](https://developer.android.com/reference/android/graphics/BlendMode)。

参考 Android 官方的 [AGSL 概览](https://developer.android.com/develop/ui/views/graphics/agsl)
及 [RuntimeShader API](https://developer.android.com/reference/android/graphics/RuntimeShader)。

## 验证

JVM 覆盖参数边界、旧配置解码、设置/主题/材质文档持久化、场的周期性与确定性、
四模板差异、配色和零对比度边界。真机覆盖 AGSL 编译与 uniform 复用、GPU/软件
颜色及变换对照、软件 Canvas 绘制、分段相位、材质/主题资源包和编辑器草稿提交。

使用常规 debug 包进行 instrumentation，安装更新前备份数据，使用 `adb install -r`，
不清数据或卸载主包。2026-11-09 验证结果：

- 全量 app JVM：3257 项通过、2 项跳过，0 失败或错误。
- 常规 debug 真机：21 项全部通过，其中 7 项为新增 Shader 测试，另含既有外观与图片图层回归。
- debug 主包及 instrumentation APK 构建成功；IME 审计 A/A' 命中均为 0。
- 安装前备份与测试日志保存于忽略的 `app/build/reports/appearance/`。
