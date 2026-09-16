# 对话导入导出（自家格式往返）设计稿

> 状态：**设计定稿，未实现**（2026-09-16）。
> 用户决定：**①导入即换新 id（不覆盖）②第一阶段不导出附件，第二阶段再用内联 base64 ③导入时允许用户选择目标助手**。
> 关联：`design-backup-workspace-files.md`（整库备份）、`design-favorite-management.md`（同批评估）。

## 一、现状核实

### 1.1 导出已有三形态，但**都不能回填**

| 形态 | 落地 | 可导入 |
|---|---|---|
| Markdown | 文本（图片 base64 内联） | ❌ 无解析器 |
| HTML | 自包含网页 | ❌ |
| PNG | 图片（分页多图） | ❌（即用户所说"图片形式"） |

导出入口已可用（`ChatExportSheet`：列表页长按分享 + 聊天内顶栏），SAF 分享已就绪。

### 1.2 导入已有三种，**全是外部格式**

`ImportExportTab` 的 `importType` 三分支：

| 分支 | 格式 | 实现 |
|---|---|---|
| `local` | 本应用**整库备份** zip | `restoreFromLocalFile` |
| `chatbox` | Chatbox **json** | `ChatboxImporter` + `restoreFromChatBox` |
| `cherry` | CherryStudio **zip** | `CherryStudioProviderImporter` |

**结论：本应用自家格式的对话往返不存在**——这正是本设计要补的。

### 1.3 基础设施**全都现成**（这是难度低的原因）

| 现成件 | 位置 | 说明 |
|---|---|---|
| 序列化 | `Conversation` / `MessageNode` / `UIMessage` | **均 `@Serializable`**；`Conversation` 已有 `InstantSerializer` 处理时间戳 |
| **往返框架** | `data/export/ExportSerializer.kt`（205 行） | `ExportData(version, type, data: JsonElement)` + `export`/`import` 接口对；**目前只用于注入/世界书** |
| **"自家优先、外部兜底"写法** | `LorebookSerializer` | `tryImportNative` → `tryImportSillyTavern`，**这正是对话该有的形状** |
| **换新 id 写法** | `ChatService.forkConversationAtMessage:1513-1526` | `node.copy(id = Uuid.random(), ...)` |
| **助手选择 UI** | `ui/components/ai/AssistantPicker.kt`（247 行） | `AssistantPicker` + `AssistantPickerSheet` + `AssistantItem` |
| **导入确认弹窗** | `RestoreConfirmDialog` | 天然的挂载点 |
| 附件内联编码 | `Export.kt` 的 `part.encodeBase64()` | Markdown 导出已在用（二阶段复用） |
| **压缩检查点迁移（方案 A）** | `addCompressionCheckpoint` + 重映射 + `areaFingerprintFor` 重算（§5.1） |
| FTS 重建 | `MessageFtsManager.indexConversation` |
| **重复导入检测（§十）** | `ExportData.sourceId` + `Settings.importedSources` 三件套 + 确认弹窗 | 导入后调用即可 |

## 二、已定决策

| # | 决策 | 后果 |
|---|---|---|
| 1 | **导入即新建、全部换新 id，永不覆盖** | 消掉"覆盖已有会话"的数据丢失风险；`existsConversationById` 的 skip 分支**永不命中** |
| 2 | **第一阶段不导出附件** | 顺带消掉最麻烦的"附件路径跨设备失效"问题；二阶段再定附件格式 |
| 3 | **导入时用户选择目标助手** | 消掉"跨设备 `assistantId` 悬空"问题 |
| 4 | **导出文件不存 `assistantId`** | 跨设备必失效，一律以用户选择为准（§4.4） |
| 5 | **导出并携带 `compressionSummaries`，导入时重算指纹（方案 A）** | 见 §5.1；**原「不带」判断已更正**——不带会导致长会话静默的全量发送 |
| 6 | **Chatbox/CherryStudio 也加助手选择，并借此统一导入器** | **新开一轮**（见 §八） |
| 7 | **二阶段再决定附件格式** | 一阶段纯 JSON，`version` 位预留 |
| 8 | **重复导入检测 + 提示（允许继续）** | 因决策 1 换新 id 使旧判据失效，**需新增 `sourceId` 锚点**——见 §十 |
| 9 | **统一导入器：等导入功能验证后再做** | 依赖顺序（不是并行）——见 §八 |

