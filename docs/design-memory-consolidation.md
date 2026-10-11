# 记忆 V3：做梦（整理）框架

> 来源：上游 PR #2130「记忆改为文件库 + 空闲后整理（做梦）」四块拆分里的**第三块**（评估见
> 私有仓记录·上游分析 2026-10-08 节）。该块**与文件模型无关**：`MemoryConsolidationScheduler`
> 只认 `memoryId` + `MemoryConsolidationWorker` 接口，所以可以先落到本仓的记录模型上。
>
> 现状（2026-10-09）：**框架已落地**——调度策略 + 接口契约 + 保活抽象 + 22 项虚拟时间测试（§8）；
> **V3-b1 已落地**（§8.1）：进度表 + 非 LLM 四条数据面 + 助手级开关字段与 UI（DB 34 → 35）；
> worker 实现（`consolidate`）与接线见 §9——**本批故意一处都没接**（没有触发者，也就没有可跑的整理）。

## 1. 要解决什么

本仓的记忆是**只增不减**的：模型每轮都能 `create`/`edit`，但没有任何东西回头看它写下的东西。

三个后果（V1/V2 都没治，也不该由它们治）：

1. **重复与近似条目越积越多**：V1 的「已记录的换个说法也算已记录」只是**提示词**要求，模型做不到十全十美；
   同一条偏好换个说法再写一次，两条都留在库里各自占预算。
2. **`importance` 没人校准**：分数由写入那一刻的模型决定，事后没有任何机制复核
   （「声明 0.9 却从没人读」和「声明 0.2 却被反复读」都不会有人发现——后者上一批只把它抬到 0.6）。
3. **过期条目要一直占着注入预算**：时效衰减只能让它们排到后面，不能判断「这条已经没用了」。

上游的答案是**空闲后跑一次后台整理（做梦）**：吃「上次整理后新增的轮次」，对记忆库做
去重 / 合并 / 纠类 / 退场，并顺带校准 `importance`。本仓的差异：整理的是**记录**而不是 Markdown
文件，且多一个可校准维度（`importance` + 上一批落地的 `read_count`）。

## 2. 本批范围：只做「什么时候整理」

框架与存储解耦，是本批唯一的设计目标。**做的事**：

- 触发面（§3）与调度策略：空闲计时、轮数阈值、单飞、失败不自动重试、保活句柄移交；
- 接口契约（§4）：`MemoryConsolidationWorker` 只认 `memoryId` + 对话 id + 轮数；
- 保活抽象（§5）：`MemoryDreamKeepAlive`，本批默认 no-op。

**不做的事**（下一批，见 §9）：worker 实现（要 LLM 调用 / 提示词 / 审批语义）、进度表（要 DB 迁移）、
前台服务、UI、以及 Koin 与各处钩子的接线。

**为什么值得先只做框架**：这份调度器全部的价值在**策略**（什么时候跑、跑几次、失败怎么办），
而策略可以完全用假 worker 钉死（21 项虚拟时间测试）；等 worker 就位时，这些策略不用回头重验。
反过来先做 worker 的话，策略与存储会互相纠缠，改一处都要重新证明另一处。

## 3. 触发面

| 触发 | 入口 | 为什么需要它 |
|---|---|---|
| 轮末空闲 `idleDelay`（默认 **2 分钟**） | `onTurnFinished` | 连续对话只在停下来后整理一次；期间又有新轮次就**重新计时**（否则每轮都跑一次做梦） |
| 攒够 `maxPendingTurns`（默认 **6**） | `onTurnFinished` | 一直聊下去就永远不会「空闲」——这批用户永远不会被整理 |
| 切到后台 | `onAppHidden` | 后台的空闲计时**不一定走得完**（进程可能被冻结/回收），有待整理的就现在跑 |
| 离开对话 | `onConversationLeft` | 短时间内不会再有新消息，不必等空闲 |
| 启动补漏 | `resumePending` | 补上进程被杀前没来得及整理的对话 |
| 用户手动 | `organize(memoryId)` | `fullReview = true`：先处理待整理对话，再把**整个记忆库**清理一遍 |

整理以**记忆库**为单位（一次覆盖该库下所有待整理对话），同一个库同一时间只跑一次。

## 4. 接口契约（框架不许认识存储）

```kotlin
interface MemoryConsolidationWorker {
    suspend fun pendingOf(conversation: Conversation): PendingMemoryConversation?
    suspend fun memoryIdsWithUpdates(): List<String>
    suspend fun findPending(memoryId: String): List<PendingMemoryConversation>
    suspend fun markConsolidated(conversation: Conversation)
    suspend fun consolidate(memoryId: String, conversationIds: Set<Uuid>, fullReview: Boolean): MemoryConsolidationRun?
}
```

四条不许破的契约（每条都有对应的下游后果）：

1. **`memoryId` 是不透明标识**。本仓当前语义 = `assistantId`（含 `__global__`），但调度器不解释它、
   也不拼任何查询。V4 换 Markdown 文件库时这一层一个字不动。
