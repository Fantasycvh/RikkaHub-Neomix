# 皮肤第二批设计稿：工程感 / iOS 风（含分组容器形态重构）

> 状态：**M1–M3 已实施（2026-11-15），M4 真机验收中**
> 实施记录：契约、CardGroup 三形态、两档 preset 与编辑器控件均已落地；编译/单测/IME 审计/APK 装机通过。真机确认点：工程感整组线框气质、iOS 分组分隔线缩进（56dp 固定）、iOS 浅色 `#FFFFFF` 底卡在深色模式的取舍。
> 上游：[design-ui-round3-skins-geometry-motion.md](design-ui-round3-skins-geometry-motion.md)（第一批 squircle 现代旗舰 + 纸质感，2026-11-15 已实施）
> 调研依据：[design-ui-overhaul-research.md](design-ui-overhaul-research.md) §3 趋势 5「Raw aesthetics / functional minimalism」、§4 compose-cupertino 评估（**只做风格参考，不引入库**）
> 硬约束沿用调研稿 §6 与 AGENTS.md：IME 宿主规范、`supportingContent` 单子节点坑、皮肤契约（新 token 必须进 AppSkin，禁止绕过）、列表性能、布局稳定性。

---

## 0. 拍板记录（2026-11-15）

| # | 拍板点 | 决定 |
|---|--------|------|
| 1 | 第二批档位 | **工程感 + iOS 风两档**一次规划；不做主题分享/导入格式（留后续） |
| 2 | 契约深度 | **完整**：新增分组容器形态 token，允许重构 `CardGroup` 容器渲染；老皮肤默认行为不变、零迁移 |
| 3 | 技术路线 | 不引入 miuix / compose-cupertino；只借形态，落地为 AppSkin tokens |
| 4 | 分隔线起点 | **固定值**，不做按行联动：INSET 默认 56dp（对齐带 leading 图标正文），编辑器可调 0..72 |
| 5 | OUTLINED 形态 | **初版整组线框**（组容器描边 + 通栏分隔线），不做每项独立线框；真机效果不佳再评估 |
| 6 | iOS 分组底色 | 初版拍板写死 `#FFFFFF`；**2026-11-15 真机深色回归改为跟随主题 `surfaceBright`**（浅色仍接近白卡，深色保证文字可读） |
| 7 | 工程感配色 | **先做冷灰**（NEUTRAL，seed `#55606B`），对比真机后再考虑纯灰 MONOCHROME |

---

## 1. 目标与非目标

**目标**

1. 两档新皮肤能用现有皮肤机制（preset + 编辑器 + 试用/回滚 + 插件注册）直接切换，默认皮肤观感零变化。
2. 补齐第一批做不了的两类形态：**线框/网格感的整组容器**（工程感）与 **inset grouped 分组 + 内缩细分隔线**（iOS 风）。
3. token 只加可选字段，`AppSkin.normalized()` 兜底后旧皮肤 JSON 读取零迁移。

**非目标（明确不做）**

- 不做主题包分享格式 / 社区生态（本期不碰 zip 规范与签名校验）。
- 不做每项独立线框卡（首版只做「整组线框」，节点更少、更贴近开发工具面板）。
- 不引入第二套组件库；不改 M3 颜色角色体系（不新增 surface 角色，见 §10 开放问题 3）。
- 不碰聊天增量渲染、longtext 约束、输入栏宿主拓扑（IME 四条规范继续有效）。
- 不改 `Expressive` 排版开关归属（唯一家仍是皮肤编辑器）。

---

## 2. 现状核对（决定改动面）

