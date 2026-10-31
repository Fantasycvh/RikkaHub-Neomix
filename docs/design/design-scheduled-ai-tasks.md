# 定时 AI 任务（design-scheduled-ai-tasks）

> 状态：**P1 已实现（2026-10-30），真机验证中**。范围：到点自动向指定会话注入一条 prompt 并跑完整生成，
> 结果落在会话里 + 完成通知。存续档位：**Alarm 主唤醒 + WorkManager 双层兜底（revision 4）**，
> 准点性取决于「闹钟和提醒」授权与电池优化白名单，缺失时按 §6.2 的降级路径执行。
> 与 `design-workspace-background-tasks`（workspace shell 后台命令，进程级、非定时）是两个正交特性，命名刻意区分。
>
> **revision 2（2026-10-30）**：按 `purrcat-analysis.md` §五.A4 并入两个语义——
> **idle 门控**（P1，per-task 可选）与**会话级目标文件**（P2）；另补无头审批路径（§六）、
> 运行账本取向（§十 P2，对应 `peer-apps-benchmark.md` §5「后台/无头运行必须显式状态机 + 终态」）。
> **实现勘误（与初稿的三处偏差）**：① §六保活表述修正——KeepAliveService 是用户可选开关、
> 并不自动挂靠生成，等待期保护来自 WorkManager 自身的 wakelock（见 §六.1）；② RUN_LATE 补跑
> 落的是**真实执行结果**（OK/FAILED/TIMEOUT），`SKIPPED_MISSED` 只给 SKIP 静默跳过用（§五）；
> ③ §九测试表的「Fake ChatService 单测」未做——无 Robolectric 且 ChatService 是具体类，
> 改为纯函数 + Fake DAO + 源码守卫三层（§九.1）。
>
> **revision 3（2026-10-30，真机事故修复）**：「后台触发但没回复、任务还显示 OK」的根因是
> **冷启动读设置拿到 `Settings.dummy()`**——进程被 WorkManager 拉起后几百毫秒内
> `settingsFlow.value/first()` 仍是全默认配置（`providers` 空、`chatModelId` 随机），
> `handleMessageComplete` 的 `findModelById(...) ?: return` 于是**静默返回**：job 秒结、
> 无错误、队列空 ⇒ runner 记 OK。三处补丁：① 执行层注入前 `awaitLoaded()` 等真实设置；
> ② 执行层注入前 `ensureConversationLoaded()` 载入 DB 会话（同时堵住「在空 `Conversation.ofId`
> 上跑一轮、`saveConversation` 整行覆盖历史」的数据损坏路径）；③ 成功判定加**产出证据**
> （必须新增 assistant 消息）并把模型解析失败改成可见错误卡。详见 §六.1。
>
> **revision 5（2026-10-30，UI 事故修复）**：创建任务表单从 `AlertDialog` 改为**整页**
> （`ScheduledTaskCreatePage`）——对话框里七组字段 + 键盘必然溢出，`时/分` 贴着可见区边缘，
> 每敲一位都触发「滚动到光标」把内容上顶 18dp（真机表现为**界面抖动**）；`时/分/间隔` 同时
> 改数字键盘、`时 → 分` 用 `ImeAction.Next` 串焦点。复现数据与定位见 §7.1。
>
> **revision 6（2026-10-30，真机崩溃修复）**：新建/开关任务必崩——`armPrompt` 给唤醒作业同时
> 加了 `setExpedited` + `setInitialDelay`，而 WorkManager 的 expedited 作业**不允许带延迟**，
> `.build()` 直接抛 `IllegalArgumentException: Expedited jobs cannot be delayed`；异常发生在
> ViewModel 的 coroutine 里，用户看到「保存 → App 停止运行」（存量任务是直接写 DB 造的，
> 所以之前没暴露）。修法：抽出纯函数 `promptWorkPlan`——**已到期才 expedited 且延迟为 0**，
> 未来时刻退化为普通延迟作业；`rearm` 逐任务 try/catch 只记日志，单个排程失败不再打崩调用方。
> 回归护栏 `ScheduledTaskPromptWorkPlanTest`。真机验证：新建任务保存成功、启动时对账无异常、
> 闹钟正常落 `mode=alarmClock`。
>
> **revision 4（2026-10-30，触发层改造：闹钟 + 前台服务）**：真机验证发现第二层问题——
> 生成链路修好后，**Doze 熄屏下 WorkManager 到点不跑**（22:13:07 到点的任务直到 22:15:31
> 亮屏才执行）。设计稿初版把这条列为「已接受的 Doze 延迟」（§八.1），但用户的预期是
> 「后台就该跑」，因此把 P3 的 Alarm 档**提前落地**，形成三层冗余触发：
> ① `AlarmManager.setAlarmClock`（主路径，闹钟语义、无需特殊权限、真机 `window=0`；失败时退化
> `setExactAndAllowWhileIdle`、再退化 `setAndAllowWhileIdle`）→ 闹钟广播 →
> `ScheduledTaskRunService`（dataSync 前台服务 + partial wakelock + Wi-Fi 高性能锁）执行；
> ② per-task 一次性 WorkManager 唤醒作业（expedited，兜底）；③ 15 分钟周期 sweep（兜底）。
> 三个入口共用 `ScheduledTaskExecutor`，认领乐观锁保证不重复执行。
> 真机还暴露了代码之外的边界：OnePlus/ColorOS 的 `OplusAppStartupManager` 会丢弃后台广播
> （`SKIPPED ... reason: oplus startup`），必须在系统「应用启动管理」里手动放行——见 §6.2.1。
>
> **revision 10（2026-10-31，P2 后两块落地：运行账本 + DAILY 多时段）**：
> ① **运行账本**：`scheduled_task_runs` 新表（AutoMigration 52→53，纯新增无外键）——每轮执行
> 一行（含 SKIP 静默跳过、TARGET_GONE/ASSISTANT_GONE），`finished_at/status` 终态必达；
> `status == null` = 执行层中途死亡（进程被杀的直接证据），任务表 `next_run_at` 已推进所以
> 不卡调度。触发入口记 `source`（alarm/sweep/skip）；NEW_EACH_RUN 各行带本轮实际会话 id，
> 详情页历史行可点跳回。任务删除随清账本；per-task 保底留 60 条。② **DAILY 多时段**：
> payload 加 `extraMinutesOfDay`（当天分钟数列表，缺字段空 = 单时刻，老数据行为不变）；
> 推进语义改为「**当天剩余槽位 → 明天首槽位**」（`nextSlotOfDay` 逐日扫，单时刻退化为原语义）；
> 表单 DAILY 下主行外可加删时刻行，非法行（半填）阻塞保存。见 §四/§五。
>
> **revision 9（2026-10-31，P2 第一块落地：会话目标 + 会话内入口）**：`Conversation` 加
> 可空 `goal`——**conversations 表真实列**（逐列存储不是 JSON blob；`""` = 未设置，
> AutoMigration 51→52，真机已验证 50→51→52 连续迁移）；任务表加 `with_goal`（NOT NULL +
> DEFAULT 0，AutoMigration 50→51）。两个真机测出的持久化命门：① 目标编辑**强制落库**
> （`updateConversationGoal` → `saveConversation(force=true)`）——新聊天上写目标会被
> 「空会话不保存」拦截静默丢掉，而创建页开关按 DB 的 goal 判可用性；② 「为此会话创建
> 定时任务」跳转前同样 force 落库，否则未保存的新聊天目标预选落空。注入形态 `## 长期目标
{goal}

{prompt}`（`buildTaskPrompt`
> 纯函数 + 三用例）：开关没开、goal 为 null/空白一律原样注入。表单开关**仅目标会话 goal 非空时
> 显示**（隐藏时不动 state——详情页里用户开过开关、后来清了目标，保存保留原值而非静默改写；
> 执行层对 goal 为空本就按普通 prompt 走，挂着无害）。会话内两个入口：**目标编辑**挂在消息列表尾部
> 会话级卡片区（与系统提示按钮同形态，`ConversationGoalButton` 就地展开长文本编辑）；
> **创建定时任务**入口挂在「+」面板（与压缩/提示词预览同列的 ListItem——它和那些一样是
> 「对这条会话发起的动作」，不是聊天内容；跳转前照旧 force 落库，
> `Screen.SettingScheduledTaskCreate(conversationId)` 带参预选目标；
> prompt 刻意**不**预填模板文本——替用户写一句模板反而要再删，预选目标 + goal 开关自动出现
> 就是入口的全部便利）。
>
> **revision 8（2026-10-31，执行目标扩成三种）**：初版只支持「向一条**已有**会话注入」，实际
> 用起来有两个缺口：① 选会话的列表是全库平铺，几十条会话里按助手找不到；② 「让 AI 每轮独立
> 干活」的场景（每日简报、独立巡检）根本不想要历史，却被迫挤在同一条会话里，上下文越滚越脏。
> 因此加 `TargetMode`：**EXISTING**（老行为，注入已有会话）/ **DEDICATED**（创建任务时**新建**
> 一条专属会话，之后每轮都往里跑）/ **NEW_EACH_RUN**（每轮**新建**一条会话，历史互不影响）。
> 三条硬约束：① 目标模式是**创建时决定**的（详情页只读，与「执行锚点不可改」一致）；
> ② 存库仍是**同一张表**（`target_mode` 枚举名 + `conversation_id = ""` 作 NEW_EACH_RUN 哨兵，
> 避免 `conversation_id` 改可空触发整表重建），49→50 自动迁移给老行默认 `'EXISTING'`；
> ③ 后台新建会话必须**先插 DB 再 `updateConversation`**（否则 `saveConversation` 整行覆盖、
> 回复丢失——与 §6.1 同一条命门）。DEMO 见 §4.1。