## 三、换 id 清单（决策 1 展开）

| 对象 | 要换 | 依据 |
|---|---|---|
| `Conversation.id` | **要** | 主键；决定"导入即新建" |
| `MessageNode.id` | **要** | `message_node.id` 主键；**FTS 表存 `node_id`** |
| `UIMessage.id` | **要** | FTS 表存 `message_id`（与 `conversation_id` 并列）→ 不换会在两次导入后产生重复 `message_id` |
| `toolCallId` 等批内引用 | **不要** | 节点/消息内自洽；整批一起搬即保持自洽 |

**实现要点**：`forkConversationAtMessage` 只换了 `node.id`、**没换 `message.id`**——所以**不能直接照抄**，
需额外加一层 `messages = node.messages.map { it.copy(id = Uuid.random()) }`。

**⚠️ 顺序陷阱（最容易踩）**：`restoreFromChatBox` 现有逻辑是
`existsConversationById(id) ? skip : insert`（`BackupVM.kt:~137`）。
**换 id 必须在检查/插入之前完成**，否则"换了 id 却因旧 id 命中而被 skip"——换 id 白做。

## 四、目标助手（决策 3 展开）

### 4.1 现状是"隐式导入到当前助手"

```kotlin
// BackupVM.kt:129 restoreFromChatBox
assistantId = settings.value.assistantId,   // 直接用「当前助手」，UI 无选择入口
```

即"导入到哪"已有答案（当前助手），**只是没给用户选择权** → 本设计是**改进而非新建**。

### 4.2 复用 `AssistantPickerSheet` 需要先抽可见性

**关键发现**：`AssistantPickerSheet` 是 **`private`**，但它**只依赖 `settings` + `onAssistantSelected` 回调**——
**写 Settings 的副作用在 `AssistantState.setSelectAssistant` 里**（`UseAssistant.kt:31-37`，它会改
`settings.assistantId`）。

**所以**：导入场景**不能直接用 `AssistantPicker`**（否则"选导入目标"会顺带改掉用户的当前助手）。
**做法**：把 `AssistantPickerSheet` 提为 `internal`/`public` 并直接调用，只收 `onAssistantSelected`——
**零副作用、纯选择器**。

### 4.3 落点：导入确认弹窗内联选择（推荐）

`RestoreConfirmDialog` 现在列"将恢复哪些项"，加一行 **「导入到：[助手名 ▾]」**，点开 `AssistantPickerSheet`。

- **默认值 = 当前助手**（= 现行为，不选就是老样子 → **零回归**）
- **优点**：一次确认完成；用户在按"确认"前就知道会导到哪

（备选：选文件后弹第二步选择——多一次弹窗，且中途取消时文件已读一半。）

### 4.4 导出侧是否存 `assistantId`

**建议不存**（或存了只作提示）：跨设备该 id 必然失效，**一律以用户选择为准**。
同时审视现有副作用：`restoreFromChatBox` 会按 `hasConversationSystemPrompt` 改
**目标助手的 `allowConversationSystemPrompt`**——导入到**不同**助手时，这个"顺手改设置"是否仍合适，需一并确认。

## 五、格式设计

照 `ExportSerializer` 加一个 `ConversationSerializer`：

```kotlin
@Serializable
data class ExportData(version: Int = 1, type: String, data: JsonElement)   // 复用现有

object ConversationSerializer : ExportSerializer<Conversation> {
    override val type = "conversation"
    override fun export(data: Conversation): ExportData = ...
    override fun import(context: Context, uri: Uri): Result<Conversation> = ...
    override fun getExportFileName(data: Conversation) = "conversation-<title>.json"
}
```

**`version` 位是给二阶段留的**：一阶段是纯 JSON（不含附件）；二阶段若改为"附件单列 zip 条目"，
格式会从 JSON 变 zip —— **所以现在不要锁死格式**（见 §七）。

