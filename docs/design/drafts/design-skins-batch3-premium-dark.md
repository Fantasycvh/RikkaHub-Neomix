# 皮肤第三批设计稿：高级感 / Premium Blue（含图标色块与功能快捷 Banner）

> 状态：**M1 契约、M2 组件已接入（2026-11-24），M3–M4 待推进**
> 日期：2026-11-24
> 配色：**低饱和浅蓝系**（按用户要求由初版深蓝紫修订）
> 视觉稿：[`原型/RikkaHub-UI第三批-高级感视觉对比稿.html`](../../../原型/RikkaHub-UI第三批-高级感视觉对比稿.html)
> 上游：[UI 改造调研](design-ui-overhaul-research.md)、[第三轮几何/动效皮肤](design-ui-round3-skins-geometry-motion.md)、[第二批工程感/iOS 风](design-skins-batch2-engineering-ios.md)
> 约束沿用 AGENTS.md：皮肤 token 契约、IME 宿主规范、`supportingContent` 单子节点坑、长文本增量渲染、AMOLED 与低版本效果回落。
> 两稿合并后的实施准备见 [§13](#13-结合-ui-改造调研的实施准备)。

---

## 0. 本批提案（尚未拍板）

| # | 拍板点 | 本稿建议 |
|---|--------|----------|
| 1 | 档位名称 / id | **高级感** / `builtin.premium-dark`；成为第 8 个内置预设、第 5 档风格皮肤 |
| 2 | 核心视觉 | 近黑冷灰 tonal 底 + **低饱和浅蓝渐变** Banner + 40dp 圆角图标色块 + quiet dark 卡片 |
| 3 | Banner 语义 | 不采用付费墙或「高级版解锁」；作为**单一功能快捷入口**，默认「让 AI 定时为你办事」→ `Screen.SettingScheduledTasks`。任务中心开启时改跳 `Screen.TaskCenter`，也可由宿主换为现有任务/工作区入口 |
| 4 | 浅色策略 | 皮肤不强制深色；深色用截图渐变，浅色用同色相浅渐变，全局明暗设置仍是唯一权威 |
| 5 | 分组默认形态 | `FLAT` 逐行卡片，贴近截图；编辑器仍可切现有 `INSET` 整组分隔 |
| 6 | 聊天域 | 新增 `builtin.chat:premium-dark` 并绑定；用户解绑后继续使用现有独立聊天方案 |
| 7 | 输入栏 | 本批**不改输入栏宿主拓扑**；视觉稿里的胶囊输入栏仅作气质参考，pill 化另案评估 |
| 8 | 外部组件库 | SaltUI / miuix / compose-cupertino 均纳入综合参考；本批默认不新增依赖，选择性能力先过 §2.1 准入门槛 |

---

## 1. 目标与非目标

### 1.1 目标

1. 把两张参考截图中的「Soft Dark Premium」拆成可切换、可编辑、可试用/撤销的 AppSkin，而不是写死在页面里的主题分支。
2. 新增两种本仓此前没有的视觉能力：
   - 列表 leading 图标的**可选色块容器**；
   - 设置页顶部的**深浅色双渐变 Hero Banner**。
3. 复用现有 `CustomTheme`、`AppSkinGroups`、`ChatAppearance`、皮肤试用与聊天绑定机制，保持 Material3 / AppSkin 为唯一全局主题权威；外部组件库只作能力参考，任何选择性依赖另案批准。
4. 老皮肤、旧 JSON、插件皮肤与默认皮肤保持原行为；新能力只在显式 token 下启用。

### 1.2 非目标

- 本批不直接引入 SaltUI / miuix / compose-cupertino，也不叠加第二套全局主题；但允许按 §2.1 综合评估，后续若引入只做隔离 spike，不改默认皮肤与主路径。
- 不做付费墙、不制造「免费版功能受限」语义；Banner 只快捷进入现有功能页。
- 不因 Banner 新增「高级功能」信息架构分组，不复制设置入口；默认只承载一个主行动。
- 本批不把记忆、MCP、搜索、备份、工作区等全部功能塞进 Banner；多入口总览会增加皮肤组件与页面状态耦合。
- 不改 M3 颜色角色，不新增 surface 角色；截图颜色作为 seed / Hero 渐变起点，不整页写死。
- 不改聊天输入栏、长文本渲染、消息增量更新或 IME 宿主。
- 不做主题分享格式扩展、不做付费墙、不把 UI 简化提案 P1-3/P1-4 混进本批。

---

## 2. 现状核对（决定最小改动面）

| 能力 | 当前实现 | 本批处理 |
|------|----------|----------|
| 内置皮肤 | `AppSkinRegistry.kt:80-132` 已有 default / compact / reading / squircle / paper / engineering / ios，共 7 个 | 追加第 8 个 `builtin.premium-dark` |
| 皮肤组 | `AppSkin.kt:8-28` 已有 typography / shapes / spacing / motion / palette / panels / groups 等 | 末尾新增 `hero`，不重排既有字段 |
| 分组形态 | `AppSkinGroups.kt:7-45` 已有 `FLAT / OUTLINED / INSET` 与分隔线 | 复用；不新增 enum |
| 面板材质 | `AppSkinPanels.kt:8-59` 已有通用 panel、材质与 5 个 slot | Hero 有深浅色双渐变与装饰语义，**不塞进通用 panel slot** |
| 图标容器 | `CardGroup.kt:223-252` 的 `CardGroupIcon` 固定 40dp 圆底 + 20dp 图标 | 增加可选 tile token；null 时保持原圆底 |
| 设置页 leading 图标 | 本批 9 个文件有 53 处简单裸 `Icon`，其中 1 处是设置首页的 API 配置告警 | 52 处普通入口接入默认等价的皮肤感知包装器，告警保留原绘制 |
| Hero 宿主 | `SettingPage.kt:121-127` 已有稳定 key 的设置搜索入口 | 搜索栏后保留可选 Hero；作为功能快捷入口，跳现有任务页，不改搜索栏 |
| 聊天联动 | `DisplaySettings.kt:148-153` 与 `AppSkinRegistry.kt:61-64` 已支持绑定态实时联动、解绑态独立覆写 | 只追加 preset 与 link，不重构机制 |
| 编辑器 | `AppSkinSettings` 使用 registry 自动生成预设；`SkinPanelsControls.kt` 编辑通用 panel | 新增独立「图标色块」「功能快捷 Banner」控件段 |
| 试用/撤销 | `SkinTrialController` / `SkinTrialHost` 已有 CAS 试用、确认、一步撤销、自动撤销 | 原样复用 |

**关键纠正**：只改 `CardGroupIcon` 不足以实现截图风格。设置首页大多数入口直接使用裸 `Icon`，必须通过默认无视觉变化的包装器接入新 token。

### 2.1 外部组件库综合评估（2026-11-24）

**结论**：三家都值得参考，但当前没有一家适合作为 Premium Blue 的直接依赖。SaltUI 与「分组元项 + 开关形态」最接近；miuix 的偏好密度、连续曲率与渐进模糊最值得借鉴；compose-cupertino 只适合补充 iOS 控件细节。默认路径仍是用 AppSkin / CardGroup / Hero 吸收形态，外部依赖若确有价值，必须另立隔离 spike 并通过下门槛。

#### 2.1.1 版本与工具链兼容

本仓当前基线为 AGP `9.4.0`、Kotlin `2.4.10`、Compose BOM `2026.06.01`、Material3 `1.5.0-rc01`、compileSdk 37、minSdk 26、Java/JVM target 17；`bettertext-text` 另用 Compose BOM `2026.09.00`。因此「库能编译」不等于「能无冲突并入当前依赖图」。

| 库 | 本轮可确认版本 | 工具链 / 主题边界 | 当前结论 |
|----|----------------|-------------------|----------|
| **SaltUI** | latest stable `3.0.0`（2026-11-02）；main README 已出现 `3.1.0-alpha01`，alpha 不作为稳定基线 | 3.0.0 文档要求 Kotlin `2.4.20`、Compose/CMP `1.12.2`、AGP `9.1.1`、JDK 21、compileSdk 37；本仓 Kotlin/Compose 低于门槛，AGP/compileSdk 已满足，Java target 17 不等于构建 JDK 21。使用独立 `SaltTheme`；已依赖 haze `2.0.1` | **形态参考优先，不作本批依赖**。若要引入，先解决 Kotlin/Compose 升级与主题适配，且只允许稳定版 |
| **miuix** | latest release `0.9.4`（2026-09-20），README 明确 experimental；main catalog 基线持续前移 | `MiuixTheme` / `ThemeController` 与 Material3/AppSkin 分离；模块可拆，但 API churn 与实验性风险仍在 | **只借鉴，不直接引入**。现有 squircle/haze/CardGroup 已覆盖大部分能力，等待稳定版与明确缺口后再评估 |
| **compose-cupertino** | 旧调研记录为 `1.6.1`；本轮 latest release 查询超时，未确认当前最新 stable | README badge 仍标 CMP `1.6.1` / Kotlin `1.9.23`；master catalog 标 Kotlin `2.0.20` / Compose `1.7.0` / AGP `8.6.1`，均明显旧于本仓。核心库不是 Material3，adaptive/decompose 会扩大边界 | **只借鉴 iOS 控件细节，不直接引入**。不引入 `cupertino-decompose` 或默认打包 SF Symbols |

#### 2.1.2 SaltUI：最接近目标形态，但先吸收模式

值得借鉴：

- `Item` 的标题 / 副文 / tag / 箭头层级，以及整行只拥有一个点击语义的组织方式；
- `ItemSwitcher` 把交互放在整行、`Switcher` 只负责绘制的语义拆分，避免开关被读屏播报两次；
- `LabelValue` 的「左侧标签 + 右侧当前值」元项结构；
- `Switcher` 的 unchecked 圆环到 checked 竖条 morph、`drawWithCache` 与 RTL 处理；
- `ItemDivider` 的起始缩进和 hairline；`PillButton` 的胶囊几何。

映射到本仓：

| SaltUI 能力 | 本仓落点 | 本批处理 |
|------------|----------|----------|
| `Item` / `ItemLabelValueContainer` | `CardGroup.item` + `trailingValue` / `CardGroupValueChip` | 复用现有 DSL，不引第二套偏好行 |
| `ItemSwitcher` / `Switcher` | 现有 `Switch` + `Role.Switch` + `LocalMotionTokens` | 保留单行单语义；若后续做 morph，只做本地绘制组件，时长进 motion token |
| `LabelValue` | 设置页枚举 / 跳转型值 chip | 不照搬固定 `96.dp` 标签宽，必须适应中文、英文、大字体与 RTL |
| `ItemDivider` | `AppSkinGroups.divider` / `dividerInsetDp` / `dividerWidthDp` | 已有契约，直接复用 |
| `PillButton` / `Sheet` / Navigation 3 | 现有 M3 按钮、`ModalBottomSheet`、Nav3 | 本批不替换宿主与导航 |

直接依赖还需审计 SaltUI 使用的 `AndroidHiddenApiBypass` 及 Google Play dependenciesInfo reporting；这不是皮肤层可以忽略的发布边界。

#### 2.1.3 miuix：参考 HyperOS 气质，不重复建设

值得借鉴：

- preference 行的密度、`startAction` / `endActions` / `bottomAction` 分层和按压反馈；
- squircle 连续曲率与 HyperOS 分组节奏；
- progressive blur 的层级与性能预算思路；
- `miuix-nav` 的 BreadcrumbBar 形态，仅作为后续导航密度参考。

本仓已有 `SmoothCornerShape`、`AppSkinGroups`、`CardGroup`、haze blur/glass 与 SDK 31/33 降级框架。因此本批不引入 `miuix-preference`、`miuix-squircle`、`miuix-blur`、`miuix-glass` 或 `miuix-nav`，避免相同能力出现两套 token、两套主题和两套降级策略。若未来证明现有 haze/形状存在 measurable gap，再对单个模块做稳定版 spike。

#### 2.1.4 compose-cupertino：服务已有 iOS 皮肤，不引入导航栈

值得借鉴：

- iOS Switch 的几何、滑块刻度与触控反馈；
- 扁平导航栏的层次、边缘返回手势与转场节奏；
- 与 Material3 对照时的自适应布局思路。

本仓 iOS 风已由 `AppSkinGroups.INSET`、连续曲率和现有 M3 控件实现，Premium Blue 也不需要 Cupertino 主题。直接引入会带来旧工具链、`CupertinoTheme` 与 Material3 双主题、包体和 SF Symbols 版权边界；`cupertino-decompose` 还与当前 Navigation 3 主架构不一致，明确排除。若只借鉴单个控件，应优先用本地 M3/Canvas 实现并接入 AppSkin motion，而不是拖入整套库。

#### 2.1.5 选择性依赖准入门槛

后续任何外部库进入 Gradle 前，必须同时满足：

1. **能力缺口**：本仓没有等价实现，且能说明用户可见收益；不能只为「像某个库」而引入。
2. **稳定版本**：不使用 alpha / experimental 作为生产基线；SaltUI 必须先解决 Kotlin/Compose 门槛，miuix 必须先退出实验性风险区间。
3. **主题边界**：业务页仍只读 AppSkin/Material3。库主题如不可避免，只能收口在一个适配器内，把颜色、字体、形状、动效映射进来，不允许页面同时读取两套主题权威。
4. **语义与无障碍**：整行/开关只保留一个交互语义；值 chip 不重复播报；RTL、大字体、读屏、系统移除动画全部通过。
5. **性能与降级**：给出 APK 增量、R8、冷启动、长列表滚动数据；AMOLED、深浅色、SDK 31/33 均有确定回落，不新增无预算 shader。
6. **发布与合规**：审计许可证、隐藏 API、依赖披露和图标版权；SF Symbols 不作为普通 Apache 图标资产打包。
7. **可移除性**：限定页面、限定组件、独立提交/模块，失败时能完整回滚，不进入默认皮肤、聊天管线或输入栏宿主。
8. **用户批准**：本稿不授权添加依赖、升级 Kotlin/Compose 或修改 AGP；实际引入需另行拍板。

#### 2.1.6 Kotlin / Compose 升级影响评估（2026-11-24）

**结论**：若只为借鉴 SaltUI 形态，不需要升级；若批准直接依赖 SaltUI 3.0.0，Kotlin/Compose 升级是**中等风险、影响面广但通常可控**的全工程变更，必须独立于皮肤实现提交。当前无需同步修改 AGP、compileSdk、minSdk 或 CI JDK。

| 项目 | 当前基线 | SaltUI 3.0.0 门槛 / 目标 | 影响 |
|------|----------|------------------------|------|
| Kotlin | `2.4.10`（[libs.versions.toml:5](../../../gradle/libs.versions.toml#L5)） | 至少 `2.4.20` | **需要升级**；Compose 编译器插件跟随 Kotlin，KSP/Room、序列化插件和语言/API 兼容性需重验 |
| Compose | app BOM `2026.06.01`；BetterText 模块 BOM `2026.09.00`（[bettertext-text/build.gradle.kts:7](../../../bettertext-text/build.gradle.kts#L7)） | 文档标注 Compose/CMP `1.12.2` | **需要对齐实际解析版本**；不能只改 BOM 名称，要确认 runtime/UI/foundation 最终版本一致 |
| AGP | `9.4.0`（[libs.versions.toml:2](../../../gradle/libs.versions.toml#L2)） | `9.1.1` | 当前已更高，**无需为此降级或升级** |
| compileSdk / minSdk | 37 / 26（[app/build.gradle.kts:17-33](../../../app/build.gradle.kts#L17)） | 37 / 23（navigation 24） | 当前已满足，**无需改动** |
| JDK / Java target | CI 已用 JDK 21（[release.yml:40](../../../.github/workflows/release.yml#L40)）；app Java/JVM target 17（[app/build.gradle.kts:111-113](../../../app/build.gradle.kts#L111)） | 文档要求构建 JDK 21 | CI 已满足；本地 JDK 需确认，**Java/JVM target 不一定需要改成 21** |
| Gradle | Wrapper `9.6.0`（[gradle-wrapper.properties:4](../../../gradle/wrapper/gradle-wrapper.properties#L4)） | 随工具链验证 | 当前先保留；若插件矩阵不兼容，再单独评审 |

主要影响：

1. **依赖图与插件链**：Compose compiler、KSP/Room、kotlinx.serialization 与 Gradle/AGP 需要重新确认兼容矩阵；Material3 `1.5.0-rc01`、haze `2.0.1`、Navigation3、adaptive 等可能被连带解析或发生冲突。
2. **双 BOM 收敛**：app 与 `bettertext-text` 必须解析到一致的 Compose runtime/UI/foundation；保留两套不一致版本会把问题推迟到运行时或发布构建。
3. **Compose 运行回归**：重点覆盖文本测量、焦点与 IME、Lazy 列表、Canvas/Shape、触摸和绘制时序；本仓高风险点是 ChatInput 中文输入法、长文本增量渲染、连续曲率和玻璃效果。
4. **发布与性能重验**：Clean build、全量单测、R8/APK/AAB、启动时间、长列表滚动与 Baseline Profile 都需要重新确认；SaltUI 的隐藏 API 和 dependenciesInfo reporting 另行审计。
5. **变更隔离**：工具链 spike 与 Premium Blue 皮肤提交分开，失败时只回滚工具链，不牵连 AppSkin、聊天外观或输入栏。

升级前置验收：

- 用 `dependencyInsight` 核对 Compose runtime/UI/foundation、Material3、haze 的最终解析版本；
- `:app:compileDebugKotlin`、`:app:testDebugUnitTest`、Clean assemble 全通过；
- `python scripts/audit_ime_trap.py` 的 A/A' 为零，真机中文 IME 注入无丢字/光标漂移；
- release R8、APK/AAB 体积、冷启动和 7000 节点长文本/长列表回归通过；
- 旧 AppSkin JSON、聊天绑定/解绑、深浅色与 AMOLED 回归通过。

**本批决定**：M1–M4 不依赖三家组件库，不改 Gradle。后续 spike 候选优先级为 SaltUI 的 Item/Switcher 语义与形态、miuix 的渐进模糊/偏好密度、compose-cupertino 的 iOS 控件细节；优先级只代表研究顺序，不代表已批准引入。

来源：[SaltUI 仓库](https://github.com/Moriafly/SaltUI) / [3.0.0 release](https://github.com/Moriafly/SaltUI/releases/tag/3.0.0)、[miuix 仓库](https://github.com/compose-miuix-ui/miuix) / [v0.9.4 release](https://github.com/compose-miuix-ui/miuix/releases/tag/v0.9.4)、[compose-cupertino 仓库](https://github.com/alexzhirkevich/compose-cupertino)。Cupertino 的 latest stable 本轮未复核，文中只记录可确认的 README/catalog 基线。

---

## 3. 视觉规范

### 3.1 浅蓝配色（2026-11-24 修订）

初版参考截图为深蓝紫；按用户要求，本稿整体改为**低饱和浅蓝系**，只保留 tonal、渐变与色块形态。下表是本稿实际预览值，不再把旧截图紫值作为实现起点：

| 用途 | 深色值 | 浅色值 | 实现方式 |
|------|--------|--------|----------|
| 页面底色 | `#12161C` | `#F7FBFE` | 由 `ColorScheme.background` 近似生成，不写死 |
| 行卡片 | `#202730` | `#FFFFFF` | 由 `surfaceBright` / 现有列表角色近似生成，不写死 |
| Hero 起点 | `#16344F` | `#D6ECFF` | Hero 渐变覆写 |
| Hero 中间 | `#245979` | `#C4E3F7` | Hero 渐变覆写 |
| Hero 终点 | `#356985` | `#D9F0F5` | Hero 渐变覆写 |
| 图标底 | `#1C3447` | `#DCEFFF` | 由 seed 生成的 `secondaryContainer` 近似 |
| 图标前景 | `#B5E5FF` | `#22678E` | 由 seed 生成的 `onSecondaryContainer` 近似 |
| supporting 提案 | `#B3C0CC` | `#526674` | 实现优先用 `onSurfaceVariant`，不直接抄灰值 |

配色原则：

- 去掉紫色 seed、紫色 Hero 与紫色聊天气泡，不再混入 `#9B82C4` / `#EADFFF` 一类色值；
- 深色保持高亮雾蓝，浅色保持通透冰蓝，避免把浅蓝误做成高饱和电蓝；
- 聊天气泡、图标、强调色与 Hero 共用蓝色相，但不互相复制 hex；
- `onSurface` / `onSurfaceVariant` 仍是正文权威，装饰圆与 Hero 文案的对比度单独验收。

### 3.2 无障碍修正

旧截图 supporting 文字 `#8A8B94` 落在 `#28292E` 上约 **4.29:1**，低于普通文字 AA 4.5:1；该问题仍作为设计约束保留，但不再沿用旧紫灰配色。新方案规定：

- 列表标题、supporting 分别使用 `onSurface` / `onSurfaceVariant`；
- Hero 标题、副文案优先取 `onSurface` / `onSurfaceVariant`。当实际渐变与装饰下的对比度不足时，在 Hero 内调整前景色或使用局部遮罩，阈值见 §9；
- 图标容器与图标使用 M3 成对角色，不手写固定前景色；
- 装饰圆只画背景，不进入 semantics；
- 最终对比度以真机生成的 `ColorScheme` 为准，截图色值只作起点。

### 3.3 形态原则

- quiet dark：低对比度边框、tonal 底卡、少阴影；不靠厚重描边或纯黑卡片堆层级。
- 图标色块：40dp 容器、20dp 图标、12dp 圆角；连续曲率只用于显式启用 tile 的皮肤。
- Hero：28dp 外圆角、20dp 内边距、静态斜向渐变、两个低透明度装饰圆；无循环动画、无 shader。
- 分组：默认 `FLAT`，行间距 10dp，复用现有分段圆角（外侧 22dp、内侧 4dp）。编辑器可切 `INSET` 分组底卡，并单独选择内缩分隔线。

---

## 4. 契约增量

### 4.1 可选图标色块 `AppSkinShapes.iconTile`

在 `AppSkinShapes` **末尾**增加可空字段，旧 positional 构造与旧 JSON 不受影响：

```kotlin
@Serializable
data class AppSkinIconTile(
    val sizeDp: Float = 40f,
    val radiusDp: Float = 12f,
    val iconSizeDp: Float = 20f,
) {
    fun normalized(): AppSkinIconTile {
        val size = sizeDp.bound(32f, 56f, 40f)
        return copy(
            sizeDp = size,
            radiusDp = radiusDp.bound(0f, size / 2f, 12f),
            iconSizeDp = iconSizeDp.bound(16f, minOf(28f, size), 20f),
        )
    }
}

data class AppSkinShapes(
    // ...既有字段保持不变...
    val continuous: Boolean = false,
    /** null = 保留现有圆形 CardGroupIcon / 裸 Icon 行为。 */
    val iconTile: AppSkinIconTile? = null,
)
```

`AppSkinShapes.normalized()` 串联 `iconTile?.normalized()`。不新增顶层覆写组：图标色块属于 shapes，`AppSkinOverrides.shapes` 已能承载整组覆写与恢复继承。

### 4.2 渲染接线：包装器 + 既有 `CardGroupIcon`

新增 `AppSkinListItemIcon`，作为 CardGroup leading 的标准入口：

```kotlin
@Composable
fun AppSkinListItemIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val tile = LocalAppSkin.current.skin.shapes.iconTile
    if (tile == null) {
        Icon(imageVector = icon, contentDescription = contentDescription, modifier = modifier)
    } else {
        CardGroupIcon(icon = icon, modifier = modifier)
    }
}
```

约束：

1. `tile == null` 时走的必须与当前裸 `Icon` 完全相同的默认路径（24dp、`LocalContentColor`、无底色），保证老皮肤像素不变。
2. `CardGroupIcon` 增加 tile 消费：
   - tile 为 null：保持现有 `40.dp + CircleShape + 20.dp`，即使 squircle / iOS 皮肤也不能被动变成圆角方块；
   - tile 非 null：使用 tile 尺寸；`continuous=true` 时用 `SmoothCornerShape`，否则用 `RoundedCornerShape`；
   - 调用方显式传入的 `containerColor` / `contentColor` 仍优先。
3. `cardGroupItemShape()` 公开语义不动，`SettingProviderPage` 的分段拖拽列表不受影响。
4. 迁移范围：下表 9 个文件中的 52 处普通 leading `Icon`。API 配置告警，以及带自定义 tint、modifier、Avatar、Switch 等的 leading 内容保留专用绘制。

当前分布：

| 文件 | 简单 leading Icon |
|------|-------------------|
| `SettingPage.kt` | 17 |
| `AssistantDetailPage.kt` | 9 |
| `ExtensionsPage.kt` | 8 |
| `SettingPreferencesPage.kt` | 6 |
| `BackupPage.kt` | 5 |
| `SettingAboutPage.kt` | 4 |
| `LocalNetworkPermissionCard.kt` | 1 |
| `NotificationPermissionCard.kt` | 1 |
| `MediaGenerationProviderConfigure.kt` | 1 |

### 4.3 独立 Hero token `AppSkinHero`

Hero 不是通用 panel：它需要深浅色双背景与纯装饰层，塞进 `AppSkinPanelSlot` 会迫使通用编辑器解释不属于 panel 的语义。新增独立文件 `data/model/AppSkinHero.kt`：

```kotlin
@Serializable
data class AppSkinHero(
    val visible: Boolean = false,
    val radiusDp: Float = 28f,
    val paddingHorizontalDp: Float = 20f,
    val paddingVerticalDp: Float = 20f,
    /** null = 该模式回落到另一模式渐变。 */
    val lightGradient: AppearanceGradient? = null,
    val darkGradient: AppearanceGradient? = null,
    /** 装饰圆透明度；0 = 关闭装饰。 */
    val decorationOpacity: Float = 0.06f,
) {
    fun normalized() = copy(
        radiusDp = radiusDp.bound(0f, 48f, 28f),
        paddingHorizontalDp = paddingHorizontalDp.bound(0f, 32f, 20f),
        paddingVerticalDp = paddingVerticalDp.bound(0f, 24f, 20f),
        lightGradient = lightGradient?.normalizedOrNull(),
        darkGradient = darkGradient?.normalizedOrNull(),
        decorationOpacity = decorationOpacity.bound(0f, 0.2f, 0.06f),
    )
}
```

`AppearanceGradient.normalizedOrNull()` 沿用现有色标格式校验，保留 2..8 个合法色标，少于两个时返回 null。`AppSkinHero.gradientFor(darkMode)` 负责可见性、明暗选择及跨档回落。

`AppSkin` 末尾增加：

```kotlin
val hero: AppSkinHero = AppSkinHero()
```

`normalized()`、`AppSkinOverrides.applyTo()`、`withEdits()`、`from()` 同步串联 `hero`。老 JSON 无 `hero` → 默认 `visible=false`；`panels()` / `textureIds()` **不纳入** hero，因为本批只允许渐变与静态装饰，不引入纹理资源。

### 4.4 Hero 组件与宿主

新增 `ui/components/ui/AppSkinHeroCard.kt`：

- 读取 `LocalAppSkin.current.skin.hero`；
- 根据 `LocalDarkMode.current` 选择 `darkGradient` / `lightGradient`，缺失时回落另一模式；
- `visible=false` 或两套渐变都为空时不发射可见节点；
- 背景用静态 `Brush.linearGradient`，形状优先消费 `AppSkinShapes.continuous`；
- 装饰圆用 `Canvas` / `drawCircle` 静态绘制，透明度来自 token；不进 semantics、无动画；
- 标题与副文案按 §3.2 处理对比度，以最终选中的渐变为准（含借用另一明暗档的情况）；
- 宿主负责图标、文案与单一目的地；组件只拥有视觉容器。

#### Banner 内容策略（非付费墙）

用户明确允许「除付费墙之外都可以考虑」。据此，Banner 不再限定为设置搜索，而是作为**现有功能的单一快捷入口**。默认推荐：

- 标题：**让 AI 定时为你办事**
- 副文案：**按计划运行对话、整理记忆或执行自动化任务**
- 图标：时钟 / 自动化
- 点击：`settings.taskCenterEnabled=false` 时进入 `Screen.SettingScheduledTasks`；开启时进入 `Screen.TaskCenter`，与现有定时任务入口的迁移语义一致。

其他现有功能也可替换宿主内容：记忆整理 → `SettingMemoryTrigger`；任务中心 → `TaskCenter`；工作区 → `Workspaces`。MCP、联网搜索、备份在设置页已有直接入口，不作为默认 Banner 内容，避免同一操作重复出现。Phone Use 依赖具体助手且要求 Android 11+，不做无参数 Banner 入口。

宿主放在 `SettingPage` 搜索栏之后：

```kotlin
val hero = LocalAppSkin.current.skin.hero
val showHero = hero.visible && (hero.lightGradient != null || hero.darkGradient != null)

// LazyColumn 内
if (showHero) {
    item("featureShortcut") {
        AppSkinHeroCard(
            title = stringResource(R.string.setting_page_feature_shortcut_title),
            description = stringResource(R.string.setting_page_feature_shortcut_desc),
            icon = HugeIcons.Time02,
            onClick = {
                val destination = if (settings.taskCenterEnabled) {
                    Screen.TaskCenter
                } else {
                    Screen.SettingScheduledTasks
                }
                navController.navigate(destination)
            },
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}
```

语义边界：

- 不新增「高级功能」CardGroup；Banner 只展示一个现有功能快捷入口，不复制其页面或完整入口列表。
- Hero 是稳定 Lazy item，无 TextField，不触碰 IME 规范。
- 文案使用 string resource；实现期补齐 `values` / `values-zh` / `values-zh-rTW`，不硬编码中文。

### 4.5 兼容边界

- `AppSkinShapes.iconTile` 与 `AppSkin.hero` 都放在各自数据类末尾，减少 positional 构造影响。
- 不新增枚举值，不存在旧构建读新枚举失败问题。
- 默认皮肤、compact、reading、squircle、paper、engineering、ios 均保持 `iconTile=null`、`hero.visible=false`。
- 插件皮肤未提供新字段时自动继承默认值。
- 用户只编辑 Hero 时仅产生 `AppSkinOverrides.hero`；未编辑组继续跟随预设更新。
- 新字段进入现有外观快照/分享序列化；本批不新增纹理，不扩大资源包边界。

---

## 5. `builtin.premium-dark` 预设终值（浅蓝配色提案）

> id 暂保留 `premium-dark` 以维持文件与映射稳定；名称与视觉策略均为「高级感 / Premium Blue」。若评审希望去掉 id 中的 dark，可在实施前统一改名，不影响本稿契约结构。

| 组 | 值 |
|----|----|
| id / name | `builtin.premium-dark` / 高级感 |
| palette | `CustomTheme(primaryColorArgb = 0xFF22678E, secondaryColorArgb = 0xFF4B7086, tertiaryColorArgb = 0xFF4E899F, paletteStyle = TONAL_SPOT, contrastLevel = 0.0)` |
| typography | DEFAULT / sizeRatio 1.0 / expressive 关 / weightDelta 0 / letterSpacing 0 / 数字与标签不等宽 |
| shapes | xs 8 / sm 12 / md 16 / lg 20 / xl 28；cardGroup 外 22 内 4；button 16；input 16；continuous=true |
| iconTile | `AppSkinIconTile(sizeDp = 40f, radiusDp = 12f, iconSizeDp = 20f)` |
| spacing | page 12 / section 20 / groupItem 10 / formH 16 / formV 14 / formGap 10 |
| groups | `FLAT` / divider `NONE`；容器色跟随主题，不写死 |
| motion | `STANDARD`，安静、短、跟手 |
| topBar | `CAPSULES`；panel radius 24 / border 0.5 / paddingH 12 / paddingV 8；背景跟随宿主 |
| drawer | item radius 12；panel 跟随宿主 |
| cards | reasoning / tool radius 16、border 0.5；不新增材质 |
| hero | visible=true；radius 28 / padding 20×20；decorationOpacity 0.06 |
| hero 深色渐变 | `#16344F → #245979 → #356985`，`DIAGONAL` |
| hero 浅色渐变 | `#D6ECFF → #C4E3F7 → #D9F0F5`，`DIAGONAL` |
| resources | 默认；无 logo / chatBackground / 纹理覆写 |

预设结构示意：

```kotlin
AppSkinPreset("builtin.premium-dark", "高级感", AppSkin(
    typography = AppSkinTypography(),
    shapes = AppSkinShapes(
        extraSmallDp = 8f, smallDp = 12f, mediumDp = 16f, largeDp = 20f, extraLargeDp = 28f,
        cardGroupOuterDp = 22f, cardGroupInnerDp = 4f,
        buttonDp = 16f, inputDp = 16f, continuous = true,
        iconTile = AppSkinIconTile(sizeDp = 40f, radiusDp = 12f, iconSizeDp = 20f),
    ),
    spacing = AppSkinSpacing(
        pageDp = 12f, sectionDp = 20f, groupItemDp = 10f,
        formHorizontalDp = 16f, formVerticalDp = 14f, formGapDp = 10f,
    ),
    motion = AppSkinMotion.STANDARD,
    topBar = AppSkinTopBar(
        mode = TopBarChromeStyle.CAPSULES,
        panel = AppSkinPanel(radiusDp = 24f, borderWidthDp = 0.5f,
            paddingHorizontalDp = 12f, paddingVerticalDp = 8f),
    ),
    drawer = AppSkinDrawer(item = AppSkinPanel(radiusDp = 12f)),
    cards = AppSkinCards(
        reasoning = AppSkinPanel(radiusDp = 16f, borderWidthDp = 0.5f),
        tool = AppSkinPanel(radiusDp = 16f, borderWidthDp = 0.5f),
    ),
    groups = AppSkinGroups(style = AppSkinGroupStyle.FLAT),
    hero = AppSkinHero(
        visible = true,
        lightGradient = AppearanceGradient(
            colors = listOf("#D6ECFF", "#C4E3F7", "#D9F0F5"),
            direction = GradientDirection.DIAGONAL,
        ),
        darkGradient = AppearanceGradient(
            colors = listOf("#16344F", "#245979", "#356985"),
            direction = GradientDirection.DIAGONAL,
        ),
    ),
    palette = CustomTheme(
        id = "builtin.premium-dark.palette", name = "高级感",
        primaryColorArgb = 0xFF22678E,
        secondaryColorArgb = 0xFF4B7086,
        tertiaryColorArgb = 0xFF4E899F,
        paletteStyle = ThemePaletteStyle.TONAL_SPOT,
        contrastLevel = 0.0,
    ),
))
```

说明：

- seed 与 panel 数值是浅蓝视觉稿起点，最终必须在真机看生成后的 tonal 阶，不把预览 hex 直接扩写成整套 M3 角色。
- `continuous=true` 只显式作用于 M3 Shapes、Hero 与 icon tile；`cardGroupItemShape()` 保持原圆角契约。
- Hero 不参与 `AppSkinPanelSlot`，因此 `AppSkinPanelsTest` 的 5 slot 基数不变。

---

## 6. 聊天域预设与联动

在 `ChatAppearanceRegistry.kt` 的 `builtinChatAppearancePresets` 追加：

```kotlin
ChatAppearancePreset("premium-dark", "高级感（浅蓝渐变）", ChatAppearance(
    userBubble = ChatBubble(
        material = BubbleMaterial.GRADIENT,
        gradient = AppearanceGradient(
            colors = listOf("#2A6687", "#173D58"),
            direction = GradientDirection.DIAGONAL,
        ),
        geometry = ChatBubbleGeometry(
            radiusDp = 20f,
            tailCornerDp = 6f,
            maxWidthFraction = 0.92f,
            paddingHorizontalDp = 14f,
            paddingVerticalDp = 10f,
        ),
    ),
    assistantBubble = ChatBubble(visible = false),
))
```

在 `DisplaySettings.kt` 的 `builtinSkinChatLink` 追加：

```kotlin
"builtin.premium-dark" to "builtin.chat:premium-dark",
```

行为保持现有语义：

- 绑定态：换肤实时解析到推荐聊天方案；
- 解绑态：使用用户独立选择的 preset / details；
- 手动微调聊天外观仍通过 `withChatAppearance()` 解绑；
- 插件皮肤无 link 时行为不变；
- 不新增联动状态机，不重做一次性迁移。

输入栏胶囊化不在本批；不得借聊天 preset 修改输入栏宿主或焦点拓扑。

---

## 7. 编辑器接线

### 7.1 图标色块

在 `AppSkinSettings` 的现有 CardGroup 编辑体系中增加独立段「图标色块」：

| 控件 | 范围 / 行为 |
|------|-------------|
| 启用色块 | 开 = `AppSkinIconTile(...)`；关 = `null` |
| 容器尺寸 | 32..56dp |
| 圆角 | 0..size/2 |
| 图标尺寸 | 16..min(28, size)dp |
| 连续曲率 | 复用现有 shapes.continuous，不另设开关 |
| 预览 | 直接用 `AppSkinListItemIcon`，确保与页面消费口一致 |

颜色不增加控件：容器/前景继续使用 `secondaryContainer` / `onSecondaryContainer`，换 seed 即整体联动。

### 7.2 功能快捷 Banner

独立段「功能快捷 Banner」，不混入「分组容器」选择器：

| 控件 | 行为 |
|------|------|
| 显示 Banner | `hero.visible` |
| 浅色渐变 | 2..8 个颜色，编辑器本批限制 2..3 个；方向固定 `DIAGONAL` |
| 深色渐变 | 同上 |
| 圆角 | 0..48dp |
| 水平 / 垂直留白 | 0..32 / 0..24dp |
| 装饰圆透明度 | 0..0.20 |
| 恢复默认 | 回到 `AppSkinHero()`，不写死 preset 值 |

实现约束：

- 编辑器沿用本地 `EditState` 权威 + 保存统一 `normalized()`，不写穿回灌；
- 多字段编辑继续放 `ModalBottomSheet + Column + verticalScroll + imePadding`；
- 颜色输入保留半成品字符串，保存时校验；
- `visible=true` 但两套渐变都为空时保存禁用，避免空 Banner；
- 预设列表由 registry 自动生成，不需要为高级感单写列表项。

---

## 8. 分阶段计划

| 阶段 | 内容 | 关键文件 | 验收 |
|------|------|----------|------|
| M1 契约 | `AppSkinIconTile`、`AppSkinHero`、normalized / overrides / withEdits / from | `data/model/AppSkin.kt`、新 `data/model/AppSkinHero.kt`、`ui/theme/AppSkinTheme.kt`（如确需 helper） | 旧 JSON 回读、默认值等价、边界归一、hero 组级隔离 |
| M2 组件 | 皮肤感知 leading icon、`CardGroupIcon` 消费 tile、Hero 组件与设置页宿主、52 处简单 Icon 迁移 | `ui/components/ui/CardGroup.kt`、新 `AppSkinListItemIcon` / `AppSkinHeroCard`、`SettingPage.kt` 等 9 文件 | 默认皮肤截图不变；高级感色块/Banner 深浅色可见；无重复 IA |
| M3 预设与编辑器 | 第 8 个 preset、聊天 preset/link、图标与 Hero 控件、字符串 | `AppSkinRegistry.kt`、`ChatAppearanceRegistry.kt`、`DisplaySettings.kt`、`AppSkinSettings.kt`、strings | 试穿/撤销、聊天绑定/解绑、编辑器实时预览 |
| M4 验收 | 回归矩阵、真机截图、性能与无障碍 | `docs/design/verification/` | 下列矩阵通过后转 completed |

每阶段独立可真机验证、可单独回滚；未获实施批准前不进入 M1。§2.1 的外部组件库评估不进入 M1–M4 关键路径；若后续批准依赖 spike，应单独立项，不阻塞本批皮肤实施。

---

## 9. 验证矩阵

| 类别 | 项 |
|------|----|
| 静态 | `:app:compileDebugKotlin`、`:app:testDebugUnitTest`、`python scripts/audit_ime_trap.py`、`python -B scripts/test_audit_ime_trap.py` |
| 契约 | 旧 `AppSkin` JSON 无 hero/iconTile 时等于默认行为；新 JSON round-trip；NaN/Infinity/越界归一；`withEdits` 只改 hero 或 shapes 的组级隔离 |
| 默认回归 | default / compact / reading / squircle / paper / engineering / ios 的 `iconTile=null`、`hero.visible=false`；裸 Icon 包装器默认路径与原来一致 |
| 图标 | 52 处迁移逐页抽查；自定义 tint / error 图标不被 secondary 角色覆盖；RTL 与大字体不挤压文字 |
| Hero | 深色浅蓝渐变、浅色冰蓝渐变、AMOLED 纯黑页面上的可读性；装饰圆不进 semantics；点击按任务中心开关进入现有任务页 |
| 聊天 | 绑定态换肤实时切换；解绑态保留独立方案；手动微调解绑；助手平铺与用户渐变气泡 radius / tail / padding 正确 |
| 宿主 | 设置页、助手详情、备份、扩展、工作区、定时任务等 CardGroup 抽查；`SettingProviderPage` 拖拽分段圆角不变 |
| 无障碍 | 普通文字 ≥4.5:1、大文字 ≥3:1；图标与交互边界 ≥3:1；Hero 单节点点击语义；值 chip / Switch 不重复播报 |
| 性能 | 7000 节点会话页打开设置、长列表滚动无新增掉帧；Hero 静态绘制，无逐帧 path/shader 重建 |
| IME | 本批无 TextField 拓扑变化，仍跑例行审计；编辑器 sheet 保持 `verticalScroll + imePadding` |
| 低版本 | SDK 31 / 33 的渐变、连续曲率、Canvas 装饰与现有效果回落一致；不引入 RuntimeShader 依赖 |

---

## 10. 风险与红线

1. **52 处 Icon 迁移是视觉覆盖面的关键**：不能只改 `CardGroupIcon`；包装器 null 分支必须逐字段等价旧 `Icon`。
2. **默认皮肤红线**：新 token 默认关闭；squircle / iOS 也不能因 `continuous=true` 被动获得 icon tile。
3. **IA 红线**：Banner 不是新功能集合，不做付费墙；默认只承载一个现有任务入口，不复制路由或完整入口列表。
4. **Hero 不复用通用 panel slot**：深浅色渐变与装饰层是独立语义，避免污染 5 slot 编辑器与纹理收集。
5. **配色红线**：本稿只使用低饱和浅蓝/雾蓝；不得把已废弃的紫 seed、紫 Hero 或紫聊天气泡重新引入，也不把 `#12161C / #202730` 写死成全局 surface。AMOLED、动态色覆写与用户角色覆写继续按现有优先级工作。
6. **聊天联动红线**：只追加 link，不改绑定/解绑状态机；用户独立方案必须继续保留。
7. **输入栏红线**：视觉稿胶囊输入栏不代表本批实施；不得改动 IME 宿主。
8. **连续曲率边界**：`cardGroupItemShape()` 与拖拽列表保持原语义；continuous 只消费于 M3 Shapes、Hero、icon tile。
9. **组件库边界**：Material3 / AppSkin 仍是唯一全局主题权威；不得为 Premium Blue 叠加 SaltTheme、MiuixTheme 或 CupertinoTheme，也不得借外部库重做导航、表单宿主或聊天气泡材质。

---

## 11. 待评审项

以下均为提案，不视为已批准：

1. 名称是否采用「高级感」与 id `builtin.premium-dark`；若希望名字不暗示强制深色，可改用 `builtin.premium`，视觉策略不变。
2. Banner 默认承载「让 AI 定时为你办事」；评审可替换为记忆整理、任务中心或工作区，但保持单一快捷入口语义。
3. 浅色采用同色相浅渐变，而非隐藏 Banner 或保持深色 Hero。
4. 默认分组采用 `FLAT`，`INSET` 仅保留为编辑器选项。
5. 聊天域默认绑定 `builtin.chat:premium-dark`，保留用户解绑。
6. 输入栏 pill 化继续留到后续独立评估，本批不实施。
7. 是否批准选择性组件库 spike；默认不批准，本批保持零新增依赖。

---

## 12. 视觉稿验证记录

- 2026-11-24 初版：深色模式全页截图已渲染，无控制台错误/警告。
- 2026-11-24 初版：浅色与「整组分隔」按钮已实际点击；计算样式确认主题切换、行背景透明、行圆角归零、整组底色生效。
- 2026-11-24 初版：页面与手机 mockup 均无横向溢出。
- 2026-11-24 配色修订：Hero、图标、seed、聊天气泡、预览缩略图与说明文案已统一改为低饱和浅蓝；深浅色色值分别校准。
- 当前内嵌浏览器未提供可见正尺寸 viewport，浅色截图输出失败；因此浅色只声明结构/计算样式通过，不声明像素截图通过。
- 视觉稿后续仍在同一 HTML 内调整文案；最终实施前需重新打开并复核深浅色、FLAT/INSET 两种形态。

---

## 13. 结合 UI 改造调研的实施准备

### 13.1 本批从调研承接的能力

| 调研方向 | 本批落点 |
|----------|----------|
| 方向 E：设置页分组 | 复用第二批的 `AppSkinGroups` 和现有 `CardGroup`。Premium 默认 `FLAT`，`INSET` 底卡和分隔线分别沿用现有编辑能力。Bento 栅格和服务组重分组仍按各自提案评估。 |
| 设置页信息架构 | 沿用第三轮「一项一家」及值摘要、低频折叠规则。Banner 是搜索入口下方的单一快捷操作，`taskCenterEnabled` 关闭时去「定时任务」，开启时去「任务中心」。两条路径复用各自现有目的页和入口。图标迁移保留行原有的点击、开关和值摘要语义。 |
| 形状、动效与材质 | 复用 `SmoothCornerShape`、`AppSkinMotion.STANDARD` 和现有聊天气泡几何。Hero 只用静态渐变和装饰绘制，兼顾 AMOLED、系统移除动画和低版本验证。输入栏形态、流式动效和大屏布局各走原提案。 |
| 组件库 | SaltUI 的标题、副文案与整行点击组织可作参考，miuix 的分组节奏与曲率用现有 token 表达，iOS 控件细节沿已有 iOS 档评估。M1–M4 沿用 Material3 / AppSkin 与当前 Gradle 配置，选择性依赖按调研 §3.3 独立评估。 |

历史候选状态以[调研稿页首](design-ui-overhaul-research.md)、[第三轮实施记录](design-ui-round3-skins-geometry-motion.md#9-m3m4-实施记录2026-11-15未提交)和[第二批设计状态](design-skins-batch2-engineering-ios.md)为准。Expressive 排版、值摘要、折叠段、动效 token 和气泡几何均有现成落点。第三轮的[设置页盘点表](../verification/ia-inventory-round3.md)供宿主回归时对照。

### 13.2 开工时的核对项

- **M1 契约。** Hero 渐变单独校验，合法色标不足两个时将该档视为空。现有 `AppearanceGradient.normalized()` 会用默认紫色填补失效渐变，适合原聊天用途。验收覆盖旧 JSON、非法值、单档回落、双档为空，以及 Hero 和 Shapes 覆写分别保存、清除和恢复继承。
- **M2 组件。** 用仓库实际 Material 色值计算，深色 `onSurfaceVariant` 在 `#356985` 上为 3.51:1。验收应测实际渐变与装饰的叠色、调换明暗档后的回落、AMOLED 和角色覆写。图标按 §4.2 的 52 处迁移，关注旧版裸图标的尺寸和继承色，以及大字体、RTL 下标题和操作的排布。Banner 整体只提供一个点击语义，两种任务中心开关状态均走到对应目的页。
- **M3 预设与编辑器。** 按 §0 建议准备第 8 个预设及聊天绑定，沿用试用、确认、撤销和手动编辑解绑。编辑器保持本地草稿，颜色字段放在带 `imePadding` 的常驻滚动表单里。实测开启中文 IME 连续编辑、翻到表单底部、保存后重新打开。
- **M4 验收。** 用同一设备和字体档对照默认皮肤与 Premium，覆盖深浅色、分行卡片与整组底卡加内缩线、200% 字体、启用和关闭任务中心、聊天绑定与解绑、API 31/33，以及已有 7000 节点会话的滚动基线。真机结果按 §9 记录。

### 13.3 准备基线（2026-11-24）

- App Kotlin 编译任务和离线 App 单测通过，共 3,755 项，2 项跳过。IME 审计 `A=0`、`A'=0`，脚本自测 11 项通过。
- 当前 HTML 原型在 390px 和 1040px 下验证了深浅色与两种分组形态的切换，均无横向溢出。Android 端待 M2–M4 验证，准备阶段检测到的真机为 API 35。
- 本节记录实施前基线，§11 保留预设和宿主的评审项。

### 13.4 M1 实施记录（2026-11-24）

- `AppSkinShapes.iconTile` 与 `AppSkin.hero` 已接入归一化和现有序列化。图标色块归 Shapes 组，Hero 可单独覆写和恢复继承。已有皮肤默认关闭这两项能力。
- Hero 渐变用有效色标判断明暗档是否可用，两档都不可用时 `gradientFor()` 返回空。聊天渐变继续沿用自己的默认色标。页面渲染和路由接线属于 M2。
- `AppSkinHeroTest` 覆盖 6 个契约用例，包括旧 JSON、尺寸边界、明暗回落、两组覆写及外观快照读回。
- `:app:compileDebugKotlin` 和 `:app:testDebugUnitTest` 通过。单测共 3,761 项，2 项跳过，新增 6 项全过。IME 审计 `A=0`、`A'=0`，脚本自测 11 项全过。

### 13.5 M2 实施记录（2026-11-24）

- 9 个文件的 52 个普通入口已接入 `AppSkinListItemIcon`。色块关闭时，普通入口沿用裸图标，已有 `CardGroupIcon` 沿用圆底。显式图标颜色在两种样式下均有效。色块与 Banner 复用 `AppSkinShapes` 的圆角选择。
- 设置搜索入口后接入 `featureShortcut`。Banner 以选中的明暗渐变和装饰叠色检验文字对比度，必要时在组件内换前景色或加遮罩。整卡是一项点击操作，两种任务中心开关状态各走现有路由。两条文案覆盖英文、简体和繁体。
- Kotlin 编译与 App 单测通过，共 3,764 项，2 项跳过。新增 3 项 Banner 对比度测试全过。IME 审计无新增阻断，脚本自测 11 项全过。AppSkin 静态审计的发现项与 HEAD 一致。
- PJX110（API 35）两项组件测试通过，覆盖默认图标逐像素对照、色块尺寸、Banner 深浅色、200% 字体与 RTL、单一点击语义、两个任务目的地及关闭时隐藏。全量 Android 测试编译受 4 个旧夹具缺少 `messageFileRefDAO` 阻塞。本轮将两个用例和运行器放在同一个隔离 APK 中验证。
