# BiliPai（purebilibili）评估 —— 液态玻璃工程化

> 对应总表 §一 #9。深度：**源码**（本地克隆 `F:\build\projects\BiliPai`，2026-10-31 逐文件读核验）。
> 许可：仓库带 `THIRD_PARTY_NOTICES.md`；本次**只取设计不摘代码**（门控层三件为 Kotlin 小件，重写成本低）。
> 本仓对应的自身历史背景：液态玻璃在本仓 2.4.10–2.4.12 时代做过全主题（方案 B），**三版真机闪退后回退**
> （`hazeBlur` 直套 LazyColumn 测量无限高 2147483647 → 固定尺寸 Box 包裹 → blur 容器不能含可滚动子节点 →
> 兄弟层方案存活）。当前 master 保留：ChatList 兄弟层 16dp 磨砂（`enableGlassTheme` 默认关）+
> 输入框 blur + 背景图双 source（`ChatPage.kt:471`）。

## 一句话结论

液态玻璃「工程化最完整」的参照系：官方 haze 2.0.1 API + **自建门控层**（SDK 分级 / 前后台 gate / 后端兼容探针）
+ **可读性一等设置** + 51 个 UI 回归测试。本仓缺的不是效果本身，是这层防线。

## 三方对照（2026-10-31 核验）

| 维度 | 本仓 | 上游 `a5db9c9b7` | BiliPai |
|---|---|---|---|
| haze | **2.0.0-alpha05**（`-material3` 旧 artifact） | **2.0.1 正式** | 2.0.1 正式（build.gradle.kts 内联 val；另有 miuix-blur 个别组件） |
| 铺开 | 聊天列表磨砂 + 输入框 | **仅输入框** | 全 App 组件级（25 文件：HomeChrome 顶栏 / TabRow / 回顶部 / 评论 pager / 播放条 / CoverFlow…） |
| 路线 | 自研兄弟层（踩坑产物） | 官方 `hazeGlass` + `GlassStyle.Material3` | 官方 API + 门控层封装 |
| 防线 | 开关默认关 | 范围克制（一处消费点） | SDK 分级 + 前后台门控 + 回归测试 |
| 可读性 | 未处理 | 铺底 + 0.3 tint | 一等设置 |

## BiliPai 关键设计（`core/ui/blur/RecoverableVisualEffects.kt`）

1. **SDK 分级门控**：RenderEffect 后端 ≥31(S)；RuntimeShader/HazeEffect 后端 ≥33(T)；home chrome 玻璃 ≥33；
   「回前台重建 HazeState」≥36。低版本自动退化为无效果而非闪退。
2. **前后台门控**：`recoverableBlurGates: WeakHashMap<HazeState, MutableState<Boolean>>`——
   进后台关 blur（后台 RenderEffect 失效 = 崩溃源 + 费电），回前台只在 ≥36 重建（进后台重建无收益，
   还把全屏重录成本推到回前台第一帧）。
3. **`hazeSourceCompat`**：后端不允许的 SDK 上 source 侧也不注册，两端成对门控。
4. **可读性**：`LiquidGlassReadabilityMode`（稳定内容色 / 自动适配）存 DataStore
   （`LiquidGlassSettingsStore`）——玻璃上文字可读性当一等设置做。
5. **测试兜底**：`ChromeBackdropSourceUiRegressionTest` / `GlassCommentPagerGestureTest` /
   `BackToTopBackdropUiRegressionTest` 等（全仓 51 个 UiRegressionTest/Glass 测试）。

上游对照：`BackgroundEffectType.BLUR/GLASS` 二选一（默认 BLUR，`Settings.kt:168`），
GLASS 走 `hazeGlass` + `GlassStyle.Material3`（`ChatInputHaze.kt`，铺底 + 30% tint + 暗色适配）。
上游不闪退的秘诀就是「只铺输入框一处」。

## 落地清单（2026-10-31 评估；同日全量落地 ✅，见文末落账）

