# 自动压缩（harness 式）设计与评估

> 状态：**✅ 已落地（Phase 0.5 → Phase 2 全链，2026-09-13/14，真机验证）**——本节正文保留为设计与评估存档；
> 落地提交与验证见 §七 与 `docs/roadmap.md` 第八节。**仍暂缓**：Phase 4 真懒加载（附录 A，观察阈值未到）、Rust 引入（附录 B）。
> 背景：Yuihub（RikkaHub 二改，`<work>\projects\Yuihub`）在 `e0cb5101` 引入「harness 式自动压缩」，自述对齐 **deepseek-harness compaction-basic**。本文核实两边实现后给出借鉴判定与适配 fork 的设计，并附录三项相关探讨的结论（界面/发送解耦、超长对话懒加载、引入 Rust）。
> 一句话结论：**机制值得借鉴，但 fork 落地的第一步不是搬代码，而是补 `Model.contextLength` 与 token 估算，然后把「发送侧丢老消息」升级为「摘要压缩」——架构上 fork 已经是目标形状。**（该路径已按序执行完毕。）
>
> **文档构成**：一~四（压缩机制借鉴 + fork 适配设计）、4.3.1（**Phase 0.5 可见先行项：对话界面「已使用上下文」统计**）、4.6（导出/分享与压缩的关系，2026-09-13 核实）、五（附录 A 懒加载，含 **5.1 深化评估**：四层框架 + 三项修正 + 渲染层 key 核实 + L0-L3 排序）、六（附录 B Rust，含 **6.5 大文件备份修订**）、六.5（附录 C 工作区文件送达：发送文件工具 + 侧滑文件面板子集）、七（落地路线）、八（明确不做）。

## 一、现状核实（fork）

### 1.1 fork 已是「界面全显示 + 发送侧截断」

| 层 | 位置 | 行为 |
|---|---|---|
| 界面 | `ChatList.kt:347` | 渲染 `conversation.messageNodes` **全量**，无任何边界过滤 |
| 发送 | `GenerationHandler.kt:539` | 才应用 `messages.limitContext(assistant.contextMessageLimit)` |

即「界面是历史，发送是上下文」这个解耦 **fork 已具备**。要做的只是把发送侧的「丢弃老消息」升级为「摘要压缩」——改造面比「新架构」小得多，方向也是自然演进。

### 1.2 `limitContext` 语义（值得保留的既有资产）

`ai/.../Message.kt:335`，比「按条数从头砍」精细得多：

- **阶梯式滞回**：越过 `limit` 后回落到 `limit × CONTEXT_KEEP_RATIO(0.5)`，步幅 `stride` 前进——降低截断频率，避免每加一条就砍一条。
- **`alignContextStart`**：截断起点向前回退，**保证不拆散 tool call 与其结果**、不让上下文从半截工具调用开始。
- `limit <= 0 || size <= limit` 时原样返回。

### 1.3 fork 的手动压缩（不可回溯）

`ChatService.compressConversation:1119`：

- `keepRecentMessages` 默认 32（**条数驱动**，非 token 驱动）
- 产出 `newMessageNodes = 摘要节点 + 保留节点`，再 `saveConversation` 整体覆盖
- **原始消息被覆盖，不可回溯**（与 Yuihub 的「轨迹只追加」相反）
- 摘要提示词为自由文本式（`DEFAULT_COMPRESS_PROMPT`，7 条要求），非结构化分节

### 1.4 其他相关现状

| 事实 | 影响 |
|---|---|
| `Model` **无 `contextLength` 字段** | token 驱动需先补字段 |
| `ChatSizeChecker`：768 节点 / 300K 输入 token 警告阈值 | 已有「长对话有问题」的认知与 UI |
| `loadMessageNodes`（`ConversationRepository.kt:441`）分页查询（pageSize 64）但**拼成全量 List** | 分页只为规避 `SQLiteBlobTooBigException`，不是懒加载 |
| 搜索 = SQLite FTS（`MessageFtsManager` 103 行 + `SimpleDictManager` 52 行） | 已够用，无替换需求 |

## 二、Yuihub 实现要点（harness 式）

### 2.1 参数（对齐 deepseek-harness compaction-basic）

定义在 `utils/ContextLength.kt`：

| 参数 | 值 | 含义 |
|---|---|---|
| `AUTO_COMPRESS_THRESHOLD_RATIO` | 0.8 | 占模型窗口 80% 触发自动压缩 |
| `AUTO_COMPRESS_RETAIN_RATIO` | 0.16 | 保留尾部窗口的 16% 作为原文，其余进摘要 |
| `AUTO_COMPRESS_TARGET_TOKENS` | 2000 | 摘要体量目标 |

### 2.2 三个设计精髓

**① token 驱动 + usage 优先/陈旧判定**（`estimateWindowTokens`）

优先用上次真实 `usage.promptTokens`，但有一个巧妙的失效判定：**压缩检查点之后若还没有新的助手回复，那次 usage 是压缩前的旧值（虚高），回退本地估算**。本地估算按 CJK 1.5 字符/token、其余 4 字符/token，并**纳入工具输出**。

**② 检查点替换 + prior checkpoint 合并**（`compressToSummary`）

摘要以 `<compressed-summary>` user 消息形式**替换**早期历史（不是追加）；旧摘要作为 prior checkpoint **参与合并重写为单一摘要**——避免摘要层层累积、无限膨胀。

检查点文本 = 前导语 + 摘要正文（`CompressPrompt.kt`）：

```
This is an automatically generated checkpoint condensing an earlier span of the conversation to free up
context. Treat the captured context as established background and build on it without restating it.
Continue the task directly from the messages that follow, without acknowledging this checkpoint.
<compressed-summary>…</compressed-summary>
```

前导语的三个约束（当背景、不复述、不承认）是必要的——否则模型会对摘要致谢或转述。

**③ 轨迹只追加**（`boundaryNodeId`）

`compressToSummary` **不删除/改写任何消息节点**，只落库「新摘要 + 压缩边界 `boundaryNodeId`」；请求组装时才在 `requestWindowMessages()` 用检查点合成消息作为新段起点，边界前节点留在库里不发送。好处：历史可回溯 + **检查点之后的前缀字节稳定**（对 prompt cache 友好）。

