# 记忆取回与整理补全（检索动作 / 存量历史 / 建议应用）

> 状态（2026-10-16）：**① 检索动作已落地**（含单测 + 接线守卫 + 证伪）；**② 存量历史整理**、
> **③ 整理建议落库与二次确认应用** 为设计稿，尚未实施。
>
> 起因：用户问「反思回路、记忆网络与连续思维能力等我们考虑自己的实现？」→ 评估入档
> `peer-apps-benchmark.md` **§11.3** → 用户拍板「先度量判据」→ 度量做完（§11.3 E）发现
> **判据不是杠杆、预算是杠杆** → 转为「按需检索」（本文 ①）。
>
> 相关：`design-memory-discipline.md`（V1 纪律 / V2 渐进披露）、`design-memory-injection-tiering.md`
> （L0–L3 分层与尾部注入）、`design-memory-consolidation.md`（做梦框架）、`design-memory-scoring.md`。

## 1. 范围

| # | 项 | 状态 | 一句话 |
|---|---|---|---|
| ① | `memory_tool action="search"` | ✅ **已落地** | 把注入预算之外、清单层也没列到的记录变成**可按需取回** |
| ② | 存量历史整理 | 设计稿 | 现在只能从「开启那一刻」往后整理，**开启之前的历史任何路径都够不到** |
| ③ | 整理建议落库 + 二次确认应用 | 设计稿 | 建议只活在进程内、**且没有任何应用路径**；默认配置下这是**常态**而非边缘 |

**明确不做**（理由见 §11.3 B/C/D）：重建反思回路、记忆网络本体（图/向量）、DAG 任务执行层、
让 AI 自己写「思维步」、自动 importance pin。

## 2. 为什么是「取回」而不是「换判据」

真机 50 条库（58,569 cl100k token）度量结论（全文见 §11.3 E）：

- 现生产预算 8000 token ⇒ 全文 7 + 清单 20，**23 条完全不出现**（库覆盖 50–54%）；
- **五种清单层判据下覆盖度几乎不变**（25.5–26.5 / 50）⇒ 换判据只改「看见哪 26 条」；
- 预算才是主导项（16000 ⇒ 只剩 1 条不可见），但全库 24K+ token 不可能常付；
- 模型**在 `read` 动作还不存在时（2026-06/07）就自发试过 4 次**取记忆体，4 次全被拒。

⇒ 唯一能把「不可见」变「可取回」的机制是**按需检索**。这也是本节唯一的 P1。

## 3. ① 检索动作（已落地）

### 3.1 形态

`memory_tool` 新增 `action="search"`，参数 `query`（必填）+ `limit`（可选，默认 5、上限 20）。

**判据复用注入侧同一套词法相关性**（`MemoryRelevance.kt` 的 `memoryQueryTokens` + `relevanceOf`）：

- 与 L2 注入的 `relevance` 维度**同一个实现** ⇒ 同一种「覆盖查询词元」语义、同样的 CJK bigram
  与功能字过滤，不新增 tokenizer、不新增依赖、**全 provider 可用**
  （embedding 只有 `OpenAIProvider` 实现，见 §11.3 C）。
- 先按相关性降序、同分按 `sortKey`（写入先后）取前 `limit` 条。

### 3.2 三条设计取舍（都有反例可证伪）

**① 回全文，不回一行主题。** 检索是模型的**主动**动作，回指针等于多花一个往返、并诱使它照主题词猜。
渐进披露要约束的是「自动注入」（无上限），不是「主动取回」（有 `limit` + 字符预算兜底）。

**② 无命中不是错误。** 与 `read` 的 unknown id 不同，检索要回答的是「查过了，没有」这个**事实**；
报错会让模型以为调用方式错了而重试。回执给 `{"matches":0,...}`，并提示「换关键词再试，别急着 create」。

**③ 检索不写读取遥测**（`read_count` / `last_read_at`）。
一次检索返回 N 条，其中可能有 N-1 条模型根本没看；若在这条路径给全部命中加计数，读数就从
「这条真的有用」退化成「这条被搜到过」。**口径的价值在 §11.3 E5 已经兑现过一次**：正是靠
「读数只由 `read` 写」，才查得出「清单层自 V2 上线以来 0 次读取」这个可判定的事实。
代价（诚实记账）：检索命中的记忆享受不到读取信用加成——接受，因为无法判断哪一条真被用了。

### 3.3 装配点（两处，缺一即静默失效）

