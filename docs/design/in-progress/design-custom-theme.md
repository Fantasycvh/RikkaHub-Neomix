# 自定义主题增强（主题内核 + 角色覆写 + 外观快照导入导出）— 设计稿

> 状态：**已实现（2026-11-01，编译 + 全量单测通过：2650 项 0 失败），待真机验证**。
> **范围（用户已拍板）**：P1 主题内核 + **BiliPai 式角色覆写（非简化方案）** + 导入/导出升级为「外观快照」。
> **实现落点**：`CustomTheme.kt`（`ThemePaletteStyle` 9 风格 + `contrastLevel` 最近档归一）、`ThemeRoleOverride.kt`（新文件，含 `userBubbleHex` fork 特有槽位 + WCAG 对比度 + tone 自适应）、`ThemeSnapshot.kt`（新文件，`theme` 字段**必填**防旧 JSON 静默解码成默认主题）、`Theme.kt`（覆写应用位次 + `LocalUserBubbleColor`）、`PreferencesStore.kt`（`DisplaySetting.themeRoleOverrides`）、`ChatMessage.kt`/`Export.kt`（气泡消费点；导出用户卡顺手改为与屏显同源 primaryContainer）、`SettingThemePage.kt`（hex 输入 + 风格/对比度选择 + 覆写区块 + 导入双格式 + sakura fallback 修正）；字符串 27 键 ×3 locale（locale-tui 翻译 API 无 key，en 由 skill 落、zh/zh-rTW 手动补）。单测：`ThemeRoleOverrideTest`（13 项）+ `ThemeSnapshotTest`（5 项）；顺手修 `BatchRenamePlanTest` 既有失败（66a20deda 枚举化重构后测试未跟上，全量单测因此长期红）。
> **参照系**：① **BiliPai（实现参照）**（`docs/references/other projects/bilipai.md`，源码级调研在档）——主题引擎工程化：seed 优先管线、角色覆写 + 对比度预警、主题=设置快照；② **橘瓣 OrangeChat（仅灵感来源）**（`docs/roadmap.md` §一，watermio123-droid/orangechat 的 `liquid-glass-ui` 分支）——提供「用户想直接改哪几个颜色位」的需求洞察（背景/气泡/重点色），**实现不照抄**，由角色覆写全量承接。两者是**不同应用**。
> **重要前提（现状核实结论）**：本仓**已有**自定义主题（`ui/theme/CustomTheme.kt`：3 种子色 → TONAL_SPOT 生成 ColorScheme、剪贴板 JSON 导入导出、HSL 取色器、7 套预设、动态取色+种子色）。本次是**增强**，不是从零设计。

## 一、现状核实（2026-11-01 逐文件核验）

| 环节 | 现状 | 位置 |
|---|---|---|
| 主题引擎 | `CustomTheme(id/name/primaryColorArgb/secondaryColorArgb?/tertiaryColorArgb?)` → `ColorSpecs.get(DEFAULT_SPEC_VERSION)` + **写死 `Variant.TONAL_SPOT` + `contrastLevel = 0.0`** → `DynamicScheme` → `toColorScheme()` | `ui/theme/CustomTheme.kt:21-67` |
| 挂载 | `RikkahubTheme` 三分支：dynamicColor(≥S) / `findThemeById(themeId, customThemes)` / AMOLED 纯黑覆盖；最终 `MaterialExpressiveTheme` | `ui/theme/Theme.kt:58-88,142` |
| 存储 | DataStore `custom_themes` 单 key 存 JSON 列表（`JsonInstant`，`ignoreUnknownKeys`）+ `theme_id` | `data/datastore/PreferencesStore.kt:257,491-493` |
| 设置 UI | 主题管理页：预设选择 + 自定义主题列表（增删改导出）+ 编辑 sheet（名称 + 3×`ColorPickerRow` HSL 滑条 + 预览）+ 导入对话框（粘贴 JSON） | `ui/pages/setting/SettingThemePage.kt`（725 行） |
| 取色器 | `ColorPickerRow`：HSL 三滑条 + `hsl(...)` 文本框，**无 hex 输入** | 同上 L544-641 |
| 用户气泡上色 | `colorScheme.primaryContainer.copy(alpha = bubbleOpacity)` | `ui/components/message/ChatMessage.kt:514`；导出侧 `ui/pages/chat/Export.kt:1234` 同源 |
| 助手气泡上色 | `colorScheme.surfaceContainerHigh.copy(alpha = bubbleOpacity)` | `ChatMessage.kt:534` |
| M3 色彩工具 | material-color-utilities **整套 vendored**：`Variant` 9 值、`ColorSpecs` SPEC_2021/2025/2026、`TonalPalette`、quantize/score（图片取色链路现成） | `material3/material-color-utilities/kotlin/dynamiccolor/` |