| 项 | 实际状态 | 对第二批的影响 |
|----|----------|----------------|
| `CardGroup` 容器 | `Column` 实现；每行是 `ListItem`/`Surface`，行自带 `surfaceBright` 底 + 逐行圆角（外/内圆角）+ `groupItemDp` 间隙；**无容器背景、无描边、无分隔线** | 工程感/ iOS 风的形态差异全在这里，必须加容器层与分隔线，属于「形态重构」 |
| `cardGroupItemShape` | 公开 API，`CardGroup` 与 `SettingProviderPage` 的模板列表共用 | **不能直接改语义**：`SettingProviderPage` 是独立分段落列表，仍需分段圆角；新形态只在 `CardGroup` 内部另走解析器 |
| `AppSkinPanel` | 已有 `background/radius/borderColor/borderWidth/padding/gap`，仅用于顶栏 / 抽屉 / 推理卡 / 工具卡 | 分组容器可参考字段形状，但**不复用 ChatBubble 背景**（会牵入纹理/shader 链路），另立轻量 token |
| 排版 token | `family`（DEFAULT/SERIF/MONOSPACE/CUSTOM）、`sizeRatio`、`expressive`；无字重/字距/数字等宽 | 工程感需要「数字对齐 + 标签等宽」才有气质；iOS 风需要轻微负字距 |
| 配色 | `CustomTheme`（主/辅/三色 + paletteStyle + contrastLevel）经 MCU 生成全角色 | iOS 风用 FIDELITY 保留品牌蓝；工程感用 NEUTRAL 冷灰。均不新增 surface 角色 |
| 聊天域 | 气泡有 `visible/colorHex/geometry/material`；**无描边** | 两档聊天域只用「平铺助手 + 圆角用户气泡」，零新字段；工程感不强行画气泡框 |
| `CardGroup` 覆盖面 | 20 个文件 / 53 处调用（设置、助手、备份、工作区、定时任务等） | 默认 `FLAT` 分支必须与现状逐像素一致，回归面按 53 处抽查 |
| 皮肤编辑器 | `AppSkinEditor` 已含圆角/间距滑杆；无容器形态/分隔线/排版细项 | M3 增加一段「分组容器」+ 排版高级项，沿用 CardGroup（Column 实现，天然免疫 Lazy IME 坑） |

---

## 3. 契约设计（M1）

### 3.1 分组容器 token（新文件 `data/model/AppSkinGroups.kt`）

```kotlin
@Serializable
enum class AppSkinGroupStyle { FLAT, OUTLINED, INSET }

@Serializable
enum class AppSkinDividerStyle { NONE, FULL, INSET }

/**
 * 分组容器形态。默认值 = 第一批现状（FLAT + 无分隔线），老皮肤 JSON 零迁移。
 * 只服务 CardGroup 这类「一组多行」容器；不接管顶栏/抽屉/气泡（各自有 AppSkinPanel/ChatBubble）。
 */
@Serializable
data class AppSkinGroups(
    val style: AppSkinGroupStyle = AppSkinGroupStyle.FLAT,
    val divider: AppSkinDividerStyle = AppSkinDividerStyle.NONE,
    /** 分隔线起点缩进；INSET 档生效，FULL 档忽略。 */
    val dividerInsetDp: Float = 16f,
    val dividerWidthDp: Float = 1f,
    /** null = 继承 colorScheme.outlineVariant。 */
    val dividerColorHex: String? = null,
    /** 仅 OUTLINED / INSET 生效：整组外描边。 */
    val borderWidthDp: Float = 0f,
    val borderColorHex: String? = null,
    /** 仅 OUTLINED / INSET 生效：组容器底色；null = surfaceBright（现状行底色）。 */
    val containerColorHex: String? = null,
    /** 组容器内边距；INSET 档留 0，靠行内 padding 对齐。 */
    val containerPaddingDp: Float = 0f,
) {
    fun normalized() = copy(
        dividerInsetDp = dividerInsetDp.bound(0f, 72f, 16f),
        dividerWidthDp = dividerWidthDp.bound(0f, 3f, 1f),
        dividerColorHex = dividerColorHex?.takeIf(HEX::matches),
        borderWidthDp = borderWidthDp.bound(0f, 3f, 0f),
        borderColorHex = borderColorHex?.takeIf(HEX::matches),
        containerColorHex = containerColorHex?.takeIf(HEX::matches),
        containerPaddingDp = containerPaddingDp.bound(0f, 16f, 0f),
    )
}
```