| # | 项 | 优先级 | 备注 |
|---|---|---|---|
| 1 | haze 2.0.0-alpha05 → **2.0.1** | P2 | ✅ 已落地。同族 API（本仓已在用 `HazeInput.Sources`/`HazeBlurStyle.Material3`），平滑。**勘误**：`haze-blur-material3` 在 2.0.1 仍在 Maven 发布（上游 a5db9c9b7 也继续在用），**无需改名**；`HazeMaterials.*` 在独立 `haze-blur-materials` 里，不改不缺功能。真玻璃在独立 `haze-glass`/`haze-glass-material3`（`hazeGlass` + `GlassStyle.Material3`，`@ExperimentalHazeApi`），本仓已一并引入 |
| 2 | **门控层三件**（SDK 分级 / 前后台 gate / `hazeSourceCompat`） | P2 | ✅ 已落地。`ui/components/haze/RecoverableVisualEffects.kt`（按本仓重写，~150 行）：blur 后端 ≥S(31) / 玻璃后端 ≥T(33) / 回前台重建 ≥36；前后台 gate 用 `ProcessLifecycleOwner`（BiliPai 用自家 BackgroundManager），按 state 存 WeakHashMap；`recoverableHazeBlur` 把 gate 写进 `blurEnabled(...)`。三处消费点（ChatPage 背景双 source + 顶栏、ChatList 兄弟层 + 预览列表、ChatInput）全部挂上 |
| 3 | 上游 `BackgroundEffectType` 二选一模式对照 | P3 | ✅ 已落地（`BackgroundEffectType.BLUR/GLASS`，默认 BLUR，入 DisplaySetting + 设置页 SegmentedPill，开模糊开关才露出）。输入框 GLASS 走 `hazeGlass` + `GlassStyle.Material3`（<T(33) 或后台 gate 关时退回 blur 路线）；**兄弟层未替换**（真机闪退换来的，替换需完整真机回归） |
| 4 | 可读性模式（稳定内容色 / 自动适配） | P3 | ✅ 已落地。`LiquidGlassReadabilityMode.AUTO/STABLE_CONTENT` 入 DisplaySetting（默认 AUTO）；AUTO = 现状 Material3 铺底，STABLE_CONTENT = 兄弟层/顶栏 `containerColor=surfaceContainerHigh` 厚铺底、输入框玻璃 tint 0.3→0.55；设置页在任一效果开启时露出 |
| 5 | 玻璃开关 androidTest 冒烟一条 | P3 | ✅ 已落地。`ui/components/haze/LiquidGlassSmokeTest.kt`：兄弟层开关挂载/卸载 + SDK 分级策略断言（复用 SketchTestHostActivity，低版本设备因门控自动退化同样应过） |

**明确不做**：全 App 组件级铺开（本仓历史已证伪——三版闪退；BiliPai 敢铺是先有门控层和测试才铺的，
顺序不能反）；miuix-blur 引入（多一个 UI 库依赖，haze 已够）。

## 落地勘误与备忘（2026-10-31 实施时核对源码所得）

1. **artifact 结论修正**：评估稿里「material3 改名 materials」只是 BiliPai 的选择（它内联了 `haze-blur-materials`）；
   `haze-blur-material3:2.0.1` 在 Maven 正常发布且只含 `blur.material3` 一个包，与 `haze-glass` 无类冲突，保留可零改动通过升级。
2. **同步后增量**：BiliPai 同日快进 299 提交到 v0.7.1，门控层核心未变，新增 `recoverableBlurEnabledState()`（响应式 gate 读取，
   本仓未搬——消费点已在 `recoverableHazeBlur` 内读 gate，无此需求）；`HazeInput.Backdrop(fallback)` 官方背景采样为清单外增强，暂不采用。
3. **BiliPai 未用 hazeGlass**（2.0.1 下），全 App 玻璃实际仍是 haze-blur 材质；真玻璃（RuntimeShader）只有上游在输入框一处用了，
   本仓 #3 的 GLASS 路线即对齐上游实现（含 `appearanceResponse` 明暗预设写法）。
4. **兄弟层已退役（2026-10-31 实验）**：本仓评估稿里「不用 hazeGlass 替换兄弟层（真机闪退换来的）」的禁令针对的是 haze 2.0.0-alpha05
   时代的父子拓扑闪退；haze 2.0.1 + 门控层下把 ChatList 玻璃层改回**父子层**（blur 挂包含 LazyColumn 的父容器，仍只采背景），
   真机初测无闪退——「blur 容器不能含可滚动子节点」约束疑似已解除。观察期后若稳定，2.4.10~2.4.12 的历史教训应改写为「alpha05 时代的约束」。
5. 真机验证待办：blur/glass 在 ≥31 真机冒烟一遍（后台→回前台往返是历史闪退场景，重点回归）；父子层玻璃同样需过这轮。

