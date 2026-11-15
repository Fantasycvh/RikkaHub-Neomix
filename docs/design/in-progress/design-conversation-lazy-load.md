# 会话懒加载与保存契约改造（设计稿）

> 状态：草案（2026-11-15）
> 关联：[design-auto-compaction.md](../completed/design-auto-compaction.md)、
> [design-conversation-tree-branching.md](../completed/design-conversation-tree-branching.md)、
> [design-conversation-import-export.md](../completed/design-conversation-import-export.md)
> 代码现状：[ConversationNodeStore.kt](../../../app/src/main/java/app/androidrik/rikkahub/data/repository/ConversationNodeStore.kt)、
> [ConversationTree.kt](../../../app/src/main/java/app/androidrik/rikkahub/data/model/ConversationTree.kt)、
> [ChatList.kt](../../../app/src/main/java/app/androidrik/rikkahub/ui/pages/chat/ChatList.kt)

## 一、问题与目标

### 1.1 现状

- [loadMessageNodes](../../../app/src/main/java/app/androidrik/rikkahub/data/repository/ConversationNodeStore.kt#L67-L140)
  用 64 行分页读 `message_node`，但**所有页都进内存**：分页只规避 SQLite cursor 窗口，不是懒加载。
  7000 节点会话的 `messages` JSON 总量可达数 MB，打开会话即全量 decode。
- `pathNodes()` 每次调用都遍历 `messageNodes` 建 `parentById` 索引；ChatList、窗口计算、
  树面板、统计、发送组装都会调用，且 Compose 重组会重复触发。
- UI 已用 `windowNodes()` 折叠压缩边界前的内容，但只省渲染，不省加载/内存/保存。
- 保存侧 [computeNodeDiff](../../../app/src/main/java/app/androidrik/rikkahub/data/repository/ConversationNodeStore.kt#L187-L213)
  按「库中行 − 内存行」求删除，**隐含契约：内存 ≡ 全量节点集**。

### 1.2 目标

1. 打开长会话的耗时与内存与「窗口大小」相关，而不是与历史总量线性相关。
2. 不破坏历史可回溯性（删除历史是独立的可选能力，不在本稿范围内）。
3. 压缩检查点作为默认加载边界：模型只需要「摘要 + 窗口」，UI 默认也只加载这一段。
4. 分支树、编辑、重新生成、分支切换、导出、备份、统计行为不退化。

### 1.3 非目标

- 不改变 `message_node` 的 JSON 存储格式。
- 不做物理删除历史（另见后续「压缩后清理」方案）。
- 不引入分页 LazyColumn 之外的虚拟化渲染改造。

## 二、方案对比

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| A. 仅优化缓存 | 缓存 `pathNodes()` 索引，避免重复遍历 | 改动小、零数据风险 | 全量 decode 仍在，大会话冷启动仍慢 | **先做**（阶段 1） |
| B. UI 分页但数据全量 | LazyColumn 分页渲染 | 渲染流畅 | 内存/加载/保存不变 | 已具备，不够 |
| C. 稀疏加载 + 变更集保存 | 摘要全量、正文按需；保存只写显式变更 | 直击瓶颈 | 改持久化契约，面大 | **本稿主方案**（阶段 2） |
| D. 物理删除已压缩前缀 | 删表行 | DB 变小 | 不可回溯、检查点迁移风险 | 阶段 3 可选 |

## 三、核心设计：稀疏加载

### 3.1 两层数据

1. **节点摘要（全量、轻量）**
   `id / parent_id / node_index / select_index / is_favorite / message_bytes`。
   不含 `messages`，7000 行也只有几百 KB 级。
2. **节点正文（按需）**
   `messages` 完整 JSON，按 id 或 `node_index` 区间批量取。

DAO 新增（示意）：

```kotlin
@Query("""
  SELECT id, parent_id, node_index, select_index, LENGTH(messages) AS message_bytes
  FROM message_node WHERE conversation_id = :conversationId ORDER BY node_index
""")
suspend fun getNodeSummaries(conversationId: String): List<MessageNodeSummaryRow>

@Query("SELECT * FROM message_node WHERE conversation_id = :cid AND id IN (:ids)")
suspend fun getNodesByIds(cid: String, ids: List<String>): List<MessageNodeEntity>

@Query("""
  SELECT * FROM message_node WHERE conversation_id = :cid AND node_index < :beforeIndex
  ORDER BY node_index DESC LIMIT :limit
""")
suspend fun getNodesBefore(cid: String, beforeIndex: Int, limit: Int): List<MessageNodeEntity>
```

收藏 id 仍走 `FavoriteDAO` 一次查询；摘要层不 JOIN，避免重复行。

### 3.2 初始加载集

以激活叶片 `activeLeafNodeId` 为锚：

1. 摘要全量加载，构建 `parentById`，求激活路径（仅 id 序列，不 decode 正文）。
2. 计算加载起点：
   - 有**有效**压缩检查点 → 起点 = 边界节点之后（与 `compressionWindow()` 语义一致）；
   - 无检查点 → 从路径尾部向前累计，直到达到**预算**（节点数 300 或正文字节 1 MB，先到者停）。
3. 只 decode 起点之后的正文；检查点消息由 `compressionWindow()` 合成，不需要正文。
4. 更早的节点保留摘要，UI 显示「加载更早的 N 条消息」占位行。

### 3.3 向上加载

- 滚动到占位行或用户点击 → 按 `node_index < 当前最早` 取一页（同样按预算），合并进 loaded 集。
- 跳转（引用、搜索命中、树面板定位）→ 若目标节点未加载，先按 id 加载该节点及其激活路径上下文，
  再滚动。
- 加载失败（坏行/超大 blob）沿用现有「收窄到单行隔离」策略，最坏只丢一行。

### 3.4 分支语义

- 路径计算只用摘要（`parent_id` + `select_index`），不再依赖正文。
- 树面板展示全部分支的元数据；点选未加载分支时按需 hydrate 该路径。
- 编辑/重新生成/删除针对未加载节点时，先按 id hydrate 该节点，再执行现有逻辑。
- `pathNodes()` 改为基于摘要，返回 `List<MessageNodeRef>`；需要正文的调用方显式 `hydrate(ids)`。

## 四、保存契约改造（最高风险项）

### 4.1 新 API

```kotlin
suspend fun saveNodeChanges(
    conversationId: String,
    upserts: List<MessageNode>,
    deletedIds: Set<Uuid>,
)
```

- 只 upsert 显式变更的节点，只删除显式声明的 id。
- **禁止**再以「内存节点集」推断删除；`computeNodeDiff` 降级为「全量模式」内部实现
  （导出/备份/整树重写等场景），并加参数 `fullSet: Boolean` 强制调用方表态。

### 4.2 变更来源审计

需要逐点确认「变更点是否知道自己的 upsert/delete」：

| 位置 | 现状 | 改造 |
|---|---|---|
| 发送追加消息 | 构造新节点 | 直接 upsert 新节点 |
| 编辑/重新生成 | 修改节点 `messages` + `selectIndex` | upsert 该节点 |
| 分支切换 | 改 `activeLeafNodeId`（会话行） | 不写节点 |
| 删除节点/子树 | 从列表移除 | 显式收集被删 id |
| 压缩检查点 | 新增检查点行（独立表） | 不写节点 |
| 导入/恢复 | 全量写入 | 保持全量模式 |

任何无法明确列出变更点的旧路径，改造期回退到 `loadAll + fullSet` 并打日志，逐步收敛。

### 4.3 会话行与节点的一致性

`activeLeafNodeId` 与会话元数据仍在 `conversations` 表；节点保存只写 `message_node`，
沿用现有「会话更新 + 节点保存」事务边界（`ConversationRepository.saveConversation`）。

## 五、压缩检查点的配合

- 有效检查点直接作为默认加载起点，实现「模型上下文 = UI 默认视图」。
- 指纹校验目前需要区域内每个节点的**选中消息 id**；摘要层没有该字段，两个选项：
  1. 指纹改为 `node id + select_index`（分支切换/编辑后 selectIndex 必变，覆盖主要失效路径）；
  2. 新增 `selected_message_id` 列（DB v72），保存时写入，摘要直接带出。
  建议先做 1（零迁移），若发现「原地改内容不改下标」的漏网场景再升级 2。
  **已实施（阶段 5）**：采用 1，指纹值加 `v2:` 前缀区分新算法；旧指纹保留原校验逻辑，
  稀疏时仍按「信任落库值」处理，重新压缩即升级。
- 检查点失效（边界缺失/指纹不符）→ 回退为「尾部预算加载」，不要求全量历史。

## 六、UI 行为

- 打开会话：默认渲染窗口（现有 `windowNodes()` 逻辑不变）+ 顶部「加载更早」入口。
- 展开「压缩检查点」折叠行：只展开检查点摘要文本，不自动加载边界前正文；
  再点「查看原文」才分页加载。
- 树面板：摘要即可绘制；分支内容未加载时显示骨架，点选后加载。
- 会话内搜索/引用跳转：命中未加载节点时按 id 加载并定位。
- 导出/分享/备份：显式 `loadAll`，进度提示沿用备份页。

## 七、缓存与派生计算

- `Conversation` 增加按版本缓存的 `PathIndex`（`activeLeafNodeId + summaries revision`），
  `pathNodes()` / `windowNodes()` 命中缓存，避免每次重组 O(n)。
- `healMissingParents()` 只在摘要加载时执行一次；派生计算不再调用。
- 变更后递增 revision（新增/删除节点、切换叶片），缓存失效。
- 阶段 1 可先只做这一项，独立上线验证收益。

**实施记录（2026-11-15）**：`Conversation.pathIndex`（实例级 `lazy(PUBLICATION)`）已落地，
`pathNodes()` 复用同一份父索引/heal 结果/路径；`copy()` 产生新实例自动失效。同一夹具复测：
加载 655 ms（基线 623 ms，持平，符合预期——本阶段不减少 decode）；滚动掉帧 50.7% /
p90 85 ms（基线 47.6% / 81 ms，在噪声范围内）。收益主要是消除重组期重复 O(n) 索引，
并为阶段 2 的摘要索引提供承载点，帧时间改善要等稀疏加载落地后才会体现。

**阶段 2 基础（2026-11-15）**：`MessageNodeSummaryRow` + `getNodeSummaries / getNodesByIds /
getNodesBefore` 已加入 DAO；`ConversationLoadWindow` 纯函数按「压缩边界 + 尾部节点/字节预算」
计算路径与正文集合，含断链/环回退；`ConversationNodeStore` 提供摘要、批量正文、向上翻页读取。
本阶段**尚未接线**——按 §八 顺序，必须先完成阶段 3 的保存契约改造，才能让 ChatVM 使用稀疏列表。

**阶段 3 保存契约（2026-11-15）**：`computeNodeDiff` 的删除集合改为调用方显式传入
`deletedNodeIds`，不再用「库 − 内存」推导；未出现在内存中的库中行永不删除，冲突 id 忽略。
`ConversationRepository.updateConversation(conversation, deletedNodeIds)` 透传，
`ChatService.saveConversation` 用内存态 previous/current 差集作为唯一删除来源。
`messageNodes` 为空时仍跳过节点写入（保留轻量对象保护）。单测覆盖「稀疏窗口不误删」
与「显式删除生效」；全量单测与装机通过。

**阶段 4 稀疏接线与按需 hydrate（2026-11-15）**：

- **启用条件**：`ChatService.initializeConversation / ensureConversationLoaded` 改走
  `ConversationRepository.getConversationByIdSparse`。只有「最新检查点的边界在摘要路径上、
  且边界节点不是导入的原版检查点消息」才稀疏；无检查点/无边/边界被剪枝/窗口为空一律
  回退全量——宁可慢，不可静默截断发送上下文。导出/备份/Web 仍走全量 `getConversationById`。
- **模型**：`Conversation.nodeSummaries`（`@Transient`，非空即稀疏）+ `summaryPathNodeIds()`；
  `activeCompression()` 的边界存在性改在**摘要路径**上判定；稀疏且边界正文未加载时信任
  落库指纹（摘要层无法校验，阶段 5 摘要化）。稀疏窗口用 `boundaryIndex = -1` 哨兵表示
  「传入消息全在边界之后」；若「加载更早」把边界节点本身 hydrate 回来，则改用真实下标，
  避免边界原文重复发送。本轮新生成/写入的尾部节点不在摘要快照里，按创建序追加到路径末尾。
- **按需 hydrate**：`ChatService.hydrateNodes(ids)`（按 id 补正文并按摘要序合并）、
  `loadEarlierMessages()`（按摘要路径向前补一页）、`hydrateAll()`（全量补齐）。
  `editMessage / selectMessageNode` 目标缺失时自动全量补齐；树面板打开、分支切换、
  树内分叉、剪枝/删除、整会话分叉、压缩（含中途压缩）与「压缩后在新会话继续」入口
  在稀疏时先 `hydrateAll`——这些路径要么需要整棵树，要么需要完整路径前缀。
- **保存侧**：`MessageFtsManager.indexConversation(..., removeMissing)`；稀疏保存传
  `removeMissing = false`，否则未加载前缀的 FTS 行会被当作已删除，永久退出搜索。
- **UI**：聊天列表顶部显式「加载更早的 N 条消息」（`omittedNodeCount`，不自动触底，
  见 §十二.4）；`ChatRenderIndex` 增加 `hasLoadEarlier` 参与 leading 计数，
  LazyColumn 稳定 key 保持可见消息不跳。
- **已知限制（留待阶段 5）**：稀疏时区域指纹无法在摘要层校验（编辑已 hydrate 的
  摘要区域节点不会立刻使检查点失效）；引用跳转到未加载节点仍会放弃（需按消息 id 查询
  → hydrate 的入口）。单测见 `ConversationSparseModeTest`。

**阶段 5 指纹摘要化（2026-11-15）**：

- `NodeSummary` 增加 `selectIndex`（DAO 摘要查询本来就带 `select_index`，只是此前没映射），
  `ConversationNodeStore.loadNodeSummaries` 一并带出。
- 新指纹算法 = `节点 id + selectIndex` 的拼接哈希，带 `v2:` 前缀（`areaFingerprintFor`
  统一产出）。校验时按前缀分派：v2 用摘要路径 + 摘要 selectIndex 计算（已加载节点优先用
  内存 selectIndex，未加载用落库摘要），因此**稀疏会话也能判失效**；旧指纹只在全量时校验，
  稀疏时保持「信任落库值」，重新压缩后自动升级，无需 DB 迁移。
- 覆盖失效路径：切换分支、编辑（候选追加并选中）、重新生成、删除——都改变 selectIndex 或
  节点序列。已知盲区「原地改内容且候选数/index 不变」旧指纹同样覆盖不到，属存量行为。
- 单测新增：v2 指纹在无正文的稀疏会话中有效、摘要 selectIndex 变化后失效、已 hydrate 节点
  选中候选变化后失效；`ConversationSparseModeTest` 与全量单测通过。
- 仍待办（阶段 6 选项）：引用/搜索跳转到未加载节点的「按消息 id → hydrate」入口；
  「原地改内容」若要严格覆盖，需新增 `selected_message_id` 列（DB 迁移）。

**阶段 6 跳转 hydrate + 压缩前缀物理清理（2026-11-15）**：

- **引用/搜索跳转**：`ChatVM.ensureNodeLoaded(nodeId)` → `ChatService.hydrateNodes`；`ChatList`
  的 `pendingQuoteJump` 在目标缺失且仍在摘要快照里时先 `onHydrateNode(targetId)` 再返回，
  **不清空 pending**——正文加载后 `LaunchedEffect` 重跑并完成定位。`ChatPage` 初始化时若
  `nodeId` 未加载也先补拉再 `scrollToMessage`。这补上了阶段 4 的「跳转到未加载节点会放弃」。
- **物理清理（独立开关，默认关）**：`Settings.pruneCompressedPrefix`（DataStore
  `prune_compressed_prefix`），设置页模型 Tab 的自动压缩卡片末尾有独立开关，**开启需二次
  确认**（文案明示不可回溯）。压缩落库后 `ChatService.commit()` 若开关开启则调用
  `ConversationRepository.pruneCompressedPrefix`。
- **删除范围**：`prefixPruneCandidates(summaries, boundary)` 取「边界节点的子树之外」的节点，
  而不是简单 `nodeIndex < 边界`——分支树里更早的旁支同样属于被摘要覆盖的前缀，一并清理；
  边界自身及其子树保留（检查点仍需要边界节点存在）。
- **数据安全守卫**（任一不满足整体放弃，绝不部分删除）：边界必须在摘要路径上；候选节点的
  `nodeIndex` 不得晚于边界（父链异常拒绝）；边界与激活叶子必须在保留集内；被删区域不能含
  收藏节点；重写指纹必须可计算。父链异常的主要来源是存量 `parent_id = null` 行，已在
  `ConversationNodeStore.loadNodeSummaries` 按创建序回填前驱（与 `healMissingParents` 同语义）。
- **指纹重写**：删除后原 v2 指纹（含前缀节点序列）必然失效，用
  `summaryAreaFingerprint(remaining, activeLeaf, boundary)` 按保留子树重算，事务内
  `UPDATE compression_checkpoint`，避免检查点刚落库就自我失效。
- **FTS**：`MessageFtsManager.deleteNodes` 按节点 id 先删 `message_fts_rowid` 映射再删 FTS 行
  （分块 500，规避 SQLite 变量上限），与节点删除同事务。
- **内存态收窄**：`commit()` 在清理成功后同时把 `messageNodes` 过滤到保留集、
  `nodeSummaries` 换成 `remainingSummaries`（仍为稀疏模式），UI 不再持有已删节点。
- **不可回溯**：清理掉的正文无法恢复；关闭开关只停止后续清理，不影响已删数据。导出/备份
  仍走全量（此时已只剩保留子树）。
- 单测新增：`prefix prune keeps boundary subtree only`（线性链 + 旁支，只删边界之外）、
  `pruned checkpoint stays valid with rewritten fingerprint`（重写指纹后稀疏会话仍能算出
  压缩窗口）。`ConversationSparseModeTest` 与全量单测、装机通过。

## 八、兼容与迁移

- 数据库：阶段 2 无 schema 变更（仅新增查询）；若采用 `selected_message_id` 则 v72。
- 导入/导出：`Conversation` 序列化格式不变；导入仍走全量。
- Web/备份：走全量加载，语义不变。
- 旧版本回退：无新列则完全兼容；有 v72 需按现有降级策略处理。

## 九、验收指标

用本稿配套夹具（§十）在真机测量：

1. 7000 节点 / ~4.8 MB 会话：打开到可交互 < 500 ms（现状基线先记录）。
2. `loadMessageNodes` 日志耗时只与窗口预算相关；窗口 300 节点时 < 100 ms。
3. 进入会话后首帧渲染节点数 ≤ 预算 + 占位行。
4. 发送一轮消息只写 1–2 行节点（沿用现有 id-key diff 的常规场景）。
5. 树面板打开 < 200 ms（摘要全量）。
6. 编辑/重新生成/切换分支/导出/备份回归通过。

### 9.1 现状基线（2026-11-15，PJX110 / debug）

方法：每轮 `am force-stop` 后冷启动 → 打开会话抽屉 → 记录打开前 PSS → 点入夹具 →
`logcat` 取 `loadMessageNodes` → 记录打开后 PSS → `gfxinfo reset` 后固定 6 次上滑 → 取掉帧与分位。

| 夹具 | 节点 | 加载耗时 | PSS 增量 | 掉帧率 | p50 | p90 |
|---|---|---|---|---|---|---|
| 线性 7000（4.85 MB） | 7000 | 623 ms | +75.0 MB | 47.6% | 36 ms | 81 ms |
| 分支 7000（4.87 MB） | 7027 | 716 ms | +74.3 MB | 36.9% | 23 ms | 85 ms |
| 压缩 7000（4.85 MB） | 7000 | 651 ms | +23.8 MB | 46.7% | 32 ms | 150 ms |

补充：打开前进程 PSS 约 324–331 MB（debug 构建、含启动与列表缓存）；压缩夹具 PSS 增量较低，
但 `loadMessageNodes` 仍解全部 7000 节点，说明内存差异主要来自渲染窗口与 GC 时机，不是加载量。
掉帧率方差约 ±10 个百分点，纵向对比需同设备、同构建类型、同滑动脚本。

### 9.2 实施后复测（2026-11-15，PJX110 / debug，阶段 6 之后）

方法：与 9.1 相同；每夹具 3 轮取中位数（PSS 增量、加载耗时），掉帧为 1 轮固定 6 次上滑。
新增 `getConversationByIdSparse` 耗时埋点，全量/稀疏两条路径同口径可比；另用 `log -t PerfMark`
打点计算「点按 → DB 载入完成」的墙钟耗时（含触摸分发与主线程排队）。

| 夹具 | 库中节点 | 载入路径 | 载入节点 | `getConversationByIdSparse` | `loadMessageNodes` | 点按→载入完成 | PSS 增量 | 掉帧率 | p50 | p90 | p99 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 线性 7000 | 7000 | 全量 | 7000 | 1062 ms | 604 ms | 1371 ms | +58.9 MB | 23.8% | 21 ms | 48 ms | 121 ms |
| 分支 7000 | 7028 | 稀疏 | 300 | 556 ms | — | 824 ms | +37.4 MB | 22.0% | 21 ms | 48 ms | 125 ms |
| 压缩 7000 | 7000 | 稀疏 | 200 | 534 ms | — | 806 ms | +37.0 MB | 22.7% | 20 ms | 46 ms | 129 ms |

结论与偏差：

- **滚动帧时间**全面改善：三夹具 p90 从 81/85/150 ms 降到 48/48/46 ms，掉帧率 47.6%/36.9%/46.7%
  → 23.8%/22.0%/22.7%（单轮，方差 ±10pp）。线性夹具也在改善范围内——它仍走全量加载，
  收益来自阶段 1 的路径索引缓存与重组期 O(n) 消除，不是加载量。
- **稀疏路径已生效**：压缩夹具载入 200 节点、分支 300 节点（窗口预算），且不再调用
  `loadMessageNodes`；`getConversationByIdSparse` 从基线的全量 623–716 ms 降到 534–556 ms。
- **打开耗时未达 < 500 ms**：稀疏路径 DB 侧 534–556 ms，点按→载入完成约 0.8 s。剩余成本在
  `loadNodeSummaries`（7000 行摘要解码）与点按→`createSession` 的主线程排队，不在正文加载。
- **PSS 偏差**：压缩夹具 PSS 增量 +37.0 MB，高于基线的 +23.8 MB——基线该夹具未走稀疏
  （解 7000 节点），说明增量主要不是节点正文，而是常驻的摘要列表/路径索引与测量时机
  （载入后 7 s 取数，未强制 GC）。分支夹具基线为全量（7027 节点、无检查点），本轮因
  修复压缩 fork 时补写了检查点才走稀疏，两列不可直接相减。
- 未覆盖项：验收指标 3（首帧渲染节点数，未做布局侧计数）、4（发送一轮写入行数）、
  5（树面板打开耗时；注意稀疏会话打开树面板会 `hydrateAll`）、6（编辑/重新生成/导出回归，
  需人工走查）。

## 十、测试数据与验证

### 10.1 夹具生成器

`app/src/test/java/app/androidrik/rikkahub/data/export/ConversationFixtureGeneratorTest.kt`
（只在测试源集，不打包进 APK）：

```bash
./gradlew :app:testDebugUnitTest --tests "*ConversationFixtureGeneratorTest*"
```

输出到 `app/build/test-fixtures/`：

| 文件 | 规模 | 用途 |
|---|---|---|
| `conversation-linear-7000.json` | 7000 节点线性，4.85 MB | 冷启动、`pathNodes()`、保存 diff |
| `conversation-branched-7000.json` | 7000 节点 + 每 250 节点一个兄弟分支，4.87 MB | 树面板、分支切换 |
| `conversation-compressed-7000.json` | 6800 压缩前缀 + 200 尾巴 + 检查点，4.85 MB | 压缩边界懒加载 |

生成器在写入前用 `ConversationSerializer.parse` 回读校验节点数、激活路径长度与检查点有效性。

### 10.2 导入方式

1. `adb push app/build/test-fixtures/*.json /sdcard/Download/neomix-fixtures/`
2. App → 设置 → 备份/恢复 → 导入对话 → 选择文件 → 选择目标助手。
3. 打开会话，观察首屏、向上加载、树面板、编辑与发送。

### 10.3 自动化测试

- 纯函数：摘要路径计算、窗口预算裁剪、`saveNodeChanges` 只删显式 id。
- 回归：导入夹具后 `pathNodes()` 数量与原始一致；压缩窗口与 `compressionWindow()` 一致。
- 真机：按 §九 的指标记录 before/after。

## 十一、实施阶段

| 阶段 | 内容 | 风险 | 可独立上线 |
|---|---|---|---|
| 1 | `pathNodes()` 索引缓存 + `healMissingParents` 只跑一次 | 低 | 是 |
| 2 | 摘要 DAO + 稀疏加载（只读路径） | 中 | 是（先只影响打开） |
| 3 | 保存契约改 `saveNodeChanges`，逐点审计 | **高** | 是（需全量回归） |
| 4 | 编辑/分支/树面板按需 hydrate | 中 | 是 |
| 5 | 指纹摘要化 / 可选 `selected_message_id` | 低 | 是 |
| 6 | （可选）压缩前缀物理清理 | 中高 | 独立开关（已实施，默认关） |

阶段 3 之前不得把 ChatVM 的节点列表改成稀疏集，否则保存会误删未加载行。

## 十二、待决问题

1. 窗口预算按节点数还是字节数？建议双阈值，字节优先（现有实测表明字节量才是驱动因素）。
2. 摘要 `select_index` 是否足以替代指纹中的消息 id？需补一个「原地改内容」的回归用例。
3. 树面板是否需要显示未加载分支的消息预览？建议首版只显示角色/时间/摘要。
4. 是否把「加载更早」做成自动触底加载还是显式按钮？建议显式按钮，避免长会话误触发全量加载。
