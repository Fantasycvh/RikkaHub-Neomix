# 会话批量迁移到指定助手（设计稿）

> 状态（2026-11-22）：**草案**。定案两条核心语义：文件夹跟随（同名归并/克隆）、单会话入口统一为同一语义。
> 来源：单会话「移动到助手」已存在但只能逐条操作；本稿补多选批量入口，并把文件夹处理语义一并定死。

## 1. 这份稿子要解决什么

抽屉多选目前只有「删除所选」（ChatDrawer.kt:553-595）。用户换助手/整理会话时需要把一批会话
整体迁到另一个助手，逐条长按太慢。数据层面成本极低：`ConversationEntity.assistant_id` 是单列，
ChatService 已有统一的元数据原语 `updateConversationMetadata`（ChatService.kt:3153）与单列落盘
`updateConversationAssistant`。

真正的复杂度只有一个：**文件夹隶属助手**（FolderEntity.assistant_id，Folder.kt:11），跨助手迁移时
folderId 不能字面保留——否则会话既不在新助手的未归档列表（`folder_id = ''` 过滤，ChatDrawerVM.kt:99），
也不在任何可见文件夹（文件夹列表按当前助手过滤，ChatDrawerVM.kt:61），直接从抽屉里「消失」。
现有单会话迁移因此刻意清空 folderId（ChatService.kt:3430 注释）。

## 2. 语义定案（勿回退）

**D1 文件夹跟随：同名归并，无同名则克隆。**
- 会话带 folderId 且 `getFolderById` 命中时：在**目标助手**的文件夹里找**同名**文件夹——
  命中 → 会话 folderId 指向该文件夹 id（归并）；未命中 → 在目标助手下克隆同名文件夹
  （`createFolder(target, name)`，sortIndex 追加到末尾，不复制源排序），会话指向克隆体。
- **原文件夹永远不动**（哪怕迁空了也不删除、不改名）。
- 多个会话来自同一个源文件夹 → 克隆/归并只做一次，映射复用；多个源文件夹同名 → 归并到同一个目标文件夹（同名即同义，可接受）。

**D2 单会话入口统一为同一语义。**
`moveConversationToAssistant`（ChatService.kt:3430）改为走同一套文件夹跟随逻辑，
替换现有「清空 folderId」行为；`ConversationSessionMetadataTest` 相关断言同步更新。

**D3 已属于目标助手的会话整条跳过**（assistantId 与 folder 都不动），计入「跳过数」用于 toast。

**D4 冻结锚点照旧清空**（frozenAnchors / frozenFileAnchors，跨助手串味防回归，沿用现状）。

**D5 usage 统计不回填**：历史 usage 按写入时的 assistantId 归属，迁移不改历史口径。

**D6 逐条走 `updateConversationMetadata` 原语，不引入批量整对象写。**
活跃 session 的在途内容安全由该原语保证；批量 N 条 = N 次单列 UPDATE（每次一条 SQL，量级无压力）。
不打包进单个 Room `@Transaction`——元数据原语的内存态读取无法放进 DAO 事务。

**D7 workspaceCwd / upstreamConfig / 其他会话级配置一律不动**：它们是会话属性，不是助手属性。

## 3. 数据层落点

复用件（全部已存在，零新增 DAO 查询）：
- `ConversationDAO.updateConversationAssistant(id, assistantId)` — 单列 assistant_id
- folder 单列写：`clearFolder` 同族应有 folder_id 单列 UPDATE（`moveConversationToFolder` 链路在用）
- `FolderRepository.getFolderById / getFoldersOfAssistant / createFolder`

新增 ChatService 入口：

```kotlin
suspend fun moveConversationsToAssistant(
    ids: Collection<Uuid>,
    targetAssistantId: Uuid,
): MoveResult  // data class(moved: Int, skipped: Int)
```

内部流程：
1. 目标助手存在性前置校验：`settings.assistants.find { it.id == target }` 不存在 → 直接返回全跳过
   （**禁止**走 `getAssistantById` 的静默回落首个助手——迁移写库必须显式失败）。
2. 目标助手文件夹快照取一次：`folderRepo.getFoldersOfAssistant(target).first()`，构建 `name → id` 表。
3. 逐条：`skip` 判定（D3）→ `updateConversationMetadata { copy(assistantId = target, frozenAnchors = null, frozenFileAnchors = null) }`
   → folderId 解析（D1）→ folder 单列 UPDATE → frozen 锚点库侧补清（沿用 ChatService.kt:3439 模式）。
4. folderId 解析缓存：`mutableMapOf<Uuid, Uuid?>`（源 folderId → 目标 folderId，null = 解析失败按未归档落），
   同源文件夹只查/建一次；`getFolderById` 返回 null（迁移中途文件夹被删）按未归档处理，不阻塞整批。

单会话 `moveConversationToAssistant` 收敛为 `moveConversationsToAssistant(listOf(id), target)` 的薄壳。

## 4. UI 落点

- 多选操作条（ChatDrawer.kt:554-595）：在「删除所选」左侧加「移动」TextButton（非 error 色），
  与删除共享 `enabled = selectedIds.isNotEmpty()`。
- 复用现有「移动到助手」ModalBottomSheet（ChatDrawer.kt:1025）：触发态从单会话
  `conversationToMove: Conversation?` 扩展为「单条或多条」；助手列表项的 `isCurrentAssistant`
  高亮语义改为「选中会话已全部属于该助手」；**不置灰**——点下去由 service 按 D3 跳过，
  toast 明确反馈「所选会话已属于该助手」，比无反馈的禁点更清楚。
- 确认弹窗：不加。迁移不丢数据且语义可逆（再迁回来即可），保持操作轻量；Sheet 内文案列出
  「n 条会话 → 目标助手名」。
- 结果 toast：moved=0 且 skipped>0 → 「所选会话已属于该助手」；否则「已迁移 n 条」（skip>0 时追加）。
- 迁移后 `conversations.refresh()`（与删除链路一致）；若当前打开的会话在迁移名单里，
  沿用 ChatVM.kt:724 的 `settingsStore.updateAssistant(target)` 联动切换当前助手。

## 5. 字符串

新增键（en/zh/zh-rTW，走 locale-tui）：`conversation_multi_select_move_selected`、
`conversation_move_sheet_batch_title(n)`、`conversation_move_result_moved(n)`、
`conversation_move_result_skipped(n)`、`conversation_move_result_noop`、
`conversation_move_result_all_same`。

## 6. 测试

- Folder 解析映射（纯逻辑抽函数便于单测）：同名归并 / 无同名克隆 / 源文件夹已删 / folderId 为空 /
  多源同名归并到同一目标。
- ChatService：skip 判定（D3）；目标助手不存在全跳过；冻结锚点清空；返回计数正确。
- `ConversationSessionMetadataTest`：单会话迁移语义从「folderId = null」更新为「文件夹跟随」。

## 7. 不做

- 不做跨助手文件夹批量整体搬迁（把整个文件夹及其全部会话一起迁）——本稿只动被选中的会话。
- 不做目标侧同名文件夹的模糊匹配（仅精确同名）。
- 不做 usage 回填（D5）。