## 2026-10-31 → 11-02 增量评估（2026-11-03，`8486dbf7d..7fec7694`，50 提交）

50 条中 38 条为 B 站播放器/信息流/评论/空间域（不适用）；路径过滤命中 12 条玻璃/动效/门控，精读 6 条。**门控层核心 `core/ui/blur/RecoverableVisualEffects.kt` 本批未变**。总评：**吸收 1 / 候选 1 / 其余不适用**。

- **吸收：`ReduceMotion.kt` + `VisualWorkPolicy.kt`**（`67a81ff4f` 等批次使用）。前者 40 行读 `Settings.Global.ANIMATOR_DURATION_SCALE` 并用 `ContentObserver` 实时跟随（注册后再同步一次防窗口期漏变）；后者 20 行纯函数 `shouldRunVisualWork(pageActive, resumed, focused)`，消费方 `DecorativeAnimationGate` 组合「组件开关 && !系统减弱动效 && 窗口聚焦 && 可视」。本仓 `ui/components/haze/RecoverableVisualEffects.kt:55-60` 只有 `userEnabled && !background`，全仓无「系统移除动画」/窗口焦点判断（grep 零命中）。建议：`ReduceMotion` 整件移植；焦点 gate 先只用于装饰动效、模糊侧观察一版再加（避免焦点抖动闪断）；补一条 gate 组合表纯 JVM 单测。**✅ 已落地 `3254e33b0`（2026-11-03）：`ui/components/motion/{ReduceMotion,VisualWorkPolicy}.kt`，接入渐变背景装饰动效；模糊侧仍保守未接。**
- **候选：玻璃参数实时预览**（`LiquidGlassLivePreview` 同面板调参 + 当前参数渲染）。本仓玻璃设置页 5 组，改完要退出看聊天页；建议预览用静态采样背景，不新增 HazeState 消费点。
- **不适用**：弹性弹窗 / 液态形变（`5c44a5a45` `ElasticPopupMotion.kt` 156 行）· 同窗玻璃弹窗（`71892cc2c`，玻璃弹窗必须同 Window 才能采到 Activity 内容）· 联动玻璃底栏（`f57790887` 386 行）· 重力高光传感器（`0ecc4146c`）· 手写渐进模糊 `RenderEffect`（`OfficialProgressiveTopBlur.kt`——**本仓三版闪退坑族**，只在将来做顶栏渐进模糊时参考其 shader/效果缓存写法）。

## 2026-11-02 → 11-08 增量评估（2026-11-08，`7fec7694..f87566b86`，108 提交）

108 条中约 85 条为播放器/弹幕/直播/番剧/评论/搜索/WBI 签名/官方消息 API/图标与发版本（不适用）。**门控层核心 `core/ui/blur/` 本批零改动，无需跟进**。总评：**吸收 0 / 候选 2 / 判据记账 8 / 不适用其余**。

### 候选（2）

1. **动效令牌收口（`d271384dc`/`e214b59df`/`f4dd3bf62` 批次，`AppMotionTokens.kt:85-189`）**——形状：`object AppMotionTokens` + 顶层 tween 工厂（emphasizedEnter/Exit/continuity/iosMorph）+ 命名 spring 族（softLanding/interactiveSnap）+ `resolveXxxSpec(uiStyle)` 按 MIUIX/M3 双风格分支、时长单源。本仓 grep 核实：**全仓零中央动效令牌**（无任何 `CubicBezier`/命名 easing，`tween(` 散落 ~8+ 组件各写各的）。对本仓不必抄双风格分支，抄「object + @Composable accessor + 命名时长/弹簧」形状即可，~0.5 天，属于设计卫生件——聊天 App 动效面小，优先级低于 nmoi 候选池。
2. **TextFieldState 单向同步纪律（`0125561e6`，SearchScreen.kt:699/1150）**——判据四条：编辑态唯一权威是 `TextFieldState`（VM 字段只是投影）；`snapshotFlow { text }` 单向同步；程序化写入先比较再 `setTextAndPlaceCursorAtEnd`；**绝不向活跃 IME 会话回写**。与本仓 AGENTS.md「TextField Host Rules」同族但互补——那是宿主拓扑规范，这是**状态流向**规范（本仓历史坑「input text 丢字符」「整对象写丢更新」即同族病）。建议沉淀为 AGENTS.md 规范第 4 条扩展判据，不移植代码。

