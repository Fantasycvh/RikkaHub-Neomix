# 皮肤↔聊天外观联动升级：绑定 + 可解绑覆写模型

> 状态：**M1+M2 已实施并真机验证（2026-11-22）**：契约（`chatAppearanceDetached` + `builtinSkinChatLink`/`migrateChatLink` 落 data 层）、解析层绑定（`resolveAppSkin` link 遮蔽 + `effectiveChatPresetId` 助手）、迁移接 PreferencesStore（**一次性落盘迁移**：`migrateChatLinkOnce` 启动写回 + `CHAT_LINK_MIGRATION_DONE` 标记；decode 路径仅在标记未落时兜底派生——真机发现「读时常驻派生」会把绑定态覆写槽残留误判成解绑，已修）、试穿预览整份重解析、聊天外观页「与皮肤联动」状态行 + 独立设置/跟随皮肤按钮、外观方案 Select 改显生效方案、`withChatAppearance`/`withAppearanceSnapshot` 写 detached=true。单测：AppSkinTest 解析/迁移矩阵 + ThemeSnapshotTest 回环断言更新（导入即物化解绑）。全量单测绿 + audit A/A'=0。
> 真机矩阵（OPPO 6c841f95，全过）：迁移判定、绑定态状态行/Select、独立设置（物化）、跟随皮肤（回绑）、四档皮肤换肤跟随、绑定态换肤冷启动不误判、微调即解绑、试穿预览全局观感（聊天域气泡无消息场景未可视验证）。状态行沿用页面硬编码中文惯例，未走 locale-tui。
> **真机事故记录**：验证中曾用「手工拼接 preferences_pb」还原测试污染的 display_setting，varint 边界解析错误截断 JSON 尾部 2 字节 → DataStore 判整文件损坏 → corruption handler 清空 DataStore（providers/assistants/显示设置全部回默认）。已从会话开始的基线 pb 整体回滚恢复并逐项确认（providers 横幅消失、皮肤/绑定/外观全部还原）。教训：**禁止手工拼接 DataStore pb**（entry 层还有 `0a <len>` 包裹，极易漏算），还原一律整机文件替换或走应用内备份。
> 上游：[design-ui-round3-skins-geometry-motion.md](design-ui-round3-skins-geometry-motion.md)（builtinSkinChatLink 首次引入）、[design-skins-batch2-engineering-ios.md](design-skins-batch2-engineering-ios.md)、[UI 精简提案讨论](../../ui-simplification-proposal.md)（「多套可切换」体感补强项）
> 硬约束：AGENTS.md IME 宿主规范（本稿不动任何 TextField 宿主）、皮肤契约（新字段必须可选、老 JSON 零迁移）、字段级 `settingsStore.update { }` transform（禁锁外快照写）。

---

## 0. 摘要

把「应用皮肤时联动写入聊天方案」从**一次性写入**升级为**实时绑定**：绑定态下聊天域方案由当前皮肤在解析层派生（换肤即整套观感切换，含聊天域）；用户在聊天外观页的任何独立选择/微调都自动**解绑物化**（覆写槽），提供一键「重新跟随皮肤」。同时修复两个现存缺口：试穿皮肤不预览联动聊天方案、皮肤切换静默覆盖用户自定义气泡。

## 1. 现状与问题（证据）

