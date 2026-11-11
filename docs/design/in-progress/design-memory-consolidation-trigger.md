# 记忆整理触发方式配置（design-memory-consolidation-trigger）

> 状态：**rev 2——2026-11-11 P0 已实施（配置模型/三种轮次模式/范围/触发页），P1（墙钟四模式）未实施**。
> 挂靠 `../completed/design-memory-consolidation.md`（做梦 V3，已发布）与
> `../completed/design-scheduled-ai-tasks.md`（定时任务，时间层基建来源）的增量稿；
> 不改动两条已定案语义，只在其上叠一层「触发方式可配置」。
>
> **一句话**：把做梦现在硬编码的触发时机（空闲 2 分钟 + 攒 6 轮 + 6h 限流）抽成一个
> **单选互斥**的触发配置，新增「每 N 轮 / 第 N 轮 / 一次性 / 固定间隔 / 每日 / 每周」六种模式，
> 配「范围 = 指定助手 / 全部」。cron 与子代理执行本稿不做（见 §十）。
>
> 来源：2026-11-11 会话——用户拿了一张竞品截图（7 个触发方式开关 + 执行类型 + 范围），
> 评估结论为路线 A（扩展现有调度器）+ 分期；四项语义决议已当场拍板（§〇）。

## 〇、决议记录（已拍板，2026-11-11）

| # | 分叉 | 决议 |
|---|---|---|
| 1 | 「第 N 轮」口径 | **按会话累计**（该会话自创建起的累计用户轮数），**触发一次后失效**（命中标记持久化，不重置） |
| 2 | 多触发方式关系 | **单选互斥**（不做多选 OR；UI 为单选列表，选中的模式独占触发权） |
| 3 | 墙钟 vs 6h 自动限流 | **绕过 `MIN_AUTO_INTERVAL_MS`（6h），保留建议闸门 + 单飞锁 + 失败不自动重试** |
| 4 | 本轮交付范围 | **P0+P1**（配置模型 + 轮次触发 + 范围 + 页面骨架 + 墙钟四模式）；cron、子代理执行缓做 |
| 5 | 范围 = 全部时的去重 | **按 memoryId 去重**（共享 `__global__` 库的多个助手一轮只整理一次；调度器本来就以库为单位） |
| 6 | 「主线程 / 子代理」执行类型 | 本期不做；命名预留为「应用内 / 子代理」（Android 无「主线程跑模型」语义） |

## 一、现状与差距

### 1.1 现有触发面（`design-memory-consolidation.md` §9.2，硬编码）

| 入口 | 位置 | 现行为 |
|---|---|---|
| 轮末空闲 | `MemoryConsolidationScheduler.onTurnFinished` → `restartTimer` | 结束后等 `DEFAULT_IDLE_DELAY = 2min`，期间新轮次重新计时 |
| 攒够轮数 | `onTurnFinished` 内 `item.turns >= DEFAULT_MAX_PENDING_TURNS(6)` | 不再等空闲，立即整理 |
| 切后台 | `MemoryConsolidationRuntime` `onStop` → `onAppHidden` | 有待整理就现在开始 |
| 启动补漏 | `resumePending()` | 逐库捞未整理对话，重排空闲计时 |
| 手动 | `organize(memoryId)` | `manual = true`，不受限流与基线约束 |

消费侧闸门（保持不动）：`hasUnhandledProposals` 建议闸门、`runLocks` 单飞、
`failed` 失败不自动重试、`MIN_AUTO_INTERVAL_MS = 6h`（仓库层，只约束自动路径）。

### 1.2 截图能力对照

| 截图项 | 本仓映射 | 期 |
|---|---|---|
| 每 N 轮 | 现有 `maxPendingTurns` 参数化 | P0 |
| 第 N 轮 | 新增：会话累计轮数 + 命中标记 | P0 |
| 一次性 | `ScheduleType.ONCE` + `ScheduleMath` | P1 |
| 固定间隔 | `ScheduleType.INTERVAL`（系统下限 15min） | P1 |
| 每日 / 每周 | `ScheduleType.DAILY / WEEKLY` | P1 |
| cron | 无现成实现，**本期不做** | 缓 |
| 执行类型（主线程/子代理） | **本期不做**，见 §十 | 缓 |
| 范围 指定助手/全部 | 全局配置 + 助手多选 + memoryId 去重 | P0 |