配套：`activeCompression()` 在边界节点不存在时（被 regenerate 截断/分支切换）自动失效回退全量。

### 2.3 摘要提示词（结构化 8 段）

`DEFAULT_COMPRESS_PROMPT` 强制固定 Markdown 结构，缺项写 "(none)" 但不许丢段：Primary Request and Intent / Key Technical Concepts / Files and Code / Errors and Fixes / Pending Jobs / Current Work / Next Step / Critical Context，并要求保留精确路径/命令/错误串/标识符/数值。

### 2.4 一个值得注意的例外

重新生成历史消息时用**原始轨迹**（`ChatService.kt:722`，`messageRange != null` 分支取 `currentMessages`）——即「重生成某条旧消息」时上下文完整。说明作者意识到了「摘要 ≠ 原始事实」的代价。

### 2.5 已知不足（我们的改进点）

- **`CompactionRow` 不可展开**（单行 ellipsis，无点击）→ 用户看不到被压缩的原文
- **界面同样隐藏被压缩部分**（`ChatList.kt:274` 的 `visibleNodes` 用 `.drop(boundaryIndex + 1)`），搜索也走同一过滤窗口 → 「保留全部内容」**仅在数据库意义上成立**；用户看不见、搜不到
- retainRatio 0.16 / 0.8 是为**编码 agent** 调优的值，聊天/角色扮演场景未必合适

## 三、借鉴判定

### 3.1 值得借鉴（按价值排序）

1. **token 驱动的自动触发** —— 最大增量。把 fork 的「按条数丢老消息」换成「到窗口阈值自动摘要」，长对话体验提升直接。
2. **检查点替换 + prior 合并** —— 比「摘要 + 保留」的覆盖式更优雅，摘要不无限累积。
3. **轨迹只追加（boundaryNodeId）** —— 强烈推荐。fork 现在压缩后原消息即丢失，改成只存边界、请求期组装，历史完整性大幅提升，且对 prompt cache 友好。
4. **结构化摘要提示词** —— 固定分节比自由文本更能保住可续接的事实（路径/命令/错误串/决策）。

### 3.2 需适配 / 谨慎

- **参数配置化**：0.8 / 0.16 是编码 agent 值。聊天场景建议保留比从 **0.3~0.4** 起（更保守，避免把有意思的对话内容摘要掉），阈值可留 0.8。全部做成可配置。
- **两层分工**：压缩是**异步**的（要调模型生成摘要），截断是**同步**保底。设计为：压缩负责主动优化，`limitContext` 退化为「压缩不可用/未触发」时的同步兜底（见 4.2）。**不可简单替换**。
- **fork `ChatService` 是重度定制文件**（队列、多 key/fallback、审批串行化、#1812 半截保留都在内），Yuihub 的同名实现**不能 cherry-pick**，须手工适配。
- **摘要成本**：每次压缩一次模型调用（Yuihub 还分块并行）。复用 fork 已有的 `compressModelId` 配置。

### 3.3 不照搬

`e0cb5101` 是**大杂烩提交**（自动压缩 + prompt cache + 助手自进化 + 子智能体 + 统计账本 + 备份 + UI 等 14 类改动），**不可整体 pick**；只提取 2.2 的三点机制与提示词思路。

## 四、适配 fork 的设计

### 4.1 核心：三边界对齐

```
存储层   […… 全量消息节点（只追加，永不删改）……]
界面层   [折叠行：以上 N 条已压缩为摘要 ▸ 可展开看原文] + [边界后消息]
发送层   [检查点摘要消息] + [边界后消息]
                  ↑
            同一个 boundary
```

三者对齐后用户心智简单：**折叠行就是「AI 看到的是摘要，你看到的是全文」**。这也是对 Yuihub 2.5 不足的直接修正（折叠行可展开 → 「用户保留全部内容」在界面层也成立）。

**⚠️ 发送层必须「每步重算」，不能冻结成回合快照**（2026-09-14 修复 `468a25d6`，回归来自 `ce311156`）：

发送层 = `[检查点] + 边界后的 live 消息`，其中「边界后」必须取**本回合当前**的 messages。若把窗口在回合开始算一次、整个工具循环沿用该快照，本回合内新增的 tool call 与工具结果就**进不了上下文** → 模型看不到执行结果，重复调用同一工具（实测 memory_tool create 反复创建、search_web 同一 query 跑两次、回复偶尔重复）。

- 实现：`Conversation.CompressionWindow(checkpointMessage, boundaryIndex)` + `applyTo(messages)`；生成循环每步对 live `messages` 调用（`GenerationHandler` 参数由冻结列表改为该描述）。
- 只向尾部追加 → 以前缀形式保留 turn-start 消息时 boundary 下标语义不变，故「检查点前缀 + live 尾部」可安全重算。
- 检查点两条约束不变：固定窗口最前、不参与 `limitContext` 截断。
- `requestWindowMessages()` 保留为**回合初**一次性组装（供「仅导出 AI 所见上下文」之类的场景），**生成路径不要再用它**。

### 4.2 与 `limitContext` 的分工

```
发送前：
  1) 自动压缩判断（token 超阈值）→ 成功则发送 [检查点] + 尾部原文
  2) 未触发 / 摘要生成失败 / 单条消息本身超长 → 落到 limitContext(滞回 + tool 对齐)
```

`limitContext` 保留现有滞回与 `alignContextStart` 逻辑（1.2），定位为**同步安全网**——防止「压缩还没跑完/失败了」时上下文溢出。

### 4.3 前置条件（Phase 0）——「已使用上下文统计」为可见先行项

Phase 0 的估算底座**可独立交付**：用户先看到「上下文用了多少」（无需压缩），压缩后置。Yuihub 已有同款（`ChatInput.kt` 的 `ContextUsageBarButton`：细进度条 + 百分比 + 点击弹「已用 X / 窗口 Y」+ 80% 警告变色），它的**显示侧与压缩共享同一套估算**——这是先行项的天然依据。