现行联动是 `AppSkinSettings.onSelect` 里的**一次性写入**（[SettingPreferencesUIPage.kt:227-233](../../../app/src/main/java/app/androidrik/rikkahub/ui/pages/setting/SettingPreferencesUIPage.kt#L227)）：

```kotlin
val link = id?.let { builtinSkinChatLink[it] }     // ui/theme/AppSkinRegistry.kt:123
if (link != null) current.copy(appSkinId = id, chatAppearancePresetId = link)
else current.copy(appSkinId = id)
```

解析层完全不感知联动（[AppSkinRegistry.kt:47-55](../../../app/src/main/java/app/androidrik/rikkahub/ui/theme/AppSkinRegistry.kt#L47)）：`chat = chatPresets.firstOrNull { it.id == display.chatAppearancePresetId }?.appearance ?: display.toChatAppearance()`。

由此产生四个问题：

| # | 问题 | 场景 |
|---|------|------|
| 1 | **静默覆盖**：用户微调过气泡（`withChatAppearance` 置 `presetId=null` 写 details）后切换皮肤 → 自定义被 link preset 覆盖，无提示、无回退 | 换肤丢自定义 |
| 2 | **无绑定语义**：聊天域看不出「跟随皮肤」还是「独立设置」；改一次 preset 后与新皮肤的联系永久断裂（其实从未真正建立） | 心智混乱 |
| 3 | **试穿不预览聊天域**：`SkinTrial` 只覆盖 skin 字段，`rememberAppSkin` 的 trial 分支只 `copy(skin=…)`（[AppSkinRegistry.kt:64-65](../../../app/src/main/java/app/androidrik/rikkahub/ui/theme/AppSkinRegistry.kt#L64)）——试穿「纸质感」看不到纸质感气泡，试用/确认的决策信息缺失 | 试用体验断裂 |
| 4 | **一键整套观感不成立**：一旦用户动过聊天域，换肤只剩半套（全局 token 变、聊天域不变），与「多套可切换风格」的预期相悖 | 体感缺口 |

`chatAppearancePresetId` 的全部写入点（改造范围）：皮肤 onSelect（:231）、聊天 preset 选择（:245-246）、`withChatAppearance`（[ChatAppearance.kt:189](../../../app/src/main/java/app/androidrik/rikkahub/data/model/ChatAppearance.kt#L189)，details 编辑时置 null）、外观快照导入（[ThemeSnapshot.kt:83](../../../app/src/main/java/app/androidrik/rikkahub/ui/theme/ThemeSnapshot.kt#L83)，物化为 details）。

## 2. 目标 / 非目标

**目标**

1. 绑定态：换肤时聊天域方案在**解析层**实时跟随（不再依赖写入时机），含试穿预览。
2. 解绑覆写：聊天域任何独立选择/微调自动解绑并物化当前值为覆写槽；提供「重新跟随皮肤」一键恢复。
3. 老用户零感知迁移：迁移后行为与迁移前完全一致（不丢自定义、不强制换方案）。
4. 试用/撤销（SkinTrialController）CAS 语义不破坏。

**非目标**

- 不做「绑定 + delta 叠加」（绑定态上叠局部微调，字段扁平无法表达 delta，首版不碰）。
- 不新增皮肤 token、不动 AppSkin 契约（本稿只动 DisplaySetting 与解析/UI 层）。
- 不动输入栏宿主拓扑、不动聊天增量渲染。
- 不做皮肤↔聊天方案编辑器 UI 重排（状态行复用现有 CardGroup/FormItem 体系）。

## 3. 契约设计

### 3.1 数据层（data/datastore/DisplaySettings.kt + data/model）

```kotlin
// DisplaySetting 新增可选字段，老 JSON 读取缺省 false = 绑定态（配合 §4 迁移判定，行为不变）。
@Serializable
data class DisplaySetting(
    // …现有字段…
    val chatAppearancePresetId: String? = null,   // 语义变更：解绑后的独立选择槽（绑定态下被 link 遮蔽）
    /** false=跟随当前皮肤的推荐聊天方案（builtinSkinChatLink）；true=独立设置（覆写槽生效）。 */
    val chatAppearanceDetached: Boolean = false,
)
```

`builtinSkinChatLink`（现 [AppSkinRegistry.kt:123-128](../../../app/src/main/java/app/androidrik/rikkahub/ui/theme/AppSkinRegistry.kt#L123)）是纯静态 `Map<String,String>`，无 Compose 依赖——**挪到 data 层**（`data/datastore/DisplaySettings.kt` 同文件或 `data/model/`），供迁移纯函数与解析层共用；ui/theme 原 import 改路径。插件皮肤无 link 的行为不变。

### 3.2 解析层（AppSkinRegistry.kt，唯一改动点）

```kotlin
fun resolveAppSkin(display: DisplaySetting, skins:…, chatPresets:…): ResolvedAppSkin {
    val base = skins.firstOrNull { it.id == display.appSkinId }?.skin ?: AppSkin()
    // 绑定态下 link 优先（遮蔽 presetId）；无 link / 解绑态回落 presetId ?: details（= 旧行为）
    val effectivePresetId = if (display.chatAppearanceDetached) null
        else builtinSkinChatLink[display.appSkinId]
    val chat = chatPresets.firstOrNull { it.id == (effectivePresetId ?: display.chatAppearancePresetId) }
        ?.appearance ?: display.toChatAppearance()
    return ResolvedAppSkin(display.appSkinOverrides.applyTo(base), chat.normalized())
}
```

解析纯函数化后，**换肤 = 只写 `appSkinId`**，聊天域跟随由解析自动生效（AppSkinSettings.onSelect 删除 one-shot 写入，[SettingPreferencesUIPage.kt:230-232](../../../app/src/main/java/app/androidrik/rikkahub/ui/pages/setting/SettingPreferencesUIPage.kt#L230) 收敛为 `current.copy(appSkinId = id)`）。

### 3.3 写入语义（状态机）

| 用户动作 | 写入 | 说明 |
|----------|------|------|
| 皮肤 onSelect / onReset | 只写 `appSkinId`（+ overrides） | 绑定态聊天域即时跟随；解绑态不碰聊天域 |
| 聊天 preset 选择（onSelectPreset） | `detached=true` + `presetId=id` | 独立选择即解绑 |
| 气泡/布局微调（`withChatAppearance`） | `detached=true` + `presetId=null` + details 更新 | 现有行为 + 解绑标记；微调即解绑，物化基准 |
| 「重新跟随皮肤」按钮 | `detached=false` | presetId **保留**（作为无 link 皮肤时的回落槽），绑定态下被 link 遮蔽 |
| 外观快照导入（`withAppearanceSnapshot`） | `detached=true` + `presetId=null` + details 物化 | 快照是物化语义，导入即独立（[ThemeSnapshot.kt:82-99](../../../app/src/main/java/app/androidrik/rikkahub/ui/theme/ThemeSnapshot.kt#L82)） |

### 3.4 试穿/撤销兼容

- `SkinSelection`/`SkinTrial`/`SkinUndo` **不改**：CAS 只覆盖 skin 字段，聊天域不再被换肤写入，undo 天然不会丢聊天设置（比现状更安全）。
- **试穿预览接线**：`rememberAppSkin` 的 trial 分支改为整份重解析——`resolveAppSkin(display.withSkinSelection(trial.target))` 的 chat 借绑定规则自动跟随 target 皮肤；无 link 的试穿目标回落当前 presetId/details。预览即所见，确认/撤销不额外写聊天字段。

## 4. 迁移（老用户零感知）

新增纯函数（可单测，幂等）：

```kotlin
fun DisplaySetting.migrateChatLink(builtinSkinChatLink: Map<String, String>): DisplaySetting {
    if (chatAppearanceDetached) return this                       // 已迁移，幂等
    val link = builtinSkinChatLink[appSkinId]
    val detached = when {
        link == null -> false                                     // 皮肤无推荐方案：绑定无害（解析回落）
        chatAppearancePresetId != null && chatAppearancePresetId != link -> true  // 选过别的 preset
        chatAppearancePresetId == null && chatAppearanceDetails != ChatAppearanceDetails() -> true // 微调过
        else -> false                                             // 干净态（含 presetId==link）→ 绑定
    }
    return copy(chatAppearanceDetached = detached)
}
```

执行位置：SettingsStore 读到 displaySetting 流后做**一次性字段级 transform 写回**（`settingsStore.update { }`，走取消安全 scope，遵守 AGENTS.md 写规范）；判定纯函数放 data 层单测覆盖矩阵。迁移判定不依赖 Registry 实例（只看静态 map），无启动时序风险。

**行为核对**（迁移前后逐项等值）：
- 皮肤=squircle、presetId=glass（老联动写入）→ 绑定 → 解析 = glass ✓
- 皮肤=paper、用户后来改选 engineering preset → 解绑 → 解析 = engineering ✓
- 皮肤=paper、用户微调过 details（presetId=null）→ 解绑 → 解析 = details ✓
- 皮肤=默认/紧凑/舒展阅读/插件、任意状态 → 绑定但无 link → 回落 presetId ?: details ✓
- 全新安装 → 绑定 → 无皮肤无 link → details ✓

## 5. UI（M2）

**聊天外观设置页**（`ChatAppearanceSettings`，CardGroup 体系，注意 supportingContent 多块必须 Column 包一层）：

- 顶部状态行：
  - 绑定态：「聊天外观跟随皮肤《现代旗舰》· 玻璃气泡」+ `TextButton`「独立设置」（= 解绑物化）；
  - 解绑态：「独立于皮肤《现代旗舰》」+ `TextButton`「跟随皮肤」（= 仅置 `detached=false`）；
  - 当前皮肤无推荐方案时显示「当前皮肤无推荐聊天方案」弱化文案，按钮隐藏。
- `AppSkinSettings` 卡片不加角标（联动是解析层行为，状态行一处说清即可；如真机反馈不直观再补）。

**字符串**：新增 4–6 个键（`setting_page_chat_appearance_bound/detached/follow/unlink/no_link_skin` 等），走 locale-tui（`--skip-translate` 后手工补 zh / zh-rTW）。

## 6. 分阶段计划

| 阶段 | 内容 | 验收 |
|------|------|------|
| M1 契约+解析+迁移 | 挪 `builtinSkinChatLink` 入 data 层；`chatAppearanceDetached` 字段；`migrateChatLink` 纯函数 + SettingsStore 一次性写回；`resolveAppSkin` 改绑定解析；皮肤 onSelect 删 one-shot；`withChatAppearance`/快照导入补 detached 标记 | 单测：解析矩阵 + 迁移矩阵 + 幂等；全量单测绿；`audit_ime_trap.py` 无新增 |
| M2 UI+试穿预览 | 聊天外观页状态行 + 解绑/跟随按钮；`rememberAppSkin` trial 分支整份重解析；字符串 i18n | 真机：换肤跟随（4 档内置皮肤逐一）、解绑微调不丢、重新跟随、试穿预览含聊天域、无 link 皮肤回落、AMOLED + 深浅色、外观快照导入导出回环 |

每阶段独立可编译可回滚；M1 落地后老用户行为零变化，M2 才出现新交互。

## 7. 测试矩阵（M1 单测）

1. `resolveAppSkin`：绑定+link 命中 / 绑定+无 link 回落 presetId / 绑定+无 link 回落 details / 解绑走 presetId / 解绑走 details / presetId 悬空（preset 卸载）回落。
2. `migrateChatLink`：§4 行为核对五行 + 重复执行幂等 + 皮肤 id 不在 map。
3. 试穿：`resolveAppSkin(display.withSkinSelection(target))` chat 跟随 / 无 link 回落。
4. 现有 round3/batch2 单测回归（resolveAppSkin 签名未变，语义分支新增，旧断言应全绿）。

## 8. 拍板点（2026-11-22 用户逐项确认「可以，开始实施」，1–5 全按建议执行）

| # | 问题 | 决定 |
|---|------|------|
| 1 | 迁移判定：曾有自定义/异 preset 的老用户**不自动跟随**新皮肤（解绑），干净态才绑定 | 按稿执行（零感知优先） |
| 2 | 绑定态微调 = 自动解绑物化（不做 delta 叠加） | 按稿执行 |
| 3 | 绑定态无 link 皮肤回落顺序：link 无 → presetId → details（presetId 保留为回落槽） | 按稿执行 |
| 4 | 状态行只放聊天外观设置页顶部，皮肤卡片不加角标 | 按稿执行，真机看反馈 |
| 5 | 字符串 i18n 走 locale-tui（zh/zh-rTW 手工补） | 按稿执行 |
