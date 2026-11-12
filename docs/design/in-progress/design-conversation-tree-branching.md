# 设计：会话树形分支（线性主干 + 节点内候选 → 真树）

- 日期：2026-11-12
- 状态：已批准（2026-11-12）· P0 实施中
- 来源：用户需求「改造线性分支为树形分支」；同日难度评估结论 = 高（结构性重构，全链路下标假设 + 下游共享语义）
- 关联文档：
  - `completed/design-regenerate-scope.md` §四（**历史决策：否决自动 fork 分支**，本稿 §四.3 正面回应）
  - `drafts/design-history-tools-and-delta-emit.md` Part B（id 制合并 `mergeGenerationMessages`，本稿 P0 吸收/复用）
  - `completed/design-auto-compaction.md`（检查点边界 + 区域指纹，本稿 §六.4）
  - `completed/design-conversation-import-export.md`、`completed/design-backup-staged-restore.md`、`in-progress/design-db-downgrade-rebuild.md`（兼容纪律）

## 一、现状核实（本仓代码事实，均已核对）

### 1.1 模型：线性主干 + 节点内候选，下游全分支共享

- `Conversation.messageNodes: List<MessageNode>`（`data/model/Conversation.kt:33`）是**线性列表**；
  `MessageNode(messages: List<UIMessage>, selectIndex)`（:368）存同槽位的兄弟候选。
- 激活路径唯一定义：`currentMessages = messageNodes.map { it.messages[safeSelectIndex] }`（:153）。
- **切分支只改 selectIndex，下游历史所有分支共享**：`ChatService.selectMessageNode`
  （`service/ChatService.kt:3196`）仅 `node.copy(selectIndex = …)`。这是一棵「所有分支立即汇合」的退化树，
  与真树（各分支独立子树）的差异是**语义级**的，不是数据结构级的。

### 1.2 全链路建立在线性下标坐标系上

| 环节 | 位置 | 下标依赖 |
|---|---|---|
| 流式回写 | `Conversation.updateCurrentMessages`（Conversation.kt:166） | `messages[i] → messageNodes[i]` 索引对齐；历史事故修复函数 `repairCorruptedCandidates`（:495）即此族 |
| 落库 | `MessageNodeEntity(id, conversation_id, node_index, messages, select_index)`（`data/db/entity/MessageNodeEntity.kt:21`）；`ConversationNodeStore.saveMessageNodes`（`data/repository/ConversationNodeStore.kt:151`） | **绝对 node_index + 公共前缀 diff 增量写**，假定内存列表 ≡ 库中全量线性序 |
| 流式检查点 | `ConversationRepository.checkpointGeneratingNode`（ConversationRepository.kt:515） | 入参 nodeIndex（= 回写列表 lastIndex，注释明确 THIS_ONLY 重生成时 ≠ messageNodes.lastIndex） |
| 重生成 | `data/model/RegeneratePlan.kt` | `regenerateReplyNodeIndex = nodeIndex+1`（:17）、`truncationPlan = drop(idx+1)`（:54）、`restoreAfterTruncation = take(n)+拼尾`（:76） |
| 压缩 | `Conversation.computeAreaFingerprint`（:224）/ `CompressionWindow.applyTo`（:256）/ `windowNodes`（:318） | boundaryIndex 全局下标 + take/drop；检查点实体已存 `boundaryNodeId`（`CompressionCheckpointEntity.kt:43`，id 制地基已有） |
| 渲染 | `ui/pages/chat/ChatList.kt`、`ChatRenderIndex.kt` | 展平/锚点/quoteJump/贴底闩锁全在线性下标空间；LazyColumn key 已用 nodeId（稳定） |
| 分享/导出 | `ChatMessageActionsSheet.onShare`、`data/export/Export.kt`、`ExportImage` | `subList(0, indexOf(node)+1)` 线性前缀；「包含全部版本」= flatMap 全候选 |
| Web | `WebDto.MessageNodeDto(selectIndex)`、`ConversationRoutes`、web-ui `types/helpers.ts` / `export-markdown.ts` / `chat-message.tsx` | ±1 下标运算 |
| 导入 | `ConversationImportRemap.kt`、`ChatboxImporter` | 按新下标重算指纹；线性化导入 |

### 1.3 有利事实（改造地基）

1. **节点 id 已是稳定 Uuid**，`ChatVM.withReplacedNode` 已按 id 整节点替换；FTS（`MessageFtsManager`）
   索引全部候选，天然树安全。
