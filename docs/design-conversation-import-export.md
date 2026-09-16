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
| FTS 重建 | `MessageFtsManager.indexConversation` | 导入后调用即可 |

## 二、已定决策

| # | 决策 | 后果 |
|---|---|---|
| 1 | **导入即新建、全部换新 id，永不覆盖** | 消掉"覆盖已有会话"的数据丢失风险；`existsConversationById` 的 skip 分支**永不命中** |
| 2 | **第一阶段不导出附件** | 顺带消掉最麻烦的"附件路径跨设备失效"问题；二阶段再定附件格式 |
| 3 | **导入时用户选择目标助手** | 消掉"跨设备 `assistantId` 悬空"问题 |
| 4 | **导出文件不存 `assistantId`** | 跨设备必失效，一律以用户选择为准（§4.4） |
| 5 | **不导出 `compressionSummaries`** | **但需配套处理上下文长度——见 §5.1**（原「无效应」判断已更正） |
| 6 | **Chatbox/CherryStudio 也加助手选择，并借此统一导入器** | **新开一轮**（见 §八） |
| 7 | **二阶段再决定附件格式** | 一阶段纯 JSON，`version` 位预留 |

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
| `compressionSummaries` | **不带**（用户决定）。**但这会引出上下文长度问题，需配套处理——见 §5.1**。原判断「带了也无效应」**有误，已更正**。 |
| 附件 `file://` 引用 | **一阶段：保留占位文本**（或按 §七 选项清理）；二阶段内联 |

### 5.1 「不带检查点」的真实代价与配套（更正原判断）

**原判断有误**：我曾写「带了也无效应」，但核实 `activeCompression()` 后发现相反——

```kotlin
val fingerprint = checkpoint.areaFingerprint ?: return checkpoint   // ← 指纹为 null 时【仍生效】
```

即 **`areaFingerprint = null` 会退化为「仅边界存在性校验」，检查点依然生效**（`Conversation.kt:116`）。
所以「带检查点但指纹失配」不是"无效应"，而是**两种可选策略**：

| 策略 | 做法 | 后果 |
|---|---|---|
| **A. 带 + 重算指纹** | 导入换 id 后，用 `areaFingerprintFor(boundaryIndex)` **重算**（该 API 是 public） | 检查点**导入后有效**，上下文立刻是"压缩后"的大小 |
| **B. 不带**（用户决定） | 不导出 `compressionSummaries` | 导入后**无检查点** → 发送全量历史 |

**选 B 的代价（用户反问的正是这个）**：长会话导入后，`currentMessages` 全量参与发送，
只靠 **`limitContext(assistant.contextMessageLimit)`** 兜底——而它的默认值是 **0（= 不限制）**！

```kotlin
// Assistant.kt:29
val contextMessageLimit: Int = 0      // 0 = 不截断
```

`limitContext` 在 `limit <= 0` 时**直接返回原列表**（`Message.kt:336`）。所以：

- 若目标助手的 `contextMessageLimit` 为 0（默认）→ **全量历史直接发给模型**
  → 超过模型窗口时报错，或（更糟）被供应商静默截断/计费暴涨；
- 只有用户**显式配过** `contextMessageLimit` 时才有兜底。

**因此选 B 必须配套其一**：

1. **导入后提示**：「该对话已压缩过，但摘要未随文件导入；若历史很长，建议在助手设置里配置
   上下文消息上限，或对其手动执行一次压缩」；
2. **或：导入时自动压缩一次**（复用现有 `compressToCheckpoint`，成本=一次 LLM 调用，
   需用户确认/开关）；
3. **或：改主意选 A**（重算指纹），零额外成本且语义最好。

**我的建议**：**A 更好**（技术上一行 `areaFingerprintFor` 即可，且"导入的会话保持原压缩状态"更符合直觉）；
若坚持 B，则**至少要做 1（提示）**，否则长会话导入是静默的上下文膨胀。

> 注：B 的一个附带好处是"避免带进无效检查点行"。但 A 通过重算指纹就消除了"无效"，
> 所以这个好处不构成选 B 的理由。

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
4. **压缩检查点与上下文长度**（§5.1）：不带检查点是决策，但**必须配套**，否则长会话导入后
   会出现「全量发送 + 无压缩」的上下文膨胀。
5. **`restoreFromChatBox` 的助手设置副作用**（§4.4）：改目标助手 `allowConversationSystemPrompt`
   的行为在"导入到任意助手"后是否仍合理，需确认。
6. **文件命名与 SAF**：`getExportFileName` 含会话标题 → 需**清洗非法字符**（`/ \ : * ? " < > |`）。
7. **FTS 与备份**：导入后必须 `indexConversation`，否则新会话搜不到；整库备份已含 FTS 之外的一切，
   两者不冲突。
8. **幂等性**：换新 id 后**重复导入会得到多份副本**（这是决策 1 的直接后果，符合"导入即新建"语义）——
   需在 UI 文案里说清"每次导入都是新副本"。

## 八、统一三种导入器 + 补助手选择（**已定为新开一轮**）

现在 `"local"/"chatbox"/"cherry"` 是三个独立分支 + 两个独立 Importer 类，**彼此无共同抽象**。
而 `ExportSerializer` 已提供正确形状，`LorebookSerializer` 也示范了"自家优先 + 外部兜底"。

**统一后新增格式只需加一个 serializer**。用户已决定：**Chatbox/CherryStudio 也要加助手选择，
并借此统一导入器——单开一轮**（不塞进本设计的阶段 1）。

该轮的范围：①抽 `ExportSerializer` 同族抽象覆盖三种外部导入器（现三分支 + 两独立类无共同抽象）；
②三处（含自家格式）共用「选择目标助手」这一步；③`importType` 分支收敛。

## 九、待确认

1. **上下文长度配套怎么选**（§5.1）：**A 带检查点+重算指纹**（我建议，零额外成本、语义最好）
   还是 **B 不带 + 导入时提示**（用户已选 B 的大方向，但需确认配套）？
2. **重复导入的文案**是否需要显式提示"将新增副本"？
3. 统一导入器那一轮（§八）是否本设计落地后紧接着做？

（原待确认项 1/2/3/5 均已定：Chatbox/CherryStudio 加助手选择并统一导入器=新开一轮；
`compressionSummaries` 不带=已定（配套见上）；二阶段附件格式=二阶段再定。）