- `AppSkin` 新增 `val groups: AppSkinGroups = AppSkinGroups()`；`normalized()` 串联。
- `AppSkinOverrides` 新增 `groups: AppSkinGroups? = null`；`applyTo` / `withEdits` / `from` 同步（组级覆写规则不变）。
- `AppSkin.panels()` / `textureIds()` **不纳入** groups（无纹理能力）。
- 语义约束：`FLAT` 时分隔线仍可按 `divider` 生效（= 行间画线、行仍各自带圆角会导致线头外露，首版 **FLAT 强制 NONE**，`normalized()` 里归一；编辑器在 FLAT 档下禁用分隔线选择）。

### 3.2 排版 token 扩展（`data/model/AppSkin.kt`）

```kotlin
data class AppSkinTypography(
    val family: ChatFontFamily = ChatFontFamily.DEFAULT,
    val customFontPath: String = "",
    val customFontName: String = "",
    val sizeRatio: Float = 1f,
    val expressive: Boolean = false,
    /** 全局字重微调（-200..200，落在 100..900）；默认 0 保持现状。 */
    val weightDelta: Int = 0,
    /** 全局字距微调（sp，-0.5..1.0）；默认 0。M3 typography 的 letterSpacing 本身就是 sp，避免单位混算。 */
    val letterSpacingSp: Float = 0f,
    /** 等宽数字（fontFeatureSettings = "tnum"），用于工程感的数值对齐。 */
    val tabularNumerals: Boolean = false,
    /** label* 系列改等宽族（CJK 自动回落系统字体），工程感专用。 */
    val monoLabels: Boolean = false,
)
```

- `normalized()`：`weightDelta.coerceIn(-200, 200)`、`letterSpacingSp.bound(-0.5f, 1f, 0f)`。
- `appSkinTypography()` 现有早退条件 `ratio == 1f && family == null` 必须纳入四个新字段，避免「设了 weightDelta 但不生效」。
- 字重/字距只对 typography 层统一生效，不在业务 Composable 内散改；`monoLabels` 优先级：`customFontPath` > `monoLabels`（仅 label 系）> `expressive` 的 Google Sans。

### 3.3 渲染语义（契约层写死，避免各页各自解释）

| style | 行背景 | 行圆角 | 分隔线 | 容器 |
|-------|--------|--------|--------|------|
| `FLAT`（默认） | 各行 `surfaceBright` / 调用方 colors | 外角 = `cardGroupOuterDp`，内角 = `cardGroupInnerDp`，按压外扩动画保留 | 强制 NONE | 无 |
| `OUTLINED` | 容器底色（默认 `surfaceBright`） | 行内 0，整组由容器裁剪 | `FULL`（通栏）或 NONE | 外描边 + 外圆角，`groupItemDp` 建议 0 |
| `INSET` | 容器底色（默认 `surfaceBright`） | 行内 0，整组由容器裁剪 | `INSET`（默认 16dp 起）或 NONE | 无描边（可选），外圆角，`groupItemDp` 建议 0 |

- 描边宽度 0 时 `OUTLINED` 退化为「无间隙整组底卡」，仍与 `FLAT` 可分（`INSET` 与它的差别只在分隔线缩进）。
- 分隔线颜色默认 `outlineVariant`，AMOLED 下自动跟随角色，不写死白/黑。

---

## 4. CardGroup 渲染重构（M2）

### 4.1 分支结构