2. **删除消息不清理文件**：`buildConversationAfterMessageDelete`（ChatService.kt:3251）只改结构；
   文件清理在会话级 `deleteConversationFiles`（ConversationRepository.kt:559）⇒ **树内剪枝天然文件安全**。
3. **压缩失效机制已感知分支切换**：区域指纹 = 节点 id + 选中消息 id 哈希（:224），selectIndex 变化即失效。
4. **fork 的文件复制语义可复用但非必需**：`copyWithForkedFileUrl`（ChatService.kt:3282）逐字节拷贝
   （`FilesManager.createChatFilesByContents`）——树内分支共享节点，**零拷贝**。
5. `updateMessageReasoning`（ChatService.kt:3332）等「原地改」操作刻意不动分支结构，树形化后语义不变。

### 1.4 历史决策（必须回应）

`design-regenerate-scope.md` §四（2026-09-15）否决了「scope B 自动 fork 分支」，三项代价：

1. 附件被物理复制（带图会话分支一次 = 磁盘图片翻倍）；
2. `createForkConversation`（`service/ChatServiceModels.kt:130`，已核实）**不搬 compressionSummaries** ⇒ 副本丢压缩状态；
3. 会话列表膨胀 + FTS/备份随之增大。

取舍本质原文：「要『后悔药』用撤销（便宜）；要『两条时间线都留』才需 fork（贵）」。
**树内分支恰好把「贵」变便宜**：共享节点零拷贝、同会话检查点不丢、无新会话。本稿是对该决策 #3 的重访。

## 二、目标 / 非目标

**目标**

1. 从任意回合可派生独立续写分支，切分支 = 切换整条下游路径（真树语义）。
2. 「两条时间线都留」成为廉价原语：scope B 重生成 / 带下游编辑 / 显式分叉不再丢数据。
3. 存量会话无损迁移；旧行为在 P0 阶段逐字节不变（纯重构可独立验收）。

**非目标**

- DAG / 共享子树（分支严格独立，不交叉引用）；
- 多分支同屏对比渲染（渲染永远是单条激活路径）；
- 跨会话分支合并；
- 无用户动作的自动分支创建；
- 树视图拖拽改父（移动端不做图编辑）。

## 三、核心洞察：路径仍线性，树只是存储/分叉结构

激活路径 root→leaf **仍是线性消息序列**。因此：

- **消息级**下标运算（压缩窗口 take/drop、token 估算、分享 subList、渲染展平）作用于
  `currentMessages`（路径）即可存活，改动很小；
- 必须重写的是**节点列表级**操作：落库 diff、流式回写寻址、截断/fork、分支导航。

这决定了改动面收敛在「存储 + 回写 + 分支操作 + 导航」，而非整个聊天管线；也决定了分期可以
「先 id 化（行为不变），后树语义」。

## 四、语义模型

**已定决策（2026-11-12，用户批准）**

| # | 决策 | 说明 |
|---|---|---|
| 1 | **scope B 默认非破坏（分支）** | 截断+撤销改为兄弟分支；确认弹窗由「警告丢失」降级为「告知保留」，撤销机制 P1 移除；「直接截断」降级为剪枝入口 |
| 2 | **有下游编辑 = 可选项** | 基态「下游归旧版本」（分支，新编辑处重新开始）+ 开关「是否在本版本保留后续」：开 = 候选追加、下游共享（现行为）；关 = 纯分支。「保留」= 共享同一份下游，**不是复制两份**。**默认开**（2026-11-12 定：保留后续 = 现行为，最小惊讶） |
| 3 | **旧「分叉为新会话」入口暂时保留** | 不改动；树内分叉作为新增入口的定位仅探讨，是否进 P1 待定（§十二.1） |

### 4.1 两层结构：候选 = 版本，分支 = 时间线

| 层 | 载体 | 语义 | 下游 |
|---|---|---|---|
| **候选**（节点内） | `messages + selectIndex`（现状保留） | 同一回合的多个版本 | **共享**（scope A 的「容忍错配」语义不变） |
| **分支**（节点间） | 兄弟节点（新增 parentId） | 独立时间线 | **各自独立子树** |

区分标准：**改动是否要求后续历史跟着变**。scope A 明说「只修这一段、容忍下游错配」→ 候选；
编辑/scope B 隐含「从新版本继续」→ 分支。

### 4.2 分支创建规则（建议）

