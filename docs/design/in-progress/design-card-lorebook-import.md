# 角色卡内嵌世界书（character_book）导入 — 设计稿

> 状态：**一期（世界书拆分导入）+ 二期（重复导入检测 §六）均已实现**，本地单测全绿（2026-10-31，`:app:testDebugUnitTest`），待真机验证。
> **一期实现**：`parseSillyTavernCharacterBook` + 卡内嵌书 DTO（`ExportSerializer.kt`）、`TavernCardImport` 结果类与解析接线（`AssistantImporter.kt`）、代码块导入 toast 带书信息（`HighlightCodeBlock.kt`）、`code_block_add_assistant_success_with_lorebook` 三语言；单测 `SillyTavernCharacterBookTest`（15 项）+ `AssistantImporterTest` 内嵌世界书 region（4 项）。
> **二期实现**：`tavernCardSourceKey` 指纹 + `findCardDuplicate`/`upsertCardSourceRecord`/`commitCardImportExtras`（复用 `Settings.importedSources`，键空间 `card:` 前缀）；两入口共用 `CardDuplicateConfirmDialog`（文案复用对话导入通用键）；单测指纹 4 项 + upsert 1 项。实现期补充：`importAssistantFromJsonText` 一站式入口因「确认对话框无法阻塞 suspend」撤销，改为调用方编排（`parseAssistantFromJsonText` + 查重 + `appendAssistantWithLorebook`）。
> **方向**：角色卡（chara_card_v2/v3）内嵌的 `data.character_book` 拆分为**独立世界书**，导入时**绑定到新导入的助手**（`assistant.lorebookIds`）。不把条目压进人设、不新建存储模型。
> 关联：`AssistantImporter.kt`（卡解析）、`ExportSerializer.kt`（`parseSillyTavernLorebook` 及 ST 映射助手函数）、`PromptInjectionTransformer.kt`（注入链路，**零改动**）、`design-conversation-import-export.md` §十（去重机制原型）、`docs/peer-apps-benchmark.md` §6（ST 保真原则）。

## 一、现状核实

**卡导入丢弃世界书。** `AssistantImporter.kt` 的 `parseCardData` 只取 `name / first_mes / system_prompt / description / personality / scenario / mes_example`；`data.character_book` 被**静默丢弃**——PNG 卡（tEXt `chara` chunk）与 JSON 卡同一条解析路径，故两种入口行为一致地丢。

**独立世界书导入已存在且保真度高。** `ExportSerializer.kt` 的 `parseSillyTavernLorebook` + `SillyTavernEntry.toRegexInjection()` 已覆盖：主/次键、`/pattern/flags` 正则键（`parseStKeywords`）、`selective:false` 次要键作废、`selectiveLogic` 四值映射、position 0–7 映射（含 ANTop/ANBottom→`AT_DEPTH` 的诚实损失）、6 个 `match*` 开关并成 `matchAssistantPrompt`。

**绑定链路已通，下游零改动。** `collectInjections()`（`PromptInjectionTransformer.kt:90`）按 `assistant.lorebookIds` 过滤 `settings.lorebooks`（含书级 `enabled` 闸门）+ 全局 `lorebookBudgetTokens` 预算。只要导入时把书写入 `settings.lorebooks` 并把书 id 写进 `assistant.lorebookIds`，注入即生效；UI 侧助手详情 → 扩展 → 世界书页签天然可见、可编辑、可解绑。

**两条持久化路径，职责不同（本设计的关键约束）：**

| 入口 | 现状落库方式 |
|---|---|
| 对话内 JSON 代码块（`HighlightCodeBlock` → `importAssistantFromJsonText`） | 导入层直接 `SettingsStore.appendAssistant`（一次原子 update） |
| 助手页创建弹窗（`AssistantPage.AssistantCreationSheet` → `onImport`） | 导入回调只把 `Assistant` 塞进 **EditState 草稿**，用户点「保存」才经 ViewModel 落库 |

即：助手页路径的助手**不在导入时落库**。书若也等草稿确认，就得穿透 EditState 改回调签名——不值。

## 二、已定决策（2026-10-31）