## 一、要解决的问题

用户想让 AI 在指定时间**主动**干活：每天早八对某会话做昨日摘要、每周整理一次记忆/巡检某话题、
「今晚十点提醒我总结这段代码」。现在一切生成都必须由用户打开 App 手动发起——进程死了就什么都不会发生。

## 二、目标与非目标

**目标**

- 定时/周期任务**跨进程死亡存活**：App 被杀、设备重启后，到点仍会执行（WorkManager 语义）。
- 执行 = 向目标会话注入一条用户消息并走**完整既有生成管线**（transformer、工具、MCP、
  工作区、流式落库全部白拿），不在旁边另起一条生成通道。
- 错过的周期任务按 per-task 策略处理：补跑一次或跳过。
- 完成后通知，点进会话；失败可诊断。

**非目标（v1 明确不做，理由写在括号里）**

- **精确到点**（AlarmManager exact 档：需要 `SCHEDULE_EXACT_ALARM` 授权且中国 ROM 不可靠；
  「每天摘要」差几分钟无意义。确有需求走 §十 P3）。
- **定时提醒通知（不跑模型）**（纯 Alarm/WorkManager + 通知，与 AI 任务机制不同，需要时另立设计）。
- **cron 表达式**（daily/weekly/interval 三种已覆盖主流诉求；cron 解析是独立复杂度）。
- **无会话任务 / 全局任务**（任务必须挂在一个会话上执行——这是复用生成管线的代价，也是最简模型）。

## 三、调度器选型：单 Sweeper，不搞 per-task work