1. 计算 `visible` 列表（现有 advancedGroup 折叠过滤逻辑不变），得到 `count`。
2. `style == FLAT`：走现有 `CardGroupListItem` / `CardGroupFormItem`，**代码路径与视觉保持现状**（回归基线）。
3. `style != FLAT`：
   - 外层 `Surface(shape = RoundedCornerShape(groupOuter), color = containerColor, border = BorderStroke(...) 或 null, modifier = clip)`；
   - `Column(verticalArrangement = spacedBy(0.dp))`，行间插入 `HorizontalDivider`（`divider != NONE` 且不是最后一行）；
   - 行 `ListItem` 的 `colors` 覆写为透明容器色（调用方显式传入的自定义 `colors` 保留，此时该行不透明、分隔线照画）；
   - `CardGroupFormItem` 同步改为透明底 + 行内 padding 不变。
4. 分隔线 Insets：
   - `FULL`：`Modifier.fillMaxWidth()`；
   - `INSET`：`Modifier.padding(start = dividerInsetDp.dp)`（只缩进起始侧，RTL 跟随）。
5. 按压反馈：非 FLAT 不再做逐角圆角外扩动画（容器裁剪下无意义），保留 `clickable` ripple；`advancedHead` 的 chevron 动画不变。
6. `cardGroupItemShape()` **公开语义不动**（`SettingProviderPage` 继续用）；新增内部 `cardGroupRowShape(style, index, count)` 只给 CardGroup 用，非 FLAT 返回 0 圆角。

### 4.2 兼容检查点

- `supportingContent` 单子节点坑：本次不改 slot 结构，分隔线画在 `ListItem` 之外，不做 slot 内拼装。
- `formItem`：分隔线画在表单项之后；高度由 `spacing.formVerticalDp` 决定，不额外加 gap。
- `advancedGroup`：分隔线只按 `visible` 列表插入，折叠成员不会留下悬空线。
- `CardGroupValueChip` / `trailingValue`：不受影响；chip 背景色在 INSET 底卡上仍需可辨（对比度项进验收）。
- 焦点/无障碍：分隔线是纯装饰，不加 semantics；不改变行数与播报顺序。
- 性能：分隔线是静态布局节点（无动画、无 shader）；每行最多新增一次 draw（border），53 处调用规模下可忽略。不做 `LazyColumn`，不引入 IME 风险。

---

## 5. 两档 token 终值（M3）

### 5.1 工程感（`builtin.engineering`，名称「工程感」）

| 组 | 值 |
|----|----|
| palette | `CustomTheme(primaryColorArgb = 0xFF55606B, paletteStyle = NEUTRAL, contrastLevel = 0.0)`（冷灰工具感；用户自行换 seed 仍成立） |
| typography | family DEFAULT / sizeRatio 0.98 / expressive 关 / **tabularNumerals = true / monoLabels = true** / weightDelta 0 / letterSpacingSp 0.05 |
| shapes | xs 2 / sm 4 / md 6 / lg 8 / xl 12 · cardGroup 外 10 内 0 · 按钮 6 · 输入框 6 · continuous=false |
| spacing | page 10 / section 14 / **groupItem 0**（配合整组线框）/ formH 14 / formV 10 / formGap 10 |
| groups | **style OUTLINED / divider FULL** / dividerWidth 1 / **borderWidth 1** / containerColor null / containerPadding 0 |
| motion | STANDARD |
| 顶栏 | `topBar.panel`：radius 0 / borderWidth 1 / borderColor null / background null |
| 抽屉 | `drawer.item`：radius 6 / borderWidth 0；`drawer.panel`：radius 0 / borderWidth 1 |
| 聊天域 | assistant 平铺（visible=false）；user geometry radius 8 / tail 3 / padding 12×8；气泡色 null（继承角色，深色自适应） |

**气质来源**：整组线框 + 通栏细线 + 等宽标签/数字 + 极小圆角；不靠背景纹理，AMOLED 与深色天然成立。

### 5.2 iOS 风（`builtin.ios`，名称「iOS 风」）