2. **自动整理的开关全在这两个入口**：`pendingOf`（轮末）与 `memoryIdsWithUpdates`（启动补漏）——
   隐身对话（`Conversation.memoryDisabled`）、停用的助手、没开记忆的助手、**助手级「做梦」开关为关**
   （§7）都必须在这里返回 `null`。「不整理」的语义在这层落地（V2 §4.4 承诺过的那件事），
   调度器不重复判断、也不知道这些字段存在。
   **`findPending` 不判任何开关**：它回答的是「库里还有没有没整理的轮次」，而手动整理（`organize`）
   走的正是这条路径——用户显式点击要放行。把开关也塞进 `findPending`，「关了还能不能手动整理」
   这个决定就被藏进实现细节了（而我们明确要求：**能**）。
3. **`findPending` 返回「一次整理能处理的数量」**。**轮次切分与分批归 worker**：只有它知道一次能塞进多少
   上下文、哪些轮次该合成一批；没处理完的部分下次再被 `findPending` 捞出来即可（调度器每次整理后都会
   重新扫一遍库，见 §5 的 merge 规则）。
4. **`consolidate` 必须幂等**：同 `conversationIds` 重复调用不能重复动作（进程被杀后重启会重扫）。
   没可做的事返回 `null`；**失败抛异常**（调度器记成 `error` 并停止自动重试）。

## 5. 策略细节与理由

- **失败后不自动重试**：模型一直出错时，切后台 / 离开对话会一次次重打模型（花钱、烧配额、用户还看不出来）。
  失败的记忆库被挂起，**待整理内容留着**，等下一轮对话结束（用户又有新动作）或用户手动触发。
  `CancellationException` 单独放行（不算失败）：进程要走、作用域被取消不是「模型整理失败」。
- **一次只跑一个**：每个记忆库一把 `Mutex`。`organize` 还会**先把状态标成运行中**——否则用户连点几下，
  每次点击都会排在「查数据库」后面各排一次全库复查。
- **扫库结果只能并入、不能覆盖**：`findPending` 是从数据库查出来的，**查询期间结束的轮次不在结果里**；
  整体赋值会把它们刚记下的待整理弄丢（有专门的单测钉住）。
- **只减掉这次整理的对话**：整理成功后从待整理表里减去 `conversationIds`，而不是清空——
  运行期间新结束的轮次要留到下一次。
- **保活句柄的移交**：应用不可见时，`onTurnFinished` 先占住保活再查数据库（否则可能在查询期间被冻结），
  然后**要么交给定时器管、要么当场放开**（`handedOver` 标记）——提前 return 不许泄漏句柄。
  前台不占保活（没必要白占）。
- **保活失败不构成功能失败**：`MemoryDreamKeepAlive` 拿不到时返回一个空句柄，整理照常进行。

## 6. 与上游 #2130 的差异

| 差异 | 说明 |
|---|---|
| 运行结果**不带** `steps` / `changedPaths` / `deletedPaths` | 那三个是文件模型的产物（路径、工具步骤）。本仓只留 `changedMemories` 计数；「改了哪几条、为什么」属于 worker 自己的日志。这样 V4 换存储时框架层不用改（同理也没有理由现在就把 `UIMessagePart.Tool` 引进框架类型） |
| 上游**没有**的语义：隐身对话不整理 | 上游是「一个助手一个记忆库」，没有 per-conversation 隐身；本仓 V2 §4.4 已承诺「不整理留给 V3」，所以把它写进 `pendingOf` 的契约（实现与测试在 worker 批次） |
| 默认值提到 `companion object` | `DEFAULT_IDLE_DELAY = 2.minutes`、`DEFAULT_MAX_PENDING_TURNS = 6`（与上游取值一致，便于将来对照） |
| **不移植** `MemoryDreamForegroundService` | 本批不接线；本仓已有 `KeepAliveService` 可复用，保活接口（`fun interface` + 空句柄默认）已经留好 |

## 7. 助手级启停与成本护栏（**决策已定**；字段与 UI 已随 V3-b1 落地）

做梦与注入**不是一类成本**：注入只是同一次请求里多几千 token（被动、不额外花钱），做梦是**额外的一次
后台模型调用**（多步、在用户没看着的时候发）。所以启停与限额必须是一等公民，而不是"有了再说"。

### 7.1 开关放在助手级（三条决策 + 编码方式，2026-10-09）