| 方案 | 做法 | 问题 |
|---|---|---|
| per-task Work | 每个任务一条 unique `OneTimeWorkRequest`，跑完自我续链 | 任务↔work 对账复杂（改名/禁用/失败重试各有边角）；链断一处就静默停摆 |
| **单 Sweeper（选定）** | **一条 15 分钟周期 Worker，到点查 Room 里 `nextRunAt <= now` 的启用任务，逐个认领执行** | 空转（见下面的开关）；粒度最细 15 分钟 |

选 Sweeper 的决定性理由：**状态只有一个权威来源（Room 表）**，App 启动时对账 = 「确保表里有启用任务时 sweeper 在排队」一行逻辑；错过/补跑/漂移全部退化为「查询条件怎么写」，没有链路可断。

**实现勘误（2026-10-30，真机实测）**：纯周期作业存在**相位不同步**——WorkManager 周期作业的
首次执行在「入队 + 一个周期」，与任务 `nextRunAt` 不同相，到点后最长要再等 15 分钟才被扫到
（实测 20:23 到点的任务要等 20:36 首扫）。修法是**周期兜底 + per-task 一次性唤醒**两层：
唤醒作业（`OneTimeWorkRequest`，唯一名含 `<taskId>:<nextRunAt>`，认领推进后旧名自然退役）
把执行对齐到到点时刻，sweep 收尾时 rearm 下一轮；唤醒与周期共用同一 Worker、同一认领语义，
Room 仍是唯一状态权威，唤醒早到/偏离/积压都退化为空扫一轮。

空转代价的收敛：**启用任务数为 0 时取消 sweeper**，创建/启用首个任务时再排上；WorkManager 唤醒本身是批量合并的，空查一次 DAO 的代价可忽略。约束挂 `NetworkType.CONNECTED`（AI 任务离线跑必失败）。

## 四、数据模型

```kotlin
// Room 实体（v1 草案）
@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey val id: Uuid,
    val conversationId: Uuid,        // 目标会话；会话删除 → 任务自动失效（§八.4）
    val title: String,               // 用户起的名（通知里显示）
    val prompt: String,              // 到点注入的 prompt
    val scheduleType: String,        // ONCE / DAILY / WEEKLY / INTERVAL
    val schedulePayload: String,     // JSON：hour/minute、dayOfWeek、intervalMinutes
    val nextRunAt: Long,             // 权威调度字段，sweeper 只看它
    val lastRunAt: Long?,
    val lastStatus: String?,         // OK / FAILED / SKIPPED_MISSED / …（诊断用）
    val missPolicy: String,          // RUN_LATE（默认，补跑一次）/ SKIP（静默推进）
    val idleOnly: Boolean = false,   // idle 门控（purrcat A4.1）：目标会话有进行中生成则本轮让路
    val withGoal: Boolean = false,   // 目标语义（purrcat A4.2，P2）：注入「会话目标 + 本次 prompt」
    val enabled: Boolean = true,
    val createdAt: Long,
    // revision 8（DB v50）：执行目标三态
    val targetMode: String = "EXISTING",  // EXISTING / DEDICATED / NEW_EACH_RUN（列默认值 'EXISTING'）
    val assistantId: Uuid? = null,        // DEDICATED / NEW_EACH_RUN 用：新建会话绑哪个助手
    val lastConversationId: Uuid? = null, // NEW_EACH_RUN 上一轮建出来的会话，详情页「查看最近一次会话」
)
```

> `conversationId` 对 `NEW_EACH_RUN` 存**空串哨兵**（不是 null）：那类任务没有「目标会话」，
> 但把列改成可空要整表重建，收益不抵风险。空串在读路径一律按「无已有会话」处理，真实历史
> 落 `lastConversationId`（每轮覆盖）。

**会话目标的存放位置**：`Conversation` 模型加一个可空 `goal: String?`（长期目标，用户在聊天页
编辑），**不落任务表**——目标是会话的属性，多个任务可以共享同一份。`withGoal = true` 的任务
注入时把目标拼在 prompt 头部（见 §六）。

**不在表里的东西（刻意）**：助手快照（模型/温度等）。任务执行用**会话当前绑定的助手设置**——
用户改了助手的模型，预期是「之后的定时任务也用新模型」；快照反而违背直觉。真需要冻结再谈。

`nextRunAt` 的推进规则（**认领时**原子推进，不是跑完后）：

- ONCE：认领即置 `enabled=false`（防双跑的兜底）；
- DAILY/WEEKLY：从 `nextRunAt` 起算**下一个**严格大于 now 的时刻（连续错过多次不会连环补）；
- INTERVAL：`nextRunAt += interval`，若已落后超过一个周期则对齐到 now（避免追帧式连环触发）。

### 4.1 执行目标三态（revision 8）

| TargetMode | `conversation_id` | 建会话时机 | 适用场景 | 列表中显示 |
|---|---|---|---|---|
| `EXISTING` | 选中的会话 id | 不建（老行为） | 每天往同一会话里补一条 | 会话标题 |
| `DEDICATED` | 保存时**新建**的会话 id | **保存时**（用户在场，失败可当场报错） | 长期主题连成一条线、上下文要累积 | 「标题 · 专属新会话」 |
| `NEW_EACH_RUN` | `""`（哨兵） | **每轮执行时** | 日报/巡检这类「每轮独立、不要历史」 | 「每轮新建 · 助手名」 |

要点：

- **创建时决定，之后只读**：与「执行锚点不可改」同一条设计线——改目标等于换任务，不如重建。
  老库行由迁移给默认 `'EXISTING'`，`targetModeOrDefault()` 对未知/空值一律回退 `EXISTING`，
  保证「宁可按老行为跑，也不因新字段读不出而停摆」。