| # | 决策 | 说明 |
|---|---|---|
| 1 | **拆独立世界书 + id 绑定**，不改 `Lorebook` 模型、不加存储 | 用户已定方向；`lorebookIds` 绑定是现成机制 |
| 2 | **新建卡内嵌书专用 DTO 与转换函数**，不共用 `SillyTavernEntry` | 字段名体系不同（见 §三.1）；复用的是映射**助手函数**（`parseStKeywords` / `mapSillyTavernPosition` / `mapSillyTavernSelectiveLogic`），不是 DTO |
| 3 | **书在导入时落库**（不等草稿确认）；助手仍走各自路径 | 两条路径统一为「导入层负责书、助手按现状」；代价见 §五.2 孤儿书 |
| 4 | **条目为空不建书**，也不报错（助手照常导入） | 延续「拒绝静默空书」教训（`parseSillyTavernLorebook` 的 `entries` 可空判据）；空书无信息量还占列表 |
| 5 | 书名：`character_book.name`，缺省 `"{角色名}的世界书"` | ST 的 `convertCharacterBook` 同样以书名为准、无则空 |
| 6 | 卡内嵌书条目统一 `matchAssistantPrompt = false` | 卡内嵌 spec 无 `match*` 字段（那是世界书文件的字段），默认 false 与 ST 转换行为一致 |

## 三、字段映射（保真要点）

### 3.1 为什么不能复用 `SillyTavernEntry`

| 卡内嵌（chara_card_v2 spec 的 CharacterBook） | ST 世界书独立文件 | fork 目标 |
|---|---|---|
| `entries` 为**数组** | `entries` 为 map（uid→条目） | `List<RegexInjection>` |
| `keys` / `secondary_keys` | `key` / `keysecondary` | `keywords` / `secondaryKeywords` |
| `enabled`（正向） | `disable`（反向） | `enabled`（直接取，不反转） |
| `insertion_order` | `order` | `priority` |
| 顶层 `position`：字符串 `"before_char"` / `"after_char"` | `position`：整数 0–7 | `InjectionPosition` |
| 条目 `extensions.position`：整数 0–7（ST 转换时塞回这里） | — | **优先取它**，见 3.2 |
| 条目 `name` 或 `comment`（两者都可能出现） | `comment` | `name` |

### 3.2 位置映射（对齐 ST `convertCharacterBook`）

1. `extensions.position` 存在且为 0–7 整数 → 走现有 `mapSillyTavernPosition`（含 ANTop/ANBottom→`AT_DEPTH` 的既有取舍，注释口径一致）；
2. 否则按顶层字符串：`"after_char"` → `AFTER_SYSTEM_PROMPT`、`"before_char"` → `BEFORE_SYSTEM_PROMPT`、缺省/未知 → `AFTER_SYSTEM_PROMPT`（ST 的 fallback 也是 1=after_char）。

`extensions.depth`（有则用）→ `injectDepth`；缺省 4。次键判定复用 `mapSillyTavernSelectiveLogic`（卡内嵌 `extensions.selectiveLogic` 整数，缺省 0=AND_ANY）。`selective` 语义与独立文件一致：`selective == false` 时次要键整体作废，且在算 `useRegex` 前作废。`case_sensitive`：条目顶层 `case_sensitive`，回退 `extensions.caseSensitive`。

### 3.3 书级字段

| 字段 | 处理 |
|---|---|
| `name` | 书名（决策 5 的 fallback） |
| `description` | `Lorebook.description` |
| `scan_depth` | 作**条目级默认**：条目自身无 `extensions.scanDepth` 时用书级值，否则条目值 |
| `token_budget` | **诚实忽略**——fork 的世界书预算是全局设置（`lorebookBudgetTokens`），无书级预算概念 |
| `recursive_scanning` | **诚实忽略**——fork 无递归扫描（注入后不参与后续触发扫描） |

### 3.4 忽略清单（诚实损失，注释里写明）

`extensions.probability` / `useProbability`（随机触发）、`extensions.exclude_recursion` / `prevent_recursion` / `delay_until_recursion`、`extensions.group` / `group_weight`、`extensions.matchPersonaDescription` 等 6 个 `match*`（卡内嵌 spec 本就不携带）、`extensions.sticky` / `cooldown` / `delay`。均为 fork 无对应概念的字段，丢弃方向与既有独立世界书导入的取舍一致。