| 决策 | 取值 | 为什么 |
|---|---|---|
| 粒度 | **助手级**（`Assistant.memoryConsolidationSince`） | 记忆本身是助手级的（`memoryId` = `assistantId`，含 `__global__`），开关粒度应与"被影响的单位"一致；花费也记在助手头上 |
| 默认值 | **默认关**（显式开启） | 「会在没看着的时候花钱/发请求」的自动行为，本仓一贯显式开启（世界书预算、记忆提取管道「须配开关」）。**代价要认**：默认关 ⇒ 功能对多数用户静默闲置，所以**手动入口必须同时存在**（记忆页「整理一下」，见 §4 的契约收窄） |
| 手动整理 | **开关关掉后仍可用**（手动绕过开关） | 开关管的是**自动化**；用户显式点击即主动授权（与审批语义同源：显式动作放行）。对应契约里 `findPending` 不判开关（§4） |
| 编码方式 | **时间戳而不是 `Boolean`**：`0` = 关，`> 0` = 开启时刻 | 它**同时是基线**（见下）。两个字段（开关 + 基线）会漂移，一个字段只有一个事实 |

**为什么需要"开启时刻"这个基线**：做梦只对「开启之后的新轮次」负责。若没有它，刚拨开开关就会把该助手
**几百个历史会话一次性灌进整理队列**——用户没要求的后台模型花费，而且会把新内容挤到后面。关掉再开以新的
时刻为准（关闭期间的内容不补算）；想整理历史走**手动整理**（不受基线与开关约束）。全局库（`__global__`）
被多个助手共用时，基线取参与助手中**最晚**的开启时刻（保守方向：宁可少整理，不许多花钱）。

**不加全局总闸**（全局「禁用一切后台自动化」）：助手级已经能表达"这个助手别整理"，再加一层会让
「为什么它没整理」变成一个要看两处的问题。等本仓的**后台自动化**多起来（做梦 / 定时任务 / 自动备份），
再用一个统一的「后台自动化」总闸把它们一起收口——那时收口是有内容的。

### 7.2 与现有开关的交互矩阵

| 项 | 现状 | 与做梦的关系 |
|---|---|---|
| `Enableable.enabled`（停用助手） | 有 | 停用 ⇒ 不整理（上游 PR 的阻塞④正是「停用助手照样被整理」） |
| `Assistant.enableMemory`（默认 false） | 有 | 关 ⇒ 没有记忆可整理 ⇒ 不整理（天然前提，不重复开关） |
| `Conversation.memoryDisabled`（隐身对话） | 有 | 该对话的轮次不进整理（已在 §4 契约里） |
| `memoryModifyApproval` / `memoryCreateApproval` | 默认拦改、放行新增 | **做梦是后台 agent 写记忆，必须尊重它**：审批开着 ⇒ 做梦**只做不动库的分析**（产出降级为待办/提案，不改记忆库）；审批关着 ⇒ 全自动改库。否则用户的审批设置会被架空成「只在对话里有效」的半截开关——那正是本仓评估上游 #2130 时列的阻塞④，不能自己再犯一遍 |
| `Assistant.memoryConsolidationSince`（新，默认 0 = 关） | 已落地（V3-b1） | 只控制**自动**整理（手动不受它约束，见 §7.1）；同时是「只对开启之后负责」的基线 |

审批开着时的「提案」形态**定为 B（2026-10-09 拍板，见 §9 的对比）**：**会话内一次性通知 + 记忆页列出
本次整理的建议**，不做提案表、不做一键应用。建议只活在进程内（调度器的
`statusOf(memoryId).lastRun.proposals`），重启即失；用户要按建议改，走记忆页已有的编辑能力。
为什么不上来就做「落库 + 逐条批准」：那需要先设计好**成组**（合并 = `edit` + `delete`，逐条批准下
「批准 delete、忽略 edit」会直接丢内容）与**过期**（提案期间目标被改/被删需要前置条件）两件事，
而这两条得看过真实提案才好定。

### 7.3 成本护栏（**已随 V3-b2 落地**，与护栏家族同款：优雅停止 + 结果照常回填 + 诊断日志）

- **单次整理最大步数**：自动 ≤ **16** 步 / 手动 ≤ **32** 步（`MemoryConsolidator.MAX_AUTO_STEPS` /
  `MAX_MANUAL_STEPS`；上游同款）——后台 agent 跑飞比对话里更难看，而用户显式手动的整理允许更彻底；
- **每库自动频次上限 = 6 小时**（`MIN_AUTO_INTERVAL_MS`）：`onTurnFinished` 的规则是「一轮结束 + 空闲
  2 分钟」就整理，不设间隔的话**活跃会话会变成每 2 分钟一次后台模型调用**（一轮对话就够触发）。
  6 小时把上限压到每库每天 ≤4 次，同时保证「今天聊的，今晚被整理」。
  **只约束自动路径**：`pendingOf` / `memoryIdsWithUpdates` / 自动的 `findPending` 判它，
  `findPending(manual = true)`（用户点「整理一下」）不判——为此契约多了一个 `manual` 参数
  （§4 已记），否则调度器整理后的自动重扫会绕过限流；
- **离线 / 无可用模型：不发请求，但记成一次失败**（换退避）。实现时改了原计划（原来是"跳过且不记失败"）：
  返回 `null`（= 没可做的事）会让调度器**每 2 分钟重扫同一批内容**空转；记成失败则拿到现成的退避
  （`failed` ⇒ 不再自动重试，直到下一轮对话结束或用户手动触发），而**水位没推进 ⇒ 内容不丢**；
