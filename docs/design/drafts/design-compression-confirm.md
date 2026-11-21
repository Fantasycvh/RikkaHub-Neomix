# 压缩前范围预览确认 + 压缩记录计数展示（#2294 立项方案）

> **落地状态（2026-11-21）**：已按本稿实现并本地提交。与稿面差异：无（strings 走了三语，用户拍板）。
> 手测项（真机待验）：对话框两阶段流转 / 无可压缩禁用 / 片段与实际边界一致 / 折叠行计数 / sheet 序号 / 旧数据回退文案。

> 上游 [issue #2294](https://github.com/rikkahub/rikkahub/issues/2294)（rikkahub，报告者 Caellux）：「优化对话压缩范围预览与压缩记录展示」。上游以 `63a5192d8` 关闭，但该提交只做了**消息概览标压缩点**（ChatListPreview），未覆盖报告者的主需求；报告者在 issue 里明确反驳过关闭——**报告者是对的**。本仓自研方案，`63a5192d8` 的概览标点另列评估候选，不在本方案内。

## 一、需求拆解（以报告者原文为准）

| # | 需求 | 上游现状 | 本仓现状 |
|---|---|---|---|
| R1 | **压缩执行前预览并确认本次将被压缩的消息范围**（显示起止边界消息片段，确认无误后再执行） | 无 | 手动压缩**触发即执行**，只有执行中进度对话框 |
| R2 | 压缩摘要显示序号 / 本次条数 / 时间（「#3 · 本次压缩 12 条 · 10-13 18:26」） | 无 | sheet 有「已压缩 X 条 · 时间」，无序号、无「本次」条数 |
| R3 | 压缩历史显示当前未压缩数与总数（「现 20 条（总 50 条）」） | 无 | 折叠行只有累计「已压缩 N 条」 |

**范围裁定**：R1 只做**手动压缩**（`CompressContextDialog` 路径）。自动压缩（`maybeAutoCompress`）、中途压缩（`bcc649abc` 线）、超窗恢复压缩都是静默后台路径——加确认框等于打断生成，不做（与 design-auto-compaction §4.2「压缩自动触发、limitContext 兜底」的定位一致）。

## 二、现状链路（已核实）

- 入口：[ChatPage.kt:1011](../../app/src/main/java/app/androidrik/rikkahub/ui/pages/chat/ChatPage.kt#L1011) `CompressContextDialog`（配置 + 进度一体的 AlertDialog，`verticalScroll + imePadding` 已合规）→ [ChatVM.handleCompressContext](../../app/src/main/java/app/androidrik/rikkahub/ui/pages/chat/ChatVM.kt#L369) → [ChatService.compressToCheckpoint](../../app/src/main/java/app/androidrik/rikkahub/service/ChatService.kt#L2600)
- 切分：`compressToCheckpoint` 内联完成（ChatService.kt:2683-2703）——`conversation.compressionWindow()` 取有效窗口 → `retainRatio` 算 token 预算 → [estimateCompressionPlan](../../app/src/main/java/app/androidrik/rikkahub/data/ai/compression/CompressionPlanner.kt#L87)（token 驱动 + `alignCutIndex` 安全切点 + 进度闸）。**纯函数、确定性**：相同路径/内容/预算得到相同切点。稀疏会话先 `hydrateAll` 补全。
- 展示：折叠行 [CompactionCheckpointRow](../../app/src/main/java/app/androidrik/rikkahub/ui/components/message/CompactionCheckpoint.kt#L69)（title = 累计 `messageCount`）；编辑 sheet `CompressionSummarySheet` 元信息行 =「已压缩 X 条 · 时间」。
- 数据：`CompressionSummary.messageCount` 是**累计值**（`priorCount + messagesToCompress.size`，ChatService.kt:2851-2855），无「本次条数」、无序号。表 `compression_checkpoint` 只保留最新一行（`trimCompressionCheckpoints` 清旧行，摘要合并接力）——**无压缩历史可列**，R2/R3 只能落在折叠行 + sheet 上。
- DB：`AppDatabase` version = **78**（c1c3c4d12 复合索引那轮）。

## 三、设计

### 3.1 R1：压缩前范围预览确认（两阶段对话框）

**切分预览与执行共用同一段代码**，防复制漂移：

1. 把 `compressToCheckpoint` 里的切分段（ChatService.kt:2683-2706：hydrate → compressionWindow → budget → plan → windowTokensBefore）抽成私有方法

```kotlin
// ChatService 内私有：preview 与执行共用；只读（不写库、不调模型）
private suspend fun planCompressionWindow(
    conversationId: Uuid,
    windowTokens: Int,
    retainRatio: Double,
    keepRecentMessages: Int,
): Pair<Conversation, CompressionPlan>
```

2. 新增公开方法（只读、无副作用）：

```kotlin
data class CompressionPreview(
    val toCompressCount: Int,     // 本次将压缩条数
    val keptCount: Int,           // 保留原文条数
    val estimatedTokens: Int,     // 被压缩前缀 token 估算（estimateTokenCount，标注「估算」）
    val firstSnippet: String,     // 起始边界消息片段（toCompress.first，前 ~120 字）
    val lastSnippet: String,      // 结束边界消息片段（toCompress.last，前 ~120 字）
)

suspend fun previewCompressionPlan(...): CompressionPreview?   // null = 无可压缩
```

3. **UI：`CompressContextDialog` 改两阶段**（仍是同一个 AlertDialog，宿主规范不变）：
   - **阶段 1（配置）**：现状不变；confirmButton 文案「确认」→「预览范围」
   - **阶段 2（预览确认）**：点击后经 `ChatVM.previewCompression(...)`（suspend，包 loading 态）取得 `CompressionPreview`，展示：
     - 主行：「本次将压缩 N 条消息（约 X token），保留 M 条原文」
     - 起止片段卡：两条带 role 标签的片段（`Text(maxLines = 3, overflow = Ellipsis)`）——**禁止内嵌滚动**（AlertDialog 不与 IME insets 协调，AGENTS 规则 #3；片段截断即可，展开查看交给现有「展开压缩历史」）
     - `preview == null`（无可压缩）：显示现有 `chat_page_compress_not_enough_messages*` 文案，执行按钮禁用
     - confirmButton「执行压缩」→ 原样调 `onConfirm(...)`；dismissButton「返回调整」回阶段 1
   - **预览是快照，执行重算**：确认后 `compressToCheckpoint` 按最新会话态重切（现状行为不变）。预览与执行输入相同 ⇒ 切分一致（同一确定性函数）；仅当预览后消息变动（新回复/编辑）才可能偏差，对话框文案注明「以执行时为准」。

**不做**：预览完整消息列表（起止片段 + 条数已够定位范围；完整列表要嵌套滚动，踩 AlertDialog 宿主坑）。

### 3.2 R2：摘要序号 / 本次条数 / 时间

- **DB 迁移 78 → 79**：`compression_checkpoint` 加两列（`ALTER TABLE ... ADD COLUMN`，带 DEFAULT，旧行零回填）：
  - `seq INTEGER NOT NULL DEFAULT 0` —— 会话内压缩序号（1 起）
  - `batch_count INTEGER NOT NULL DEFAULT 0` —— 本次压缩条数
- `CompressionCheckpointEntity` / `CompressionSummary` / mapper 加字段；导入检查点、旧数据保持 0。
- **赋值**（`compressToCheckpoint` commit 前）：`seq = (conversation.displayCompression()?.seq ?: 0) + 1`（表只留最新行，最新行的 seq 就是接力起点，无需数行数）；`batchCount = messagesToCompress.size`。
- **展示**：
  - `CompressionSummarySheet` 元信息行：`#3 · 已压缩 50 条（本次 12 条）· 2026-10-13 18:26 · 修改后下次对话生效`；`seq <= 0`（导入/旧数据）回退现状文案。
  - 折叠行 title 维持「已压缩 N 条」（累计语义，用户对齐三边界心智）；序号/时间进 sheet。

### 3.3 R3：折叠行「现 N 条（总 M 条）」

- 数据在 [ChatList.kt:661-677](../../app/src/main/java/app/androidrik/rikkahub/ui/pages/chat/ChatList.kt#L661) 处直接可算，零数据层改动：
  - 未压缩数 = 折叠态 `visibleNodes` 中非检查点节点数（即边界后 live 消息；稀疏会话为已载入尾巴数，如实显示）
  - 总数 = `activeCheckpoint.messageCount + 未压缩数`（累计已压缩 + 现存未压缩）
- `CompactionCheckpointRow` 加参数 `currentCount: Int` / `totalCount: Int`，第二行显示「现 N 条（总 M 条）」。

## 四、明确不做

- 压缩历史列表页：表只留最新一行，无历史可列（报告者「压缩记录展示」在本仓的落点就是折叠行 + sheet）。
- 自动/中途压缩加确认：打断生成，不做。
- `63a5192d8` 消息概览标压缩点：另列 11-18 评估候选，不并入本方案。
- 三语 strings：按仓规默认硬编码中文（近期实现一致：折叠行展开/加载更早等均为硬编码中文）；如需三语再走 locale-tui。

## 五、改动文件清单

| 文件 | 改动 |
|---|---|
| ChatService.kt | 抽 `planCompressionWindow`；新增 `previewCompressionPlan` + `CompressionPreview`；commit 前 seq/batchCount 赋值 |
| CompressionPlanner.kt | （不动——切分纯函数已齐备） |
| CompressionSummary.kt / CompressionCheckpointEntity.kt / mapper | 加 `seq`、`batchCount` |
| AppDatabase.kt + Migration | version 79，compression_checkpoint 加两列 |
| CompressContextDialog.kt | 两阶段：配置 → 预览确认（loading / null 态） |
| ChatVM.kt | `previewCompression(...)` suspend 桥接 |
| ChatPage.kt | 传 preview 回调 |
| ChatList.kt | 折叠行传 currentCount/totalCount |
| CompactionCheckpoint.kt | Row 加计数行；sheet 元信息行加序号/本次条数 |

## 六、测试点

- **迁移**：compression_checkpoint 78→79 加列默认值（androidTest schema 既有套路）。
- **seq 接力**：连续两次压缩 seq = 1、2；导入检查点（seq=0）后再压缩 seq = 1。
- **batchCount**：含 prior 累计时 `messageCount` 仍累计、`batchCount` 只记本次。
- **preview 一致性**：同输入下 `previewCompressionPlan` 与 `compressToCheckpoint` 切分一致（抽公共函数后由实现保证，补一条单测锁 `planCompressionWindow` 纯度——不写库不调模型）。
- **手测**：对话框两阶段流转 / 无可压缩禁用 / 片段与实际边界一致 / 折叠行「现 N（总 M）」/ sheet「#x · 本次 N 条」/ 旧数据回退文案。