| 操作 | 现状 | 树形后 |
|---|---|---|
| scope A 重生成 | 同节点追加候选 | **不变**（候选，下游共享） |
| scope B 重生成 | 截断下游 + snackbar 撤销 | **建兄弟分支**（已定）：旧候选 + 旧下游留在旧兄弟子树，新回复开新子树。非破坏 ⇒ 确认弹窗降级为告知，撤销机制不再需要 |
| 编辑（有下游） | 追加候选、下游共享（错配） | **可选**（已定）：开关「保留后续」开 = 候选追加（现行为原语）；关 = 兄弟分支（新编辑处重新开始，下游归旧版本）。路由见 §四.4 |
| 编辑（叶子） | 追加候选 | 不变 |
| 显式分叉 | 复制前缀成**新会话** | 旧入口**保留不动**（已定）；树内分叉（该回合处建兄弟分支，零附件拷贝）作为新增入口仅探讨，是否进 P1 待定 |
| 发送新消息 | 列表尾部追加 | 追加为**激活叶子的子节点** |
| 删除 | 删候选 / 删整节点 | 删候选 / **剪枝子树**（破坏性，需确认）；不触碰文件（§一.3 事实 2） |

分支点定位沿用 `RegeneratePlan` 的角色规则（用户消息 → 其回复节点，助手消息 → 自身），
只是「截断」换成「兄弟化」。

### 4.3 对历史决策的回应

§一.4 三项代价逐一消解：附件零拷贝（共享节点）、检查点不丢（同会话）、列表不膨胀（无新会话）。
「要两条时间线都留才需 fork（贵）」→ 树内分支使该需求廉价，故重访决策 #3。
**撤销机制**（scope B 的 snackbar）在 P1 后失去存在理由（无数据丢失），随 P1 移除；
「导出后继续」保留为通用能力不变。

### 4.4 编辑选项的实现路由与统一语义

两个选项恰好路由到两层结构的两个既有原语，**不需要新数据模型**：

- 开关开（保留后续）→ 现 `editMessage` 候选追加路径（ChatService.kt:3147），逐字节不变；
- 开关关（纯分支）→ 兄弟分支路径（与 scope B 共用同一原语）。

由此编辑与重生成统一为同一组「后续对话处理」语义：

| 操作 | 共享下游（容忍错配） | 独立时间线（旧版保留） |
|---|---|---|
| 重生成 | scope A（THIS_ONLY） | scope B（INCLUDE_FOLLOWING，新语义 = 分支） |
| 编辑 | 开关开 | 开关关 |

UI：编辑 sheet（已是 ModalBottomSheet，TextField 宿主纪律天然满足）内**条件显示**开关行
（仅 `followingCount > 0` 时，**默认开**）；重生成沿用 `RegenerateScopeDialog`，B 文案改「后续对话将保留在
旧时间线」。用户视角的「上一个候选」在实现上 = 分支点旧兄弟节点，切换器统一呈现为「版本」
（控件形态属 §十二.2 范畴）。

## 五、数据模型与持久化

### 5.1 模型增量

```kotlin
data class MessageNode(
    val id: Uuid,
    val parentId: Uuid? = null,   // 新增：null = 根；兄弟序 = 全局创建序（见 5.2）
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,     // 保留：候选选择
    …
)
data class Conversation(
    …
    val activeLeafNodeId: Uuid? = null,  // 新增：激活路径 = root→此叶子
)
```

- `currentMessages` = 沿 parentId 上溯到根、反转得路径，逐节点取 `currentMessage`。
- **路径选择由 activeLeaf 唯一确定**（路径上每个祖先的「被选中孩子」= 路径上的那个孩子），
  无需每节点存「选中孩子」；候选选择仍走 selectIndex。
- 分支切换落地 = 改 `activeLeafNodeId`。切到某兄弟子树时，落点取该子树**最近更新的叶子**
  （沿后代 max(updateAt/createdAt) 计算，无额外状态；精化见开放问题 7）。
- 序列化：`@Serializable` 结构变更 ⇒ JSON 导出/导入加 schema 版本；新字段缺省值保证
  旧 JSON 可读（parentId=null + activeLeaf=null ⇒ 加载时按线性链补齐，与迁移同逻辑）。

### 5.2 存储与迁移

- `MessageNodeEntity` 增 `parent_id TEXT NULL`；**`node_index` 语义重定义为全局创建序**
  （append-only、不复用），兄弟排序 = 按 node_index。旧行的 node_index 恰好已是线性创建序 ⇒ 无需重排。
- 迁移（DB vNext）：逐会话按 node_index 升序，`parent_id[i] = id[i-1]`，根为 null；
  `activeLeafNodeId = 末节点 id`。**无损、幂等、可单测**（纯函数 `linearToChain`）。
