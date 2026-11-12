# 模型卡片大小自定义（连续缩放）

## 背景与目标

模型卡片目前是硬编码固定尺寸，无任何大小调整能力：

| 位置 | composable | 现状硬编码 |
|---|---|---|
| 聊天页模型选择弹窗 | `ModelItem`（`ui/components/ai/ModelList.kt` L762） | Row padding 12/16dp、图标 32dp |
| Provider 详情页模型管理 | `ModelCard`（`ui/pages/setting/ModelListPage.kt` L696） | Row padding 16/8dp、图标 36dp |

目标（已与用户确认）：

- **连续缩放**（非档位开关），类似聊天字号 `fontSizeRatio` 的思路；
- **四处卡片（模型选择弹窗 / 管理页 / 添加模型弹窗 / 提供商列表）共用同一机制，但各自独立取值**（ModelPicker 与提供商列表卡片为实施时并入）；
- 范围 **75% – 150%**，默认 100%（= 现状，零回归）。滑块沿用 `SliderSettingField`，最小步进 1%（比原计划的 5% 更细，实现直接复用现成控件）。

## 方案

### 1. 存储 — `DisplaySetting` 两个字段

`data/datastore/DisplaySettings.kt` 的 `DisplaySetting` 追加：

```kotlin
/** 模型选择弹窗卡片缩放（0.75–1.5），作用于 ModelItem 整卡。 */
val modelPickerCardScale: Float = 1.0f,
/** Provider 管理页卡片缩放（0.75–1.5），作用于 ModelCard 整卡。 */
val modelManageCardScale: Float = 1.0f,
/** 添加模型弹窗卡片缩放（0.75–1.5），作用于 ModelPicker 的候选卡片。 */
val modelAddCardScale: Float = 1.0f,
/** 提供商列表卡片缩放（0.75–1.5），作用于 ProviderItem 整卡。 */
val providerCardScale: Float = 1.0f,
```

- 复用 `display_setting` 单 JSON key，**不加新存储键**（本仓惯例）；
- 旧配置缺字段 → 默认 1.0f，行为完全不变。

### 2. 渲染机制 — 子树覆写 `LocalDensity`

不逐个改 padding/图标/字号硬编码值，而是在 Card 内容子树覆写密度，让 dp 与 sp 同时按比例缩放：

```kotlin
val base = LocalDensity.current
val scale = scale.coerceIn(0.75f, 1.5f)
CompositionLocalProvider(
    LocalDensity provides Density(base.density * scale, base.fontScale)
) {
    Card { ...现有内容原封不动... }
}
```

优点与边界：

- 一处包裹，两个 composable 各自生效，机制同源；
- padding、图标、文字、圆角、Tag 全部等比缩放，视觉自洽；
- `fontScale` 保持系统值不变（只乘 density，不再叠加系统字号缩放）；
- 选择弹窗内的 stickyHeader（收藏/供应商分组标题）、搜索框、底部供应商 Badge 行**不在卡片子树内**，不随缩放，保持导航骨架稳定；
- LazyColumn item 高度随缩放自然变化，无额外处理；拖拽排序的 `Modifier.scale(0.95f)` 与本机制互不干扰。

两个 composable 各加一个参数 `cardScale: Float = 1.0f`（带默认值，调用方不传即现状），由调用处从 `settingsStore.settingsFlow` 读对应字段传入：

- `ModelItem` 的调用点全在 `ModelList` 内部（`ModelList.kt` 已注入 `SettingsStore`，L351），直接取 `modelPickerCardScale` 传下去；
- `ModelCard` 调用点在 `ModelListPage.kt` 的 `ModelList`（L147 起），同样读 `modelManageCardScale`。

> 实施时已并入：「添加模型」弹窗 `ModelPicker`（ModelListPage.kt，弹窗内部自读 `modelAddCardScale`）与提供商列表 `ProviderItem`（SettingProviderPage.kt，读 `providerCardScale`），机制与前两处相同。

### 3. 设置入口 — `SettingModelPage`

设置 → 模型设置页（`ui/pages/setting/SettingModelPage.kt`）末尾追加 `ModelCardScaleSetting`（CardGroup），复用现有 `SliderSettingField`（L485）：

- 「模型选择弹窗卡片」→ 写 `modelPickerCardScale`
- 「模型管理页卡片」→ 写 `modelManageCardScale`
- 「添加模型弹窗卡片」→ 写 `modelAddCardScale`
- 「提供商列表卡片」→ 写 `providerCardScale`

滑块 valueRange `0.75f..1.5f`，滑块/数值框以整数百分比换算（Float 比例 × 100），显示 `100%`。拖动即时预览、松手落库（`SliderSettingField` 自带缓冲），实时生效（三处卡片均直接消费 settingsFlow，无需重启）。

### 4. 文案

遵循 AGENTS.md 默认规则：未明确要求本地化时用硬编码文案；如要求本地化再走 locale-tui 补 en/zh/zh-rTW。

## 实施步骤（单阶段即可，改动小）

1. `DisplaySetting` 加两个字段；
2. `ModelItem` / `ModelCard` 加 `cardScale` 参数 + `LocalDensity` 包裹；调用处接线；
3. `SettingModelPage` 加两个滑块；
4. 编译 + 真机验证：滑块拖动两处卡片实时缩放、100% 与现状像素级一致、拖拽收藏排序与选中态不受影响。

## 风险点

- 密度缩放影响 Tag 内 `LocalTextStyle.current.lineHeight.toDp()` 图标尺寸（ModelModalityTag L883）——它随文本缩放，正是预期行为；
- 弹窗 `fillMaxHeight(0.8f)` 固定：卡片变大时可见行数减少，属缩放的正常代价，不改弹窗高度；
- 旧配置兼容：JSON 缺字段回退默认值，无迁移需求。