1. **`Model.contextLength` 字段**（ai 模块），含宽松解析（对齐 Yuihub `parseContextLength` 的多字段名：`context_length` / `context_length_limit` / `context_window` / `max_context_length` / `max_input_tokens` / `input_token_limit` / `inputTokenLimit`，值域 (0, 100M]），拉取模型列表时顺带解析；用户可手填覆盖。
2. **token 估算 util**：usage 优先 + 陈旧判定（检查点后无新回复 → 回退本地估算）+ CJK 感知估算、**纳入工具输出**（fork 工具输出上限 128KB/32KB，不纳入会低估）。
3. **参数配置化**：阈值 / 保留比 / 目标 token / 压缩模型（复用 `compressModelId`），落 Settings（顶层平铺项 → **必须补 PreferencesStore 三件套**）。
4. 摘要提示词升级为结构化分节（基于现有 `DEFAULT_COMPRESS_PROMPT` 改造，保留用户可编辑）。

#### 4.3.1 先行项：对话界面「已使用上下文」统计（压缩无关，可立即落地）

**形态**（对齐 Yuihub `ContextUsageBarButton`）：输入框附近一条细进度条 + 百分比数字，达到 80%（复用 `AUTO_COMPRESS_THRESHOLD_RATIO` 阈值，但**仅作警告色，不触发任何压缩动作**）变红，点击弹出具体用量（`已用 X / 窗口 Y`），450ms 动画平滑过渡。

**估算**（与压缩共用 `estimateWindowTokens`）：

- **usage 优先**：取发送窗口最后一条 assistant 消息的 `usage.promptTokens`——它恰是「上一次实际发给模型的完整上下文 token 数」，比任何本地估算都准。
- **fork 无检查点时的简化**：Yuihub 的陈旧判定（检查点后无新回复 → usage 虚高回退本地估算）依赖 `activeCompression()`；**fork 在压缩落地前没有检查点，`usage.promptTokens` 直接可信**——先行版只需 `usage 优先 + 无 usage 时本地估算兜底`，陈旧判定留到 Phase 1 随压缩一起加。
- **本地估算**：CJK 1.5 字符/token、其他 4 字符/token，计入工具输出。
- **窗口值**：`Model.effectiveContextLength()`（contextLength ?: 256K 兜底）。

**fork 适配注意**：

- `Settings.getCurrentChatModel()` 已存在（PreferencesStore.kt:706），模型来源零成本。
- `Model` 加 `contextLength` 是**唯一的 ai 模块改动**（`@Serializable` 可选字段，旧数据零迁移）；UI 侧只新增组件 + 状态传参，不重构 ChatInput 现有结构。
- **与 `limitContext`（条数截断）并存的暂时不一致**：`contextMessageLimit` 只留 N 条时，实际发送远小于模型窗口，进度条会偏低——显示的是「相对模型窗口的占用」而非「相对 limitContext 的占用」，两机制并存期属正常现象；Phase 1 压缩落地后发送窗口由压缩接管，两者统一。
- 设置项（可选）：显示开关 + 模型窗口值手填覆盖；6 语言文案。
- 单测覆盖：`parseContextLengthInput`（`256k`/`1M`/非法）、`estimateTokenCount`（CJK/工具输出）、`estimateWindowTokens`（usage 优先/兜底）。

**与压缩的关系**：本项是 Phase 1 的估算底座，**独立交付不依赖压缩**；压缩落地后同一估算直接复用（触发判断 + UI 显示同源，Yuihub 即如此）。

### 4.4 数据模型（需 DB 迁移）

检查点存储有两个候选：

| 方案 | 做法 | 评价 |
|---|---|---|
| i（推荐） | 新增 `compression_checkpoint` 表：`conversationId` / `content` / `messageCount` / `boundaryNodeId` / `createdAt` | 不污染消息轨迹；导出/统计/搜索天然不受影响；失效判定同 `activeCompression` |
| ii | 检查点作为特殊 user 消息节点落库 | 需要靠文本前缀识别（Yuihub 用 `isCompactionCheckpoint` 判 `COMPACTION_CHECKPOINT_PREAMBLE`），会混进消息流与统计 |

选 i。Yuihub 自身也是两代方案并存（老的检查点节点仍在库里，靠前缀识别兼容）。

> **事后印证（2026-09-13，见 4.6）**：方案 i 标榜的「导出/统计/搜索天然不受影响」已核实为真——
> 导出/分享/Web API 全部读 `message_node` 原文，摘要不进入任何产物；备份整库迁移自洽。
> 这是选表而非选消息节点的直接收益。

### 4.5 UI

- **折叠行**：标题 + 摘要单行 + **可点击展开原文**（LazyColumn 内切片切换，零数据层改动）
- 设置项：自动压缩开关 + 阈值 + 保留比 + 压缩模型
- 6 语言文案

### 4.6 导出 / 分享与压缩的关系（2026-09-13 核实，行为保持现状）

**结论：导出/分享天然拿到「未被压缩的完整原文」，摘要不出现在任何导出产物中；备份则完整保留检查点。**
这是 §4.4 选「独立检查点表」而非「检查点消息节点」的直接收益——读消息的全链路都不需要知道压缩存在。

**按路径核实**（三处 `ChatExportSheet` 调用点：`ChatPage:559` / `ChatDrawer:739` / `ChatList:564`）：

| 路径 | 取数 | 结果 |
|---|---|---|
| 导出 Markdown / HTML / 图片 | "全部版本"开 = `conversation.messageNodes.flatMap { it.messages }`；否则 `selectedMessages` = `currentMessages` | 当前分支**全量原文** |
| 抽屉/列表长按分享 | `currentMessages` | 全量原文 |
| Web API `/conversations` | `messageNodes.map { it.toDto() }`（`WebDto.kt:330`） | 全量原文 |
| 单条消息复制/分享 | 单条 `UIMessage` | 与压缩无关 |
| 备份（DATABASE） | 整个 `rikka_hub.db` | **含 `compression_checkpoint` 表** |

**摘要行不会混进导出**：聊天页「已压缩 N 条历史」折叠行是 `ChatList` LazyColumn 的独立 item（`ChatList.kt:359`），不是 `UIMessage`；导出只遍历消息列表。另：检查点合成消息只在**发送时于内存构造**、不落库（早先泄漏进 `message_node` 的 bug 见 `ce311156`）——若该 bug 未修，因导出直读 `message_node`，是**会**污染导出的；现已无此链路。