**导入需处理的字段**：

| 字段 | 处理 |
|---|---|
| `assistantId` | **丢弃**，改用用户选择（§四） |
| `id`（会话/节点/消息） | **全部换新**（§三） |
| `createAt` / `updateAt` | 直接反序列化（`InstantSerializer` 已就绪；比外部格式的 min/max 推断更准） |
| `compressionSummaries` | **携带**（方案 A）：`conversationId`/`boundaryNodeId` 重映射 + `areaFingerprint` 重算，详见 §5.1 |
| 附件 `file://` 引用 | **一阶段：保留占位文本**（或按 §七 选项清理）；二阶段内联 |

### 5.1 压缩检查点：**决策 A（带检查点 + 重算指纹）**

> **更正**：本文档早期版本写「不导出检查点（带了也无效应）」——**该判断有误**。
> 核实 `Conversation.activeCompression()`：`val fingerprint = checkpoint.areaFingerprint ?: return checkpoint`
> （`Conversation.kt:116`）→ **指纹为 null 时检查点仍生效**（退化为「仅边界存在性校验」）。
> 故这不是"有效/无效"，而是两种策略。**用户拍板选 A**。

**A 的做法**：导出携带 `compressionSummaries`；导入换 id 后**重算指纹**再落库。

**为什么可行**：`Conversation.areaFingerprintFor(boundaryNodeIndex)` 是 **public**
（`Conversation.kt:201`），且指纹算法本就是「区域内节点 id + 选中消息 id 的哈希」——
换 id 后在新 id 上重算即可自洽。

**A 的实施清单（比"一行调用"多几点，均已核实）**：

| # | 事项 | 依据 |
|---|---|---|
| 1 | **`conversationId` 重映射** | 检查点是**独立表** `compression_checkpoint`，主键自增、外键指向 `conversation_id`（`onDelete = CASCADE`）→ 必须改成**新会话 id**，否则外键悬空/被级联删 |
| 2 | **`boundaryNodeId` 重映射** | 指向 `message_node.id`；换 id 后必须**换成新节点 id**（旧 id 在新会话里不存在 → 检查点立即失效） |
| 3 | **重算 `areaFingerprint`** | 用 `areaFingerprintFor(boundaryIndex)`（新节点序列上）覆盖文件里带的值 |
| 4 | **`messageCount` 原样保留** | 它只是**展示**用（「已压缩 N 条」，`CompactionCheckpoint.kt:61`）与下次压缩的累计基数（`ChatService.kt:1266`），与新 id 无关 |
| 5 | **`content` / `createdAt` 原样** | 摘要正文与时间不受换 id 影响 |
| 6 | **落库时机** | 用现成的 `addCompressionCheckpoint(summary)`（`ConversationRepository.kt:444`，签名收 `CompressionSummary`） |
| 7 | **顺序** | 必须在**会话与节点 id 已确定之后**落库（与 §三 的换 id 同批完成） |

**A 的收益**：导入的会话**保持原压缩状态**——`currentMessages` 仍是「检查点合成消息 + 边界后原文」，
发送上下文立刻是压缩后的大小，**不依赖 `limitContext` 兜底**（其默认 `contextMessageLimit = 0` = 不限制，
见下方"为何必须处理"）。

### 5.2 为何必须处理（B 的代价，作为 A 的反证）

若**不带**检查点（原 B 方案）：

- `currentMessages` 全量参与发送，**唯一兜底是 `limitContext(assistant.contextMessageLimit)`**；
- 而 **`contextMessageLimit` 默认值是 `0` = 不限制**（`Assistant.kt:29`）——
  `limitContext` 在 `limit <= 0` 时**直接返回原列表**（`Message.kt:336`）；
- → 目标助手**未显式配过上限时，长会话导入等于全量发送**：超模型窗口报错，
  或被供应商静默截断/计费暴涨。

**这是静默的上下文膨胀，不是"少了点优化"**——故 A 不只是"更优雅"，而是避免一条真实故障路径。

## 六、分阶段

### 阶段 1（低，约 1 提交）