## 二、配置模型与存储

### 2.1 存储位置：全局 Settings（DataStore JSON），不放 Assistant

理由：决议 #2 单选互斥 ⇒ 全局只有一份触发策略；决议 #5 的「范围 = 全部」本身就要求
配置能在助手之外表达（放进任何一个助手的 JSON 都无法表达「全部」）。现有的
`Assistant.memoryConsolidationSince`（开关 + 基线）**保持每助手不动**——它管「要不要/从哪算」，
本稿的配置管「什么时候」。两层正交。

### 2.2 模型

```kotlin
// data/datastore/（随 Settings 序列化；JsonInstant ignoreUnknownKeys + 默认值 ⇒ 零迁移）
enum class MemoryTriggerMode { IDLE, EVERY_N_TURNS, TURN_N, ONCE, INTERVAL, DAILY, WEEKLY }

@Serializable
data class MemoryTriggerConfig(
    val mode: MemoryTriggerMode = MemoryTriggerMode.IDLE,
    /** EVERY_N_TURNS：单个会话待整理轮数阈值（现 maxPendingTurns 的可配置化） */
    val everyNTurns: Int = 6,
    /** TURN_N：会话累计用户轮数阈值 */
    val triggerTurn: Int = 8,
    /** 墙钟四模式的周期参数：直接复用 scheduled 的 SchedulePayload（hour/minute/runAt/intervalMinutes…） */
    val schedule: SchedulePayload = SchedulePayload(),
    /** 墙钟模式已认领的槽位（advance 的输入）；重启后凭它 rearm。null = 未排程 */
    val nextRunAt: Long? = null,
    /** ONCE 已执行（认领后置 true；重开 ONCE 时复位） */
    val onceFired: Boolean = false,
    /** 范围：空集 = 全部助手；非空 = 仅这些助手（按 assistantId 选，运行时映射成 memoryId） */
    val scopeAssistantIds: Set<Uuid> = emptySet(),
)
```

要点：

- `SchedulePayload` 从 `data/scheduled/ScheduleSpec.kt` 原样复用（不复制），DST/补跑/多时段逻辑
  与定时任务同源；`MEMORY` 触发不需要 `MONTHLY`（截图没有，`enum` 里就不放）。
- `nextRunAt` 存进配置而不是内存：进程被杀后闹钟重排（§六.4）需要它；认领时用
  `ScheduleMath.advance` 推进后回写。
- `IDLE` 模式附带暴露现有两个常量为配置项（`idleDelayMinutes`、`everyNTurns` 兼任），
  即「最小改造」档的需求被 P0 免费覆盖。

### 2.3 范围解析（纯函数，可单测）

`resolveMemoryIds(config, assistants): Set<String>`：
`scopeAssistantIds` 为空 → 全部启用做梦的助手；否则按所选助手。产出按
`assistant.memoryId()`（`__global__` 归并）**去重**（决议 #5）。

> **rev 2 实施修订**：范围门控落在**仓库层逐会话判**（`Assistant.inTriggerScope`），且对
> **所有模式**（含 IDLE）一致生效——设计稿 rev 1 原写「IDLE 不受范围」，实施时改为统一：
> 默认空集时行为与历史版本完全一致，而一旦勾了范围，「只有这些助手触发」在哪种模式下都该成立。
> 启动补漏（`participantsByStore`）同样过滤。

## 三、各模式触发语义

模式 = 触发策略，**不改变整理的内容口径**（基线、水位、建议闸门全在仓库层不动）。

### 3.1 IDLE（默认 = 现行为）

五个入口全保留，`idleDelay` / 阈值改读配置。唯一变化：常量变配置。

### 3.2 EVERY_N_TURNS（每 N 轮）

- 轮末（`onTurnFinished`）：单会话待整理轮数 ≥ `everyNTurns` → **立即** `runAutomatically`，
  不挂空闲计时器；未达标则不计时（等下一轮再判）。