**备份/恢复一致性**：`compression_checkpoint` 的 `boundary_node_id` 与区域指纹和 `message_node` 同在**一个被替换的库**内，恢复后 `activeCompression()` 的两项校验（边界节点存在性 + 指纹）自洽，不会出现「摘要还在、原文没了」的悬空。ChatBox / Cherry 导入是**新建**会话，不产生检查点，不受影响。

**三个已知行为后果（接受，不修）**：
1. 导出的对话**看不出被压缩过**，无任何标记。
2. 导出内容 **≠ AI 当时实际收到的上下文**（导出=存储全量；AI 实际=摘要+边界后尾部）。想要「AI 所见上下文」的归档，目前无导出入口。
3. **图片导出比屏幕多显示内容**：图片导出用 `currentMessages`（全量），而屏幕上折叠行默认收起（`windowNodes()`）。

> 若日后需要 (2)，最小做法是给导出面板加「含压缩摘要 / 仅导出 AI 所见上下文」选项，底层直接复用 `requestWindowMessages()` + `activeCompression()`（两个方法已就位，改动面很小）。**当前决策：保持现状**——「导出 = 完整内容」作为归档语义成立，且符合 §4.4 的隔离设计。

## 五、附录 A：超长对话的懒加载评估

**结论：渲染层不需要；数据层真懒加载是独立立项项，且与压缩解耦。**

- **渲染层已经懒了**：`ChatList` 用 `LazyColumn + itemsIndexed`，Compose 只组合/测量可见项，条目数增长不改变滚动开销。
- **真实成本在数据层，且今天已在付**：打开会话全量读 + 逐节点 JSON 反序列化（`loadMessageNodes` 循环拼全量）、内存常驻、搜索全量 `toText()` 扫描。**加压缩不会让这些变差**（加载量与现状相同）。
- **真懒加载的难点是「索引基座漂移」**，多处代码假设位置全局稳定：滚动定位 `requestScrollToItem(messageNodes.lastIndex + 10)`、批量选择 `subList(0, messageNodes.indexOf(node) + 1)`、搜索跳转 `mapIndexed { index, node -> index to node }`、导出 `flatMap { it.messages }`。窗口化需虚拟索引映射或 `reverseLayout` + 锚定——是聊天分页的知名复杂度，工作量大于压缩本身。
- **推荐中间形态**：数据保持全量在内存，仅让 `LazyColumn` 的 items 默认只放边界后窗口 + 一行可展开折叠行。零数据层改动、零索引漂移；省的是渲染/滚动 item 数（内存不省，但内存今天非瓶颈——768 节点警告阈值说明量级可控）。
- **触发立项的观察阈值**：单会话约 3000~5000 节点，或打开会话出现可感知延迟。届时先加 profile 埋点确认瓶颈（怀疑点是「全量重载」策略与 `Conversation` 整对象复制，而非序列化速度）。

### 5.1 深化评估（2026-09-13 讨论定稿）

**核心矛盾**：渲染层已通过 LazyColumn 实现懒加载，但数据层仍全量加载（全量读取、全量 JSON 反序列化、内存常驻、全量搜索扫描）。以下按「破解索引漂移 → 数据层方案 → UI 折中 → 架构演进」四层评估，含三个修正。

**关键事实（修正一：稳定锚点已存在）**：

- `message_node` 表有 **`node_index` 列**（持久化、单调递增序数），且 DAO 查询 `ORDER BY node_index ASC`——内存列表位置 == node_index 顺序。
- 节点主键是 UUID（创建生成、落库持久化）。
- fork 已在用 Paging 3（会话列表 `Pager` + `collectAsLazyPagingItems`），依赖已在栈内。

即**「索引基座不必漂移」——把锚点从「内存列表下标」换成「node_index」即可**。四处漂移点的解法：

| 漂移点 | 现状 | 换锚点后的解法 |
|---|---|---|
| 批量选择 `subList(0, indexOf(node)+1)`（ChatList:386） | 内存下标 | **前缀语义适合序数比较**：`nodeIndex <= target` 即前缀成员。注意 **ID 替代不适用**——「选这条及其之前」是顺序语义，UUID 无法表达「之前」，序数才可以 |
| `requestScrollToItem(lastIndex + 10)`（ChatList:300） | 内存下标 + 过滚 hack | 窗口模型下「底部」= 已加载窗口末尾，索引仍有效；+10 hack 可保留 |
| 搜索跳转 `mapIndexed { index, node -> index to node }`（ChatList:644） | 内存下标当跳转目标 | 改用 `node_index`——DB 顺序即显示顺序，目标天然稳定 |
| 导出 `flatMap { it.messages }`（Export.kt:134） | 全量 | 导出是低频批量操作，**保留一次性全载**，不纳入窗口模型 |

**修正二（reverseLayout 是负资产）**：`reverseLayout = true` 是聊天新项目的标准模式，但 fork 列表现状为自上而下 + haze 毛玻璃 + 底部滚动逻辑，改造 reverseLayout 等于把所有既有索引假设翻转一遍，**迁移本身就制造一次全量索引漂移**。锚定改用 prepend 后 `scrollToItem(新增条数, 保留 firstVisibleItemScrollOffset)` 的经典补偿（Paging 3 内置此机制）。

**修正三（真正的成本 = Conversation 是领域模型，化解钥匙 = 压缩）**：

- `Conversation` 不只是 UI 模型，是**领域模型**——ChatService 流式生成每 chunk 整体替换 `messageNodes` 并写库，transformers/分支/备份都假设其完整。真窗口化 = 把「已加载窗口」与「领域会话」拆成两个模型，这是最大架构成本，不是 SQL 改写。
- **化解**：发送侧已被 `limitContext` 限界（只保留 `contextMessageLimit` 条）——**生成只需要逻辑尾部，不需要全量**。窗口化只需覆盖「显示 + 搜索」，导出走一次性全载。
- **冷热分离混合模型**：冷前缀（检查点之前）分页加载、不可变、按需取；热尾部（边界之后）内存态、可变、流式追加。**冷热边界恰好就是压缩检查点**——压缩决定「AI 看到哪」，窗口化决定「App 加载哪」，二者在同一边界合流。

**渲染层核实（2026-09-13）：稳定 key 已就位**：

| 位置 | 列表 | key |
|---|---|---|
| ChatList:346 | 主消息列表 | `key = { index, item -> item.id }`（节点 UUID） |
| ChatList:698 | 会话内搜索面板 | `key = { index, item -> item.second.id }`（同为 UUID） |
| 特殊项 | 系统提示卡/加载指示/滚底按钮 | 字符串哨兵 key（与 UUID 不冲突） |