- **水位只推进到「真正带进模型」的位置**（`transcript.consumed` 而不是请求里的 `conversationIds`）：
  否则没装进这一轮预算的轮次会被标记成整理过，静默丢失；
- 框架层已有：失败不自动重试、同库单飞、取消不算失败、保活失败不构成功能失败。

### 7.4 落地成本与位置（**已随 V3-b1 落地**）

- `Assistant.memoryConsolidationSince: Long = 0L` ⇒ **零 DB 迁移**（助手是 DataStore 里的 JSON，
  `Settings.assistants`；缺字段反序列化即默认。与 `Conversation.memoryDisabled` 不同——那个在 Room
  表里所以加了列），并有单测钉住「老 JSON 缺这个键必须落到 0」；
- 归属：**助手详情 → `AssistantMemoryPage`**，与 `enableMemory` / `memoryModifyApproval` 同组，
  从属关系 `enabled = assistant.enableMemory` 现成；6 语言文案各 2 条；
- **⚠️ 当前状态：开关已可见、判据已生效，但拨动后暂时没有可观察效果**——自动整理要等四个钩子接线
  （V3-b3），`consolidate` 要等 V3-b2。这是拆批的诚实边界：字段与门控是"活"的（有单测与真实消费者），
  只是还没有触发者。若在接线前不想让它出现在 UI 里，把这一项从 `AssistantMemoryPage` 摘掉即可（无副作用）。

## 8. 验收面

`MemoryConsolidationSchedulerTest` **21 项**，全部用 `runTest` 的**虚拟时间**（`advanceTimeBy` /
`runCurrent`）——被测的全是「什么时候该整理」，用真实 `delay` 测既慢又会飘（「期间没有多跑一次」
这种断言更是没法用真实时间钉死）。

- 触发面：连续轮次只在空闲后整理一次（且期间**没有**提前跑）、攒够轮数不等空闲、离开对话立刻跑、
  切后台立刻跑、一次覆盖全库、启动补漏按库各查各的、未知记忆库返回默认状态；
- 保活：整理期间占住、结束放开、后台轮末提前占住、连续轮次下句柄**不断档**（旧句柄随被顶掉的定时器释放）、
  「没有新内容」时提前占的句柄不泄漏；
- 失败与并发：失败的整理被记下 + 轮数保留 + 不再自动重试 + 下一轮对话结束才重试、**取消不算失败**、
  运行期间新结束的轮次留到下一次、扫库期间的轮次不丢、连点 `organize` 不排第二次；
- 手动与通知：无待整理也能全库复查、`organize` 把手上的待整理一起交出去、**只有自动整理发通知**。

外加一条**源码扫描守卫**：调度器源码里不许出现 `MemoryEntity` / `MemoryDAO` / `MemoryRepository` /
`AssistantMemory` / `ConversationEntity`（框架一旦认识存储，V4 换存储时这层就得跟着改）。

**新增测试依赖**：`kotlinx-coroutines-test`（版本跟 `kotlin-coroutines-core` 走）。这是本仓第一次用它，
代价是一个 test-only 依赖；收益是上面这些关于「时间」的断言可以写成确定的。

## 8.1 V3-b1 已落地（2026-10-09）：进度表 + 非 LLM 四条 + 助手级开关

**做了什么**

- **进度表**：`memory_consolidation(conversation_id PK, consolidated_at)`，挂 `ConversationEntity`
  **CASCADE 外键**（会话删了进度跟着走——孤儿行会让"恢复备份后又出现同一 id"拿旧进度当基线、静默漏轮次）；
  **DB 34 → 35 纯新增表**（AutoMigration，无回填：没有进度行就等于用开关开启时刻当基线）；
- **数据面**（`MemoryConsolidationRepository`）：实现契约里与模型无关的四条——`pendingOf` /
  `memoryIdsWithUpdates` / `findPending` / `markConsolidated`。**刻意不实现 `MemoryConsolidationWorker`
  接口**：`consolidate` 属下一批，半实现的接口比不实现更容易让人以为"已经能整理了"；
- **纯策略**（`data/ai/MemoryConsolidation.kt`）：门控三道闸、基线 = `max(开启时刻, 进度)`、
  轮数计数、记忆库 id 解析。顺手消掉一处重复：`Assistant.memoryId()` 原先在 `ChatToolFactory`
  与 `AssistantDetailVM` 各写一遍（全局记忆分支），现在有唯一定义；
- **助手级开关字段 + UI**（见 §7）。

**两个实现细节值得记**

- **轮数怎么数**：`message_node` 表**没有时间戳**（时间在消息 JSON 里），所以精确轮数只能在内存里数；
  于是"粗筛走 SQL、精算走内存"——SQL 只按 `COALESCE(last_message_at, update_at)` 与基线过滤，
  精算按"节点里有没有基线之后的消息"，为 0 的候选**不写进度行**（写了就把没整理的内容标记成整理过了）。
  `last_message_at` 由保存路径从末尾消息的 `createdAt` 维护（改标题不会刷新它），所以粗筛几乎不误报；