- `saveMessageNodes`：前缀 diff → **id 键 signature map diff**（变更 upsert + 消失 delete）。
  签名本就全量计算（`getNodeDiffRows`，P0 落地名），成本同量级；事务边界不变。
  ⚠️ 这是 P0 最高风险点之一：崩溃中途写的一致性依赖既有事务，需中断注入测试。
- `checkpointGeneratingNode(nodeIndex)` → `checkpointGeneratingNode(nodeId)`：本就按主键
  `@Insert(REPLACE)` upsert，改寻址即可。
- 降级：建议**拒绝降级 + 明示警告**（树→线性必然丢分支，与 `design-db-downgrade-rebuild`
  纪律对齐）；若必须降级，只 flatten 激活路径（有损，开放问题 5）。
- 备份：整库文件备份随迁移自动升级；`LegacyBackupConverter` 补旧→新；新→旧不支持（既有纪律）。

## 六、生成链 id 化（P0 核心，行为不变）

### 6.1 回写合并

实现/复用 `drafts/design-history-tools-and-delta-emit.md` B2-a 的 `mergeGenerationMessages`：
按 message.id 定位所属节点 → 就地替换候选（更新选中候选时保持 selectIndex）；
未知 id → 新节点挂为激活叶子之子。`updateCurrentMessages` 的索引对齐**直接退役**
（结构性重构不保留两套语义；delta-emit 稿的「两套并存」是其增量发射场景的妥协，若该稿先落地则 P0 复用其函数）。
`markGeneratingNodeIncomplete(nodeIndex)`（Conversation.kt:417）同步改 nodeId。

### 6.2 RegeneratePlan id 化

纯函数重写（保持可 JVM 单测）：`nodeIndexOfMessage` → 按 id 找节点；`followingCount` → 子树节点数；
`truncationPlan` → `prunePlan`（P1 用）；`restoreAfterTruncation` → P1 随撤销移除。
`regenerateBaselineRange` 改为路径切片（基线 = 路径上目标节点之前，语义不变）。

### 6.3 发送基线

`handleMessageComplete` 的 `currentMessages.subList(messageRange)`：消息级下标在**路径**上仍成立，
只需保证基线取自激活路径而非节点列表。生成循环只向路径尾部追加 ⇒ 前缀稳定、prompt cache 语义不变。

### 6.4 压缩 × 分支

- `activeCompression()` 增一条判定：**边界节点必须在激活路径上**；多检查点时取
  「在路径上且指纹匹配」的最新一条（替代「末行 = active」）。
- 指纹算法不变，改为沿**路径前缀**计算（路径线性 ⇒ 同一算法）。切到不同前缀的分支 ⇒
  指纹不匹配 ⇒ 自动失效回退全量（现有机制自然延伸，无需新逻辑）。
- `compressionWindow()` 的 boundaryIndex = 边界节点在**路径**中的下标；
  `CompressionWindow.applyTo` / `windowNodes()` 作用于路径，take/drop 存活。
- 压缩落库（`compressConversation`）按路径边界写 boundaryNodeId（已 id 制）+ 路径指纹。

## 七、UI

### 7.1 渲染（改动小）

`ChatList` / `ChatRenderIndex` 改为消费**激活路径节点列表**——展平、切片、key（nodeId 已稳定）、
贴底闩锁逻辑不变。流式判定（index == lastIndex）在路径上仍成立。

### 7.2 分支导航（新）

- **候选版本切换**：现有 `ChatMessageBranchSelector`（±1，x/y）保留，语义 = 版本。
- **时间线切换**：分支点（有兄弟节点的回合）显示分支切换器，切换 = 改 activeLeafNodeId ⇒
  整条下游换新。视觉上与候选切换区分（形态见开放问题 4）。
- 切换后滚动定位到**分支点**（最简正确版）；每叶子独立锚点留 P2+。
- quoteJump：目标消息不在激活路径 ⇒ **先切路径再定位**（现状只滚到节点不切分支，注释明确）。

### 7.3 树视图（P2，新建）

ModalBottomSheet 或整页（遵守 TextField 宿主纪律：Column + verticalScroll，不内联 Lazy item）；
缩进树形列表（图形态见开放问题 6），节点行 = 角色图标 + 文本摘要 + 候选数/子树规模；
点击 = 切换到该叶子路径；长按/菜单 = 剪枝（确认弹窗）、树内分叉。
剪枝入口同时在消息动作 sheet 提供（「删除此分支」）。

## 八、周边对齐（P3）

