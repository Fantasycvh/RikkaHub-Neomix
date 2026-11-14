# AppSkin 明暗、大字体、对比度与性能验收

## 范围与门槛

页面清单取自生产 `AppNavHost.kt` 的全部 78 个导航入口。测试使用真实 `appEntryProvider`、
Navigation 3 状态宿主、ViewModel 和仓库；聊天、助手、提供商/模型、知识库、禁用的定时任务、
已完成运行、工作区/文件、插件、提示词条目、技能与自有 HTML 均使用本地合成数据。
媒体生成保留未配置提供商的空状态，终端保留未安装 rootfs 状态；嵌入式终端、画布、WebView
的实际渲染与交互继续由已有专项真机测试覆盖。

- 页面矩阵：360×720dp，明/暗 × 系统字体 100%/200%。每页等待加载结束并滚动检查；
  最多 40 个视口，遇到不能继续的滚动容器会在报告注明，没有把首屏代替整页。
- 大字体 Activity 通过局部 Configuration 设置 fontScale=2，不修改手机系统设置。
  这样独立 Dialog/BottomSheet 也得到实际 200% 字号；独立窗口沿用 Activity 窗口尺寸。
  只有 Compose 字号覆写会漏掉这些窗口。
- 额外在两种模式下验证皮肤字号 1.5 × 系统字号 2 的皮肤编辑器，检查实际 TextLayout 的
  fontScale 和字号，再操作保存、取消、跨页面试穿。
- 文字裁切：读取实际 TextLayout，区分编辑器滚动视口、明确的省略号与意外裁切。
  排除 1px 内取整误差，行宽按 right-left 计算，避免把居中排版偏移误判为裁切。
  所有页面检查完才断言零意外裁切，保留失败页的诊断和截图。
- 对比度：正文/控件文字采用 4.5:1，检查 7 套预设的明暗角色对，以及 9 种生成风格 ×
  4 个种子 × 3 档对比度 × 明暗。自动字色另以 4096 个 sRGB 背景采样回归。
- 性能：真实帧时钟与 Window.FrameMetrics，120 张惰性卡片，默认/阅读/字号1.5的
  Shader＋颗粒叠加，重复切换和滚动 12 轮。预热后每轮 P95 <32ms、最大帧 <250ms，
  后续循环结束的进程 PSS 相对预热后增长 <32MiB。内存比较在帧采样窗口外、相同皮肤下
  请求 GC 并静置后进行，原始 PSS 同时保留，区分临时分配与留存；这不是持续泄漏证明。
  帧采样还要求监听器无丢样。测试记录全部实际分布，而非仅记录通过。