- **每轮新建的标题带触发时刻**（`scheduledRunConversationTitle` → `"{任务名} · MM-dd HH:mm"`，
  任务名为空则只留时刻）：否则会话列表里会出现一串同名行、分不清哪条是哪一轮。
- **每轮新建的会话内容**：`Conversation.ofId(..., newConversation = true)` +
  `updateCurrentMessages(assistant.presetMessages)`，与「新建聊天」同款初始状态（预设消息不丢）。
- **顺序铁律**：先 `insertConversation()` 落库，再 `updateConversation()` 设内存会话状态；
  反过来会让 `saveConversation` 在生成结束时**整行覆盖**（§6.1 的真机事故同源）。
- **助手被删**：`DEDICATED` 在保存时报错、不建任务；`NEW_EACH_RUN` 在**执行时**发现助手不在，
  记 `ASSISTANT_GONE` 并自动 `enabled = 0`（与 `TARGET_GONE` 区分：一个是助手没了、一个是会话没了，
  用户要修的东西不同）。
- **idle 门控（§5.1）对 `NEW_EACH_RUN` 不适用**：它没有「目标会话」可忙，若照样套用会让这轮
  永远被让路。执行器的门控条件因此是 `idleOnly && mode != NEW_EACH_RUN && isConversationBusy(...)`。

## 五、状态机与认领（幂等核心）



```
(idle) --sweeper 原子认领--> (claimed) --注入生成--> (running) --生成结束--> (idle, lastStatus=OK)
                                   \--注入失败-->            (idle, lastStatus=FAILED)
```

认领是一条原子 UPDATE：

```sql
UPDATE scheduled_tasks SET next_run_at = :advanced, last_run_at = :now
WHERE id = :id AND enabled = 1 AND next_run_at = :seenNextRunAt
-- rowCount == 1 才算认领成功
```

用「`nextRunAt` 乐观锁」而不是单独 status 列：**不需要状态回滚**——认领即推进，Worker 中途被杀
（进程死亡/系统回收）任务也不会卡死在中间态，顶多这一轮丢了，下个周期照常。这比
`claimed→running→回滚` 的传统状态机少一整类「卡在 claimed 永不执行」的故障。

错过判定（sweeper 查询时逐条判）：

- `now - nextRunAt <= GRACE_WINDOW`（默认 6h）→ 正常执行；
- 超窗且 `missPolicy = RUN_LATE` → 照常执行一次，`lastStatus` 落**真实执行结果**
  （OK/FAILED/TIMEOUT——「跑晚了」从 `last_run_at` 与 `next_run_at` 的差可见，不再盖一个混淆语义的标记）；
- 超窗且 `SKIP` → 不执行，直接推进（`lastStatus = SKIPPED_MISSED`）。

### 5.1 idle 门控（purrcat A4.1）

per-task 可选（`idleOnly = true`）：目标会话**有进行中生成时本轮让路**——sweeper 在认领**之前**查
该会话的生成状态（`ChatService` 的 sessions 活跃 Job），忙则**不认领、`nextRunAt` 不动**，等下一个
sweep 周期再试。默认 `false`：走 `sendMessage` 的 FIFO 排队（消息排在用户当前轮次之后，正确性
不受影响，代价是排队而非择时）。

两条细则：

- **门控只在宽限窗内生效**：超窗后（错过判定已判 RUN_LATE）回退 FIFO 注入——保证周期任务
  「最坏情况下与默认行为一致」，不会因为会话一直忙而无限顺延到永远不跑。
- **让路不是丢任务**：不认领就没有任何状态变更，幂等性（§五认领）不受影响；ONCE 任务同样
  适用（忙则等下个 sweep，直到超窗按错过策略收口）。
- **`NEW_EACH_RUN` 不参与门控**（revision 8）：它每轮新建会话，没有「目标会话正忙」这回事；
  照套门控等于永远让路。执行器条件是 `idleOnly && targetMode != NEW_EACH_RUN && isConversationBusy(cid)`。

## 六、执行层：只做「注入」，生成走老路

Worker 单飞后对每个认领的任务：

1. 解析目标（revision 8，`targetModeOrDefault` 未知值回退 EXISTING）：
   - `EXISTING` / `DEDICATED`：会话仍存在、任务仍 enabled（认领后可能被用户禁用）；
     会话不在库里 ⇒ `TARGET_GONE` + 自动停用；
   - `NEW_EACH_RUN`：按 `assistantId` 找助手（`assistantId` 也允许为空，此时用设置里的默认助手），
     助手不在 ⇒ `ASSISTANT_GONE` + 自动停用；在则 `ChatService.createTaskConversation()` 建会话
     （标题 `"{任务名} · MM-dd HH:mm"`），建成功立刻 `recordLastConversation()`，再继续注入；
2. 组装 prompt：`withGoal = true` 且会话 `goal` 非空时，注入形态为
   `## 长期目标\n{goal}\n\n{task.prompt}`——多次自唤醒围绕同一目标（purrcat A4.2：这是
   「主动 agent」与「定时 prompt」的分界）；goal 为空则按普通 prompt 走，不报错；
3. `ChatService.sendMessage(conversationId, listOf(TextPart(prompt)))` —— **复用既有入口**：
   队列 FIFO 自然处理「用户正在这个会话聊天」的并发（任务消息排队，不插队、不调用
   `sendInterrupting`——定时任务没有打断用户当前轮次的资格）；