## 二、参照系与取舍

### 2.1 从 BiliPai 取什么（橘瓣 ≠ BiliPai，两套参照）

| BiliPai 机制 | 本轮取舍 |
|---|---|
| **seed 优先统一管线**：任何用户输入归一为 seed，由一条管线生成整个 ColorScheme；自定义强调色当「新 seed」重新生成 accent 族而非逐角色硬填 | ✅ 采纳为原则——本轮所有新字段都走 `generateColorScheme` 单出口，不做散点替换 |
| 22 种调色风格（flex_seed_scheme 移植 + MaterialKolor）+ SPEC_2025 + 对比度档 | **收窄采纳**：本仓 MCU vendored 已带 `Variant` 9 风格 + `contrastLevel`，直接暴露这两项即可，不移植 flex_seed_scheme（22 风格超出需求，9 风格已覆盖单调/中性/鲜艳/表现力/忠实/内容/彩虹/果盘） |
| 角色覆写（浅/深色各 4 hex + 对比度 <4.5 预警） | ✅ **本轮采纳（非简化方案）**：`ThemeRoleOverrides`（浅/深各 background/primaryText/secondaryText/controlAccent 四位，另加 fork 特有 userBubble 位）；覆写编辑器从当前色板反推初值 + 对比度预警；控件强调色当「新 seed」重新生成 accent 族而非逐角色硬填 |
| **主题 = 设置快照**：导入导出天然携带玻璃/字体等外观联动 | ✅ 采纳（用户已拍板），格式见 §4.3 |
| `liquidAccent` 玻璃强调色独立角色（独立 CompositionLocal + 独立参与过渡动画） | **推后**（P2，另评） |
| endpoint 与动画分离、240ms 单时钟 Transition | ❌ 本轮不做（切换动画是独立特性，风险在重组压力，须单独评估） |

### 2.2 橘瓣（仅灵感来源，roadmap §一.2/§一.3 的关系说明）

| 橘瓣机制 | 处置 |
|---|---|
| **三色映射**：颜色 1=全局背景 / 颜色 2=用户气泡 / 颜色 3=重点色 | **需求被角色覆写全量承接**：颜色 1 ≈ `backgroundHex`、颜色 3 ≈ `controlAccentHex`（或主题种子色）、颜色 2 = `userBubbleHex`（本 fork 特有槽位，见 §4.2）。**不做**橘瓣式的独立「三色快配」简化入口（2026-11-01 修订撤销——避免第二套覆写语义，一个编辑器覆盖三色与更多） |
| 丝滑切换日夜主题 | ❌ 不做（= 主题切换动画，非目标） |
| 轻量个性化：头像框 / 气泡透明度 / 输入框换背景 / 字体包导入 / 思维链样式 | 气泡透明度 ✅ 已有（`bubbleOpacity`）、字体包 ✅ 已有（`chatFontFamily=CUSTOM`）；头像框 / 输入框换背景 / 思维链样式 **维持 roadmap P2 不动**，非本轮范围 |

## 三、已定决策（2026-11-01）

