# 皮肤第三批设计稿：高级感 / Premium Blue（含图标色块与功能总览 Banner）

> 状态：**设计草稿，待拍板；未实施 App 代码**
> 日期：2026-11-24
> 配色：**低饱和浅蓝系**（按用户要求由初版深蓝紫修订）
> 视觉稿：[`原型/RikkaHub-UI第三批-高级感视觉对比稿.html`](../../原型/RikkaHub-UI第三批-高级感视觉对比稿.html)
> 上游：[UI 改造调研](design-ui-overhaul-research.md)、[第三轮几何/动效皮肤](design-ui-round3-skins-geometry-motion.md)、[第二批工程感/iOS 风](design-skins-batch2-engineering-ios.md)
> 约束沿用 AGENTS.md：皮肤 token 契约、IME 宿主规范、`supportingContent` 单子节点坑、长文本增量渲染、AMOLED 与低版本效果回落。

---

## 0. 本批提案（尚未拍板）

| # | 拍板点 | 本稿建议 |
|---|--------|----------|
| 1 | 档位名称 / id | **高级感** / `builtin.premium-dark`；成为第 8 个内置预设、第 5 档风格皮肤 |
| 2 | 核心视觉 | 近黑冷灰 tonal 底 + **低饱和浅蓝渐变** Banner + 40dp 圆角图标色块 + quiet dark 卡片 |
| 3 | Banner 语义 | 标题「功能总览」，副文案「搜索、记忆、工具与自动化，一处直达」；点击打开**现有设置搜索**，不做付费墙、不新增页面 |
| 4 | 浅色策略 | 皮肤不强制深色；深色用截图渐变，浅色用同色相浅渐变，全局明暗设置仍是唯一权威 |
| 5 | 分组默认形态 | `FLAT` 逐行卡片，贴近截图；编辑器仍可切现有 `INSET` 整组分隔 |
| 6 | 聊天域 | 新增 `builtin.chat:premium-dark` 并绑定；用户解绑后继续使用现有独立聊天方案 |
| 7 | 输入栏 | 本批**不改输入栏宿主拓扑**；视觉稿里的胶囊输入栏仅作气质参考，pill 化另案评估 |

---

## 1. 目标与非目标

### 1.1 目标

1. 把两张参考截图中的「Soft Dark Premium」拆成可切换、可编辑、可试用/撤销的 AppSkin，而不是写死在页面里的主题分支。
2. 新增两种本仓此前没有的视觉能力：
   - 列表 leading 图标的**可选色块容器**；
   - 设置页顶部的**深浅色双渐变 Hero Banner**。
3. 复用现有 `CustomTheme`、`AppSkinGroups`、`ChatAppearance`、皮肤试用与聊天绑定机制，不引入第二套组件库。
4. 老皮肤、旧 JSON、插件皮肤与默认皮肤保持原行为；新能力只在显式 token 下启用。

### 1.2 非目标

- 不引入 SaltUI / miuix / compose-cupertino，只借视觉形态。
- 不新增「高级功能」信息架构分组，不复制现有设置入口；Banner 只跳现有设置搜索。
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
| 设置页 leading 图标 | `SettingPage.kt` 有 18 处裸 `Icon`；全仓简单 `leadingContent = { Icon(...) }` 共 52 处 / 9 文件 | 新增默认等价裸 `Icon` 的皮肤感知包装器并机械迁移；否则设置首页不会出现截图色块 |
| Hero 宿主 | `SettingPage.kt:121-127` 已有稳定 key 的设置搜索入口 | 搜索栏后插入可选 Hero，跳现有 `Screen.SettingGlobalSearch` |
| 聊天联动 | `DisplaySettings.kt:148-153` 与 `AppSkinRegistry.kt:61-64` 已支持绑定态实时联动、解绑态独立覆写 | 只追加 preset 与 link，不重构机制 |
| 编辑器 | `AppSkinSettings` 使用 registry 自动生成预设；`SkinPanelsControls.kt` 编辑通用 panel | 新增独立「图标色块」「功能总览 Banner」控件段 |
| 试用/撤销 | `SkinTrialController` / `SkinTrialHost` 已有 CAS 试用、确认、一步撤销、自动撤销 | 原样复用 |

**关键纠正**：只改 `CardGroupIcon` 不足以实现截图风格。设置首页大多数入口直接使用裸 `Icon`，必须通过默认无视觉变化的包装器接入新 token。

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
- Hero 标题、副文案同样使用 `onSurface` / `onSurfaceVariant`，保证浅色与深色都可读；
- 图标容器与图标使用 M3 成对角色，不手写固定前景色；
- 装饰圆只画背景，不进入 semantics；
- 最终对比度以真机生成的 `ColorScheme` 为准，截图色值只作起点。

### 3.3 形态原则

- quiet dark：低对比度边框、tonal 底卡、少阴影；不靠厚重描边或纯黑卡片堆层级。
- 图标色块：40dp 容器、20dp 图标、12dp 圆角；连续曲率只用于显式启用 tile 的皮肤。
- Hero：28dp 外圆角、20dp 内边距、静态斜向渐变、两个低透明度装饰圆；无循环动画、无 shader。
- 分组：默认 `FLAT`，每行独立 22dp 圆角与 10dp 间隙；`INSET` 作为编辑器可选项，不新增契约。

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
4. 迁移范围：全仓 52 处简单裸 leading `Icon`（9 文件）。带自定义 tint、modifier、Avatar、Switch 等复杂 leading 内容不机械替换。

