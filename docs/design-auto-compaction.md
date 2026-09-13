# 自动压缩（harness 式）设计与评估

> 状态：**分析完成，待排期**（2026-09-13）。
> 背景：Yuihub（RikkaHub 二改，`<work>\projects\Yuihub`）在 `e0cb5101` 引入「harness 式自动压缩」，自述对齐 **deepseek-harness compaction-basic**。本文核实两边实现后给出借鉴判定与适配 fork 的设计，并附录三项相关探讨的结论（界面/发送解耦、超长对话懒加载、引入 Rust）。
> 一句话结论：**机制值得借鉴，但 fork 落地的第一步不是搬代码，而是补 `Model.contextLength` 与 token 估算，然后把「发送侧丢老消息」升级为「摘要压缩」——架构上 fork 已经是目标形状。**

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

### 4.2 与 `limitContext` 的分工

```
发送前：
  1) 自动压缩判断（token 超阈值）→ 成功则发送 [检查点] + 尾部原文
  2) 未触发 / 摘要生成失败 / 单条消息本身超长 → 落到 limitContext(滞回 + tool 对齐)
```

`limitContext` 保留现有滞回与 `alignContextStart` 逻辑（1.2），定位为**同步安全网**——防止「压缩还没跑完/失败了」时上下文溢出。

### 4.3 前置条件（Phase 0）

1. **`Model.contextLength` 字段**（ai 模块），含宽松解析（对齐 Yuihub `parseContextLength` 的多字段名：`context_length` / `context_length_limit` / `context_window` / `max_context_length` / `max_input_tokens` / `input_token_limit` / `inputTokenLimit`，值域 (0, 100M]），拉取模型列表时顺带解析；用户可手填覆盖。
2. **token 估算 util**：usage 优先 + 陈旧判定（检查点后无新回复 → 回退本地估算）+ CJK 感知估算、**纳入工具输出**（fork 工具输出上限 128KB/32KB，不纳入会低估）。
3. **参数配置化**：阈值 / 保留比 / 目标 token / 压缩模型（复用 `compressModelId`），落 Settings（顶层平铺项 → **必须补 PreferencesStore 三件套**）。
4. 摘要提示词升级为结构化分节（基于现有 `DEFAULT_COMPRESS_PROMPT` 改造，保留用户可编辑）。

### 4.4 数据模型（需 DB 迁移）

检查点存储有两个候选：

| 方案 | 做法 | 评价 |
|---|---|---|
| i（推荐） | 新增 `compression_checkpoint` 表：`conversationId` / `content` / `messageCount` / `boundaryNodeId` / `createdAt` | 不污染消息轨迹；导出/统计/搜索天然不受影响；失效判定同 `activeCompression` |
| ii | 检查点作为特殊 user 消息节点落库 | 需要靠文本前缀识别（Yuihub 用 `isCompactionCheckpoint` 判 `COMPACTION_CHECKPOINT_PREAMBLE`），会混进消息流与统计 |

选 i。Yuihub 自身也是两代方案并存（老的检查点节点仍在库里，靠前缀识别兼容）。

### 4.5 UI

- **折叠行**：标题 + 摘要单行 + **可点击展开原文**（LazyColumn 内切片切换，零数据层改动）
- 设置项：自动压缩开关 + 阈值 + 保留比 + 压缩模型
- 6 语言文案

## 五、附录 A：超长对话的懒加载评估

**结论：渲染层不需要；数据层真懒加载是独立立项项，且与压缩解耦。**

- **渲染层已经懒了**：`ChatList` 用 `LazyColumn + itemsIndexed`，Compose 只组合/测量可见项，条目数增长不改变滚动开销。
- **真实成本在数据层，且今天已在付**：打开会话全量读 + 逐节点 JSON 反序列化（`loadMessageNodes` 循环拼全量）、内存常驻、搜索全量 `toText()` 扫描。**加压缩不会让这些变差**（加载量与现状相同）。
- **真懒加载的难点是「索引基座漂移」**，多处代码假设位置全局稳定：滚动定位 `requestScrollToItem(messageNodes.lastIndex + 10)`、批量选择 `subList(0, messageNodes.indexOf(node) + 1)`、搜索跳转 `mapIndexed { index, node -> index to node }`、导出 `flatMap { it.messages }`。窗口化需虚拟索引映射或 `reverseLayout` + 锚定——是聊天分页的知名复杂度，工作量大于压缩本身。
- **推荐中间形态**：数据保持全量在内存，仅让 `LazyColumn` 的 items 默认只放边界后窗口 + 一行可展开折叠行。零数据层改动、零索引漂移；省的是渲染/滚动 item 数（内存不省，但内存今天非瓶颈——768 节点警告阈值说明量级可控）。
- **触发立项的观察阈值**：单会话约 3000~5000 节点，或打开会话出现可感知延迟。届时先加 profile 埋点确认瓶颈（怀疑点是「全量重载」策略与 `Conversation` 整对象复制，而非序列化速度）。

## 六、附录 B：引入 Rust 的评估

**结论：为性能引入 Rust 在本项目基本是伪需求。**

### 6.1 本仓原生基建现状（接入成本不高，可复现性是成本）