| 位置 | 接线 | 为什么 |
|---|---|---|
| `ChatToolFactory.kt` | `onSearch = { q, l -> memoryRepository.searchMemories(...) }` | 聊天路径 |
| `MemoryConsolidationGenerationAgent.kt` | 同上 | **整理 agent 尤其需要**：它的记忆库快照同为预算截断（`DEFAULT_LIBRARY_TOKENS = 4000`，真机库 ≈58.6K token）⇒ 看不到快照之外的条目就会**重复 create 已存在的事实**、或漏掉该 retire 的记录 |

审批：`"read", "search" -> false`（与 `read` 同类，只读、不写库）。

### 3.4 成本与边界

- 回执字符预算 `SEARCH_RESULT_CHAR_BUDGET = 6000`（单条可长达 3646 字符，5 条满配会吃掉数千 token）；
  超出时截断并**显式回 `omitted`**，让模型收窄查询而不是以为那就是全部。
- 工具 schema 变了 ⇒ **providers 的 tool 前缀缓存一次性失效**（一次性代价，之后稳定）。
- 不解决「模型不去搜」的问题：检索是**能力**，用不用取决于模型。§11.3 E5 显示 V2 上线后
  `read` 也是 0 次——所以 ① 的收益**尚未实测**（见 §6）。

### 3.5 落地记录（2026-10-16）

| 文件 | 改动 |
|---|---|
| `data/repository/MemoryRepository.kt` | 新增 `searchMemories(assistantId, query, limit)`；常量 `DEFAULT_SEARCH_LIMIT = 5` / `MAX_SEARCH_LIMIT = 20` |
| `data/ai/tools/MemoryTools.kt` | `onSearch` 参数、`search` 枚举值、`query` / `limit` 参数、说明书与示例、`search` 执行分支、`SEARCH_RESULT_CHAR_BUDGET` |
| `data/ai/tools/ChatToolFactory.kt` | 装配 `onSearch`；`"read", "search" -> false` |
| `data/ai/MemoryConsolidationGenerationAgent.kt` | 装配 `onSearch` |

**测试**：`MemoryRepositoryTest`（+6：命中/看 description/按助手隔离/无命中与空查询/相关性排序与 limit/不记遥测）、
`MemoryToolsTest`（+7：schema、回全文、limit 默认与钳制、无命中不报错、缺 query 报错、不碰 read 遥测）、
`MemoryToolApprovalTest`（+1：search 恒不拦）、`MemorySearchWiringTest`（**新文件，4 项接线守卫**）。

**证伪**（按仓库纪律做了扰动）：把 `ChatToolFactory` 的 `onSearch` 换成 `emptyList()` 并把
`"read", "search" -> false` 退回 `"read" -> false` 后，`MemorySearchWiringTest` **2 项当场变红**
（`search reaches the repository from both assembly sites` / `search never requires approval`），
恢复后转绿 ⇒ 守卫不是同义反复。

## 4. ② 存量历史整理（设计稿）

### 4.1 现状（已核实到行）

- 开关是**每条助手**的 `memoryConsolidationSince: Long`（`Assistant.kt:105`，`0` = 关）。
  **唯一的 UI 写入点**是记忆页的 Switch，`onCheckedChange` 里写的是 `System.currentTimeMillis()`
  （`AssistantMemoryPage.kt:456-471`）⇒ **开启只能把基线设成"现在"，永远无法回拨**。
- 基线被**两处**强制：候选 SQL 的 `> MAX(COALESCE(p.consolidated_at,0), :baseline)`
  （`MemoryConsolidationDAO.kt:66`）与逐会话轮次计数（`MemoryConsolidationRepository.kt:83-84`）。
  ⇒ **开启之前的历史，任何路径都够不到。**
- 「整理一下」**并不绕过基线与开关**（只绕过 6h 间隔）：`findPending(manual=true)` 仍在
  `:143` 过滤 `memoryConsolidationEnabled()`、在 `:147` 用同一个 `storeBaseline`。
  ⚠️ **这与设计稿直接矛盾**——`design-memory-consolidation.md:75-77`「`findPending` 不判任何开关」、
  `:120`「开关关掉后仍可用（手动绕过开关）」、`:125`「想整理历史走手动整理（不受基线与开关约束）」。
  **三处都写了同一件没实现的事**，且按钮本身在开关关闭时就不渲染（`AssistantMemoryPage.kt:491`）。
- 候选查询 `ORDER BY ... DESC LIMIT 8`，**没有任何游标/翻页**；唯一进度是每会话一行
  `consolidated_at`。吞吐另有三个天花板：8 会话/轮、6000 token 转写预算、16/32 步。
