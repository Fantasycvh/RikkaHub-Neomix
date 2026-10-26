# 设计：history_search/read 历史查回工具 + GenerationHandler 增量 emit

- 日期：2026-10-26
- 状态：草案（待批准）
- 来源：Yuihub 3.0.1（`ConversationTools.kt` history 工具 ~130 行 + `generationMessageUpdates` 增量发射）；账本 §五表 16
- 前置事实（本仓已核实）：压缩是 append-only——被压缩的原文**从不删除**，仍在 `message_node`；FTS（`MessageFtsManager`，simple 分词 + CJK NEAR）对全树建索引，天然覆盖被压缩区域；已有 `conversation_search`/`read_conversation`（跨会话漏斗）可参照。

## Part A：history_search / history_read（约 1 天）

### A.1 缺口确认

`conversation_search` 是**全局**检索（`searchMessages(keyword)` 无会话过滤），`read_conversation` 面向跨会话漏斗。压缩后的场景是：模型上下文里只剩合成摘要，本会话边界前的细节全部不可达——需要一个**当前会话、限定被压缩区域**的按需查回。Yuihub 仅在 `activeCompressions().isNotEmpty()` 时注册这两个工具，注册条件本身就是给模型的「现在有历史可查」信号。

### A.2 工具设计（`data/ai/tools/HistoryTools.kt`，新文件）

**`history_search`** — `{query: string (必填), limit: int (默认 12, 上限 30)}`
1. `conversationRepo.searchMessages(query, RELEVANCE)`（现有全局 FTS，返回带 node_id/conversation_id/snippet）；
2. **会话与区域过滤**：执行时实时加载当前会话（`conversationRepo.getConversation(...)`——边界可随轮移动，不能闭包快照），`compressionWindow()` 取 `boundaryIndex`，被压缩区域 = `messageNodes[0 until boundaryIndex]` 的全部 message id 集合；FTS 命中按 message_id 是否属于该集合过滤；
3. 输出 JSON 数组 `[{node_id, message_id, snippet, date}]`（`JsonInstantPretty`）。
   - 边界语义备注：若 FTS 命中在保留窗内（模型本来就看得到），过滤掉还是保留？**保留窗内的命中也返回**但标注 `in_window: true`——模型才知道「这个不用查了」vs「查到被压缩的细节」。取 Yuihub 语义（无过滤标注）+ 此增强，成本 1 行。

**`history_read`** — `{node_id: string (必填), offset: int (默认 1, 1-based 行), limit: int (默认 120, 上限 400)}`
1. 实时会话里 `messageNodes.firstOrNull { it.id.toString() == nodeId }`；找不到报 `error("History node not found: ...")`；
2. 渲染 `currentMessage`：Text 部分原样；Tool 部分 `[tool: <name>]` + 输出文本（抽纯函数 `UIMessage.historyText()`，可 JVM 测）；
3. 按行分页，行号格式 `N|line`；输出 `{node_id, role, start_line, end_line, total_lines, complete, next_offset?, text}`。
4. 输出经现有 per-call 限幅（批次 1 闸门兜底），不设豁免。

### A.3 注册与接线

- `ChatToolFactory` 新增 conversation 段内的一档：`conversation.activeCompression() != null` 时注册 `createHistoryTools(...)`。工厂现有入参若只带 `conversationId` 则改为可拿到 conversation 快照判注册条件（注册时判一次即可；执行时再实时取会话）。
- 工具描述注明「仅本会话被压缩归档的消息；查当前可见上下文用不着它」。

### A.4 测试

纯函数层（`historyText()`、行分页、hidden-region 过滤）JVM 单测 ~8 项：渲染含 Tool 部分、offset/limit 边界、complete/next_offset、越界 offset、区域过滤含 in_window 标注。工具 execute 的 IO 部分不测（与既有 conversation 工具同待遇）。

## Part B：GenerationHandler 增量 emit（1-2 天，风险主要在合并语义）

### B.1 现状与动机