| 面 | 改动 |
|---|---|
| Web | `MessageNodeDto` 增 parentId、ConversationDto 增 activeLeafNodeId；web-ui **首期仅展示激活路径**（服务端可 flatten），树管理后置 |
| 导出 | 默认激活路径；「包含全部版本」扩展为「包含全部候选与分支」（遍历树）；PNG/HTML/Markdown 同口径 |
| 导入 | JSON schema 版本 + remap 沿路径重算指纹；ChatboxImporter 线性导入 → 链（与迁移同函数） |
| 备份 | 见 §五.2；恢复兼容矩阵进回归 |
| FTS/token/统计 | 已全候选/全节点遍历，树安全，仅验证 |
| 通知/工作区 | `NoticeDelivery`、`WorkspaceNoticeInbox` 的追加语义 → 挂激活叶子之子 |
| 会话级属性 | goal/记忆/注入/文件夹/hidden 等与消息结构正交，**不动** |

## 九、分期

| 期 | 内容 | 验收 | 预估 |
|---|---|---|---|
| **P0** | parentId/activeLeaf 模型 + 迁移 + id 制落库 diff + id 制回写合并 + RegeneratePlan id 化 + 压缩路径过滤。**不引入任何分支创建**（存量 = 退化链，行为逐字节不变） | 既有单测全绿 + 新增迁移/合并/diff 单测 + 真机回归（流式中断/THIS_ONLY/压缩/备份恢复） | 1–1.5 周 |
| **P1** | 分支创建规则（§四.2）+ 编辑选项开关（§四.4）+ 剪枝 + 分支切换 + scope B 弹窗改告知 + 撤销移除；旧 fork 入口不动 | 双时间线共存零丢失、编辑两路由正确、剪枝文件安全、检查点按路径失效 | 1 周 |
| **P2** | 分支切换器打磨 + 树视图 + quoteJump 跨分支 + 滚动锚点 | 真机导航矩阵 | 1–1.5 周 |
| **P3** | Web/导出/导入/备份兼容矩阵 | 跨版本恢复 + 全分支导出 + 导入 remap | 1 周 |

P0 独立有价值（消除索引制隐患，delta-emit 稿的前置），可随时停在 P0。
回归测试约 1 周贯穿各期。总计 **5–7 周**（熟悉本仓单人）。

### 九.1 P0 实施笔记（2026-11-12，实施中敲定的细节）

1. **合并函数是 id + 位置混合制**：纯 id 制「未知 id 一律尾部追加新节点」会破坏 THIS_ONLY
   重生成——新回复必须落成**中间目标节点**的候选（`StreamCheckpointIndexTest` 钉住的语义）。
   故 `mergeGenerationMessages` 三条规则：id 命中 → 就地替换候选；id 未知且位置 i 有节点 →
   候选追加（selectIndex = 末位）；超出尾部 → 新节点。与索引制的唯一分叉是「id 错位到别的节点」
   （索引制 = 跨节点副本损坏，id 制 = 归位修正）。delta-emit 稿 B2-a 落地时须采用本混合制。
2. **迁移 = AutoMigration 可空列 + 惰性回填**（DB 65→66）：不做 SQL 回填。
   `parent_id` 随变更行写入（diff 不比它，避免升级后首次保存整会话重写）；未变更行保持 null，
   加载侧 `asLinearChain()` 按 node_index（创建序）派生内存态链。P1 加载对 null 父链同样回退线性。
3. **`updateCurrentMessages` P0 保留**作等价性测试基准（ConversationMessageUpdatesTest /
   StreamCheckpointIndexTest 不改），生成链 8 处调用点已全部切换 `mergeGenerationMessages`；P1 移除。
4. **`active_leaf_node_id` 进 `updateMetadata` 目标列 UPDATE，COALESCE 保原值**
   （同 `last_message_at`）——满足 ConversationSchemaGuardsTest 列覆盖护栏，轻量元信息保存
   （无消息）传 null 不抹掉叶子指向。P1 翻转为权威时需重审「清空会话」路径的叶子置空。
5. **`node_index` = 全局创建序**（append-only、永不复用）：存活行保持原值，新行 max+1；
   流式检查点 upsert 先查库中原值（`getNodeIndexOf`），新节点取 max+1。P0 创建序 == 路径序。
6. **`pathNodes()` P0 = 列表本身**（零开销间接层）；压缩边界/指纹/窗口与 RegeneratePlan
   已全部经它寻址。树遍历 `pathToLeaf`（含环/断链防御）P0 先单测钉住，P1 接线。
7. 截断计划新增 `anchorNodeId`，撤销还原优先按锚点 id 定位（数量语义回退）；
   P1 撤销随 scope B 分支化移除。

### 九.3 P1 实施笔记（2026-11-12，实施中敲定的细节）