| # | 决策 | 说明 |
|---|---|---|
| 1 | 范围 = P1 主题内核 + **BiliPai 完整角色覆写** + 外观快照导入导出 | 用户拍板（2026-11-01 修订：撤销三色快配简化入口，实现以 BiliPai 为参照；橘瓣仅灵感来源） |
| 2 | 引擎新字段加在 `CustomTheme` 上，可空 + 默认 null | `themeJson`/`JsonInstant` 均 `ignoreUnknownKeys` → **旧 JSON 零迁移**；null = 现行为（TONAL_SPOT / 0.0） |
| 3 | 角色覆写是**全局层**（存 `DisplaySetting.themeRoleOverrides`），叠加在任意色源之上 | 对齐 BiliPai 语义：覆写作用于动态取色/预设/自定义任意基底（`resolveConfiguredMaterialScheme` 位次），不是 CustomTheme 的私有字段；存 DisplaySetting = 复用现有单 JSON key，不加 DataStore 键，且天然可进外观快照 |
| 4 | 覆写槽位可空（null = 该角色跟随生成）；`userBubbleHex` 为 fork 特有第五槽位 | BiliPai 用固定默认值预填，我们用 null=跟随更轻；编辑器初值仍从当前色板反推（BiliPai `themeRoleOverridesFromSchemes` 同款） |
| 5 | 用户气泡覆写经 CompositionLocal 下发，不在 `colorScheme` 里替换 primaryContainer | primaryContainer 还有按钮/胶囊等其他消费者，全局替换会外溢；气泡是唯一目标 |
| 6 | 导出格式升级为 `ThemeSnapshot`（带外观子集含角色覆写），**导入双兼容**（旧版裸 `CustomTheme` JSON 照常导入） | 见 §4.3；导出侧单向升级，导入侧向后兼容 |
| 7 | 调色风格/对比度各做一个自有序列化枚举/字段，**不暴露 SPEC 版本** | SPEC_2025/2026 观感差异大且与动态取色观感不一致，留待后续按需暴露 |
| 8 | hex 输入补进现有 `ColorPickerRow`，不新建取色器组件 | 对齐 BiliPai「hex 必备」的基线体验；组件已同页复用两处 |

## 四、设计

### 4.1 主题内核扩展（BiliPai 内核的收窄版）

**`ui/theme/CustomTheme.kt`** 扩展（全部可空默认 null）：

```kotlin
@Serializable
enum class ThemePaletteStyle {   // 映射 MCU Variant；自有序列化枚举，JSON 存小写短名
    @SerialName("tonal_spot") TONAL_SPOT, @SerialName("monochrome") MONOCHROME,
    @SerialName("neutral") NEUTRAL, @SerialName("vibrant") VIBRANT,
    @SerialName("expressive") EXPRESSIVE, @SerialName("fidelity") FIDELITY,
    @SerialName("content") CONTENT, @SerialName("rainbow") RAINBOW,
    @SerialName("fruit_salad") FRUIT_SALAD,
}

@Serializable
data class CustomTheme(
    val id: String = ..., val name: String = "",
    val primaryColorArgb: Long = ..., val secondaryColorArgb: Long? = null,
    val tertiaryColorArgb: Long? = null,
    val paletteStyle: ThemePaletteStyle? = null,   // null = TONAL_SPOT（旧行为）
    val contrastLevel: Double? = null,             // null = 0.0；合法域 [0.0, 0.5, 1.0]，越界夹取
)
```

- `generateColorScheme(dark)` 把写死的 `Variant.TONAL_SPOT` / `0.0` 换成上述解析结果；secondary/tertiary 的「用户指定 → `TonalPalette.fromInt`、未指定 → `colorSpec.getXxxPalette(variant, ...)`」分支结构不变，只是 variant 参数化。**`Theme.kt` 的 `findThemeById` 调用链零改动**。
- 合法性：`contrastLevel` 读取时 `coerceIn(-1.0, 1.0)` 后按 0.0/0.5/1.0 三档归一；`ThemePaletteStyle` 反序列化失败即解码异常（`ignoreUnknownKeys` 不救枚举值——与 lorebook 导入同款坑，已知解法是导入层 `withCaseInsensitiveEnums` 或捕获后提示，**采用捕获后提示**，见 §4.3）。
- 种子色覆盖路径（`Theme.kt:68-70` 动态取色 + 种子）**不传新字段**，保持 TONAL_SPOT + 0.0 与系统 monet 观感一致——注释里已写明该契约，不动。

**UI（`SettingThemePage.kt` 编辑 sheet）**：