## 四、实现清单

### 4.1 `ExportSerializer.kt` — 新增纯函数（与 `parseSillyTavernLorebook` 并排）

```kotlin
// 卡内嵌书 DTO（chara_card_v2 spec；字段名与独立世界书文件不同，见设计稿 §3.1）
@Serializable internal data class SillyTavernCharacterBook(...)
@Serializable internal data class SillyTavernBookEntry(...)

internal fun parseSillyTavernCharacterBook(cardName: String, book: SillyTavernCharacterBook): Lorebook?
```

- 返回 null = 条目为空（调用方据此不建书）；
- 条目转换 `SillyTavernBookEntry.toRegexInjection(bookScanDepth)` 为 internal 纯函数，单测直测；
- 复用 `parseStKeywords` / `mapSillyTavernPosition` / `mapSillyTavernSelectiveLogic`（不重复实现）。

### 4.2 `AssistantImporter.kt` — 解析接线 + 书落库

1. `parseCardData` 增读 `data["character_book"]`：反序列化为 `SillyTavernCharacterBook` → `parseSillyTavernCharacterBook`；**书本身反序列化失败不拦整个卡导入**（ST 同款：卡照常导入、书失败仅提示；此层无 toast 通道，降级为无书——实现期补充，测试已钉住）；
2. 解析层返回值 `Assistant` → `TavernCardImport(assistant, lorebook)`（data class，`lorebook` 可空）；`assistant.lorebookIds = setOf(book.id)` 预绑；
3. `parseAssistantFromJson` / `parseAssistantFromJsonText` 返回类型同步换；
4. **书落库单点**：`importAssistantFromUri`（koinInject `SettingsStore`）与 `importAssistantFromJsonText` 在各自入口把 `lorebook` 并入 `settings.lorebooks`——代码块路径合并进 `appendAssistantWithLorebook`（同一次原子写）；助手页路径只写书（紧邻一次 `update`），见 4.3。

### 4.3 调用方适配

- **解包点落在导入层**（实现期定稿，与原稿「改页面回调」略有出入）：`importAssistantFromUri` 在回调前把 `TavernCardImport` 解包，`onImport` 签名保持 `(Assistant) -> Unit` 不变——`AssistantPage.AssistantCreationSheet` **零改动**（仅加注释），少动一处调用方。
- `AssistantVM.addAssistant` 走的 `appendAssistant(assistant)` 保留为 `appendAssistantWithLorebook` 的薄委托（手工创建无书），ViewModel 不动。
- `HighlightCodeBlock`：`importAssistantFromJsonText` 改返回 `TavernCardImport`，成功 toast 有书时用新键 `code_block_add_assistant_success_with_lorebook`（书名 + 条目数），无书沿用旧键。

### 4.4 反馈与文案（三语言，走 locale-tui）

- 文件导入成功 toast：现状只报错不报成功（页面侧关弹窗即成功）；代码块路径已有成功 toast，扩为「已导入助手「X」+ 世界书「Y」（N 条）」（无书时不带后半句）。

### 4.5 单测

`parseSillyTavernCharacterBook` / `SillyTavernBookEntry.toRegexInjection`：
- `keys`→`keywords`、`enabled` 不反转、`insertion_order`→`priority`；
- `extensions.position` 存在时覆盖顶层字符串 position；两者都缺省 → `AFTER_SYSTEM_PROMPT`；
- `selective:false` 次要键作废（含 useRegex 计算顺序）；
- 书级 `scan_depth` 作条目缺省、条目 `extensions.scanDepth` 覆盖；
- 空条目返回 null；
- `/pattern/flags` 键识别与解包。

## 五、边界与取舍

1. **重复导入同一张卡** → 第二本同名书（id 随机）。~~与现状重复导入助手的行为一致，不做去重~~ → §六 立项去重检测（2026-10-31 二期）。
2. **助手页路径的孤儿书**：书在导入瞬间落库，若用户随后取消弹窗（不保存助手），书已存在但无助手绑定——**接受**：书完全可编辑/可删，ST 本身也是「导入卡即建书」；若要消除需穿透 EditState 生命周期，不值。
3. **V1 卡**无 `character_book` 字段，天然走原路径，零影响。
4. **对话内代码块导入**的书落库与助手落库合并为一次 `SettingsStore.update`，保持原子；助手页路径书单独一次写（§五.2 已接受）。
5. 不做的事：卡内嵌书的去重/合并、书级 token_budget、递归扫描、probability/group 系（§3.4）。