1. **scope B 的树形化 = 收叶子而非删节点**：`regenerateAtMessage` scope B 不再截断——把
   `activeLeafNodeId` 收回到分支点（路径上目标节点的前一个），基线走全量；流式新回复经
   `mergeGenerationMessages` 位置兜底超出路径尾部 → 新建节点挂到分支点之下 = 旧回复的兄弟，
   旧子树原样留在库中。撤销机制（`undoRegenerateTruncation` / `restoreAfterTruncation` /
   `TruncationPlan` / snackbar）整体移除。**根角落**：`replyNodeIndex == 0`（助手消息在根）
   无法表达「空路径」（null 叶 = 线性回退），退化为候选追加（下游共享，数据不丢）。
2. **heal 取代整链派生**：`healMissingParents` 只给 null 父的**非首行**补列表前驱、绝不覆写
   显式父（asLinearChain 会把树掰回线性）。加载与保存边界各调一次；`pathNodes()` 内部也先
   heal（防内存态未补父构造在叶子推进后截断成后缀）。**安全前提（不变量）**：根 = 首个创建
   节点、根无兄弟——分支只在非根节点间发生；否则非首行的 null 父无法与「兄弟根」区分
   （编辑纯分支在根处同样退化为候选追加）。
3. **mergeGenerationMessages 改为路径坐标系**：位置兜底按 `pathNodes()` 算（THIS_ONLY 的
   目标节点、scope B 的尾部判断都基于路径）；新建节点链式挂 `lastLandedNodeId` 并推进
   `activeLeafNodeId`；候选追加不推进叶子。入口先 heal。
4. **发送/notice/编辑的挂父点**：`sendQueuedMessage` 新用户消息挂激活叶子并推进叶子；
   `NoticeDelivery` / `WorkspaceNoticeInbox` 的 notice 节点同样（保持「追加到路径尾」的
   可见语义——不推进叶子的追加在树形下会落到 off-path 而不可见，P0 语义依赖列表序）。
5. **删除 = 剪枝**：`buildConversationAfterMessageDelete` 候选删空时改为
   `pruneSubtree`（节点 + 全部后代），激活叶子在子树内时收回父节点（根被剪则落剩余森林
   最新叶）；UI 在 `followingCount > 0` 时新增确认弹窗（`chat_delete_branch_confirm`）。
   删候选行为不变。
6. **编辑「保留后续」开关**：编辑 UI 实际是**输入栏编辑态**（非设计稿假设的 sheet）——开关
   挂在 ChatInput 编辑横幅（`chat_edit_keep_following`，仅 `followingCount > 0` 时显示，
   每次进入编辑默认开）。关 = `editMessage(keepFollowing=false)` → 兄弟节点承载新版本并
   切叶子过去；根处退化（见 2）。
7. **分支切换器**：`ChatBranchController`（全量节点 + 切换回调）经 CompositionLocal 从
   ChatList 下发，`ChatMessageBranchSelector` 消费——避免给 ChatMessage 全链路加参。
   时间线切换 = `ChatService.switchMessageBranch(nodeId)` → 该子树**最近更新叶子**
   （消息 createdAt max，§五.1 落点规则）。切换后滚动定位留 P2（滚动锚点在 P2 范围）。
8. **压缩×分支**：`activeCompression` 从末行恒取改为「从新到旧找第一条边界在激活路径上且
   指纹匹配」的检查点——切走失效、切回可逆复活（多检查点场景）。
9. **周边小修**：fork 改用**激活路径前缀**（列表序 = 创建序 ≠ 路径序）且复制后清 parentId
   由 heal 补链；ChatList 渲染列表 `visibleNodes` 改 `pathNodes()`（原为 messageNodes，
   树形下会把 off-path 节点渲染出来）；`ConversationEntityMapper` 叶子取会话内存态权威值
   （null 回退末节点）；`checkpointGeneratingNode` 父 = 节点自身 parentId（null 回退列表前驱）。
10. **文案**：scope B 弹窗降级为告知（旧时间线保留、随时切回），B 按钮去警示色；
    `regenerate_truncated` / `regenerate_truncated_undo` 删除。web/API 默认 scope 仍为
    INCLUDE_FOLLOWING（现在=非破坏分支，无破坏性语义变化）。
11. **测试**：全量 3434 绿 / 0 失败 / 2 跳过（P0 基线 3437 − `ConversationMessageUpdatesTest`
    3 例，等价性基准随 `updateCurrentMessages` 退役）。`updateCurrentMessages` /
    `asLinearChain` / `withLinearTreeMeta` 全部删除。新增钉子：heal 语义、pathNodes 树遍历
    与回退、merge 路径坐标系（scope B 兄弟分支/链式挂父/叶子推进/THIS_ONLY 中间节点）、
    pruneSubtree（子树移除/叶子收回）、分支切换使压缩检查点失效且切回复活。
    WorkspaceNoticeInboxTest 夹具随「追加必推进叶子」语义更新。

