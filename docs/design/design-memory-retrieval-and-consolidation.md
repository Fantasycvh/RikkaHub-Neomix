# 记忆取回与整理补全（检索动作 / 存量历史 / 建议应用）

> 状态（2026-10-16）：**① 检索动作、② 存量历史整理、③ 建议落库与二次确认应用 —— 三项均已落地**
> （各含单测 + 接线守卫 + 证伪）。全套 2153 测试 0 失败。
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
| ② | 存量历史整理 | ✅ **已落地** | 以前只能从「开启那一刻」往后整理，**开启之前的历史任何路径都够不到** |
| ③ | 整理建议落库 + 二次确认应用 | ✅ **已落地** | 建议以前只活在进程内、**且没有任何应用路径**；默认配置下这是**常态**而非边缘 |

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
| `created_at` / `decided_at` / `decision` | 审计（`pending` / `applied` / `rejected` / **`stale`**） |

> **实施修正（2026-10-16）**：原稿设计了一列 `target_updated_at` 作为「目标未被改写」的前置条件，
> **落地时去掉了**。理由是它拿不到且会自相矛盾：`proposalSink` 只收到模型给的参数，
> 提案时目标的 `updated_at` 需要额外一次查询才能拿到；而整理本身就会改 `updated_at`，
> 这个前置条件会立刻把刚整理过的记忆判成「已变」。
> **替代方案已落地**：二次确认页**并排显示「目标当前内容」与「将被写成的内容」**
> （`proposeChangeSummary`）——让用户当那道前置条件。这比存一个会自相矛盾的时间戳更省也更准。
> 应用时仍**校验目标是否存在**（`edit` 对缺失 id 会抛，不校验就是一次崩溃而不是一条提示）。

**应用的安全前提**（设计稿 `design-memory-consolidation.md:460-463` 已点出）：
提案生成后到用户采纳之间，记忆库可能已被编辑/整理过。故应用前**逐条校验**：
- `edit` / `delete`：目标仍存在；不存在 ⇒ **整批**标 `stale`、不应用、并在 UI 说明原因（**不静默跳过**）。
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

### 5.4 落地记录（2026-10-16）

| 文件 | 改动 |
|---|---|
| `data/db/entity/MemoryProposalEntity.kt`（新） | 建议表：`memory_id` / `batch_id` / `action` / `target_id` / `content` / `importance` / `description` / `created_at` / `decision` / `decided_at`。**不挂外键**（助手住 DataStore 无父表可挂；挂 CASCADE 会让审计行随应用动作消失，正好毁掉审计） |
| `data/db/dao/MemoryProposalDAO.kt`（新） | `getPending` / `memoryIdsWithPending` / `insertAll` / `deletePending`（换批）/ `updateDecision` / `updateOne` / `pruneDecided` |
| `data/db/AppDatabase.kt` | 38 → **39**，`AutoMigration(from = 38, to = 39)`（纯新增表）；schema `39.json` 已生成 |
| `data/repository/MemoryProposalRepository.kt`（新） | 落库 / 恢复 / **应用（按批原子 + stale 留痕）** / 拒绝 / 审计上限 100。带窄接口 `MemoryProposalStore` |
| `data/ai/MemoryConsolidationScheduler.kt` | `MemoryConsolidationWorker` 增 5 条建议读写契约；跑完**落库并重读带 id 的版本**；`resumePending` 恢复待决策建议；新增 `refreshProposals` / `applyProposals` / `rejectProposals` |
| `data/ai/MemoryConsolidator.kt` | 5 条新契约方法**只做委托**（存储形态不进 worker 之外） |
| `data/ai/MemoryProposalText.kt`（新） | 确认页文案 `proposeChangeSummary`（纯函数） |
| `di/DataSourceModule.kt` | 注册 `MemoryProposalRepository`；`MemoryConsolidator` 注入之 |
| `data/ai/MemoryConsolidationScheduler.kt` | `MemoryConsolidationProposal` 增 `id` / `batchId` / `createdAt`（默认值 ⇒ 工具层的位置参数调用不变） |
| `ui/pages/assistant/detail/AssistantMemoryPage.kt` + `AssistantDetailVM.kt` | 勾选框 + 「采纳 N 条」/「忽略」+ **二次确认弹层**（逐条列出将发生的改动、删除显示原内容并红字警示） |
| `res/values*/strings.xml` ×6 | 8 条新文案 |