- 名称 / 三色选择器之后追加两组：
  - **调色风格**：`ThemePaletteStyle` 九选一（SegmentedPill 放不下 → 单选弹层，预设主题按钮组同款交互）；默认 TONAL_SPOT。
  - **对比度**：三档（标准 0.0 / 中 0.5 / 高 1.0），默认标准。高对比档可直接当无障碍选项用（BiliPai 对照 PiliPlus 的同款语义）。
- `ColorPickerRow` 补 **hex 输入框**：`#RRGGBB`（6 位规范化，非法回显错误不生效），与 HSL 文本框并列（两框双向同步）。解析/格式化用现有 `ColorUtils`，不引依赖。
- `ThemePreview` / 列表小色球无需改（都走 `generateColorScheme`，字段变化自动生效）。

### 4.2 角色覆写（BiliPai 非简化方案，全局层）

**数据模型**（theme 包新文件 `ThemeRoleOverride.kt`，纯函数与模型同文件便于单测）：

```kotlin
@Serializable
data class ThemeRoleOverrides(
    val enabled: Boolean = false,
    val light: ThemeModeRoleOverrides = ThemeModeRoleOverrides(),
    val dark: ThemeModeRoleOverrides = ThemeModeRoleOverrides(),
)

@Serializable
data class ThemeModeRoleOverrides(
    val backgroundHex: String? = null,      // 颜色 1（橘瓣灵感）→ background + surface
    val primaryTextHex: String? = null,     // 主文字 → onBackground + onSurface
    val secondaryTextHex: String? = null,   // 次文字 → onSurfaceVariant
    val controlAccentHex: String? = null,   // 控件强调（颜色 3 灵感）→ accent 族（新 seed 重生成）
    val userBubbleHex: String? = null,      // 颜色 2（橘瓣灵感，fork 特有）→ LocalUserBubbleColor
)
```

存储：`DisplaySetting` 追加 `val themeRoleOverrides: ThemeRoleOverrides = ThemeRoleOverrides()`（`enabled=false` 时零行为变化；复用 `display_setting` 单 JSON key，不加 DataStore 键）。

**应用位次**（对齐 BiliPai `resolveConfiguredMaterialScheme`）：`RikkahubTheme` 中 scheme 解析出**任意分支**（动态取色/预设/自定义）之后、AMOLED 纯黑覆盖（`Theme.kt:79-88`）之前，统一走纯函数：

```kotlin
fun ColorScheme.withRoleOverrides(o: ThemeModeRoleOverrides): ColorScheme
```

- `backgroundHex` → `background` + `surface`；`primaryTextHex` → `onBackground` + `onSurface`；`secondaryTextHex` → `onSurfaceVariant`。三者为**直填**（BiliPai 同款）；surfaceContainer 族不覆写（卡片保持 M3 中性，诚实边界）。
- `controlAccentHex` → **当新 seed 重新生成 accent 族**（BiliPai 核心原则：不逐角色硬填，保证容器/on 色自动配套）：以该 hex 建 `Hct.fromInt` → 新 `DynamicScheme`（TONAL_SPOT + 当前 contrastLevel）→ 摘取 primary/secondary/tertiary 全家族角色（含 on 与 container 共 12 角色）替换进基底 scheme。
- 编辑器**对比度预警**（BiliPai `themeRoleContrastRatio` 同款）：`primaryTextHex`/`secondaryTextHex` 与 `backgroundHex`（任一方为 null 则按当前色板对应角色）算 WCAG 对比度，**< 4.5 时在输入框下黄字警告**，不阻止保存（自用工具，警告即可）。
- 编辑器初值：从当前实际色板反推 hex（`toArgb` 格式化），用户改哪项就覆写哪项，null=跟随。

**用户气泡**（fork 扩展槽位）：`RikkahubTheme` 读取 `themeRoleOverrides` 当前明暗模式的 `userBubbleHex`，经新 CompositionLocal 下发：

```kotlin
val LocalUserBubbleColor = compositionLocalOf<Color?> { null }   // null = 跟随主题 primaryContainer
```

消费点两处：`ChatMessage.kt:514`（`LocalUserBubbleColor.current ?: colorScheme.primaryContainer`）与 `Export.kt:1234`（导出图/HTML 与屏显同源）。气泡内容色按覆写色 HCT tone 自适应（tone ≥ 50 → 深内容色，否则浅内容色，工具函数与导出共用）。覆写是全局层 → 预设主题/动态取色下同样生效（与 BiliPai 全局语义一致）。