### 九.4 P1 验证记录（2026-11-12）

- **单测**：`:app:testDebugUnitTest` 3434 通过 / 0 失败 / 2 跳过（P0 基线 3437 −
  ConversationMessageUpdatesTest 3 例随 updateCurrentMessages 退役）。
- **构建**：`:app:assembleDebug` 成功。
- **真机（6c841f95，智谱 glm-4.5-air）**：
  - 流式回写：两条消息顺序发送，回复完整落库；拉库复核 parent_id 链
    （root NULL → reply=父 id）与 `active_leaf_node_id` 指向末节点。
  - **scope B 非破坏分支**：第二轮完成后对第一条用户消息重生成 → 弹窗为新告知文案
    （「旧时间线原样保留，随时可切回 / 其后的 3 条消息将保留在旧时间线」，无警示色）→
    选「重做并开新时间线」→ 新回复生成；拉库：5 节点，新回复 idx4 的 parent =
    分支点（u1 节点）= 旧回复的兄弟；旧子树 idx1→2→3 原样保留；leaf = 新节点。
  - **时间线切换**：消息下「时间线 2/2」控件点「<」→ 内容切回旧时间线（旧回复），
    控件变 1/2；拉库 leaf = 旧子树叶（52a03bc5…），持久化正确。
  - **编辑开关**：编辑第一条用户消息 → 输入栏横幅显示「保留后续」开关（默认开）→
    关闭后提交 → **根节点退化生效**（u1 为根，兄弟根不可表达 ⇒ 候选追加）：
    u1 节点 2 候选、selectIndex=1、叶子不动。非根纯分支路由由单测覆盖（与 scope B
    共用兄弟化原语）。
  - **删除剪枝**：删除第二条用户消息（有 1 条下游）→ 确认弹窗（「将同时删除其后的
    1 条消息（整条分支），且不可恢复」）→ 确认后 5→3 节点（u2+a2 整棵子树移除）、
    leaf 收回父节点（a1）、scope B 兄弟分支不受影响。
  - 旧库（P0 写入的会话）加载渲染正常（heal 兜底生效）。
- **未真机覆盖**：非根编辑纯分支 / THIS_ONLY 中间节点候选（P0 真机已验 + 单测钉住）/
  强杀恢复（checkpointGeneratingNode 仅改父寻址）/ 压缩检查点路径判定（单测覆盖，
  会话未触发压缩）。

### 九.2 P0 验证记录（2026-11-12）

- **单测**：`:app:testDebugUnitTest` 3437 通过 / 0 失败 / 2 跳过（基线）。新增
  `ConversationTreeTest`（链/路径派生、pathToLeaf 环/断链防御、id 制合并四场景 +
  THIS_ONLY 中间节点 + 跨节点 id 归位 + 与索引制等价性扫描）、`NodeDiffPlanTest`
  （append/原地编辑/截断/创建序不复用/parent 派生/签名含 selectIndex）。
  既有等价性基准（ConversationMessageUpdatesTest / StreamCheckpointIndexTest）与
  schema 护栏（ConversationSchemaGuardsTest / SchemaVersionConsistencyTest v66）全绿。
  两处测试随改名同步：MidTurnCompressionWiringTest 的写回字面量、等价性扫描改钉
  内容/选中态/存量 id（新节点 id 为各自随机 Uuid，属比较伪差异）。
- **构建**：`:app:assembleDebug` 成功；Room KSP 导出 66.json，仅两可空纯列。
- **真机（6c841f95）**：安装 + 启动无崩溃；拉库（exec-out 二进制安全 + 连 WAL）确认
  `user_version=66`、`message_node.parent_id` 与 `conversationentity.active_leaf_node_id`
  均已建列 ⇒ 65→66 AutoMigration 在设备生效。
- **未在本机exercised**：该 debug 配置为全新库（0 会话），无存量数据行迁移、无已配置
  提供商，故**流式回写/重生成/编辑的真机交互未跑**；其语义由上述单测覆盖。交互冒烟待
  有提供商与存量会话的环境补做（P0 验收矩阵见 §十一）。