对比度口径参考 [W3C 正文对比度说明](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)，
200%/非线性字号测试参考 [Android 字体缩放说明](https://developer.android.com/about/versions/14/features#non-linear-font-scaling)。

## 本轮修复

- 黑白预设的浅色 secondary/暗色 error 文字、极简与 Claude 的次文字、Claude 的浅色
  主按钮文字达到正文对比度门槛。表单说明提高不透明度，JSON 树使用随主题变化的语义色。
- 自动气泡字色根据实际 WCAG 对比度选色，中间亮度不再仅靠 HCT tone=50 分界。
  用户明确指定的文字/背景色仍按原契约生效。
- 权限组件和聊天抽屉从 Activity 入口取得宿主，支持带配置包装的 Context；权限状态的
  启动器、生命周期观察和初始化同步跟随状态实例更新。
- 主题页操作组与提供商操作组允许换行；模型选择值移到支持内容，给长标题保留宽度。
- 提示词拼装检查的元数据允许换行；聊天上下文读数在窄空间明确省略，可点击进入完整详情。
- 定时任务整页可滚动，避免权限提示在大字号下占满页面后把任务列表截掉。
- 缓存迁移说明与操作分行，技能长简介明确使用省略号，终端加/关操作使用矢量图标。
- 统计热力图的行高、月份栏与星期栏按实际文字测量，并随系统/皮肤字体增大；标题与指标选择分行。
  头像 Emoji 按图标容器尺寸绘制，避免装饰图形被正文缩放挤出容器。
- “关于”页粒子循环在空闲时挂起，不再持续请求动画帧；有粒子才唤醒并重置帧时间。
- 皮肤编辑器底部操作允许换行，在实际大字体弹窗里保持保存、取消和试穿可达。

## 复现与证据

先构建同包 debug 和使用隔离启动器的测试 APK：

```powershell
.\gradlew.bat -PlongTextBaseline=shared :app:assembleDebug :app:assembleDebugAndroidTest --offline
python -B scripts/run_skin_acceptance.py --adb <adb路径> --serial <序列号>
python -B scripts/audit_skin_acceptance.py --results <报告目录>/skin-acceptance --report docs/design/drafts/skin-acceptance-matrix.md
```

构建可按本地 JDK 配置添加 `-Dorg.gradle.java.installations.paths=...`。
复用既有 LongTextBaselineRunner 只为了抑制生产 Application 启动；本轮仍用常规 debug 包，
没有依赖新装隔离包的绑定行为。测试自行启动私有 Koin 图、内存 Room 和独立 DataStore；
测试设置、数据库与工作区文件使用私有目录或内存库，测试结束销毁页面再关闭依赖。
网络客户端拒绝请求，定时任务不启用，不触发真实模型、SSH、下载或通知操作。

脚本只用 install -r；安装前备份现有 debug 数据，不卸载、不清数据。`--no-install`
用于复用已安装的匹配 APK。日志、截图、矩阵 JSON、帧分布与私有备份均留在忽略的
`app/build/reports/appearance/skin-acceptance-*`，不进入仓库。am instrument 的退出码
不能证明成功，脚本另外检查 JUnit 完成标记；覆盖审计还拒绝漏路由、缺截图和文字裁切。

## 结论的适用范围

2026-11-10，PJX110 / Android 15（SDK35）、常规 debug 包，最终验收：

| 项目 | 结果 |
| --- | --- |
| 页面矩阵 | 78×4=312 个页面配置，792 个滚动视口；全部到底，意外文字裁切 0 |
| 实际大字体编辑器 | 明暗两项通过；弹窗 TextLayout 验证 fontScale=2、皮肤字号=1.5 后操作三个按钮 |
| 独立验收 JUnit | 四项页面矩阵＋两项编辑器＋一项性能，共 7 项通过 |
| 常规 debug 真机回归 | 105 项通过，覆盖既有皮肤、资源包、试穿/回滚、嵌入式渲染器与抽屉交互 |
| JVM | 3293 通过，2 跳过；对比度三项均通过 |
| 性能，预热后 9 轮 | P95 7.0–8.8ms；最大帧 58.7ms；>32ms 为 17/1342 帧，丢样 0 |
| 静置回收后 PSS | 483008→482220KiB，变化约 -0.77MiB；保留原始逐轮 PSS |

完整原始证据：`app/build/reports/appearance/skin-acceptance-20261110-174232/`。
常规回归日志：`app/build/reports/appearance/skin-acceptance-regression.txt`。
另对聊天、模型设置、统计图等代表页面做截图抽查；没有将每张截图都声称为人工审查。

逐路由结果见 [生成矩阵](skin-acceptance-matrix.md)。本机页面语言为 zh-CN，合成数据中也含英文长文本。
这是指定测试数据与窗口配置下的验收，
没有把它换算成整个应用或所有皮肤的百分比。

性能结论限本次 Android 真机的 debug 构建及所记录的卡片工作负载。发布版 Macrobenchmark、
冷启动、低端设备/长时间温升、电量、真实长对话/大量图片等仍需专门测量。
对比度结论覆盖内置色板、生成配色与不透明纯色的自动字色；任意用户覆写、透明/渐变/纹理/
玻璃叠加、插件内部 UI 和远程网页内容需要独立检查。页面矩阵不穷举所有弹窗、数据状态、
横屏/平板、多窗口或 TalkBack 操作，也不构成完整的无障碍合规认证。