- **全局库的基线取"最晚开启"**：`__global__` 被多个助手共用，若取最早，某个助手刚拨开开关就会给整库
  倒灌历史——保守方向是宁可少整理。

**验证**（:app **1393 → 1412**）：`MemoryConsolidationPolicyTest` 7 项（门控矩阵 / 基线取大 / 轮数边界 /
一轮多条消息只算一轮 / 空节点不炸 / 全局库 id / **老 JSON 缺键落到默认关**）+
`MemoryConsolidationRepositoryTest` 12 项（四道门控各自生效 / 只数开启之后的轮次 / 进度顶过基线 /
共享库跨助手合并且基线取最晚 / 粗筛命中但精算为 0 被丢弃且不写进度 / 限流与"新内容优先" /
未知库不查库 / 标记已整理后不再待整理）。真机冒烟见 §8.2。

**仍未接线**（诚实边界）：没有触发者（四个钩子）也没有 `consolidate`，所以**拨动开关暂时没有可观察效果**。

## 8.2 真机迁移冒烟（V3-b1）

debug 包（`app.androidrik.rikkahub.debug`，用户真实库）升级安装并启动后实测：

- `PRAGMA user_version` 34 → **35**；
- 新表建出且带外键：
  `CREATE TABLE memory_consolidation (conversation_id TEXT NOT NULL, consolidated_at INTEGER NOT NULL,
  PRIMARY KEY(conversation_id), FOREIGN KEY(conversation_id) REFERENCES ConversationEntity(id) ON DELETE CASCADE)`；
- **既有数据零丢失**：记忆 **54 → 54**、会话 250 条不变；进度表 0 行（符合预期：`consolidate` 未实现，
  没有任何东西写过进度）；
- 启动与交互零 `FATAL`；新字段已随设置落盘（DataStore 里能看到 `memoryConsolidationSince`，值 0 = 关）。
- **未在真机验证的部分**：开关的观感与"拨动后确实没有行为"（需要人点一下；本批没有触发者，
  拨动本来就不该有可观察效果）。

## 8.3 V3-b2 已落地（2026-10-09）：worker + 整理 agent + 审批降级

**做了什么**

- **`MemoryConsolidator`**（实现 `MemoryConsolidationWorker`）：四条数据面委托给 V3-b1 的仓库，
  自己只做「需要模型」的那一件事——切轮次 → 给库现状 → 解析模型 → 调 agent → 推进水位 → 组装运行结果；
- **`MemoryConsolidationAgent` 抽象**：策略层（切多少、推进到哪、审批怎么降级、失败怎么办）全部
  可用 JVM 单测钉死；生产实现 `MemoryConsolidationGenerationAgent` 只剩「建消息 → 调 `generateText`
  → 数改动」三件事（它依赖 Android，由源码扫描守卫兜住，见 §8.3 末尾）；
- **输入侧**（`MemoryConsolidationPrompts.kt`，纯函数）：
  - 正文切分：只取基线之后的轮次、按 token 预算装（默认 6K）、**装不下的会话不推进水位**、
    单轮超预算时只要这一轮还什么都没装下就带上（防饥饿）、非文本折叠成 `[image]`/`[file: x.pdf]`；
  - **校准表**：`id ｜ importance ｜ reads ｜ 主题`——这是「事后复核」职责的输入。**刻意不进聊天注入**
    （V2 用 `@Transient` 挡掉是对的：聊天模型看到「这条被读过 7 次」会把它当权威），只给整理 agent；
  - 库现状**复用** `buildMemoryPrompt`（两层披露、换更小预算 4K），不另造一套视图；
- **审批降级（只提案模式）**：库的任一参与助手开着 `memoryModifyApproval` / `memoryCreateApproval`
  ⇒ 整轮只提案。做法是在**工具层**加一个 `proposalSink`：`create`/`edit`/`delete` 不动库、把结构化建议
  交给它、回执 `applied:false` 并说明「已记录为建议，别重试」。为什么不靠既有审批机制拦：
  那套是**交互**的（挂起等用户点），后台整理没有用户可等；而"直接拒绝"会丢掉模型原本想改的内容。
  **代码级兜底优先于提案**：命中身份证/卡号的写入连建议都不留（测试钉住）；
- **模型选择**：优先全局「快速模型」（`Settings.fastModelId`，它本来就是给标题/建议这类后台任务用的），
  没配再回落到助手的聊天模型；做梦用**临时 profile**（不带用户的人设/世界书/记忆注入）。

**两条与文档不同的实现修正**（§7.3 已就地更新）：离线/无模型改成"不发请求但记成失败换退避"；
频次上限取 **6 小时**（不是"每天 1 次"的举例），且为此给 `findPending` 加了 `manual` 参数。