含义：窗口化 prepend 历史时，每个可见项的 `rememberSaveable`/`animateItem`/重组 diff 都会正确跟随 key，状态不串位——**方案一（稳定 ID）在渲染层已完全落地**，漂移面仅剩上表 4 处逻辑调用点（L0 范围进一步收窄）。**但 key 不解决滚动锚定**：`scrollToItem` 仍按 index 定位，prepend N 条后不补偿则内容整体下跳 N 项——「不跳动」仍需 L2 显式补偿或 Paging 内置锚定（key 就位使补偿实现安全，无状态丢失次生问题）。

**其余方案判定**：

- **虚拟索引映射**：窗口 = 历史尾部（最新 N 条）时，映射是**算术**（真实 node_index = 窗口首条 + 虚拟下标），非查表；真正难的只有 prepend 平移（归锚定解决）。手写完整 IndexMapper 不必要。
- **游标分页**：用 `node_index` 作游标（`WHERE conversation_id=? AND node_index < ? ORDER BY node_index DESC LIMIT 50`）；**不是 createdAt**（时间在 JSON 内、非列）。DAO 已有 offset 分页（`getNodesOfConversationPaged`，当初为规避 `SQLiteBlobTooBigException`），换游标是小改。
- **延迟反序列化**：**大部分被窗口分页吸收**——节点 JSON 通常 ~1KB 级，成本驱动是条数非单条大小；窗口 50 条时按需 decode 的额外复杂度收益有限。**不做独立逐条 lazy decode**。
- **时间线/区间模型**：索引映射变两级（区间内下标 + 区间序），复杂度高于收益；全局搜索已有 SQLite FTS（`MessageFtsManager`），会话内跳转搜索改 FTS 或窗口内过滤即可，不需要区间模型。
- **Paging 3 化（架构演进）**：栈内已有但**有真冲突**——流式生成每 chunk 产生新 `Conversation`，若消息列表走 `PagingSource`，每次保存触发 invalidate → 窗口重载，流式期间抖动。化解 = 上述冷热分离（冷前缀分页 + 热尾部内存态），而非整体 Paging 化。
- **搜索引擎**：fork 已有 FTS，全局搜索已解耦；会话内搜索改查 FTS 或窗口过滤，无需新引擎。

**2026-09-13 实测校验：L2 暂缓（`21acd31a`）**——按本附录纪律「先加 profile 埋点确认瓶颈」，给 `loadMessageNodes` 加了耗时日志并实测：

| 场景 | 实测 |
|---|---|
| 全库规模 | 215 会话 / 5112 节点 / 26MB JSON |
| **最大单个会话** | **220 节点 / 927KB**（此前误把 5112 当作单会话规模，实为全库总和） |
| 加载耗时（热态） | 188 节点 198ms / 154 节点 293ms（≈1.5ms/节点，与字节量相关） |
| 会话列表路径 | 已用 `emptyList()` 传节点，**不存在全量加载** |

**结论**：距 L2 触发条件（3000~5000 节点）**差一个数量级**，最坏加载约 300ms，未达可感知阈值。 → **L2 暂缓**；**修订触发条件**：单会话接近 1000+ 节点、或实测 `loadMessageNodes` >500ms。

**顺带修掉一处真实浪费**：`get_recent_chats` 工具此前为最多 30 个会话加载全量消息节点，但只用 id/标题/时间 → 改为默认不加载（`loadNodes = false`）。

**落地排序（L0 可先行独立做）**：

| 阶段 | 内容 | 理由 |
|---|---|---|
| **L0（先行）** | 4 处漂移点改 `node_index`/`id` 锚点（选择用序数、搜索跳转用 node_index） | 不需要窗口化就值得做——预先消灭漂移面，风险低 |
| **L1** | UI 折叠中间形态（= 压缩 Phase 3 折叠行，**同一份工作**） | 与压缩合流，零数据层改动 |
| **L2** | DB 游标分页（node_index 游标 + prepend 锚定补偿）+ 导出专用全载 + 会话内搜索改 FTS/窗口 | 真懒加载本体；前置 = L0 已清漂移面 |
| **L3（可选）** | 冷前缀/热尾混合模型或 Paging 3 化 | 等压缩（Phase 1-2）落地后评估，冷热边界以检查点为准 |

## 六、附录 B：引入 Rust 的评估

**结论：为性能引入 Rust 在本项目基本是伪需求。**（含大文件备份场景在内，见 6.5——该场景下相对价值上升，
但绝对收益仍不在关键路径，正解是应用层算法而非语言。）

### 6.1 本仓原生基建现状（接入成本不高，可复现性是成本）

| 模块 | 方式 |
|---|---|
| `workspace` | 真 `externalNativeBuild` + CMake（proot） |
| `app` | **预编译 `.so` 手工签入 `jniLibs/`**（`libpdf2svg_bridge.so` = TikZ 的 mupdf 桥、`libtex_poc.so`、`libsimple.so`）；源码在 `app/src/main/cpp/` 但 Gradle 不参与编译 |
| `ai` | `externalNativeBuild` 被注释 |

即「Rust 编成 .so 供 Kotlin 调用」这条最后一公里有成熟先例（TikZ 桥）；但 app 的 .so 是**手工编译后签入**的，可复现性差，正经做需把 `cargo-ndk` 纳入 `scripts/build.sh`（并要求构建环境有 Rust 工具链），两个 ABI 各自交叉编译。

**开发机 Rust 环境已核实就绪（2026-09-13）**：`~/.rustup/toolchains/` 已装 `stable-x86_64-pc-windows-msvc` + `gnu` 两个 toolchain，`cargo`/`rustc` **1.97.0** 可用；`~/.cargo/` 有 `registry` 与 `git` 缓存（说明此前编译过 Rust 项目，依赖缓存已热）。**但 PATH 未配 shim**（`~/.cargo/bin` 空，可执行真身在 `~/.rustup/toolchains/.../bin/`），用时需把该目录加进 PATH 或用全路径。引入 Android 交叉编译只需 `rustup target add aarch64-linux-android x86_64-linux-android` + `cargo install cargo-ndk`——接入成本比预估更低（工具链/缓存已就位）。