4. 等结束：**不能等 `generationDoneFlow`**——它的 emit 全部在生成 job 体**内**发出
   （`ChatService.kt` 五处），等信号时 job 还没结束。实际判定是
   `combine(getGenerationJobStateFlow, getMessageQueueFlow)` 上等
   `job == null && 队列空`（sendMessage 同步入队+派发，首个观测必然是活跃态，不会把
   「还没开始」误判成「已结束」）。超时上限 10 分钟（LoopGuard 常量同款取舍：先证明需要
   再开放配置）；超时按失败记，但**不取消生成**（消息还在老管线里正常收尾落库）；
5. 发完成/失败通知（直接 `sendNotification` 走 `chat_completed` 渠道，deep link 进目标会话；
   `ChatNotificationManager` 的事件驱动形态不需要也不应该被改）；生成错误已由老管线走
   `ChatService.addError` 错误卡通道，runner 只做「注入后有无新增错误」的比对定 FAILED。

进程保活（**revision 4 后已改写**）：KeepAliveService 仍是用户可选开关、不自动挂靠定时任务；
定时任务自己的存续由 §6.2 的「触发层三层冗余 + 执行期前台服务」保证。原「Worker 不另起前台服务」
的结论作废——真机证明不挂 FGS 时后台生成会被 cached-app freezer 冻结。

### 6.2 触发层与执行载体（revision 4，2026-10-30 真机补充）

触发层现在是**三层冗余**，全部收敛到 `ScheduledTaskExecutor`（唯一执行实现）：

| 层 | 载体 | 时机 | 作用 |
|---|---|---|---|
| 主 | `AlarmManager.setAlarmClock`（闹钟语义，无需特殊权限）→ `ScheduledTaskAlarmReceiver` → `ScheduledTaskRunService` | 精确到点（真机 `window=0`） | 准点性来源；闹钟广播自带临时豁免，允许后台 `startForegroundService` |
| 主（降级） | `setExactAndAllowWhileIdle`（需「闹钟和提醒」授权）→ 同上 | 精确/被系统窗口化 | `setAlarmClock` 意外失败时兜底；真机实测此档会被放进 ~67–90s 窗口批处理 |
| 兜底 1 | per-task 一次性 WorkManager 唤醒作业（到期即跑时 expedited；未来时刻只能普通延迟，见 §6.2 注） | 到点 +5s | 闹钟被系统压制/丢失时仍有概率触发 |
| 兜底 2 | 15 分钟周期 sweep（WorkManager） | 每 15 分钟 | 漏槽、进程死亡、错过的维护窗口自愈 |

> 注（revision 6）：WorkManager 的 expedited 作业**不允许带 `initialDelay`**（`build()` 抛
> `IllegalArgumentException`）。所以「到点 +5s 的 expedited 唤醒」只在**已经到期**时成立
> （延迟 0 立即跑）；为未来时刻排的兜底作业是**普通延迟作业**，没有 expedited 的前台豁免
> ——这也符合定位：准点性与执行期保护由闹钟 + FGS 负责，WorkManager 只是漏网兜底。

执行载体（`ScheduledTaskRunService`，dataSync 前台服务）在生成期间同时持：

- 前台服务：进程进入 FGS 态，不被 ROM 的 cached-app freezer 冻结；
- `PARTIAL_WAKE_LOCK`（10 分钟上限）：Doze/熄屏时 CPU 不睡，流式回包持续落库；
- Wi-Fi 高性能锁：避免休眠间隙 Wi-Fi 降档断流（与 KeepAliveService 同款）。

去重：闹钟、兜底作业、周期 sweep 可能同时醒（甚至同一任务被两个入口同时查到）。`claim()` 的
乐观锁（`WHERE next_run_at = :seen`）保证只有第一个入口能执行，其余空跑；执行器不假设任何
入口优先级。

#### 6.2.1 真机边界：OnePlus/ColorOS 的「应用启动管理」会掐掉后台启动（2026-10-30 实测）

实测序列（PJX110 / ColorOS 15 / Android 15）：

| 时刻 | 现象 | 证据 |
|---|---|---|
| 22:34:48、22:40:59 | `setExactAndAllowWhileIdle` 闹钟被放进 ~67–90s 窗口，**窗口结束才投递** | `dumpsys alarm` 的 `windowLength=67481/89788`，`maxWhenElapsed` 晚 ~1 分钟 |
| 22:36:12、22:40:59、22:44:43、22:53:46 | 即使 `setAlarmClock` 精准到 0ms 投递，广播仍被 **SKIPPED** | `dumpsys activity broadcasts`：`SKIPPED terminal ... reason: oplus startup`；logcat：`W/OplusAppStartupManager: prevent start ... by broadcast system[alarmManger]` |

结论：**闹钟层是对的（`window=0`），但 ROM 在「投递 → 启动进程」这一步拒绝**。这是
`OplusAppStartupManager`（设置 → 电池 → 应用启动管理）的配置，App 侧无法绕过；加入 Android
的电池优化白名单（`dumpsys deviceidle whitelist +pkg`）也**不解除**这一层。国产 ROM 的同款
拦截（MIUI/HyperOS 自启动、vivo 后台启动、华为自启管理）在语义上等价。

因此 v1 的诚实口径（也是设置页提示条要表达的）：**用户必须把本应用加入 ROM 的「自启动 /
后台活动」白名单**，否则熄屏后台不会执行；这不是代码缺陷，也不是本 App 能自动授予的权限。
设置页对 ColorOS/realme UI 尝试直接打开
`com.oplus.battery/com.oplus.startupapp.view.StartupAppListActivity`（实测该页由
`oplus.permission.OPLUS_COMPONENT_SAFE` 保护，普通应用跳转会被拒 → 自动回退到应用详情页，
用户从「电池/自启动」入口进入）。

#### 6.2.2 已验证可行的组合：常驻前台服务（保活开关）+ 熄屏