**UI（`SettingThemePage.kt` 新区块「高级角色覆写」）**：

- 总开关（`enabled`）+ 浅色/深色两个页签（Compose 内取当前模式的页签默认选中，方便对照），每页签五个 hex 输入位（复用 §4.1 补了 hex 输入的 `ColorPickerRow`，或紧凑型 hex 单行 + 色块预览——实现时按 `CardGroup` 版面定，**语义同构**即可）。
- 开关关闭时区块折叠置灰；对比度警告见上。
- 明确不做：背景色不做整页图片/渐变（背景图已是 per-assistant 能力，`BackgroundPicker.kt` 先例）；壁纸逐角色覆写（BiliPai `WallpaperPaletteOverrides`，我们没有壁纸 retoning 管线，无对象可覆）。

### 4.3 外观快照导入导出（BiliPai「主题=设置快照」语义）

**导出格式**（新增 `@Serializable`，与 `CustomTheme` 同文件或 theme 包内新文件）：

```kotlin
@Serializable
data class ThemeSnapshot(
    val formatVersion: Int = 1,
    val theme: CustomTheme,
    val appearance: ThemeAppearanceSnapshot? = null,   // 可选：外观子集
)

@Serializable
data class ThemeAppearanceSnapshot(
    // 液态玻璃组（五开关 + 三方案 + 可读性）
    val enableBlurEffect: Boolean, val inputEffectType: BackgroundEffectType,
    val enableGlassTheme: Boolean, val listGlassEffectType: BackgroundEffectType,
    val enableTopBarBlur: Boolean, val topBarEffectType: BackgroundEffectType,
    val liquidGlassReadabilityMode: LiquidGlassReadabilityMode,
    // 字体（只带族选择，不带本机文件路径）
    val chatFontFamily: ChatFontFamily,
    // 气泡/字号
    val showAssistantBubble: Boolean, val bubbleOpacity: Float,
    val fontSizeRatio: Float,
    // 角色覆写（含 userBubble 位；快照始终携带，是否应用由导入勾选决定）
    val themeRoleOverrides: ThemeRoleOverrides = ThemeRoleOverrides(),
)
```

- **导出**：`CustomThemeItem` 的导出按钮改为导出 `ThemeSnapshot`（`appearance` 从当前 `displaySetting` 取值，**始终携带**）；仍是剪贴板 JSON，沿用 `themeJson`（`ignoreUnknownKeys + prettyPrint`）。
- **导入**（`ImportThemeDialog`）：
  1. 先按 `ThemeSnapshot` 解码，失败再按裸 `CustomTheme` 解码（旧版导出/手写 JSON 兼容）；两者都失败才报错。
  2. 解码成功且带 `appearance` → 对话框追加勾选项「**同时应用外观设置**（玻璃/字体/气泡）」，**默认不勾**（主题色共享是常态，外观子集是侵入性操作，宁可多一步）。勾选后导入动作 = `settings.copy(customThemes = +theme, displaySetting = displaySetting.withAppearanceSnapshot(appearance))`——`withAppearanceSnapshot` 为**逐字段合并**（只覆盖快照携带的字段，`displaySetting` 其余字段不动），纯函数便于单测。
  3. 枚举解码失败（未来版本新增枚举值）→ 错误信息提示「主题文件来自更新版本」，不静默降级。
- **明确排除项**：`chatCustomFontPath/Name`（本机文件路径，跨设备悬空）、`userAvatar`（多态 + 隐私）、`customAppName`/`launcherAliasId`（应用身份是设备级决策）、背景图（per-assistant）。`formatVersion` 为将来格式演进留位，当前只有 1。

### 4.4 i18n

新 UI 沿用本页既有惯例：`stringResource` + en/zh/zh-rTW 三份（`setting_theme_page_` 前缀），用 `locale-tui-localization` skill 落键。

## 五、兼容与边界