### 6.2 对本文三个议题的收益判定

| 议题 | Rust 收益 | 理由 |
|---|---|---|
| token 统计 | **零** | 计算在 SQLite 侧（本身就是 C）与 Compose UI，无落点 |
| 自动压缩 | **≈零** | 主成本是调模型生成摘要（网络 IO）；本地 token 估算占 <1%，且只在触发判断时算一次 |
| 超长对话 | **有一点，但不在关键路径** | 真瓶颈是加载策略（架构）与可能的整对象复制，不是序列化速度（语言）；且**渲染层（Compose）Rust 完全帮不上**，跨 JNI 只会更慢 |
| 大文件备份（2.8GB，见 6.5） | **相对上升、绝对仍不在关键路径** | DEFLATE 本就是 C（zlib）；支配项是网络上传；内存已流式无堆爆；正解是 STORED/multipart/单遍哈希等与应用层算法，非换语言 |

### 6.3 Rust 真正值得的场景

| 场景 | 价值 | 现状 |
|---|---|---|
| ~~本地向量检索 / 语义记忆（HNSW）~~ | ~~高~~ **已否决（2026-09-14）** | 记忆系统现为「模型自觉调工具写入 + 取前 N 条注入」，无语义检索。**经评估不做**：三方独立一致——①本仓成本（新建原生/模型依赖 + DB 迁移 + 存量回填，属中大型功能；短文本正是嵌入区分度最差处）；②橘瓣云同步与隐私定位冲突；③**Yuihub（RikkaHub fork）上线语义检索后第二天整套删除**（`a8c33b2d`→`fbd15331`）。改走「打分校准 + 字符预算」，无需新依赖（详 `peer-apps-benchmark.md` §四、`roadmap.md` §一） |
| 真 tokenizer（精确上下文预算） | 中（`tiktoken-rs`；但 Kotlin BPE 可替代——价值在「准」不在「快」） | 现为字符数估算 |
| 替换 SQLite FTS 为 tantivy | 负收益（现有 FTS 仅 155 行、够用） | SQLite FTS 已工作 |
| 跨平台共享 core（桌面版） | 取决于「将来」是否真做 | 无计划 |

### 6.4 建议

引入的触发条件 = 需精确 tokenizer、或确定做桌面版共享 core（**原「本地语义检索/向量记忆」触发条件已于 2026-09-14 否决**，见 6.3）。路径 = 先做**一个边界清晰的小模块**（如 tokenizer），用 `cargo-ndk` + jniLibs 打包（沿用 pdf2svg_bridge 模式）验证工具链，再考虑扩大；**不要一上来重写数据层**（那是把架构问题误判为性能问题，还赔上可复现性）。

### 6.5 修订：大文件备份场景（2026-09-13）

**触发**：用户提出「其他设备上导出时可能有 **2.8GB** 量级的数据」（工作区里被下架仓库的克隆等）。问题：这个量级是否让 Rust 的价值上升。

**结论：相对价值上升了一点，但绝对收益仍不在关键路径——2.8GB 暴露的是架构与算法问题，不是「计算慢」，换语言解决不了。**

**实测数字**（PC 上 Python `zlib`，与 Java `Deflater` 同为 zlib，可直接外推；arm64 单核更慢）：

| 数据 | 级别 | 耗时 | 吞吐 | 压缩比 |
|---|---|---|---|---|
| 真实 DB 81.4MB（JSON 型文本） | 1 / 6 / 9 | 0.81 / 3.30 / 5.00 s | 100 / 25 / 16 MB/s | 1.72 / 2.27 / 2.28x |
| 不可压缩 16MB（近似 git pack） | 1 / 6 | 0.29 / 0.66 s | 55 / 24 MB/s | 0.95 / **1.00x** |
| 高可压缩文本 17MB | 1 / 6 | 0.01 / 0.04 s | 2849 / 450 MB/s | 89.6 / 343x |

**2.8GB 的时间去向**：DEFLATE 级别 6 单线程 ≈ 2 分钟（PC）/ 3–5 分钟（手机）；**S3 上传前 `sha256Hex` 全量读一遍 → 再"读一遍上传" = 两次读盘**；网络上传（20 Mbps ≈ 19 分钟、100 Mbps ≈ 4.5 分钟）是**支配项**。

**为何 Rust 移动不了指针**：
- **DEFLATE 本就是原生 C**：Java `Deflater` 调的就是 zlib。换 Rust 只是 zlib→zlib-ng（~1.5–2x），把 CPU 从 2 分钟压到 1 分钟；相对 19 分钟网络不在关键路径，且跨 JNI 多一次拷贝。
- **支配项是网络**：带宽决定耗时，与语言无关。
- **内存不是瓶颈（已核实）**：S3 走 `file.readChannel()`、WebDAV 走 `put(file=…)`，均为**流式上传**；`sha256Hex` 用 8KB 缓冲分块。**没有任何地方把 2.8GB 读进堆** → 「大文件 OOM」假设不成立，也就不存在「Rust 省内存」的收益。
- **串行化不是语言问题**：`BackupArchiveEngine` 全单线程（无 parallel/async）；zip 中央目录要求顺序写，并行化在 Kotlin 也能做（分块压缩后拼接）。

**Rust 唯一可能因大文件变得值得的形态**：把 zip 换成 **zstd 多线程**（level 1–3 可达 200–500 MB/s/核，比率不输 DEFLATE）——这正是 Rust `zstd` + rayon 的舒适区。但这是**格式决策**（备份格式变更、恢复需向后兼容），且 `zstd-jni` 在 Kotlin 侧同样能调，Rust 的增量是"更顺手"而非"只有它能做"。故列为「若将来换格式再评估」，**不构成现在引入 Rust 的理由**。

**正确回应（均与语言无关）**：① 不可压缩数据用 **STORED**（跳过 DEFLATE，直接省掉 2–5 分钟 CPU）；② S3 改 **multipart**（现为单次 PUT，2.8GB 失败即从头重来，是健壮性问题）；③ **单遍哈希**（边传边算/流式签名，省一次全量读）；④ zip **直传网络不落 cacheDir**（省 5.6GB 磁盘往返）；⑤ 空间预检 + 字节级进度；⑥ 边界：工作区若超 4GB 需 ZIP64（Java `ZipOutputStream` 自动处理）。落地清单见 `docs/design-backup-workspace-files.md` §九。