### 判据记账（不立项，备查）

- **玻璃预设配方 progress 曲线 + 一键预设原子提交**（`0e1871492`，`LiquidGlassTuning.kt:14-40/107-124`）：20+ 参数不各存各的预设值，而是收成 progress 三锚点（start/mid/stop）曲线上的一个锚位（CLASSIC_FROSTED=0.85、BALANCED=0.5）；纪律 = 一键预设**同时**提交材质 progress + 高级参数（`setLiquidGlassAdvancedSettings(presetProgress:)`），防「半套配方」。本仓若将来开玻璃调参面板（上次候选的 LivePreview），用这套曲线 + 原子提交。
- **场景选择与总开关解耦 + 迁移位**（`7ddad7215`，`AppLiquidGlassSettings.kt:4-33`）：8 场景 Int 位掩码独立保存，总开关关闭不清空场景选择；迁移位 `SPLIT_SCENES_SAVED` 防旧值覆盖。本仓三场景粒度已等价，判据只在「迁移位防覆盖」。
- **MaterialBackdropBlur 磨砂参数**（`MaterialBackdropBlur.kt:14-28`）：经典磨砂强制 `downscaleFactor=1`、sigma=radius×0.45、padding=3σ，避免大半径降采样放大滚动闪烁。对磨砂档通用，本仓 ChatList 磨砂若出现滚动闪烁可对照。
- **转场位图坑族**（`SystemVideoCardTransition.kt` 系列，对任何做位图快照的 App 通用）：① HARDWARE 位图进软件 Canvas 直接 IAE → 建同配置 HARDWARE 目标+硬件画布（`8e4139272`）；可变位图建不了 HARDWARE → 反向 `PixelCopy` 落软件（`69b43674d`），PixelCopy 位图重载 API29+ 且 SAM 推断失败 → `source.copy(ARGB_8888,false)`（`482f6a6d0`）；② 引擎放大透明位图透出双层内容 → 先 drawColor 来源页底色再 drawBitmap（`d6af1f95c`）；③ **渐进模糊首帧闪烁**：恒定半径模糊只靠 layerAlpha 淡入时来源页不先 drawContent，开场 alpha≈0 透出窗口底色 → 锐利原层按 (1-blurAlpha) 垫底参与混合，两层均录 GPU 层（`ab16ad9c4`，`VideoCardTransitionSnapshotLayer.kt:203`）——对 haze 转场最有价值的一条；④ 软件化/合成失败一律 `rejected("source_pixels_unavailable")` 显式降级回窗口内导航，不硬崩。
- **KILL 前快照结构**（`f12f263a9`/`a1b9ced97`，`FairMemoryReceiver.kt:23-55`）：依赖 ITGSA 厂商广播不可移植，但结构判据通用——终止前 2.5s 预算内（goAsync+FLAG_ONEWAY）只采「导航栈取有 windowFocus 的 activity + 滚动锚点 + 播放断点」；快照带 processSessionId/账号 mid 校验防串号、consume 一次性消费、恢复前先清旧请求态。可对照本仓后台任务与对话恢复。
- **FirstFrameTracker**（`0205f28dc`）：首帧归属 player 而非页面（WeakHashMap<Player,StateFlow<Boolean>>，mediaItemTransition 非 REPEAT/STATE_IDLE 复位），跨页面复用真实首帧不回落封面。本仓暂无视频场景，做附件媒体预览时可迁移。
- **FormatUtils 收口**（`b6c1aa081`）：数量/相对时间格式化单源、真实 0 与未知占位区分（净删 188 行）。卫生判据，本仓将来 i18n 盘点时顺手对照。
- **磨砂 fallback 判据**（`084389cd0`）：关玻璃后悬浮底栏 fallback 由壳层 Modifier 画磨砂、内部 Transparent。本仓可读性模式已等价，仅记形状。

### 不适用

播放器域（竖屏精简/UP 主信息/弹幕样式/耳机 HOOK/媒体键/片尾恢复/直播分块/番剧改版/听视频胶囊）、评论楼中楼与 gRPC、搜索联想隔离重构、WBI key 刷新（`e0306d0cf`）、官方未读 API（`d5c8572b6`）、图标探索归档（`82d037c0f` 等）、发版与致谢件。