- 切后台 / 启动补漏保留（兜住「计数达标后进程被杀」的漏——条件已达标时这两个入口直接跑，
  与 IDLE 同路径）。
- 与 IDLE 的用户可见差异：去掉 2 分钟空闲等待，纯计数触发。

### 3.3 TURN_N（第 N 轮，决议 #1）

- 计数口径：**该会话自创建起的累计用户轮数**（`messageNodes` 中角色为 USER 的轮次，分叉会话
  带着原计数走—— fork 已 `markConsolidated`，不再整理，天然不重复触发）。
- 命中标记：`memory_consolidation` 进度行加一列 `trigger_turn_fired`（DB v64→65 AutoMigration，
  NOT NULL DEFAULT 0 免 spec，与 v54→55 `hidden` 同款）。为什么放进度表不放会话 JSON：
  它与 `consolidated_at` 同属「整理进度」，且进度行的生命周期（随会话删除级联）恰好是标记
  想要的；放会话 JSON 则 `markConsolidated`/fork 路径都要额外维护。
- 判定：轮末 `pendingOf` 处（仓库层）判 `totalTurns >= triggerTurn && fired == 0` → 返回
  pending；**认领时**（`repository.markConsolidated` 写水位的那一次 REPLACE）同时写
  `fired = 1`——水位与标记同行提交，不存在半提交状态（rev 2 实施修订：标记由仓库层单点写，
  不改 worker 契约；fork 的 `markConsolidated` 同样置位，分叉副本天然不重触发）。
- 整理失败：水位没提交 ⇒ 标记也没写 ⇒ 下一轮对话事件（用户又有新动作）会重判并重试
  ——与全局「失败后不自动重试、等新轮次」的语义**自然一致**（rev 2 修订：rev 1 曾写
  「失败也置标记、不重试」，实施时选择了更简单且与现有语义对齐的方案）。
- 手动整理不受 fired 约束（`manual = true` 的语义是「所有还没整理过的轮次，不挑时机」）。
- 切后台 / 启动补漏保留（捞漏网：达标但轮末入口没跑成的会话）。

### 3.4 墙钟四模式（ONCE / INTERVAL / DAILY / WEEKLY，P1）

- **互斥的严格执行**（决议 #2）：墙钟模式下，轮末空闲、攒轮数、切后台、启动补漏这四个
  入口**全部停用**（调度器入口按 mode 短路），只保留：闹钟到点 + 手动「整理一下」。
  理由：用户配「每天 9 点」就是「只在 9 点花钱」的约定，切后台偷偷跑一笔违反互斥承诺。
- 首排程：配置保存时 `ScheduleMath.firstRunAt(type, payload, now)` 算 `nextRunAt` 并回写 +
  排闹钟（§六）。
- 到点认领：`runScheduled(memoryId)`（§五.3）跑完后 `nextRunAt = advance(...)` 回写 + 重排；
  ONCE 认领即 `onceFired = true`、`nextRunAt = null`、撤闹钟，UI 显示「已完成」。
- 限流策略（决议 #3）：新执行路径 `TriggerPath.SCHEDULED`——绕过 6h，**不**复用
  `manual = true`（那个参数同时丢「开启时刻」基线，见仓库层 `pendingOfIgnoringInterval` 的
  `ignoreSince`）；建议闸门命中时**不静默跳过也不顺延**：保持 `waitingForReview = true` 状态、
  槽位照常推进（不补跑）——用户处理完建议后 `resumeAfterReview` 的主动重扫（现有逻辑）
  自然把活干掉，无需为墙钟补跑加新机制。
- 错过语义：直接复用 `ScheduleMath.decideMiss`（宽限 6h；`MissPolicy` 对 MEMORY 固定
  `RUN_LATE`——整理积压不像消息有时效性，晚了补跑仍有益）。

## 四、调度器改造（`MemoryConsolidationScheduler`）

构造参数加两项（DI 处注入，`DataSourceModule.kt:445`）：

```kotlin
configFlow: Flow<MemoryTriggerConfig>,          // 最新配置（collect 进内存，atomic 切换）
workerPath: suspend (String, TriggerPath) -> Unit  // 见 §五.3 的 runScheduled 挂接
```