## 六.5、附录 C：工作区文件送达（Yuihub `3c31a625` 评估）

**结论：采纳两块——① `workspace_present_file` 发送文件工具（推荐，成本小）；② 侧滑文件面板取两个低成本子集（不整体搬 ChatDrawer 双面板）。`ChatMessageNerdLine` 精简不跟（见八）。**

### 6.5.1 发送文件工具 `workspace_present_file`（推荐采纳）

**机制**（Yuihub `WorkspaceTools.kt` + `ChatMessagePresentedFiles.kt`）：新增 AI 工具，执行体**不返回文件内容**，只输出 JSON（`path`/`name`/`sizeBytes`/`note`）作为工具结果；聊天界面**复用 `UIMessagePart.Tool`**（按 `toolName == "workspace_present_file" && isExecuted` 过滤）渲染为文件卡片流，点卡片弹「打开 / 导出 / 分享」。

**为何成本小**：

- **零新 part 类型**——fork 的 `WorkspaceToolUIs.kt` 已按 `toolName` 分派工具卡片，**照同模式加一个 `PresentedFilesUI` 即可**，不动 `UIMessage` 模型。
- **「打开」这步 fork 现成**——Yuihub 走 `Screen.WorkspaceFileEditor`，正是 fork 刚吸收的 HTML/SVG 预览页（`b2434dce`）。
- **导出/分享 fork 有同款**——`WorkspaceDetailPage` 已用 cache + FileProvider + `ACTION_SEND`，可复用。
- **审批默认关**（`needsApproval = false`）：只读展示，无副作用，不该打断生成流。

**提示词值得原样借鉴**（Yuihub 原文要点）：文件写完后再调用；卡片由 App 自动渲染，**不要在回复里粘贴文件内容**（避免 AI 把整个 APK/文档贴进聊天的经典错误）；`note` 简述文件用途。

**fork 适配点**：fork **没有 `resolveWorkspacePath`**（Yuihub 有），需按 fork 的 `WorkspaceRepository.exportFile(id, area, path, outputStream)` 签名适配；路径校验复用 fork 既有沙箱与 `rootfsFileSize`。估半天量级。

**价值**：现在 AI 在 workspace 产出 APK/HTML/文档后只能说「路径在哪」，用户要自己去文件列表翻；有了它**产出即送达**，是 agent 工作流体验的直接提升。

### 6.5.2 侧滑文件面板（取两个子集，不整体搬）

Yuihub 把 `ChatDrawer` 重写为「会话 / 文件」双面板（1020 行），含目录导航、缓存秒开、文件名搜索、长按重命名/删除/导出/分享。

**价值**：fork 的工作区文件入口在「扩展 → 工作区 → 详情页」三层导航里，聊着天要拿文件得退出聊天；侧滑面板把文件浏览搬进聊天场景。

**成本**：`ChatDrawer.kt` 在 fork 也是重度定制（文件夹管理、移动到文件夹、新建/重命名对话框），整体搬 = 冲突 + 重写。

**采纳的两个低成本子集**：

1. **抽屉新增「文件」分段**（不重排现有会话面板）：只加一个 tab 分支，复用 fork 现有文件列表组件（`WorkspaceDetailPage` 的文件列表刚加过 SVG 缩略图，形态成熟）。
2. **缓存 + 文件名搜索**（纯 repository 层，与 UI 解耦，可直接借鉴）：
   - `filesCache: MutableMap<String, List<WorkspaceFileEntry>>` + 预加载根目录 + 失效（`invalidateFilesCache`）
   - `searchFilesByName`：BFS 遍历 + limit 80 + 防抖 250ms
   - 这两项可独立先做（`WorkspaceRepository` 侧），不依赖 UI 改造

### 6.5.3 不跟：`ChatMessageNerdLine` 精简

Yuihub 把消息统计行改为图标化（tokens/缓存图标）。fork 的 nerd line **刚定型**（追加式命中率 `(468K cached, 95%)`），形态已定案且刚真机验证，**不跟**（见八）。

### 6.5.4 顺带印证

`3c31a625` 清理了 videogen 模块——**印证 fork「videogen 不吸收」决策正确**（上游未接进 App，Yuihub 也清掉了）。

## 七、落地路线

| 阶段 | 内容 | 产出 | 状态 |
|---|---|---|---|
| **Phase 0** | `Model.contextLength` + 宽松解析；token 估算 util（usage 优先/CJK/含工具输出）；参数三件套 | 估算底座就绪 | ✅ `c9c6d8ea` |
| **Phase 0.5（可见先行）** | **对话界面「已使用上下文」统计**（4.3.1）：细进度条 + 百分比 + 点击弹用量 + 80% 警告色 | 用户即刻可见「上下文还剩多少」 | ✅ `c9c6d8ea`（进度条位/两级 contextLength 于 `67ed90c4`/`50f2d741` 细化） |
| **Phase 1** | 发送侧压缩（结构化提示词 + 检查点替换 + 轨迹只追加） | 替换 fork 现有手动压缩的覆盖式实现 | ✅ `97779543`/`d6ce22c2`/`c14c67b3`/`f58f3888` |
| **Phase 2** | 自动触发（阈值 + 失败静默）+ 与 `limitContext` 分工落地 | 长对话自动保持可用 | ✅ `c89c8b65`（+ 手动入口对齐 `b9e99c91`） |
| **Phase 3** | 界面折叠行（**可展开**）+ 设置项 + 6 语言 | 三边界对齐，用户可回看全文 | ✅ `b782419a` |
| **Phase 4（可选）** | 真懒加载（按 5 节的观察阈值立项） | 打开速度优化 | ⏸ 暂缓（阈值未到） |
| 后续加固 | 泄漏修复 `ce311156`、区域指纹校验 `bbba9ba8`、压缩模型跟随 `fcc45435`、节点增量保存 `85b2014b` | 正确性与性能 | ✅ 真机验证 |

> **Phase 0 + 0.5 可独立交付**：估算 util + 占用显示即构成一个完整可用的功能（用户看到占用），压缩（Phase 1+）后置且复用同一估算。这是本设计「先做可见面」的落地顺序。
> **全部 Phase（0→3）已落地并真机验证**（2026-09-13/14）；仅 Phase 4 真懒加载按观察阈值暂缓。