**⚠️ 本批不接线**（这一句原来写的是"前置阻塞：提案没持久化之前别接自动触发"，**2026-10-09 更正**）：
事实性内容在聊天当下就由 `memory_tool` 写入了（V1/V2 的纪律管着），做梦的增量价值是**整理**
（去重/合并/纠类/校准重要度）。所以丢掉一批建议的损失是「**整理收益延后**」，不是内容丢失——
它是体验缺口而非数据缺口，B 形态（§7.2 + §9）下可以先接线。接线本身留给 V3-b3。

**验证**（:app **1412 → 1437**）：

- `MemoryConsolidationPromptsTest` 7 项：只取基线之后 / 装不下就不推进水位 / 单轮超预算不饥饿 /
  预算耗尽后只计数 / 无正文轮次也跨过水位（否则每 2 分钟被扫）/ 附件折叠、URL 不进正文 / 校准表
  （`never read`、`read Nx`、空库不发）；
- `MemoryConsolidatorTest` 9 项：无模型 ⇒ **一次请求都不发**且记失败 / 自动 16 步 / 手动 32 步 /
  审批开着 ⇒ `proposeOnly` 且提案透传 / **水位只推进带进模型的会话** / agent 失败 ⇒ 水位不动 /
  幂等（重复调用不再请求）/ 无正文 ⇒ 不发请求但推进水位 / 未知库不参与；
- `MemoryToolsTest` +4：只提案模式下 create/edit/delete 不动库并回传结构化建议、`read` 照常、
  **兜底优先于提案**；
- `MemoryConsolidationRepositoryTest` +2：间隔内自动路径静默（`pendingOf`/`memoryIdsWithUpdates`/自动
  `findPending` 三者都判）、**手动路径不受约束**；间隔过后恢复；
- `MemoryConsolidationSchedulerTest` +1：`organize` 必须带 `manual = true`，而整理后的自动重扫不带；
- `MemoryConsolidationWiringTest` 2 项（源码扫描）：提案 sink 真的接在工具上、提案模式在系统提示里
  有说明、做梦用临时 profile、读取记遥测、护栏停止当失败；水位只用 `transcript.consumed` 推进、
  无模型记失败、两个步数常量存在。

## 8.4 V3-b3 已落地（2026-10-09）：接线 + 状态/建议呈现

**接了什么**

- **Koin 6 个单例**：进度表 DAO / 数据面 `MemoryConsolidationRepository` / 整理 agent / worker /
  调度器 / 运行时；worker 与 agent **必须显式绑定到接口**（`single<MemoryConsolidationWorker>`、
  `single<MemoryConsolidationAgent>`）；
- **三个钩子 + 一次通知**（`MemoryConsolidationRuntime`，在 `RikkaHubApp.onCreate` 里 `start()`）：
  - 轮末：`AppEvent.ChatGenerationEnded`（`ChatService` 在生成结束时发，成功/失败/取消都算）；
  - 切后台：`ProcessLifecycleOwner.onStop` ⇒ `scheduler.onAppHidden()`；
  - 启动补漏：`start()` ⇒ `trigger.resumePending()`；
  - 自动整理**有产出**时发 `AppEvent.MemoryConsolidationFinished`，`RouteActivity` 弹一次 toast
    （什么都没改是常见结果，每次都弹就成了噪音）；
- **「离开对话」不接**：`ChatPage` 没有可靠的离页信号（只有一个抽屉的 `BackHandler`），而它的收益只是
  把「等空闲 2 分钟」提前成「立刻」——轮末计时与切后台已经覆盖这条路径。要接得动导航层，留作可选优化；
- **保活留空实现**（`MemoryDreamKeepAlive` 默认 no-op）：`KeepAliveService` 是**用户自己的开关**
  （常驻通知 + 电池白名单），拿它给后台整理续命会把用户没开的功能悄悄打开；独立前台服务要新渠道 +
  manifest + 生命周期，值得单独一刀。退化的代价可接受：切后台时立刻开始跑，进程若被冻结则这一轮不算完成
  ——**水位不推进、内容不丢**，下次再来；
- **UI（决策 B）**：记忆页开关下面新增「整理一下」条目 —— 状态（待整理 N 轮 / 正在整理 / 上次结果）
  + **本次建议列表**（`describe()`：动作 · 目标 · 内容摘要）+ 手动整理按钮（不受自动频次上限约束）。

**真机踩到的坑（值得记下来）**：第一次装上去**启动就崩了**——`single { MemoryConsolidator(...) }`
只按**具体类型**注册，而调度器要的是 `MemoryConsolidationWorker` 接口，Koin 抛
`NoDefinitionFoundException`；同一类问题还有整理 agent 与进度表 DAO，三处一路崩到底。
它们**全在源码扫描守卫的盲区**：守卫只能看到「注册了」，看不出「绑定到哪个类型」。
结论：这类问题今后以**真机启动冒烟**为准（守卫里已补「必须绑定到接口」的断言，但那只是能扫到的那部分）。