| 项 | 处理 |
|---|---|
| 旧 `custom_themes` JSON | 新字段全可空 → 解码零迁移；再次保存时按新结构写回 |
| 旧版导出的裸 `CustomTheme` JSON | 导入双兼容（§4.3），永不拒绝 |
| 删除当前主题 fallback 写死 `"sakura"`（`SettingThemePage.kt:297`） | 顺手修正为 `PresetThemes[0].id`（与 `PreferencesStore.kt:490` 的默认口径一致）——一行改动，随本轮带上 |
| 覆写与 AMOLED 的叠加顺序 | 覆写在先、AMOLED 纯黑在后（AMOLED 优先级不变，与现行为一致）；单测锁定顺序 |
| 覆写作用于全部色源（含动态取色） | 有意为之（BiliPai 全局语义）；`enabled=false` 默认保证存量用户零变化 |
| 暗色/AMOLED 存 SharedPreferences（`ui/hooks/ColorMode.kt`）与 DataStore 主题数据分裂 | 记录在案，**本轮不动**（外观快照因此不携带明暗模式；迁移是独立小件，后续另评） |
| 种子色覆盖路径 | 不暴露新字段（§4.1），动态取色观感契约不变 |
| MCU `Variant` 在非 TONAL_SPOT 下的 palette 生成 | vendored 源码 `ColorSpec2021.getSecondaryPalette` 等对所有 9 值均有实现，无 fallback 分支缺口（已核 `dynamiccolor/ColorSpecs.kt`） |

## 六、实现清单与验证

| # | 件 | 文件 |
|---|---|---|
| 1 | `ThemePaletteStyle` + `CustomTheme` 两个新字段（paletteStyle/contrastLevel）+ 生成管线参数化 | `ui/theme/CustomTheme.kt` |
| 2 | `ThemeRoleOverrides`/`ThemeModeRoleOverrides` 模型 + `ColorScheme.withRoleOverrides` 纯函数（accent 新 seed 重生成 + WCAG 对比度工具 + tone 自适应） | theme 包新文件 `ThemeRoleOverride.kt` |
| 3 | `DisplaySetting.themeRoleOverrides` 字段 + `RikkahubTheme` 应用位次（scheme 之后、AMOLED 之前）+ `LocalUserBubbleColor` 下发 | `PreferencesStore.kt`、`ui/theme/Theme.kt` |
| 4 | 气泡消费点接覆写 | `ChatMessage.kt:514`、`Export.kt:1234` |
| 5 | `ThemeSnapshot`/`ThemeAppearanceSnapshot` + `withAppearanceSnapshot` 合并纯函数 | theme 包新文件 + `data/datastore` 侧复用 |
| 6 | 编辑 sheet：风格/对比度选择 + hex 输入；「高级角色覆写」区块（开关+浅深页签+五槽位+对比度预警）；导入对话框：双格式 + 外观勾选；导出切快照格式；sakura fallback 修正 | `SettingThemePage.kt`、`SettingPreferencesThemePage.kt`（如需入口联动） |
| 7 | 字符串三语言 | `values*/strings.xml` |

**单测**（`testDebugUnitTest`）：① `ThemeSnapshot` 往返 + 裸 `CustomTheme` 旧格式解码；② `withAppearanceSnapshot` 只动携带字段；③ `contrastLevel` 越界夹取 + 未知风格报错路径；④ `withRoleOverrides`：四直填角色映射正确、accent 族 12 角色随新 seed 配套变化、null 槽位不动 scheme、AMOLED 叠加顺序；⑤ WCAG 对比度阈值判定（4.5 边界）。真机验证：角色覆写全链路（浅/深切换跟随）+ 气泡覆写与 `bubbleOpacity` 叠加 + 旧 JSON 导入 + 玻璃开关与覆写背景共存观感。

## 七、非目标（本轮不做，留档）

- 玻璃强调色独立角色（`liquidAccent` + 独立 CompositionLocal）——BiliPai 专项，P2 另评（届时须与角色覆写的 `controlAccentHex` 定优先级语义）。
- 主题切换 240ms 过渡动画（endpoint/动画分离纪律届时一并引入）。
- 图片生成主题（quantize + score 源码已在仓内，接入点=`BackgroundPicker.kt` 先例 + `generateColorScheme`，独立特性另立项）。
- SPEC_2025/2026 暴露、主题 SAF 文件导入导出、外观快照携带明暗模式。