## 八、明确不做

- 照搬 `e0cb5101` 整提交（大杂烩，且 `ChatService` 冲突重）
- 界面隐藏被压缩历史（Yuihub 现状）→ 改为**可展开折叠**
- 为性能引入 Rust（见 6）
- 真懒加载先于压缩（见 5）
- 成本估算（token × 价格表）——已在 token 统计方案中排除
- **`ChatMessageNerdLine` 精简**（Yuihub 把 tokens/缓存改为图标）——fork 的 nerd line 刚定型（追加式命中率），形态已定，不跟
- **导出侧「仅导出 AI 所见上下文」选项**——导出保持「存储全量原文」语义，摘要不入导出产物（见 4.6；需要时再按其中最小做法加）

## 九、参考

**Yuihub（`<work>\projects\Yuihub`）**
- `app/.../service/ChatService.kt:1194` `autoCompressIfNeeded` / `compressToSummary`
- `app/.../utils/ContextLength.kt` 参数 + `estimateWindowTokens` + `estimateTokenCount`
- `app/.../data/model/Conversation.kt` `activeCompression` / `compressionWindow`（+`CompressionWindow.applyTo`，生成路径用）/ `requestWindowMessages`（回合初一次性）/ `windowNodes`
- `app/.../data/ai/prompts/CompressPrompt.kt` 检查点文本 + 结构化提示词
- `app/.../ui/components/ai/ChatInput.kt:756` `ContextUsageBarButton` + `ContextUsage`（**Phase 0.5 可见先行项原型**）
- `app/.../ui/pages/chat/ChatPage.kt:445` `contextUsage` 组装（`estimateWindowTokens` + `effectiveContextLength`）
- `app/.../ui/components/message/CompactionRow.kt`（不可展开，我们的改进点）
- `app/.../data/ai/tools/WorkspaceTools.kt` `createPresentFileTool`（**附录 C 发送文件工具**）
- `app/.../ui/components/message/ChatMessagePresentedFiles.kt` 文件卡片 + 打开/导出/分享（**附录 C**）
- `app/.../ui/pages/chat/ChatDrawer.kt` / `ChatDrawerVM.kt` 侧滑双面板 + `filesCache` / `searchFilesByName`（**附录 C 两个子集**）
- `app/.../data/repository/WorkspaceRepository.kt` `searchFilesByName`（BFS + limit）
- 提交 `e0cb5101`（自动压缩）、`6857dd18`（修复自动压缩失效）、`3c31a625`（侧滑文件页/发送文件工具）

**fork**
- `app/.../ui/pages/chat/ChatList.kt:274,347` 界面全量渲染
- `app/.../data/ai/GenerationHandler.kt:539` 发送侧 `limitContext`
- `ai/.../ui/Message.kt:335` `limitContext` 滞回 + `alignContextStart`
- `app/.../service/ChatService.kt:1119` 现有手动压缩（覆盖式）
- `app/.../data/db/fts/MessageFtsManager.kt` 搜索 FTS
- `app/.../ui/pages/chat/ChatSizeChecker.kt` 长对话警告阈值

**关联**：`docs/roadmap.md`（第八节状态日志）、`docs/design-proxy-two-layer.md`（同类「设计完成待排期」）、`docs/peer-apps-benchmark.md`（peer 借鉴清单）。

**上游同族议题（2026-09-14 补充）**：上游 issue **#1542《更好的上下文管理，删除提示词注入》**（维护者 re-ovo，仍 open、**pinned**）与本线的「请求前缀形态」是同一层——其动因是「提示词注入和世界书**严重影响提示词缓存**」，方向为「统一提示词注入和角色卡为**上下文插件** + 缓存友好设计」。提示词注入的落点（`InjectionPosition`）会打散请求前缀、打掉 prompt cache，与本仓压缩检查点、轮次尾部注入属同一约束族。上游 PR `#1630`（系统提示 SHA-256 稳定性 + `WorkspaceReminder` 改轮次尾部注入）实测 DeepSeek 命中率 0%→75.2%，是该方向的实证；#1499/#1562/#1558/#1178 反复报注入破坏缓存。**若将来动注入落点或检查点窗口，须一并考虑前缀稳定性**。详见 `upstream-analysis.md`「注意点」第 8 条。

**#1542 难度结论（2026-09-14）**：**窄义（缓存止血）小（天级）**——破坏缓存的根因是**位置**：`BEFORE/AFTER_SYSTEM_PROMPT` 改写 system（下标 0）→ 整段前缀全废；`TOP_OF_CHAT` 废大部分；`BOTTOM_OF_CHAT`/`AT_DEPTH` 在尾部只废后缀。**fork 现存同款隐患**：默认助手 systemPrompt 含 `{{cur_date}}`（`PreferencesStore.kt:823`）+ `PlaceholderTransformer` 每请求遍历全部消息重算 → **每天从 system 位置作废整段缓存**（用户把 `{{battery_level}}` 放 system prompt 则每请求作废）。**全量（统一重写）= 大（架构级，3–6 周）**：issue 仅草图（无设计/无 PR），且须与本仓检查点合成消息/区域指纹/`isSynthetic` 共存（注入在 `windowMessages` 组装**之后**施加，照搬上游不可能）；角色卡统一还需补 `character_book`（本仓目前不读）。**判断：只做窄义止血，全量不排期**——上游 #1542 想要的「缓存友好设计」本仓压缩线已做一版，Yuihub 也已落地（DSH 分段轨迹：模式注入新开独立段而非重写 system、世界书作轮次尾部补充段），我们是参照而非追平。

## 附录 D、压缩参数可配置化 + 压缩配置档（设计讨论中）

**2026-09-15**：用户在评估「为三个压缩参数（触发阈值/保留比例/摘要目标）提供自定义数值，并把固定值转为可选内置方案」。核实发现：**三个值只覆盖自动路径的一半**——手动压缩对话框（`CompressContextDialog`）的保留比例与摘要是**硬编码且每次重置**的。设计稿见 **`design-compress-presets.md`**（含已定三决策：三数值为唯一真相源 / 自定义档可增删改 / 阈值只属自动；及两个待定点）。