**验证**：`:app` **1437 → 1447**（+10：触发器链路 3 / 建议描述 2 / 建议黏性 2 / 接线守卫 +3）；
:ai 252 不变，全绿。
真机：debug 包重装后启动零 `FATAL`、零 Koin 错误；一次真实整理的观测见 §8.5。

## 8.5 真机观测

### 8.5.1 手动「整理一下」一次（2026-10-09，空内容库）

用户在真机上开了开关并点了「整理一下」，日志：

```
I/MemoryConsolidator: consolidate(f4e707b6-…): 0 conversation(s), 1 record(s), remaining=0, fullReview=true
I/MemoryConsolidator: consolidated f4e707b6-…: applied=0 proposals=0
```

可读出的事实：

- **整链路通**：UI → 调度器 → worker → 提示词 → 模型 → 回填结果，真机上真的走完了
  （`GenerationHandler` 的 profile dump 里能看到做梦的系统提示尾部带着**提案说明**——
  说明 `proposeOnly` 真的开了，也就是「审批开着 ⇒ 只走分析」这条降级路径确实生效）；
- 参数符合预期：`fullReview=true` ⇒ 手动路径（自动路径恒为 false）；`0 conversation(s)` ⇒
  开关打开之后还没有新轮次可整理（这正是 §7.1 的设计：只对开启之后的新内容负责）；
- **模型没有幻觉**：库里有 1 条记录、正文为空，它建议 0 条、应用 0 条 ✔；
- 记忆 **54 → 54** 不变、进度表 **0 行**（没有消费任何轮次 ⇒ 没有水位可推进）✔。

### 8.5.2 自动路径跑出 2 条建议，并暴露一个真缺陷（2026-10-09）

用户在开关打开后聊了一轮，随后：

```
21:54:12  consolidate(f4e707b6-…): 1 conversation(s), 1 record(s), remaining=0, fullReview=false
21:54:25  consolidated f4e707b6-…: applied=0 proposals=2
21:54:49  consolidate(f4e707b6-…): 0 conversation(s), 1 record(s), remaining=0, fullReview=true
21:54:53  consolidated f4e707b6-…: applied=0 proposals=0
```

- **自动路径真的触发了**（`fullReview=false`，轮末 + 空闲）✔，这是第三个触发面在真机上被验证；
- **真的提出了 2 条建议**，而 `applied=0` —— 审批开着 ⇒ 只提案，与 §7.2 的设计一致 ✔；
- 随后用户点「整理一下」（`fullReview=true`），已无轮次可整理 ⇒ **0 建议、不再花钱**（幂等）✔；
- 记忆 54 → 54（没有应用任何东西）✔；进度表出现 **2 行真实水位**（21:45:04 与 21:54:04，
  都等于「最后一条真正带进模型的消息时间」）✔。

**暴露的缺陷（已修）**：那两次整理之间只隔 24 秒，第二次（空跑）把 `lastRun` 覆盖成「0 建议」，
于是记忆页上的建议列表当场变空——**用户再也没机会看到那 2 条建议**。

修法：建议不再挂在 `lastRun` 上，而是状态里独立的 `suggestions` + `suggestionsAt`，**只有下一次真的
又提出建议才替换**（空跑不覆盖）；界面在这批建议上方标出它们来自哪一轮（`%1$s` 时间），
避免被当成「现在库里的问题」。进程重启即失，仍在决策 B 接受的范围内。

教训：**「一次性通知 + 列在页面上」这种形态，真正的风险不是没存下来，而是被后一次运行悄悄冲掉**——
这类问题只有真机按真实顺序点过才会暴露（单测里我写的第一版正是「后一次 run 直接覆盖 lastRun」，
而且看起来完全合理）。

### 8.5.3 还没看到的

- 「应用后写入」与 toast 并存时的观感（本次 `applied=0`，toast 文案是「应用 0 条，建议 2 条」）；
- 建议列表在人眼里的可读性（`describe()` 只给动作 · 目标 · 内容摘要，是否够用）；

## 8.6 真机 ANR 调查（2026-10-09，**未结案**）

用户在真机上连续遇到「无响应」（22:04–22:07），系统 ANR 文件里的主线程栈：

```
main (Runnable)
 at java.lang.StringUTF16.newString / StringBuilder.toString
 at ByteLevelBpe.encodePiece(ByteLevelBpe.kt:133)
 at TokenizerRegistry.count → estimateTokens(ContextLength.kt:127)
 at selectMemoriesForPrompt(GenerationPrompts.kt:237)
 at ChatService.handleMessageComplete(ChatService.kt:1208)
```

**已经排除的**（都做了实测，不是推理）：

- **tokenizer 本身不快不慢是够用的**：新增 `TokenizerPerformanceTest`（:common）实测
  4K 中文字符 1–2 ms、**64K 中文字符 4 ms**、64K ASCII 14 ms；
- **用户的数据不大**：`memoryentity` 全部记忆合计 **55,752 字符**、最长 3,646 字符 ⇒ 记忆注入的计数就是毫秒级。