同一台机器上，把 App 自身的保活开关打开（[KeepAliveService] FGS 常驻，进程状态
`PROC_STATE_PERSISTENT`）后，闹钟广播**能正常投递给已在运行的进程**，整条链路在熄屏无操作
下跑通（2026-10-30 23:04 实测，任务 `KillTest`，目标会话不在前台）：

```
23:04:11.016 AlarmManager: sending alarm ... windowLength 0
23:04:11.027 ScheduledTaskAlarm: alarm fired: task=1dbb5d02-...
23:04:11.039 ActivityManager: Background started FGS: Allowed ... uidState: PER;
           code:PROC_STATE_PERSISTENT; tempAllowListReason:<SYSTEM_ALLOW_LISTED>
23:04:11.085 ScheduledTaskExec: alarm: 1 due
23:04:11.115 ScheduledTaskExec: alarm: claim task=KillTest -> true
23:04:11.165 ScheduledTaskRunner: runClaimed: inject task=KillTest (settingsLoaded=true, assistantsSeen=4)
23:04:20.220 ScheduledTaskRunner: runClaimed: task=KillTest -> OK (producedReply=true)
```

结论（对用户的可操作建议，按可靠性排序）：
1. **开启保活开关**（设置 → 通用/保活）：进程常驻时 ROM 不拦广播，熄屏也能执行；
2. 同时把 App 加进 ROM 的「应用启动管理 / 自启动 + 后台活动」与「电池优化白名单」：
   即使某次进程被清理，下一次闹钟仍有机会把进程拉起来；
3. 只开保活不白名单：进程被杀后的那一轮会丢（ROM 拦截启动），下一轮保活重连后恢复。

因此设置页定时任务页顶部按此顺序给提示条：通知权限 → 电池白名单 → 应用启动管理 →
**保活开关（一键开启）**。

权限与降级（设置页定时任务页顶部提示条直达）：

- `POST_NOTIFICATIONS` 缺失 → 完成通知静默（不影响执行）；
- 未加入电池优化白名单 → Doze 仍可能压制作业/网络（功能可用，准点性下降）；
- 未授予「闹钟和提醒」（Android 12+ `SCHEDULE_EXACT_ALARM`）→ 精确档退化不精确档
  （Doze 仍放行，系统可能挪动几秒到几分钟）；服务启动被 ROM 拒绝时接收器退化到一次性兜底作业；
- 未加入 ROM 的「应用启动管理」白名单 → 闹钟能唤醒 CPU、但广播被 ROM 丢弃，任务不执行
  （§6.2.1，必须在系统设置里手动放行；**开着保活可绕过这一层**，见 §6.2.2）。

### 6.1 冷启动的两个前提（2026-10-30 真机事故）

真机症状：任务到点、后台确实触发（FGS 通知可见），**会话里只有注入的 user 消息、没有任何
assistant 回复**，管理页 `last_status` 却显示 OK。

根因链（三步，缺一环都不至于如此）：

1. 进程被 WorkManager 冷启动，`settingsFlow` 起手是 `Settings.dummy()`（`init = true`）：
   `providers` 是内置空表、`chatModelId` 是 `Uuid.random()`、`assistants` 是内置默认助手。
   执行层用这个快照注入，生成侧 `settings.findModelById(...)` 必然返回 null。
2. `handleMessageComplete` 那一行 `?: return` 是**静默返回**：job 秒结、不写错误、队列清空。
3. runner 的成功判定只看「job 空闲 + 队列空 + 无新增错误」⇒ 记 OK、发「完成」通知。

同一条路径上还藏着第二个隐患：runner 注入前**没有载入会话**。`getOrCreateSession` 会给一个
磁盘上不存在的空 `Conversation.ofId`，`sendQueuedMessage` 随后 `saveConversation` 按这个
空内存态整行写回 ⇒ 目标会话的历史、标题、工作区、注入绑定一起被抹掉（目标会话不在前台时
必现）。这次真机恰好是新建空会话，所以只表现为「没回复」，但数据损坏是同一根因。

修法（三处，均在 P1 内补齐）：

- **等真实设置**：执行层注入前 `SettingsStore.awaitLoaded()`（`settingsFlow.first { !it.init }`，
  与 `MediaCreationService` 同一个口子；runner 侧 30s 超时兜底）。
- **先载入会话**：`ChatService.ensureConversationLoaded()`——**只在 session 缺席时**从 DB 载入。
  刻意不复用 `initializeConversation`：那条路径无条件用 DB 覆盖内存态，而「待审批工具」时
  job 为 null（生成中守卫挡不住），非用户发起的入口没有资格重置用户正在看的会话。
- **产出即证据**：`evaluateRunStatus()` 把「新增了 assistant 消息」列为 OK 的必要条件；
  同时把 `handleMessageComplete` 的模型解析失败改成**可见错误卡**
  （`agent_run_error_no_model`），runner 据新增错误记 FAILED。

诚实边界：`ChatService.awaitSettings()` 15s 超时后仍退回 dummy——此时得到的是「未配置模型」
错误卡而非无限等待（fail-visible 取舍）；DataStore 长期读不出来属于另一类故障，设置页会
表现为空白。

**无头审批路径**（upstream-analysis「定时任务是生成发起方入口之一，需无头会话审批策略」的回应）：
任务会话里触发工具/子代理审批时，**走既有通知审批通道**（`SubagentApprovalReceiver` 的
通知栏「批准/拒绝」按钮）——定时任务的审批不是新问题，是既有无头审批能力的一个新入口。
通知被错过时按各审批点的既有超时/跳过语义收尾，不加特殊分支。

