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