现状：每个 SSE chunk → `handleMessageChunk` 合并进全量 `messages` → 输出 transformers 对**全量列表**跑一遍 → `GenerationChunk.Messages(全量)` → ChatService 收集器 `updateCurrentMessages(chunk.messages)` 做**按索引对齐**的合并 → 新 `Conversation` 实例换进 StateFlow。长会话 × 256 步工具循环下，每 token 的成本 = 全量 transform + 全量发射 + 全量索引合并。

**必须直面的难点**：本仓合并是**索引制**（`Conversation.updateCurrentMessages` 按 `messages[i] → messageNodes[i]` 对齐，ChatService 1668/1704/3184 三处注释明确依赖「全量对齐」基线）。Yuihub 的增量发射配套的是**id 制合并**。直接把增量列表塞进现有合并会**张冠李戴地覆盖错节点**。

### B.2 方案

**B2-a：id 制合并（新增，不动现有索引制）** —— `Conversation.mergeGenerationMessages(messages): Conversation`
- 对每条入站消息：按 message.id 找到所属节点 → 就地替换；找不到 → `message.toMessageNode()` 追加为新节点（与索引制的追加语义一致）；
- 现有 `updateCurrentMessages` 保留原样——索引制基线回写（1668/1704）继续用它，两套语义并存、调用点分明。

**B2-b：增量发射** —— `GenerationHandler`
- 生成开始时快照源消息 id 集（`sourceIds`）；
- 新增 `generationUpdates(updated) = updated.filter { !it.isSynthetic && it.id !in sourceIds }`（9 行，Yuihub 同款）；
- 应用点：**流式 chunk 的 onUpdateMessages**（最高频）+ 工具结果 commit/审批态变更两处；**工具循环边界的最终 emit 保留全量**（每步一次，成本可忽略，且携带完整确定性状态）；
- `GenerationChunk` 增加语义标记或新类型 `Delta(messages)`，让收集器知道走合并。

**B2-c：收集器切换** —— ChatService 流式路径
- `Delta` chunk → `mergeGenerationMessages`；`Messages`（全量）→ 维持 `updateCurrentMessages`；
- 2s 崩溃检查点写的是合并后的 conversation（收集器侧状态），不受影响。

### B.3 风险与对策

- **R-1 输出 transformers 只改新消息？** 增量只含新 id，历史消息若被 transform 原地修改会丢失展示。审计现有 transformer：ThinkTag/Regex/Base64ToLocal/Ocr 均只作用于本轮新增消息（生成结束的落库走全量基线回写，与展示流无关）。设计里列审计清单，实现时逐个确认；发现例外则该 transformer 的作用消息单独入增量。
- **R-2 UI 依赖全量列表的 remember 块**（如 `collectConversationImageUrls` keyed on currentMessages）：合并后每次仍产生新 Conversation 实例，recompose 语义不变——增量只是减少发射体积，不改变实例换新频率（这块的进一步优化不属本批次）。
- **R-3 分支/选中语义**：合并按 id 就地替换不动 selectIndex（仅追加时置末尾），与索引制在「生成期没有分支」的前提下等价。
- **R-4 visualTransform 与 transform 双跑**：现状已在每个 chunk 全量双跑，本批次不改 transform 调度（记为后续可选项：transformers 增量化）。

### B.4 测试

- `mergeGenerationMessages` JVM 单测 ~8 项：就地更新、追加新节点、未知 id、空列表、selectIndex 保持、与索引制混合调用不互扰；
- `generationUpdates` 纯函数 ~5 项：源快照过滤、synthetic 排除、全量为空、重复 id；
- 真机验收：长会话流式输出无错位（重点：工具循环内多轮、分支切换、压缩生效会话）。

## 落地顺序

1. **批次 A**：HistoryTools + 注册 + 纯函数单测（独立可交付，随时可停）。
2. **批次 B1**：id 制合并 + 单测（不接线）。
3. **批次 B2**：增量发射 + 收集器切换 + transformer 审计清单核对 + 真机验收。