| 项 | 复用 |
|---|---|
| `ConversationSerializer`（export/import） | `ExportSerializer` 框架 |
| 导出入口 | `ChatExportSheet` 加"JSON（可导入）" |
| 导入入口 | `ImportExportTab` 加 `importType = "rikkahub"` 分支（照现有三分支形状） |
| 换新 id + 顺序正确 | 参考 fork 写法 + 补 `message.id` |
| 目标助手选择 | **抽 `AssistantPickerSheet` 可见性** + `RestoreConfirmDialog` 挂载 |
| 助手不存在 | 一律以用户选择为准（§4.4） |
| FTS 重建 | `MessageFtsManager.indexConversation` |

### 阶段 2（中）

附件内联 base64（复用 `Export.kt` 的 `encodeBase64`）。
**先定格式**（见 §七.1），再实现。

## 七、风险与注意点

1. **格式别提前锁死**：一阶段 JSON 不含附件；二阶段若附件走 base64 内联，长对话带多图时
   **体积膨胀 ~33% 且整段进单个 JSON 字符串**（内存/解析压力）。届时可能应改为**附件单列 zip 条目**
   （即格式变 zip）。`ExportData.version` 就是为这种演进留的位。
2. **换 id 与防覆盖的顺序**（§三）：务必"先换 id、后检查插入"，否则 skip 分支吃掉导入。
3. **`AssistantPickerSheet` 的副作用**（§4.2）：**直接用 `AssistantPicker` 会改用户的当前助手**——
   必须用抽出的无副作用版本。
4. **压缩检查点**（§5.1）：方案 A 需重映射 `conversationId`/`boundaryNodeId` 并**重算指纹**；
   漏掉任一项 → 检查点失效（边界 id 悬空）或外键悬空。
5. **`restoreFromChatBox` 的助手设置副作用**（§4.4）：改目标助手 `allowConversationSystemPrompt`
   的行为在"导入到任意助手"后是否仍合理，需确认。
6. **文件命名与 SAF**：`getExportFileName` 含会话标题 → 需**清洗非法字符**（`/ \ : * ? " < > |`）。
7. **FTS 与备份**：导入后必须 `indexConversation`，否则新会话搜不到；整库备份已含 FTS 之外的一切，
   两者不冲突。
8. **幂等性**：换新 id 后**重复导入会得到多份副本**（这是决策 1 的直接后果，符合"导入即新建"语义）——
   需在 UI 文案里说清"每次导入都是新副本"。

## 八、统一三种导入器 + 补助手选择（**待导入功能验证后再做**）

现在 `"local"/"chatbox"/"cherry"` 是三个独立分支 + 两个独立 Importer 类，**彼此无共同抽象**。
而 `ExportSerializer` 已提供正确形状，`LorebookSerializer` 也示范了"自家优先 + 外部兜底"。

**统一后新增格式只需加一个 serializer**。用户决定：**Chatbox/CherryStudio 也要加助手选择，
并借此统一导入器；且该轮「等导入功能验证后再实现」**——即**依赖顺序**：先让自家格式往返落地并
真机验证通过，再动这个重构（避免边验证功能边搬结构，问题难归因）。

该轮的范围：①抽 `ExportSerializer` 同族抽象覆盖三种外部导入器（现三分支 + 两独立类无共同抽象）；
②三处（含自家格式）共用「选择目标助手」这一步；③`importType` 分支收敛。

## 九、待确认

1. **重复导入提示的文案细节**（§十.3）：是否需要显示"原会话标题"？时间格式？
2. **`importedSources` 的清理策略**（§十.4）：删掉导入的会话后，历史记录是否保留？
3. 阶段划分确认：阶段 1 是否含 §十（重复检测）？

（已定：`assistantId` 不存 / 压缩检查点**方案 A** / 二阶段再定附件格式 /
**统一导入器=导出功能验证后再做** / **重复导入检测+提示（允许继续）**。）

## 十、重复导入检测 + 提示（决策 8）

### 10.1 为什么需要新锚点