- **积压不可观测**：UI 只显示「待整理**轮数**」（`pendingTurns`），不显示会话数
  （`Scheduler.kt:122, 415`；私有 `pending` 的 key 数从未暴露）。

### 4.2 要解决的三件（按最小可用切）

1. **让基线可回拨**——把 Switch 升级为「起始点」选择：`仅从此后` / `全部历史` / `指定日期`。
   实现上仍只存一个 `Long`（`0` = 关；`> 0` = 基线），**不新增字段**：
   「全部历史」= 一个**哨兵值**（如 `1L`，即 epoch+1ms，早于一切真实消息）⇒ 零 DB 迁移、零新列。
2. **让手动整理真正绕过基线**（修掉文档与实现的分歧）：`findPending(manual = true)` 用
   `baseline = 0`（而不是 `storeBaseline`），并在 UI 上把「整理历史」与「整理最近」分开命名。
   开关仍作为**自动**路径的闸门（默认关的理由是「会在用户没看着的时候花钱」，那条对**手动**不成立）。
3. **让积压可观测且能推进**：状态里补 `pendingConversations`（`pending` map 的 key 数），
   UI 显示「N 个会话待整理」；自动路径在仍有候选时**继续排下一批**（而非等下一个 6h），
   直到排空或达到一个明确的每轮上限。

**不做**：真正的断点续传游标（现有 per-conversation `consolidated_at` 已足够表达进度，
加游标会引入第二套真相）；重放历史的「导入即整理」（成本不可控，应先让用户看得见才有意义）。

### 4.3 落地记录（2026-10-16）

| 文件 | 改动 |
|---|---|
| `data/ai/MemoryConsolidation.kt` | 新增 `MEMORY_CONSOLIDATION_ALL_HISTORY = 1L` 哨兵与 `Assistant.memoryConsolidationCoversAllHistory()`；`memoryConsolidationBaseline` 的 kdoc 改写为「两条到达历史的路径」 |
| `data/repository/MemoryConsolidationRepository.kt` | `findPending(manual)` 改用 `baseline = 0`，并把 `ignoreSince` 透到 `pendingOfIgnoringInterval` / `baselineOf` ⇒ **手动整理覆盖全部历史，但进度行仍生效** |
| `data/ai/MemoryConsolidationScheduler.kt` | `MemoryConsolidationStatus` 新增 `pendingConversations`，在 `mergePending` 处与 `pendingTurns` 一起写 |
| `ui/pages/assistant/detail/AssistantMemoryPage.kt` | 开关打开后新增「整理范围」两个 `FilterChip`（开启之后 / 全部历史）；状态行改为「N 个会话 / M 轮」 |
| `res/values*/strings.xml` ×6 | `..._consolidation_pending` 改双参 + 新增 4 条 range 文案 |

**契约定案（与旧设计稿相反的一条）**：**开关仍是总闸，手动整理不走开关后门**。
理由：开关关着表示这个助手根本不用整理后的记忆，替它整理只会写出一批没人要的记忆；
`design-memory-consolidation.md` 里「手动绕过开关」的三处说法**已在该文档内就地更正**。

**测试 +6**：`MemoryConsolidationRepositoryTest` 4 条（手动到达开启之前的历史 / 手动仍守进度行 /
哨兵 > 0 且连自动路径也覆盖 / 开关关闭时手动也为空）+ `MemoryConsolidationSchedulerTest` 2 条
（会话数与轮数分别计数、整理后归零）。

**证伪已做**：把 `findPending` 的 `if (manual) 0L else storeBaseline(...)` 与 `ignoreSince = manual`
一起退回改动前，**2 条当场变红**（`manual run reaches history older than the switch-on moment` /
`manual run still honours the per-conversation progress row`），恢复转绿。

**踩坑记录（值得进 handoff）**：韩语文案里用了 ASCII 单引号 `'전체 기록'`，aapt2 报的是
**`Invalid unicode escape sequence`**——完全误导（`'` 在 Android 字符串资源里是引用定界符，
不是转义问题）。改用印刷体引号 `‘ ’` 即通过。**下次直接避开 ASCII `'`，或用 `\'`。**

## 5. ③ 整理建议落库 + 二次确认应用（设计稿）

### 5.1 现状里有一条**真缺陷**（不只是缺功能）

- 建议只活在 `MutableStateFlow` 里（`Scheduler.kt:120-136, 231, 363-375`），**重启即失**，
  且**全仓没有任何应用路径**（`applyProposal` 零命中）。