- `runAutomatically(memoryId)` 按 mode 分派：
  - `IDLE/EVERY_N_TURNS/TURN_N`：现路径（EVERY_N_TURNS 把 `maxPendingTurns` 换成配置值并
    去掉 idle 计时；TURN_N 依赖仓库层 pendingOf 的新判据，调度器无感）；
  - 墙钟：轮末/切后台/补漏入口直接 `return`；新增 `runScheduled(memoryId)` 公开入口
    （闹钟/前台服务调用），内部走与 `runAutomatically` 相同的建议闸门 + `run`（单飞、失败集
    语义同源），但 `findPending` 以 `SCHEDULED` 路径调（绕限流、保基线）。
- `failed` 集合对 SCHEDULED 路径**不拦截**：失败后下一个闹钟槽位允许再试（轮次类模式维持
  「失败等新轮次」不变）。拦截点在 `run()` 的 `memoryId in failed` 判断处按路径放行。
- 模式切换瞬间：cancel 全部 idle 计时器；切到墙钟模式时由配置副作用重排闹钟（§六.3）。

## 五、仓库层与 worker 契约改造

### 5.1 `findPending` 的 `manual: Boolean` 升级为路径枚举

```kotlin
enum class TriggerPath { AUTO, MANUAL, SCHEDULED }
suspend fun findPending(memoryId: String, path: TriggerPath = TriggerPath.AUTO): List<...>
```

| path | 6h 限流 | since 基线 |
|---|---|---|
| AUTO | 判 | 判 |
| MANUAL | 不判 | 不判（现 manual=true） |
| SCHEDULED | **不判**（决议 #3） | **判** |

`MemoryConsolidationRepository.pendingOf` 内部对应三态；`autoIntervalElapsed` 仅对 AUTO 生效。
契约注释同步更新（`MemoryConsolidationScheduler.kt:207` 的第 3 条不许破条款）。

### 5.2 TURN_N 标记列

`MemoryConsolidationEntity` 加 `trigger_turn_fired: Int`（v64→65 AutoMigration 免 spec）；
`consolidate` 提交水位时对本次 `conversationIds` 置 1；`pendingOf`/`findPending` 在
`mode == TURN_N` 时按 §3.3 判据过滤。会话累计轮数的计算放仓库层（它已有 conversationLoader，
能数 USER 轮次），调度器不认识消息形态。

### 5.3 runScheduled 的落点

墙钟到点的调用链（P1）：

```
MemoryTriggerAlarmReceiver → MemoryTriggerRunService(FGS) → scheduler.runScheduled(memoryId)
                             └─ 同进程 Koin 单例，无跨进程
```

`MemoryDreamKeepAlive` 现为空实现（V3 留白）；FGS 服务自身就是保活（持有通知），整理期间
服务存续、结束后 `stopSelf`。**不**新造 keepAlive 实现，避免与 `KeepAliveService`（用户自己的
常驻开关）语义混淆——与 V3 设计稿的取舍一致，只是这次用前台服务而不是空转。

## 六、闹钟层（P1，镜像 ScheduledTaskAlarms）

1. **`MemoryTriggerAlarmScheduler`**：独立小类（~150 行），arm 档位照抄
   `setAlarmClock → setExactAndAllowWhileIdle → setAndAllowWhileIdle` 三档与 2026-10-30 真机
   教训注释。不复用 `ScheduledTaskAlarmScheduler`：requestCode 命名空间、receiver 类型、
   「任务停用撤闹钟」的生命周期都不同，硬抽公共层会把两条特性线焊死（路线 C 否决理由同源）。
2. **`MemoryTriggerAlarmReceiver`** → 启动 `MemoryTriggerRunService`（FGS，新通知渠道
   `memory_consolidation`，低优先级 + 整理中动态文案）。进程同体，直接取 Koin 单例。
3. **重排时机**：配置保存 / `advance` 回写 / app 启动（`resumePending` 处顺带 rearm）/
   `BOOT_COMPLETED`（并入现有定时任务 boot receiver 的一个分支，不新建 receiver）。