**决策 1（导入即换新 id）使旧判据永久失效**：现在 `restoreFromChatBox` 里的
`existsConversationById(conversation.id)` 在换 id 后**永不命中**——所以"同一文件导入两次"
会被当成两次全新导入，**静默产生重复副本**。

核实发现**没有现成锚点**：
- `ConversationEntity` 无"来源/导入批次"列（只有 id/title/nodes/时间/注入绑定等业务字段）；
- `ExportData(version, type, data)` 也无来源信息。

### 10.2 方案：导出携带 `sourceId` + Settings 存导入历史

**① 导出侧加两个字段**（`ExportData` 是 JSON，加字段**零迁移**，各 serializer 只读自己关心的）：

```kotlin
@Serializable
data class ExportData(
    val version: Int = 1,
    val type: String,
    val data: JsonElement,
    val exportedAt: Long? = null,     // 导出时间（提示文案要显示）
    val sourceId: String? = null,     // ← 导出时刻的会话 id，作为幂等锚点（随文件固定不变）
)
```

`sourceId` 用**导出时的 `Conversation.id`**——它随文件走、不随导入改变，是稳定的幂等键。

**② 导入侧记录"来源 → 时间"映射**，放 **`Settings` 顶层**（三件套，与 `compressPresets`
同一套写法：`stringPreferencesKey` + `JsonInstant` 编解码）：

```kotlin
/** 导入来源记录：sourceId -> (导入时间, 原会话标题)。用于重复导入提示。 */
@Serializable
data class ImportSourceRecord(val importedAt: Long, val title: String? = null)

val importedSources: Map<String, ImportSourceRecord> = emptyMap()
```

**为什么放 Settings 而不加 DB 列**：
- **零迁移**（DB 列需 29→30 迁移，且每个会话多一列永久占位）；
- 它本质是**用户级的"来源→时间"记录**，不是会话属性；
- 提示文案要的"xxx 已于 xxx 导入过"正好由该映射直接提供（还能带上原会话标题）。

### 10.3 流程与文案

**落点**：现有导入流程是「选文件 → `isRestoring = true` → 直接落库」，**没有中间确认态**。
需在**读完文件、落库之前**插入一步检测：

```
选中文件 → 解析出 sourceId → 查 importedSources
  ├─ 未命中 → 照常导入（换新 id）→ 成功后记入 importedSources
  └─ 命中   → 弹「重复导入」确认
              ├─ 继续 → 照常导入（换新 id）→ 更新时间戳
              └─ 取消 → 终止（不落库、不记录）
```

**文案**（用户给定形态，**语义是"允许继续"而非阻止**）：

> 检测到重复导入：**{原会话标题}** 已于 **{yyyy年MM月dd日}** 导入过。
> 继续导入将产生重复数据，确定要继续吗？

（`title` 缺失时退化为"该对话"；时间用用户给定的中文年月日格式，其余语言用当地习惯格式。）

### 10.4 注意点

1. **`sourceId` 是"导出时会话 id"，不是"导入后新会话 id"** —— 两者必须区分：
   前者随文件固定（幂等键），后者每次导入都新生成。**把新 id 记进 `importedSources` 就永远查不到了**。
2. **记录时机 = 导入成功之后**（取消/失败都不该留下记录，否则下次会误报"已导入过"）。
3. **同一文件导入到不同助手**仍算重复（锚点是文件来源，不是助手）——符合"检测同一文件"的直觉。
4. **清理策略待定**（§九.2）：删掉导入产生的会话后，`importedSources` 里的记录**建议保留**
   （它记的是"这个来源导入过"，与产物是否还在无关）；但记录会**只增不减**，
   极端情况下需要手动清理或设上限（几十条量级，暂不处理）。
5. **旧版导出文件没有 `sourceId`**（该字段为 null）→ **跳过检测**（无法判定，不是重复）：
   这是向后兼容的正确降级。
6. **与 `exportedAt` 的关系**：提示里显示的是**导入时间**（`importedSources` 的值），
   不是 `exportedAt`（导出时间）——用户说的是"已于 x 导入过"。`exportedAt` 可选用于"文件较旧"的额外提示，**本次不做**。