| 组 | 值 |
|----|----|
| palette | `CustomTheme(primaryColorArgb = 0xFF0A84FF, paletteStyle = FIDELITY, contrastLevel = 0.0)`（保留系统蓝，不被 tonal 拉灰） |
| typography | family DEFAULT / sizeRatio 1.0 / expressive 关 / tabularNumerals false / monoLabels false / weightDelta 0 / **letterSpacingSp −0.05** |
| shapes | xs 8 / sm 10 / md 12 / lg 16 / xl 22 · cardGroup 外 16 内 16 · **按钮 null（保持胶囊）** · 输入框 12 · **continuous = true** |
| spacing | page 16 / section 24 / **groupItem 0** / formH 16 / formV 12 / formGap 12 |
| groups | **style INSET / divider INSET** / dividerInset 56（固定值，对齐带 leading 图标的正文）/ 线宽 0.5 / borderWidth 0 / **containerColorHex null（跟随 surfaceBright，深色可读）** / containerPadding 0 |
| motion | STANDARD |
| 顶栏 | `topBar.panel`：radius 0 / borderWidth 0 / background null（扁平导航栏） |
| 抽屉 | `drawer.item`：radius 10；`drawer.panel`：radius 0 |
| 聊天域 | assistant 平铺（visible=false）；user geometry radius 18 / tail 6 / padding 12×8；气泡色 null |

**气质来源**：inset grouped 底卡 + 内缩细分隔线 + 连续曲率 + 品牌蓝；与 `builtin.paper` 的暖纸/衬线形成互补而非重叠。

- 两档注册进 `builtInAppSkins`；`builtinSkinChatLink` 追加：
  - `builtin.engineering` → `builtin.chat:glass`? **不追加**：工程感用现有默认聊天方案即可（平铺助手），避免误联动玻璃。
  - `builtin.ios` → 现有 `builtin.chat:paper`? 也不追加；仅 squircle/paper 保留原联动（零行为变化）。

---

## 6. 编辑器接线（M3）

`AppSkinEditor` 增加一段「分组容器」（CardGroup 自身消费新 token，可实时自见）：

| 控件 | 行为 |
|------|------|
| 容器形态 | `FLAT / OUTLINED / INSET` 三档 Select；切到非 FLAT 时提示「行间距建议 0」 |
| 分隔线 | `NONE / FULL / INSET`；`FLAT` 下禁用 |
| 分隔线缩进 | 0..72dp 滑杆；仅 INSET |
| 线宽 / 描边宽 | 0..3dp 滑杆 |
| 容器底色 | 颜色输入（空 = 跟随 surfaceBright） |
| 排版高级 | 字重微调 −200..200、字距 −0.03..0.04、等宽数字开关、等宽标签开关 |

- 新控件进现有 CardGroup（Column 实现），不新增 Lazy 宿主；编辑态沿用 `EditState` 本地权威 + 保存时统一 `normalized()`，不引入写穿回灌。
- 皮肤列表不显示「形态」值 chip（列表只显示名称/摘要，维持现状）。

---

## 7. 分阶段计划

| 阶段 | 内容 | 关键文件 | 验收 |
|------|------|----------|------|
| M1 契约层 | `AppSkinGroups` + typography 四字段 + normalized/overrides/withEdits | `data/model/AppSkin.kt`、新 `data/model/AppSkinGroups.kt`、`ui/theme/AppSkinTheme.kt` | 单元测试：旧 JSON 回读、边界归一、`withEdits` 组级隔离 |
| M2 容器渲染 | FLAT 分支冻结；OUTLINED/INSET 容器、分隔线、formItem、advancedGroup、拖拽列表兼容 | `ui/components/ui/CardGroup.kt` | 截图对照：默认皮肤 FLAT 逐像素不变；三种形态浅/深色 |
| M3 档位与编辑器 | 两档 preset + 编辑器控件 | `ui/theme/AppSkinRegistry.kt`、`ui/pages/setting/components/AppSkinSettings.kt` | 试穿/撤销、插件注册、编辑器实时预览 |
| M4 验收 | 回归矩阵 + 文档收尾 | `docs/design/verification/` | 下列全部通过后转 `completed/` |