**执行环境的诚实边界**：定时任务跑在后台，MCP/工具/工作区都能用，但**任何依赖「屏幕在前台」的
交互都会退化**（如 ask_user 工具）——这些在后台等不到人答，按各自既有超时/跳过语义
收尾。文档不为此加特殊分支，行为与「用户发完消息立刻锁屏」一致。

## 七、UI（v1 最小面）

- **管理页**：设置里新增「定时任务」页——列表（标题 / 目标 / 下次运行 / 上次状态）、
  开关、删除；新建表单 = 选目标 + 标题 + prompt + 周期（每天 HH:mm / 每周周几 HH:mm / 每 N 小时）
  + 错过策略 + **仅空闲时执行**开关（`idleOnly`，默认关）。
- **执行目标选择器**（revision 8）：表单首项是 `SegmentedPill`（已有会话 / 专属新会话 / 每轮新建）
  + 一行随模式变化的说明；下面是**目标拥有者**按钮：`已有会话` 打开会话选择器，
  另两种打开**助手**选择器。选会话的列表**按助手分组**（`groupConversationsByAssistant`：按
  助手顺序分节、节头是助手名，助手已删的会话归到末尾「未知助手」组）——原来全库平铺、
  几十条里根本找不到目标。保存按钮的可用性 = 目标就绪（EXISTING 需选会话，其余需选助手）∧ 表单合法。
  保存瞬间目标已被删（竞态）不是静默 no-op：`TargetFailure` 回落到目标区下方一行错误
  （复用「会话不存在 / 助手已删除」文案）——按钮点了必须有反馈。
- **新建 = 整页**（revision 5 改写，原为 `AlertDialog`）：七组字段 + 键盘在对话框里放不下，
  真机表现为**内容抖动 + 输入失焦**（§7.1）。现改为与详情页同构的整页表单
  （`ScheduledTaskCreatePage`）：`Column(imePadding().verticalScroll())`、顶栏「保存」动作、
  会话/助手选择器仍是小对话框（内容短，不受影响）。
- **详情页目标只读**（revision 8）：显示 `targetDescription`（会话标题 / 「标题 · 专属新会话」/
  「每轮新建 · 助手名」，目标缺失时给「会话已删除」「助手已删除」）；
  `NEW_EACH_RUN` 且有过运行记录时，多一个「查看最近一次会话」按钮直达 `lastConversationId`。
- **时刻字段**（revision 5）：`时` / `分` / `间隔` 一律 `KeyboardType.Number`；`时` 的
  `ImeAction.Next` 直接落到 `分`，`分` 的 Done 收起键盘。
- **删除二次确认**（revision 7）：列表里垃圾桶原来一点即删（任务 + 排程不可恢复，误触代价高），
  改为 `AlertDialog`：标题「删除定时任务？」+ 不可恢复说明 + 任务名 + 取消/确认（三语文案）。
- **状态文案**（revision 8）：`TARGET_GONE`（会话没了）与 `ASSISTANT_GONE`（助手没了）分开显示，
  两者都会自动停用任务——用户要修的东西不同，不能合并成一个「目标不存在」。
- **会话内入口（P2）**：会话菜单「为此会话创建定时任务」，prompt 表单预填；
  同页加**会话目标**编辑入口（`Conversation.goal`，长文本），任务表单出现「附带长期目标」开关
  （`withGoal`，仅该会话 goal 非空时可选）。
- 字符串走 `locale-tui` 全量三语。

### 7.1 真机事故：对话框里的表单「抖 + 失焦」（2026-10-30）

症状（PJX110 / ColorOS 15，创建任务对话框里输入「时」）：每敲/删一位数字，**整块表单上跳
约 18dp**（截图逐帧对比实测 63px @560dpi），有时焦点从「时」跳到「分」。

复现与定位：

- 键盘弹起后对话框内容比可见区高，`时/分` 行**正好落在可见区边缘**；每敲一位触发
  Compose 的「滚动到光标」把内容整体上顶（63px/18dp），敲「名称」等靠上的字段则完全不抖
  （逐帧对比 `dy=0`）⇒ **不是输入框本身，而是「内容比可见区高 + 光标贴边」引发的滚动补偿**。
- 对话框窗口本身不移动（`dumpsys window`：`frame=[72,140][1192,2618]`、`Requested h=2478`
  在空/有值两态一致），抖的是**内容**（居中 wrap-content 对话框 + 键盘 inset 的共同结果）。
- 附带发现：`take(2)` 截断会让 IME 的一次按键回调两次（日志 `raw='817'` → `raw='81'`），
  文字在截断瞬间闪一下；文本键盘（Gboard 自动纠错/联想条）还会放大这类抖动。
- 「失焦」的确切成因未能完全复现（ADB 注入的 `input text` 只复现了滚动抖动；真机手打时
  偶发焦点从「时」到「分」），但**抖动**与**焦点在同排两框间跳**有共同前提：表单被塞进
  高度受限、内容溢出的对话框。故修复方式是把创建表单改成整页，而不是给两框打补丁。

修复后回归要点（真机自测）：新建任务整页可滚到底、键盘不挡「时/分」与保存；`时` 敲完按
键盘「下一步」跳到 `分`；`间隔` 出数字键盘；详情页同样 `imePadding()`。

## 八、诚实声明的边界

1. **准点性分档**（revision 4 改写）：闹钟主路径在授予「闹钟和提醒」后到点误差 ≈0，Doze 也
   投递；未授权时退化为不精确档（系统可能挪动几秒到几分钟）。电池优化白名单缺失时
   Doze/厂商省电仍可能压制作业与网络，准点性下降。周期兜底仍是 15 分钟粒度。
2. **进程死亡窗口**：认领后、`sendMessage` 前被杀 → 这轮丢失（不补）。accept：概率低、
   后果是「少跑一次周期任务」，不值得引入执行日志回放。