当前分布：

| 文件 | 简单 leading Icon |
|------|-------------------|
| `SettingPage.kt` | 18 |
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
        lightGradient = lightGradient?.normalized(),
        darkGradient = darkGradient?.normalized(),
        decorationOpacity = decorationOpacity.bound(0f, 0.2f, 0.06f),
    )
}
```

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
- 标题与副文案使用 `onSurface` / `onSurfaceVariant`；
- 宿主拥有点击与文案，组件只拥有视觉容器。

宿主放在 `SettingPage` 搜索栏之后：

```kotlin
val hero = LocalAppSkin.current.skin.hero
val showHero = hero.visible && (hero.lightGradient != null || hero.darkGradient != null)

// LazyColumn 内
if (showHero) {
    item("featureOverview") {
        AppSkinHeroCard(
            title = stringResource(R.string.setting_page_feature_overview_title),
            description = stringResource(R.string.setting_page_feature_overview_desc),
            onClick = { navController.navigate(Screen.SettingGlobalSearch) },
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}
```

语义边界：

- 不新增「高级功能」CardGroup；参考截图中的列表只用于展示图标色块，实际入口仍走现有设置页 / 搜索页。
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

### 7.2 功能总览 Banner

独立段「功能总览 Banner」，不混入「分组容器」选择器：

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

每阶段独立可真机验证、可单独回滚；未获实施批准前不进入 M1。

---

## 9. 验证矩阵

| 类别 | 项 |
|------|----|
| 静态 | `:app:compileDebugKotlin`、`:app:testDebugUnitTest`、`python scripts/audit_ime_trap.py`、`python -B scripts/test_audit_ime_trap.py` |
| 契约 | 旧 `AppSkin` JSON 无 hero/iconTile 时等于默认行为；新 JSON round-trip；NaN/Infinity/越界归一；`withEdits` 只改 hero 或 shapes 的组级隔离 |
| 默认回归 | default / compact / reading / squircle / paper / engineering / ios 的 `iconTile=null`、`hero.visible=false`；裸 Icon 包装器默认路径与原来一致 |
| 图标 | 52 处迁移逐页抽查；自定义 tint / error 图标不被 secondary 角色覆盖；RTL 与大字体不挤压文字 |
| Hero | 深色浅蓝渐变、浅色冰蓝渐变、AMOLED 纯黑页面上的可读性；装饰圆不进 semantics；点击只打开现有搜索 |
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
3. **IA 红线**：Banner 不是新功能入口集合，不新增「高级功能」分组，不复制现有路由。
4. **Hero 不复用通用 panel slot**：深浅色渐变与装饰层是独立语义，避免污染 5 slot 编辑器与纹理收集。
5. **配色红线**：本稿只使用低饱和浅蓝/雾蓝；不得把已废弃的紫 seed、紫 Hero 或紫聊天气泡重新引入，也不把 `#12161C / #202730` 写死成全局 surface。AMOLED、动态色覆写与用户角色覆写继续按现有优先级工作。
6. **聊天联动红线**：只追加 link，不改绑定/解绑状态机；用户独立方案必须继续保留。
7. **输入栏红线**：视觉稿胶囊输入栏不代表本批实施；不得改动 IME 宿主。
8. **连续曲率边界**：`cardGroupItemShape()` 与拖拽列表保持原语义；continuous 只消费于 M3 Shapes、Hero、icon tile。

---

## 11. 待评审项

以下均为提案，不视为已批准：

1. 名称是否采用「高级感」与 id `builtin.premium-dark`；若希望名字不暗示强制深色，可改用 `builtin.premium`，视觉策略不变。
2. Banner 文案与「点击打开现有设置搜索」的目的地。
3. 浅色采用同色相浅渐变，而非隐藏 Banner 或保持深色 Hero。
4. 默认分组采用 `FLAT`，`INSET` 仅保留为编辑器选项。
5. 聊天域默认绑定 `builtin.chat:premium-dark`，保留用户解绑。
6. 输入栏 pill 化继续留到后续独立评估，本批不实施。

---

## 12. 视觉稿验证记录

- 2026-11-24 初版：深色模式全页截图已渲染，无控制台错误/警告。
- 2026-11-24 初版：浅色与「整组分隔」按钮已实际点击；计算样式确认主题切换、行背景透明、行圆角归零、整组底色生效。
- 2026-11-24 初版：页面与手机 mockup 均无横向溢出。
- 2026-11-24 配色修订：Hero、图标、seed、聊天气泡、预览缩略图与说明文案已统一改为低饱和浅蓝；深浅色色值分别校准。
- 当前内嵌浏览器未提供可见正尺寸 viewport，浅色截图输出失败；因此浅色只声明结构/计算样式通过，不声明像素截图通过。
- 视觉稿后续仍在同一 HTML 内调整文案；最终实施前需重新打开并复核深浅色、FLAT/INSET 两种形态。