每阶段独立可真机验证、可单独回滚；皮肤侧仍走 `SkinTrialController` 试用/撤销。

---

## 8. 验证矩阵

| 类别 | 项 |
|------|----|
| 静态 | `:app:compileDebugKotlin`、`:app:testDebugUnitTest`、`python scripts/audit_ime_trap.py`（A/A' 零命中）、`python -B scripts/test_audit_ime_trap.py` |
| 兼容 | 旧皮肤 JSON（无 `groups` / 无新排版字段）回读不崩；`AppSkinOverrides` 组级覆写与 `withEdits` 回归；默认皮肤 FLAT 截图对照 |
| 形态 | 工程感：整组线框、通栏 1dp 线、0 行距、AMOLED 纯黑；iOS：底卡 + 内缩细线、连续曲率、深色模式 |
| 宿主 | 53 处 `CardGroup` 抽查：设置四页 / 助手详情 / 备份 / 工作区 / 定时任务；`SettingProviderPage` 拖拽列表分段圆角不变 |
| 排版 | 工程感等宽标签/数字在中文界面的回落；iOS 负字距在大字体下的可读性 |
| 聊天域 | 两档平铺助手 + 用户气泡圆角/尾角；与 `builtin.chat` 方案切换不串味 |
| 无障碍 | 分隔线不进入 semantics；值 chip 读屏不重复；描边/分隔线与背景对比度可见（深浅色各验） |
| 性能 | 7000 节点会话页打开设置/长列表滚动无新增掉帧；无每帧 path/shader 重建 |
| IME | 中文 IME 注入回归（CardGroup 拓扑未变，例行防回归） |

---

## 9. 风险与红线

1. **影响面**：53 处 CardGroup 共用同一渲染分支。红线 = `FLAT` 分支代码路径与视觉不变，新形态只在显式 token 下启用。
2. **`cardGroupItemShape` 语义**：公开 API 继续服务 `SettingProviderPage` 的独立分段列表，禁止把容器形态塞进该函数。
3. **ListItem colors**：调用方显式传 `colors` 时保持自定义底，不被容器色覆盖；否则 INSET 会出现「双底」。
4. **分隔线拓扑**：只在 `visible` 行间插入；advancedGroup 折叠后不得出现悬空线或双线。
5. **配色边界**：`containerColorHex` 写死会牺牲深色/AMOLED 自适应。iOS 档初版写死 `#FFFFFF` 已被真机深色回归否决（白卡 + 浅色文字不可读），现改为跟随 `surfaceBright`；浅色观感与白卡几乎一致。
6. **排版回灌**：新排版字段只走 `AppSkinTheme` 统一注入，禁止在页面内直接改 `TextStyle`；编辑器遵守本地权威 + 保存提交。
7. **不做**：不引入 cupertino 组件库、不改 M3 角色、不动输入栏宿主、不做主题分享格式。

---

## 10. 开放问题（2026-11-15 已全部拍板）

1. **分隔线起点**：固定值，INSET 默认 56dp；编辑器可调，不按行是否有 leading 动态计算。
2. **OUTLINED 组合方式**：初版整组线框（组容器描边 + 通栏分隔线）；真机效果不佳再评估逐项描边。
3. **iOS 分组背景色**：初版写死 `#FFFFFF`；真机深色回归后改为跟随主题 `surfaceBright`（深色可读，浅色接近白卡），不扩 `CustomTheme` surface 角色。
4. **工程感配色**：先做冷灰 NEUTRAL（seed `#55606B`），真机对比后再考虑 MONOCHROME。
