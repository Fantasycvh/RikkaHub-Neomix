# 记忆 V3：做梦（整理）框架

> 来源：上游 PR #2130「记忆改为文件库 + 空闲后整理（做梦）」四块拆分里的**第三块**（评估见
> `upstream-analysis.md` 2026-10-08 节）。该块**与文件模型无关**：`MemoryConsolidationScheduler`
> 只认 `memoryId` + `MemoryConsolidationWorker` 接口，所以可以先落到本仓的记录模型上。
>
> 现状：**框架已落地（2026-10-09）**——调度策略 + 接口契约 + 保活抽象 + 21 项虚拟时间测试；
> worker 实现、进度表、接线与 UI 见 §8（下一批）。

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

**不做的事**（下一批，见 §8）：worker 实现（要 LLM 调用 / 提示词 / 审批语义）、进度表（要 DB 迁移）、
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
2. **`pendingOf` 是唯一的「要不要整理」判据**——隐身对话（`Conversation.memoryDisabled`）、停用的助手、
   没开记忆的助手都必须在这里返回 `null`。「不整理」的语义在这层落地（V2 §4.4 承诺过的那件事），
   调度器不重复判断、也不知道这些字段存在。
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

## 7. 验收面

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

## 8. 未做（下一批）

- **V3-b worker**（`MemoryConsolidator`）：吃「上次整理后新增的轮次」，对本仓记录做去重 / 合并 / 纠类 / 退场，
  并用 `read_count` + `importance` 对照校准（上一批把读数接进打分只是「当下口径」，整理才是「事后复核」）；
  含提示词、轮次切分/分批、幂等、`changedMemories` 统计；以及**隐身对话 / 停用助手在 `pendingOf` 挡掉**
  （含测试）。
- **V3-c 进度表**：上游把进度单独成表（`memory_consolidation(conversation_id, consolidated_at)`）而不是
  放在 `ConversationEntity` 上——因为**对话每次保存都会整行重写，会把进度冲掉**。本仓同理，落地时
  DB 34 → 35（纯 `CREATE TABLE`）。
- **接线**：Koin 单例 + 轮末（`ChatService` 生成结束处）/ 切后台（`RikkaHubApp` 生命周期）/ 离开对话
  （导航离页）/ 启动补漏（`RikkaHubApp.onCreate`）四个钩子 + 前台服务（复用 `KeepAliveService`）。
  本批**一处都不接**：worker 未实现 ⇒ 没有可跑的整理，接了也只有空转。
- **UI**：记忆页的「整理」按钮 + 状态（上游有 `MemoryConsolidationText`/`MemoryFilePage` 一整套）；
  本仓的 `statusOf` / `automaticRuns` 两个流已经按「设置页与一次性提示」的需要留好。

相关：`docs/design-memory-discipline.md`（V1 纪律 / V2 渐进披露 / §4.6 信用记账）、
`docs/design-memory-scoring.md`（打分口径）、`upstream-analysis.md` 2026-10-08 节（#2130 评估与四块拆分）。