所以 5 秒的锅**不是** token 计数本身，而栈顶那一帧只是快照碰巧停在的地方。

**已经改掉的（无争议的两处）**：

1. `ChatService` 里那段记忆注入的计数**只是写一条日志**，却跑在主线程上 ⇒ 丢到
   `appScope.launch(Dispatchers.Default)`（为写日志拿输入响应当代价，不值）；
2. **做梦自己的调度器不许在主线程**：`AppScope` 是 `Dispatchers.Main`（`RikkaHubApp.kt:399`），
   而整理要跑模型循环、切轮次、数 token ⇒ DI 里换成
   `CoroutineScope(get<AppScope>().coroutineContext + Dispatchers.Default)`（Job 仍挂 AppScope），
   运行时的四个收集也显式用 `Dispatchers.Default`。

**这次加上的仪表**（为了"下一次复现直接点名"）：

- `ChatService` 准备阶段分段计时：`turn prep phases on <thread>: pre=…ms compress=…ms memories=…ms tools=…ms`；
- 逐块处理：超过 200 ms 的块记 `slow chunk <类型> took …ms on <thread>`（长回复/带附件的回复里，
  真正吃主线程的往往是某一块）；
- 另加 `TokenizerPerformanceTest`（3 项）作为性能护栏。

**结论待补**：等用户在真机上复现一次（触发那条路径即可），用上面的日志点名是哪一段吃掉了主线程，
再针对性修（若是 `compress`/`tools`/某一块，各自的修法不同）。

## 9. 未做（下一批）

### 9.1 提案形态：定为 B（2026-10-09 拍板）

**B = 会话内一次性通知 + 记忆页列出本次整理的建议**（不做提案表、不做一键应用）：
建议只活在进程内（`statusOf(memoryId).lastRun.proposals`），重启即失；要看要改就在记忆页当场看、
按现有编辑能力自己改。**只提案那一轮的水位照旧推进**（不重复分析 = 不重复花钱）。

为什么不上来就做 **A = 落库 + 逐条批准**：它把正确性责任从人挪到了代码，必须先解决两件硬事——
① **成组**：合并类建议天然是 `edit` + `delete` 一对，逐条批准下「批准 delete、忽略 edit」= **内容丢失**；
② **过期**：提案期间目标记录被改/被删时，一键应用会覆盖用户的手改，需要存前置条件（`updatedAt`／内容比对）
并定义过期行为。这两条**看过真实提案才好定**（建议里合并占多大比例、动作是否低风险），
而 B 正好用来先验证最大的未知：prompt/agent 的输出质量。

将来若真实数据表明建议量大、值得批量应用，再升级为 **A′**（落库 + 每轮「全部应用/全部忽略」，
整轮原子应用 ⇒ 半状态问题消失）或 **A**（逐条 + 成组 + 前置条件）。**不要**在没有真实数据时直接做 A。

**建议形态：仍然没有可靠数据（含一次已撤回的误记，2026-10-09）**。

- 那次自动整理确实产出了 **2 条建议**（日志 `proposals=2`、`applied=0`），但它们的**类型没有被记录下来**：
  它们只活了 24 秒就被空跑覆盖（§8.5.2 的缺陷，已修），用户只看清**其中一条是"修改"类**，
  另一条的类型已忘记；
- ⚠️ 本文档一度写着"样本是 `edit` + `create`、没有 `delete`，所以 A′ 的粒度就够"——**那段是误读**：
  用户从未报告过两条的类型，只是说有一条是"修改"类、另一条忘了。**该结论已撤回**，不要拿它做决策；
- 因此 §9.1 的判断维持原样：**A 要解决的"成组"与"前置条件"两件事是否真的重要，目前无从判断**，
  继续按 B 跑，下次有人看到建议时**直接把列表内容记下来**（截图/抄一行都行），再谈要不要升级。

教训（与 §8.5.2 是同一类）：**把"用户大概记得的"当成"观测到的数据"会污染决策依据**。
这条链路上凡是没被记进日志或截图的东西，都不算证据。

### 9.2 V3-b3 剩下的小尾巴（接线主体已落地，见 §8.4）

- **真机验证一次真实整理**（§8.5 待回填）：需要用户在真机上点两下（开开关 + 发一条消息或点「整理一下」）；
- **「离开对话」钩子**：要动导航层（`ChatPage` 没有可靠离页信号），收益只是把「等 2 分钟」提前；
- **前台服务保活**（独立服务，不复用用户自己的 `KeepAliveService`）：影响「后台跑到一半被冻结」的概率，
  不影响正确性；
- **提案的落库与一键应用**：见 §9.1 的条件（等真实建议的形态数据）。

- **V4 Markdown 文件库**：等上游 #2130 合并/稳定后再评。

相关：`docs/design-memory-discipline.md`（V1 纪律 / V2 渐进披露 / §4.6 信用记账）、
`docs/design-memory-scoring.md`（打分口径）、私有仓记录·上游分析 2026-10-08 节（#2130 评估与四块拆分）。