- **交互冒烟（2026-11-12 补，智谱 glm-4.5-air）**：配置提供商（key + 是否启用，经
  「保存」按钮落库——配置页所有编辑仅存本地 internalProvider，不点保存即丢，见
  SettingProviderDetailPage L232/303）→ 选模型 → 发送「TREE-OK regen branch smoke」→
  流式回写正常（思考 1.1s，116 tok/s，完整落库）→ 助手消息重新生成 → 候选切换器
  2/2 → 切回 1/2 内容正确切换。拉库复核：`user_version=66`；两节点 idx=0/1，
  root `parent_id=NULL`、assistant 节点 `parent_id=<user 节点id>`（懒回填生效）；
  assistant 节点 2 候选且 `select_index` 随切换 1→0 持久化；`active_leaf_node_id`
  始终指向叶节点。**P0 交互验收闭环**；§十一矩阵中 THIS_ONLY 中间节点、编辑、
  压缩检查点、强杀恢复留待 P1 一并跑（P0 未改这些路径的运行时行为）。

## 十、风险与对策

| # | 风险 | 对策 |
|---|---|---|
| R-1 | id 制回写与流式中断/工具循环/THIS_ONLY 三方纠缠（历史事故族，`repairCorruptedCandidates` 为证） | P0 单测矩阵 + 真机强杀恢复 + 2s 检查点注入；合并函数纯函数化 |
| R-2 | 落库 diff 重写（前缀→map）崩溃中途一致性 | 事务边界不变 + 中断注入测试；迁移幂等 |
| R-3 | 压缩检查点「存在但不在路径上」误判 | activeCompression 路径过滤 + 指纹双闸；单测覆盖切分支/剪枝/候选切换三路径 |
| R-4 | scope B 语义变化冲击用户习惯（确认+撤销 → 分支） | 已定默认非破坏；文案明示「旧时间线已保留」；剪枝另设确认 |
| R-5 | 备份新→旧恢复 | 拒绝 + 明示（staged-restore 纪律） |
| R-6 | 剪枝的文件安全 | 已核实删除不清理文件；审计消息队列附件清理（`localFileUrls` 引用判定）确保剪枝不触发；会话级清理不变 |
| R-7 | 全量节点加载 + 建树性能 | 单会话节点数百量级可接受；树视图懒展开；signature diff 成本同量级 |
| R-8 | 编辑选「纯分支」后「下游不见了」困惑 | 已定为用户显式选项且**默认开（保留后续）**，纯分支需主动关闭开关；开关文案明示去向 + 分支切换器在编辑点即时可见 + snackbar「旧版本已保留，可切回」 |

## 十一、回归矩阵（真机，设备 6c841f95）

- 流式：正常 / 停止 / 强杀恢复 / 工具循环多轮 / THIS_ONLY 中间节点重生成
- 重生成：A/B × 用户/助手 × 中间/末尾；B 后切回旧时间线完整性
- 编辑：叶子 / 中间历史 / 推理原地改（`updateMessageReasoning` 不受影响）
- 压缩：检查点生效 / 切分支失效 / 剪枝后重压 / 候选切换失效
- 分支：切换 / 剪枝 / 树内分叉 / 旧 fork 入口 / 跨分支引用跳转
- 数据：旧库迁移 / 旧备份→新 / 新备份→旧拒绝 / JSON 导入 remap / Chatbox 导入
- 周边：Web 激活路径展示 / 全分支导出 / FTS 跨分支命中

## 十二、开放问题

**已定（2026-11-12）**：scope B 默认非破坏分支；有下游编辑做成可选；旧 fork 入口暂时保留；编辑开关默认开（保留后续 = 现行为）——见 §四 决策表。

1. **树内分叉显式入口是否进 P1**（决策 3 的探讨部分）：能力已在 P1（scope B/编辑共用原语），差一个显式按钮；建议 P1 先不做，P2 随树视图一起上。
2. **分支切换器形态**：候选/时间线两级控件 vs 统一「版本与时间线」控件？P2 前定。
3. **降级策略**：拒绝（建议）vs 有损 flatten 激活路径？
4. **树视图形态**：缩进列表（建议，移动端友好）vs 横向图？P2 前定。
5. **分支切换落点**：子树最近叶子（建议，无额外状态）vs 每分支记忆上次叶子 vs 分支点？
6. **与 delta-emit 稿的顺序**：P0 自带 id 合并；若该稿先落地则复用 `mergeGenerationMessages`，不重复实现。

## 十三、不做

DAG / 跨分支共享子树；跨会话合并；多分支同屏；无用户动作的自动分支；树视图拖拽改父；
编辑时把下游复制成两份（「保留」= 共享同一份，非复制）；
分支级权限/隔离；借本次重构顺手改压缩算法或 transformer 调度（各自有稿）。