**测试 +19**：`MemoryProposalRepositoryTest`（新，10 条：落库/恢复、换批、空列表不抹待办、
create/edit/delete 真应用、**按批原子**、stale 与 rejected 分开留痕、未知 id 空操作、拒绝不动库、
审计上限、按库隔离）+ `MemoryProposalWiringTest`（新，9 条：5 条确认页纯函数 + 4 条接线守卫）。

**证伪已做（3 处，全部当场变红后恢复）**：
① 去掉按批的 missing 校验 ⇒ `a batch with a missing target is not applied at all` 与
`stale is recorded separately from rejected` 变红；
② 去掉 `worker.saveProposals` ⇒ 接线守卫 `scheduler persists proposals and reloads them with ids` 变红；
③ （架构守卫抓到的真 bug，见下）。

**架构守卫当场抓出一个真 bug（本轮最有价值的一条纪律证据）**：第一版把
`proposeChangeSummary` 写进了 `MemoryConsolidationScheduler.kt`，而它需要 `AssistantMemory`
⇒ 该文件里出现了存储符号 ⇒ **`the scheduler only knows memory ids and the worker interface` 立即变红**。
修法是把函数移进 `MemoryProposalText.kt`（文本层需要目标内容，调度层不需要）。
**这条守卫不是形式主义：它精确地拦下了一次「顺手就查一下」的越界。**

### 5.5 真机装机记录（2026-10-16，设备 `6c841f95` / 用户手动测试）

**装机第一版当场崩**（`Application.onCreate` FATAL）：`No definition found for type … on scope '_root_'`。
根因是 **Koin 没有自动做「具体类 → 接口」绑定**——我按具体类型注册了 `MemoryProposalRepository`，
而 `MemoryConsolidator` 的形参是窄接口 `MemoryProposalStore` ⇒ 解析失败。
这正是**同一个坑的第二例**（下方 worker 绑定处早有同款注释），源码扫描守卫看不出，只有真机发现。
修法：`single<MemoryProposalStore> { … }`；并补守卫
`the proposal store is bound to its interface in DI`（**证伪**：改回 `single {` 立即变红）。

**修复后装机验证（全部通过）**：

| 项 | 证据 |
|---|---|
| APK 一致 | 设备 `base.apk` sha256 前 16 位 = 本地产物 `4aa1a2b2adb316d4` |
| 启动无崩溃 | 进程存活、`FATAL EXCEPTION` 计数 **0** |
| **DB 38→39 迁移** | `memory_proposal` 表存在且 schema 与实体逐列一致；**内存 54 条 + 会话 250 条全部完好** |
| **建议落库** | 表内 3 行：`edit`→**applied**、`delete`→**rejected**、`delete`→**pending**（三种决策各一，`decided_at` 均已落值） |
| **应用真的改了库** | 被 `edit` 的目标 `0d01f604` 的 `updated_at` = 应用时刻 21:01:01，内容确为提案内容 |
| 未越权删除 | 被 `reject` 的 `delete` 目标 `23f0e8f1` **仍在**（54 → 54，无净损失） |

#### ⚠️ 装机暴露的缺陷一：按批原子**只在勾选集合内**成立（代码与契约不符）

落库数据直接证伪了文档：`batch_id = 9fbb953d` 的两条，一条被应用、另一条被拒绝 ⇒
**半成品状态真的发生了**（库里同时留着新旧两份「全部历史对话总纲」）。
根因：`apply()` 先 `filter { it.id in ids }` **再**按 batch 分组 ⇒ 分组只覆盖用户勾选的那部分，
`batch.any { … }` 的前提校验因此够不到未勾选的同批成员。
**Kdoc 与 §5 写的「整批同生共死」是错的实现描述。**

**但重新想过之后，笼统的「整批强制」也不是对的规则**：