## 六、重复导入检测 + 提示（二期，2026-10-31 立项）

对齐 `design-conversation-import-export.md` §十 的机制与语义（检测 → **允许继续**的确认 → 成功后记录），差异在**锚点来源**：对话导入的 `sourceId` 是我们自己导出时写入的；角色卡来自外部（ST PNG/JSON），**没有可信的来源字段，锚点只能由内容派生**。

### 6.1 锚点：卡内容指纹

- **`sourceKey = "card:" + sha256(卡数据 JSON 的规范化序列化)`**：
  - 有 `spec` 的卡（V2/V3）hash `data` 对象、V1 老卡 hash 整个顶层对象（`JsonElement.toString()`，kotlinx 对同一解析结果输出稳定）；
  - hash `data`（而非整个文件）带来一个免费性质：**同一张卡的 PNG 与 .json 两种载体互认**（PNG 的 tEXt chunk 里就是同一段 JSON）；
  - 序列化在 `parseCardData` 处计算，存进 `TavernCardImport.sourceKey`。
- **Fail-open 原则（与对话导入 null `sourceId` 跳过检测同款）**：
  - 跨工具重排过键序的同一张卡 → hash 不同 → **不报重复**（漏报可接受，误报不可接受）；
  - 用户改过卡内容再导入 → hash 不同 → 不报（与对话导入「改过的文件 = 不同的来源」口径一致）；
  - 哈希计算本身失败 → null → 跳过检测。

### 6.2 存储：复用 `importedSources`，零迁移

`Settings.importedSources: Map<String, ImportSourceRecord(importedAt, title)>` 原样复用：
- 键空间用 `card:` 前缀与对话的 UUID 键天然隔离；
- `title` = 助手名（提示文案直接用现有键 `backup_page_import_duplicate_warning/hint`——措辞是通用的「%1$s 已于 %2$s 导入过」，无需新字符串）；
- `importedSources` 本就只增不减（对话导入 §10.4.4 同款，几十条量级暂不处理）。

### 6.3 流程（两个入口同一编排）

```
解析 → 算 sourceKey → 查 importedSources
  ├─ 未命中 → 照常导入 → 成功后记入 importedSources
  └─ 命中   → 弹「重复导入」确认（显示助手名 + 导入日期，允许继续）
              ├─ 继续 → 照常导入 → 刷新 importedAt
              └─ 取消 → 终止（不落库、不记录）
```

- 助手页（`AssistantImporter` 组合层）：检测在**书落库之前**；命中弹 `AlertDialog`（组合层本地 state）。
- 对话内代码块（`HighlightCodeBlock`）：**同样弹确认对话框**而非 toast 拦截——保持「允许继续」语义一致；拦截式会让「删了助手想从聊天记录重新导入」变成死路。
- 编排抽成共享的非 UI 辅助（解析/查重/记录），UI 只负责确认对话框。

### 6.4 记录时机与孤儿记录（对齐 §五.2）

- 记录在**导入动作完成时**写入（与书的落库同时机）。
- **助手页路径取消弹窗 → 记录已在**（孤儿记录）：后果只是下次再导入同一张卡时多一次「已导入过」警告（允许继续），**非数据损坏**；且此时书确实已落库（§五.2），「导入过」半真。接受，与孤儿书同一条取舍逻辑。
- 解析失败/用户取消不写记录。

### 6.5 范围与不做的事

- **本次只做角色卡导入**（PNG + JSON + 对话内代码块三入口，锚点同一）。内嵌书随卡同源检测，不单独对书做指纹。
- **独立 ST 世界书文件导入暂不纳入**：文件名即书名、列表可见易手删，收益低；将来要做，指纹 = entries 规范化序列化，机制同款。
- 不做「同名助手」启发式检测——内容指纹是唯一判据（同名不同卡、同卡不同名都存在，名字不可靠）。