3. **对话树增长的副作用**：周期任务持续往一个会话注入消息，上下文会持续膨胀——
   自动压缩（`design-auto-compaction`）已有闸门，任务 prompt 建议模板里自带
   「只总结增量」类指令；文档不在此展开。
4. **会话删除**：任务指向的会话被删 → 执行时校验失败，任务自动 `enabled=false` 并
   `lastStatus = TARGET_GONE`（不是静默消失，用户在管理页能看到原因）。
5. **备份**：v1 不进备份 manifest（表是新 schema，备份设计文档需要另议增补）——P3 处理。
6. **中国 ROM 强杀 / 启动拦截**（revision 4 改写）：闹钟唤醒 → 前台服务 + wake lock 保护执行期；
   但 **ROM 的「应用启动管理」会直接丢弃后台启动请求**（ColorOS 实测 `SKIPPED ... reason:
   oplus startup`，§6.2.1），这一层只能由用户在系统设置里放行；此外用户手动强行停止 App /
   ROM 深度清理仍会掐断当轮（与所有后台任务一致）。设置页给「电池优化白名单」「应用启动管理」
   两条引导，降低被压制的概率。
7. **每轮新建 = 会话数量线性增长**（revision 8）：`NEW_EACH_RUN` 每跑一轮多一条会话，
   一天一次的任务一年就是 365 条（`DEDICATED` / `EXISTING` 不会）。这是「历史互不影响」的
   必然代价，v1 不做自动清理（自动删用户数据风险更大）；标题带时刻至少让它们在列表里可辨、
   可批量删。若日后要「保留最近 N 条」再单开一轮设计（需要运行账本才能安全删）。

## 九、测试策略（P1 实际落点）

仓库无 Robolectric，Room SQL 与 Worker 装配进不了 JVM 单测——分三层兜底，SQL 侧由
Room 编译期校验 + 真机验证兜底：

| 覆盖点 | 方式 |
|---|---|
| `nextRunAt` 推进：DAILY 跨天/跳过错过天、DST 墙钟不漂、WEEKLY、INTERVAL 落后对齐、ONCE | `ScheduleMathTest`（纯函数，含 New York DST 用例） |
| 错过窗口：≤GRACE 执行 / 超窗 RUN_LATE / SKIP | `ScheduleMathTest` |
| 认领幂等（二次认领被拒）、ONCE 认领即禁用、disabled 行不认领、due 过滤、create 首槽位 | `ScheduledTaskRepositoryTest`（Fake DAO 复刻 `WHERE next_run_at = :seen` 语义） |
| 执行只许 `sendMessage` 禁 `sendInterrupting`；idle 门控必须先于认领且跳过 `NEW_EACH_RUN`；worker / 执行服务都必须委托 `ScheduledTaskExecutor` | `ScheduledTaskGuardTest`（源码守卫，仿 `TurnBackgroundTaskCancelGuardTest`） |
| 注入前必须先等设置（`awaitLoaded`）再载入会话（`ensureConversationLoaded`）；静默 no-op 不得记 OK | `ScheduledTaskGuardTest`（源码守卫） |
| 目标模式解析：空/未知值回退 `EXISTING`（老库行不得因新列读不出而停摆）；枚举**存库名稳定**（改名即破坏 49→50 迁移后的数据） | `ScheduledTaskTargetTest`（纯函数） |
| 会话按助手分组：按助手顺序分节、助手已删的会话进「未知助手」组且不丢、空输入不产节 | `ScheduledTaskTargetTest`（纯函数） |
| 每轮新建的标题：任务名 + `MM-dd HH:mm`、空任务名只留时刻、不同轮次不重名 | `ScheduledTaskRunTitleTest`（纯函数） |
| 触发层接线：主唤醒必须用 `setExactAndAllowWhileIdle` 且有 `setAndAllowWhileIdle` 退化；接收器必须拉起执行服务；manifest 必须有 receiver/dataSync service/`SCHEDULE_EXACT_ALARM`；执行服务必须持 FGS + partial wakelock | `ScheduledTaskGuardTest`（源码守卫） |
| 闹钟触发时刻：未来任务 = nextRunAt+slop、已过期任务 = now+slop（不得排到过去）；requestCode 稳定且按 taskId 区分 | `ScheduledTaskAlarmTest`（纯函数） |
| 唤醒作业参数：**expedited ⇒ 延迟必须为 0**（否则 WorkManager 在 `build()` 抛异常把 App 打崩），到期即跑、未来时刻走普通延迟作业 | `ScheduledTaskPromptWorkPlanTest`（纯函数，4 项） |
| 结果判定：静默 no-op→FAILED、有新错误→FAILED、超时→TIMEOUT | `ScheduledTaskStatusTest`（纯函数） |
| Worker 端到端（注入→等→超时）、Room claim SQL 本身、闹钟真实投递/Doze 行为 | 未自动化——真机验证兜底（P1 验收项） |

## 十、分期

- **P1（本设计范围）**：Room 表 + sweeper + 认领/错过语义 + idle 门控 + 执行注入 + 完成通知 +
  设置页最小管理面。
- **P2**：~~会话内创建入口 + 会话目标~~（**已实现** revision 9，2026-10-31）；
  ~~运行账本~~ 与 ~~DAILY 多时段~~（**已实现** revision 10，2026-10-31）。
- **P3（另立设计再谈）**：备份集成、cron-lite、
  无会话全局任务（需要独立执行环境，解决 §二 非目标最后一条的架构前提）、
  收尾闸门限定接入（purrcat A1 把「定时任务会话」列为可启用场景，届时在任务维度加开关）。
  ~~Alarm 精确档~~ 已在 revision 4 提前落地（§6.2）。