- `edit` 单独应用**不丢内容**，只留下一条可回收的重复（本次就是这样，可随时再删）；
- 真正危险的是**只应用 `delete` 而漏掉同批 `edit`** —— 那才是内容丢失。
- 因此正确的不变式应当收窄为：**应用任何 `delete` 时，同 batch 的其它成员必须一起应用；
  否则拒绝这次应用并说明**（`edit` 单独应用放行）。这既堵住丢内容的方向，
  又不强迫用户连带删掉他想保留的东西。

**状态**：缺陷已确认、根因已定位、修正方案已定；**尚未改代码**（等用户拍板取哪一档）。

#### ⚠️ 装机暴露的缺陷二：建议被拒绝后**原地重提**，且水位不推进

表里 `23f0e8f1` 被提了两次 `delete`：batch `9fbb953d`（已被用户拒绝）与 batch `a7a66a6e`（pending）。
即**下一次自动整理又提出了完全相同的改动**。叠加 `memory_consolidation` 进度行仍是 10-11 / 06-23 的旧值
（今晚两轮整理**没有推进水位**）⇒ `proposeOnly` 模式下每一轮自动整理都在**重做同样的工作并重复计费**。
这印证了设计稿 §5.2 那条被砍掉的闸门（「连续产建议而用户不处理 ⇒ 暂停该库」）**确实有用**，
且「已拒绝」应当被下一轮整理**看见**（现在存储里有这个信息，但整理提示里没喂给它）。

#### 另一个重要观察：`read_count` 的 4 次不是聊天模型

装机后 4 条记忆的 `read_count` 变正（原来的度量是 50/50 全 0）。但**时间对不上聊天**：
读取发生在 20:57:55 与 21:01:55，而**最近一次会话更新是当天 12:18:22**（无任何聊天轮次）。
`recordRead` 只有两个调用点（`ChatToolFactory.kt:169` 与 `MemoryConsolidationGenerationAgent.kt:53`）
⇒ 这 4 次读取来自**整理 agent**，不是聊天侧的 `memory_tool`。
**所以「聊天模型不用 `read`」这个结论仍然成立**；新增的事实是
「**整理 agent 会用 `read` 去看全文**」——它反过来证明了把 `onRead` / `onSearch` 接进整理 agent 是对的。

## 6. 诚实边界与未验证项

- **① 的收益尚未实测**：检索是能力，用不用取决于模型；V2 上线后 `read` 的实测使用是 **0 次**
  （§11.3 E5），样本仅 4 个会话。**需要在真机上观察 `search` 的实际调用率**再判断是否值得扩（如
  把 search 结果也接进读取信用、或按 action 分档调参）。
- 度量样本小（4 条查询 / 1 个助手），且三条查询是续话碎片；`cl100k` 与生产 tokenizer 未逐字节比对。
- ② 的「继续排下一批」需要防止与 6h 节流互撞（可能把「省钱」设计改成「持续花钱」）——
  落地时必须保留一个**每轮总步数/总 token 上限**，否则存量整理会变成不可控账单。
- ③ 的 `target_updated_at` 前置条件**已按上文修正去掉**（改为界面并排显示改前/改后）。
  代价要认得：**只有「目标是否存在」这一条机器校验**，「目标是否已被改写」由用户的眼睛承担。
- **③ 的「自动整理连续产建议而用户不处理 ⇒ 暂停该库」这条闸门未实施**：它写出时是设计意图，
  落地时被砍掉，因为「连续 N 次不处理」需要一个跨会话的计数器（又一个表），而本轮的净收益
  已经由「建议终于能落库 + 能应用」兑现。**未做就是未做**，别把它当成已完成的行为。
- **通知仍未带「查看建议」入口**：自动整理产出建议仍只有一条计数 toast（`RouteActivity.kt:293-299`）。
- **未做真机验证**：三项都只有 JVM 单测 + 源码守卫 + 证伪，**没有任何一项装到设备上跑过**。
  尤其是 DB 38→39 的 AutoMigration 与二次确认弹层的实际观感，属 §三.6「验证债」。
- ① 的收益、② 的实际覆盖效果、③ 的用户接受度**均为推断**。