4. **闹钟可见性**：`setAlarmClock` 会在系统里显示「下次闹钟」——照定时任务的既成事实接受，
   文案写「记忆整理」。

## 七、UI

### 7.1 落点：新整页 `MemoryConsolidationTriggerPage`

- 注册：`Screen`（+参数路由）、`AppNavHost`、`SettingSearchIndex` 各一条。
- 入口：`AssistantMemoryPage` 整理区块顶部加一行「触发方式」（副标题显示当前模式）→ 跳转；
  页脚说明「开关与基线在每个助手的记忆页单独设置」。
- 结构（遵守 AGENTS.md TextField 宿主规则）：整页 `Column + verticalScroll`（非 Lazy）；
  模式选择用 CardGroup 内的单选行；`everyNTurns` / `triggerTurn` 数字输入放
  ModalBottomSheet；时刻用 `TimePickerDialog`，日期（WEEKLY）用现成周选择 chip；
  INTERVAL 用步进器（15 分钟粒度，提示系统下限）。
- 状态展示：当前模式、下次触发时间（`nextRunAt` 相对时间）、ONCE 已完成、
  `waitingForReview` 暂停原因、范围摘要（「全部助手」/「N 个助手」）。
- 文案：沿用本特性线惯例全量进 `strings.xml`（en 默认 + zh / zh-rTW），键前缀
  `memory_trigger_`。

### 7.2 AssistantMemoryPage 的减法

「空闲后整理记忆」描述文案改为指向触发方式页（开关联动关系：`memoryConsolidationSince = 0`
的助手不在任何模式的整理对象里——触发页显示范围时按此过滤并提示）。

## 八、测试计划

| 层 | 用例 |
|---|---|
| 纯函数 | `resolveMemoryIds` 去重/过滤/空集=全部；mode 短路矩阵（墙钟模式下四入口全 no-op） |
| 调度器（现有 30+ 测试旁扩展） | EVERY_N_TURNS 达标即跑/未达标不计时；TURN_N 命中一次后不重触发、fork 不重触发；SCHEDULED 绕限流、保基线、failed 不拦截下个槽位；建议闸门命中时槽位照常推进且置 waitingForReview |
| 仓库层 | v64→65 迁移；TURN_N 标记与水位同事务提交；`TriggerPath` 三态 × 限流/基线矩阵 |
| `ScheduleMath` 接线 | firstRunAt/advance/decideMiss 对四模式的入参组装（数学本体已有测试，不重测） |
| UI | 模式切换互斥、N 输入 sheet 的 IME 行为（audit_ime_trap.py 过） |

真机验收（P1 后）：Doze 息屏到点（对照 2026-10-30 定时任务的验证方法）、ColorOS 启动管理
拦截场景、ONCE 完成后闹钟撤销、进程被杀后 rearm。

## 九、分期与估时

| 期 | 内容 | 估时 |
|---|---|---|
| P0 | 配置模型 + IDLE 参数化 + EVERY_N_TURNS + TURN_N（含 v65 迁移）+ 范围解析 + 触发页 UI | 3–4 人日 |
| P1 | 墙钟四模式：TriggerPath 三态 + runScheduled + 闹钟层 + FGS + boot rearm + 真机 | 3–4 人日 |
| 缓 | cron（自研最小解析器 + nextOccurrence）、子代理执行（SubagentRunner 无父会话运行 + AgentRun 账本）、MONTHLY | 另稿 |

## 十、明确不做（本稿边界）

- **cron**：截图有但决议缓做；若做，独立小稿（解析器选型 + 耗电评估），不并入本稿。
- **子代理执行**：`SubagentRunner.dispatchBackground` 依赖 parentConversationId，记忆整理无父
  会话；需要 runner 支持 orphan 运行 + memory_tool 信封 + 账本展示，是一条独立特性线。
- **MONTHLY / 随机窗口**：`ScheduleMath` 支持，但截图没有、需求无来源；枚举不放即不可配。
- **多选 OR**：决议 #2 单选互斥；将来若用户要 OR，`MemoryTriggerConfig` 改为
  `List<ModeRule>` 是加法不是重写（本稿的 mode 分派点已收敛在一处）。