| 模块 | 方式 |
|---|---|
| `workspace` | 真 `externalNativeBuild` + CMake（proot） |
| `app` | **预编译 `.so` 手工签入 `jniLibs/`**（`libpdf2svg_bridge.so` = TikZ 的 mupdf 桥、`libtex_poc.so`、`libsimple.so`）；源码在 `app/src/main/cpp/` 但 Gradle 不参与编译 |
| `ai` | `externalNativeBuild` 被注释 |

即「Rust 编成 .so 供 Kotlin 调用」这条最后一公里有成熟先例（TikZ 桥）；但 app 的 .so 是**手工编译后签入**的，可复现性差，正经做需把 `cargo-ndk` 纳入 `scripts/build.sh`（并要求构建环境有 Rust 工具链），两个 ABI 各自交叉编译。

### 6.2 对本文三个议题的收益判定

| 议题 | Rust 收益 | 理由 |
|---|---|---|
| token 统计 | **零** | 计算在 SQLite 侧（本身就是 C）与 Compose UI，无落点 |
| 自动压缩 | **≈零** | 主成本是调模型生成摘要（网络 IO）；本地 token 估算占 <1%，且只在触发判断时算一次 |
| 超长对话 | **有一点，但不在关键路径** | 真瓶颈是加载策略（架构）与可能的整对象复制，不是序列化速度（语言）；且**渲染层（Compose）Rust 完全帮不上**，跨 JNI 只会更慢 |

### 6.3 Rust 真正值得的场景

| 场景 | 价值 | 现状 |
|---|---|---|
| 本地向量检索 / 语义记忆（HNSW） | **高**（CPU 密集、有成熟 crate） | 记忆系统现为纯文本匹配，无语义检索 |
| 真 tokenizer（精确上下文预算） | 中（`tiktoken-rs`；但 Kotlin BPE 可替代——价值在「准」不在「快」） | 现为字符数估算 |
| 替换 SQLite FTS 为 tantivy | 负收益（现有 FTS 仅 155 行、够用） | SQLite FTS 已工作 |
| 跨平台共享 core（桌面版） | 取决于「将来」是否真做 | 无计划 |

### 6.4 建议

引入的触发条件 = 做本地语义检索/向量记忆、或需精确 tokenizer、或确定做桌面版共享 core。路径 = 先做**一个边界清晰的小模块**（如向量索引），用 `cargo-ndk` + jniLibs 打包（沿用 pdf2svg_bridge 模式）验证工具链，再考虑扩大；**不要一上来重写数据层**（那是把架构问题误判为性能问题，还赔上可复现性）。

## 七、落地路线

| 阶段 | 内容 | 产出 |
|---|---|---|
| **Phase 0** | `Model.contextLength` + 宽松解析；token 估算 util（usage 优先/陈旧判定/CJK/含工具输出）；参数三件套 | 可判断「距离阈值还有多远」 |
| **Phase 1** | 发送侧压缩（结构化提示词 + 检查点替换 + 轨迹只追加），**先命令式/开关触发**验证摘要质量与续接效果 | 替换 fork 现有手动压缩的覆盖式实现 |
| **Phase 2** | 自动触发（阈值 + 陈旧判定 + 失败静默）+ 与 `limitContext` 分工落地 | 长对话自动保持可用 |
| **Phase 3** | 界面折叠行（**可展开**）+ 设置项 + 6 语言 | 三边界对齐，用户可回看全文 |
| **Phase 4（可选）** | 真懒加载（按 5 节的观察阈值立项） | 打开速度优化 |

## 八、明确不做

- 照搬 `e0cb5101` 整提交（大杂烩，且 `ChatService` 冲突重）
- 界面隐藏被压缩历史（Yuihub 现状）→ 改为**可展开折叠**
- 为性能引入 Rust（见 6）
- 真懒加载先于压缩（见 5）
- 成本估算（token × 价格表）——已在 token 统计方案中排除

## 九、参考

**Yuihub（`<work>\projects\Yuihub`）**
- `app/.../service/ChatService.kt:1194` `autoCompressIfNeeded` / `compressToSummary`
- `app/.../utils/ContextLength.kt` 参数 + `estimateWindowTokens` + `estimateTokenCount`
- `app/.../data/model/Conversation.kt:80` `activeCompression` / `requestWindowMessages` / `windowNodes`
- `app/.../data/ai/prompts/CompressPrompt.kt` 检查点文本 + 结构化提示词
- `app/.../ui/components/message/CompactionRow.kt`（不可展开，我们的改进点）
- 提交 `e0cb5101`（引入）、`6857dd18`（修复自动压缩失效）

**fork**
- `app/.../ui/pages/chat/ChatList.kt:274,347` 界面全量渲染
- `app/.../data/ai/GenerationHandler.kt:539` 发送侧 `limitContext`
- `ai/.../ui/Message.kt:335` `limitContext` 滞回 + `alignContextStart`
- `app/.../service/ChatService.kt:1119` 现有手动压缩（覆盖式）
- `app/.../data/db/fts/MessageFtsManager.kt` 搜索 FTS
- `app/.../ui/pages/chat/ChatSizeChecker.kt` 长对话警告阈值

**关联**：`docs/roadmap.md`（第八节状态日志）、`docs/design-proxy-two-layer.md`（同类「设计完成待排期」）、`docs/peer-apps-benchmark.md`（peer 借鉴清单）。
