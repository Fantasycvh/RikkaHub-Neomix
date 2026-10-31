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

## 落地清单（2026-10-31 评估，均未落地）

| # | 项 | 优先级 | 备注 |
|---|---|---|---|
| 1 | haze 2.0.0-alpha05 → **2.0.1** | P2 | 同族 API（本仓已在用 `HazeInput.Sources`/`HazeBlurStyle.Material3`），预期平滑；**artifact 改名**：`haze-blur-material3` → `haze-blur-materials`；升完 `hazeGlass` 才可用；与上游 2.0.1 同版，后续上游玻璃相关提交才可能直套 |
| 2 | **门控层三件**（SDK 分级 / 前后台 gate / `hazeSourceCompat`） | P2 | 重写成本低于搬运（~100 行小件）；解决后台崩溃 + 费电两个现存隐患；ChatList 兄弟层与输入框两处消费点都挂上 |
| 3 | 上游 `BackgroundEffectType` 二选一模式对照 | P3 | 设置项更整洁；**不用 hazeGlass 替换兄弟层**（兄弟层是真机闪退换来的，替换需完整真机回归） |
| 4 | 可读性模式（稳定内容色 / 自动适配） | P3 | 玻璃上文字是真实痛点 |
| 5 | 玻璃开关 androidTest 冒烟一条 | P3 | 不追 BiliPai 的 51 个，先兜底 |

**明确不做**：全 App 组件级铺开（本仓历史已证伪——三版闪退；BiliPai 敢铺是先有门控层和测试才铺的，
顺序不能反）；miuix-blur 引入（多一个 UI 库依赖，haze 已够）。