- 而 `proposeOnly` 的判定是 `participants.any { it.memoryModifyApproval || it.memoryCreateApproval }`
  （`MemoryConsolidator.kt:111`），`memoryModifyApproval` **默认 true**（`Assistant.kt:76`）
  ⇒ **默认配置下整理就是 proposeOnly**。
- 两者相乘的后果：**用户按 UI 提示打开「整理」开关后，自动整理会持续烧 LLM 调用，产出永远无法采纳、
  且重启即丢的建议。** 这不是边缘情况，是默认路径。
- 更糟的是代码自己的 kdoc（`Scheduler.kt:50-53`）写着「**持久化 + 审批入口是 V3-b3 的事——
  在那之前不要接自动触发（否则等于白烧一次模型调用）**」，而自动触发**已经接了**
  （`MemoryConsolidationRuntime.kt:100` 听 `ChatGenerationEnded`）。**代码违反了自己的设计注记。**

### 5.2 落地方案

**数据**：新增 Room 表 `memory_proposal`（DB 38 → 39，AutoMigration）：

| 列 | 用途 |
|---|---|
| `id` | 自增主键 |
| `memory_id` | 属于哪个记忆库（助手） |
| `batch_id` | 同一次整理产出的一批（**成组**——edit+delete 是一次「合并」的两个半边，分开应用会丢内容） |
| `action` | `create` / `edit` / `delete` |
| `target_id` | edit/delete 的目标（可为空） |
| `content` / `importance` / `description` | 提案内容 |
| `target_updated_at` | **前置条件**：提案时目标的 `updated_at`（见下） |
| `created_at` / `decided_at` / `decision` | 审计（`pending` / `applied` / `rejected`） |

**应用的安全前提**（设计稿 `design-memory-consolidation.md:460-463` 已点出、此处落成字段）：
提案生成后到用户采纳之间，记忆库可能已被编辑/整理过。故应用前**逐条校验**：
- `edit` / `delete`：目标仍存在，且 `updated_at` **等于**提案时记录的值；不等 ⇒ 该条标 `stale`、
  不应用、并在 UI 说明原因（**不静默跳过**）。
- 成组应用：同一 `batch_id` 一次应用或一次拒绝（`edit`+`delete` 合并语义不可拆）。
- `create`：走既有 `addMemory`（内容幂等去重已存在）。

**二次确认**（用户明确要求）：应用走**两段**——
1. 用户在建议列表勾选 → 「采纳」按钮进入**确认页**，逐条列出「将要发生的改动」（新建/覆盖/删除，
   删除要显示**原内容**）；
2. 确认页上的「确认应用」才是真正的写库动作（不可逆操作不放在一次点击里）。
   批量应用时确认页必须显示条数与其中删除的条数。

**同时修 §5.1 的缺陷**：建议落库后，「自动整理产出建议」不再是白烧——
但**仍要做一条闸门**：自动路径产出的建议**只在落库成功后**才算数，且通知带上「查看建议」入口
（现在是纯计数 toast，`RouteActivity.kt:293-299`）。若某记忆库连续 N 次自动整理产出的建议
用户都不处理，则暂停该库的自动整理并在状态里说明（避免无人看管时持续烧钱）。

### 5.3 实施顺序建议

先做 **②**（改基线 + 手动绕过 + 可观测，主要是查询与 UI，无 DB 迁移），再做 **③**（要 DB 迁移与
两段确认 UI）。理由：② 让「存量历史」这条用户诉求立刻可用；③ 的价值依赖 ②（存量整理会产出
**大量**建议，先得能看见积压才不会一次性淹没用户）。

## 6. 诚实边界与未验证项

- **① 的收益尚未实测**：检索是能力，用不用取决于模型；V2 上线后 `read` 的实测使用是 **0 次**
  （§11.3 E5），样本仅 4 个会话。**需要在真机上观察 `search` 的实际调用率**再判断是否值得扩（如
  把 search 结果也接进读取信用、或按 action 分档调参）。
- 度量样本小（4 条查询 / 1 个助手），且三条查询是续话碎片；`cl100k` 与生产 tokenizer 未逐字节比对。
- ② 的「继续排下一批」需要防止与 6h 节流互撞（可能把「省钱」设计改成「持续花钱」）——
  落地时必须保留一个**每轮总步数/总 token 上限**，否则存量整理会变成不可控账单。
- ③ 的 `target_updated_at` 前置条件会让**陈旧率偏高**（整理本身会改 `updated_at`）：
  另一个可接受口径是「目标内容仍与提案时一致」而不是时间戳；落地前需拍板。
- 本节所有「收益」均为推断；两个设计稿都**未实施、未测试**。
