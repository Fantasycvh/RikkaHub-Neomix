# Agent 任务控制面：子代理 / 无头 run / 后台任务的三合一与改进（design-agent-task-runtime）

> 状态：**已拍板（2026-11-02）**；**P0 / P0.5 已全部落地**（`692ed04ad` / `23c977613` / `8df5a214b`），
> P1a 数据与持久化投递状态底座已落地（见附录 D）；后台派发与 notice 投递泵待接入。进度与遗留见附录 D。
> 日期：2026-11-02。**rev2**（第一版经交叉评审后重写）。
> 对象：本仓三套执行底座 + `AndyerNotFound/L3Hub-RikkaHub-Fork-WFadnf`（下称「L3Hub fork」，
> 评审时 HEAD `e5f7e196`）。
> 事实底稿：`.tmp/notes/neomix-three-systems.md`（审计）、`.tmp/notes/fork-l3hub-subagent.md`（fork 解剖）、
> `.tmp/notes/design-review.md`（评审，S1 六项已并入本文）。**file:line 以 2026-11-02 工作树为准**，
> 行号会漂移，引用时优先按「文件 + 类/函数名」定位。
>
> 与既有设计的关系：**修订** `design-subagent-context.md` §9.3 与 §10.4（附录 A），
> 其余决议保持。**拍板范围**：D1–D6 全部采纳；其中 D3（嵌套默认关）与 D6（不自动续跑父生成）
> 由用户于 2026-11-02 明确确认。

---

## 〇、结论先行

1. **三套不是同一执行器的三种实现，不能抽一个 `Runner` 把三件事塞进去。**
   子代理/无头 run 是独立上下文的模型循环；工作区后台任务是进程守护；定时任务是往既有会话注入消息。
   三者执行单元、失败语义、生命周期载体都不同。

2. **能合、也应该合的是「控制面」：** 统一任务身份与状态、准入与预算、取消与归属、审批策略、
   结果投递与通知。执行面保留三个 adapter，复用现有实现（`SubagentRunner` / `WorkspaceTaskRegistry` /
   `ScheduledTaskExecutor`）。

3. **L3Hub fork 值得学的只有投递协议**：`isSubAgentNotice` 的「系统通知」全链路、按轮/按终态的投递钩子、
   轨迹派生观测层。**不学**：子代理=会话、全权限、自动回投链、无上限、进程内缓冲。
   且它的 `FORK.md` 已与 HEAD 代码漂移（无 wait 模式、`MAX_DEPTH=2` 不是 4），移植必须以代码为准。

4. **notice 载体选 SYSTEM，不选 USER。** ai 层已有 `asDowngradedSystemMessage()`
   （`ProviderMessageUtils.kt:62-88`）把中部 SYSTEM 包成 `<system-reminder>` 再降级为 USER，
   Claude / Gemini / Responses 三条路径全走它（`ClaudeProvider.kt:381`、`GoogleProvider.kt:673`、
   `ResponseAPI.kt:515`）——这就是 fork 想在 `ChatRequestFactory` 里自建的 framing，且已随三家 provider 存在。
   本仓只需要「SYSTEM 消息 + `SubagentNotice` 注解（UI 用）+ 受影响路径守卫」，不需要新增 framing transformer。

5. **需要修订的旧决议只有「一条半」**：
   - §9.3「run 账本只做记录层」→ 升级为「记录层 + 控制面」，但**仍不抽通用 Runner、不做编排**；
   - §10.4「结果绝不事后注入」→ **只在显式 background 模式**允许，投递物是 **SYSTEM notice**、
     **默认不自动续跑父生成**。

6. **分期**：P0 只做「无新数据模型、可独立回滚」的统一视图与缺陷修复；P1a 数据+后台派发+投递泵；
   P1b 通知+任务树+ASK；P2 嵌套（默认关）；P3 后台命令并入；P4 定时任务并入。

7. **D1–D6 已全部拍板（见 §九），不再待拍板。** 其中 D3（嵌套：P1 不做，P2 最多 1 层且默认关）与
   D6（不自动续跑父生成；P2 才作为可选项 + 配额）为用户明确确认项。**P0 起实施，进度见附录 D。**

---

## 一、现状与五个结构性缺口

### 1.1 三套系统的形状

| 维度 | ① 子代理 / 无头 run | ② 工作区后台命令 | ③ 定时 AI 任务 |
|---|---|---|---|
| 执行单元 | 单协程 + `GenerationHandler.generateText`（独立 messages） | 宿主 `Process` + 采集线程 + `TailBuffer` | 任务行 → `ChatService.sendMessage` 注入会话 |
| 触发源 | 运行页手动派发 / 对话内 `dispatch_subagent` | 模型工具 `workspace_run_background` | Alarm / WorkManager / 15min sweep |
| 独立上下文 | **是** | 无模型参与 | **否**（复用目标会话管线） |
| 状态机 | `pending→running→awaiting_approval→completed|failed|cancelled` | `RUNNING→EXITED|KILLED|TIMED_OUT|FAILED`（内存） | 任务表无中间态；运行账本 6 态 |
| 取消 | `Job.cancel` + DB `CANCELLED` | 标记 + `/proc` 反查 + SIGKILL 整树 | **无取消**（`ScheduledTaskRunner.kt:68-70` 自认只停止等待） |
| 审批 | 预设 `DENY/ASK` + 进程内队列 + 通知 + 弹窗 | 工作区级 `tool_approvals` 三档 | 复用聊天路径 ⇒ **只有会话内卡片** |
| 并发 | `ConcurrentRunGate` 默认 2，`by lazy` 首读后冻结 | 全局 4 + 30min 寿命 | 无全局上限 |
| 结果去向 | 阻塞：summary+`run_id` 进父上下文；手动：只落 DB、无完成通知 | 只能轮询状态工具 | 会话消息 + 完成/失败通知 |
| 持久化 | `agent_run` 单表（transcript JSON） | **无 DB** | `scheduled_tasks` + `scheduled_task_runs` |

### 1.2 五个缺口

1. **没有共同执行单元/账本视角**：无法用一次查询回答「设备上在跑什么」。
2. **并发三套互不知情**：2 / 4 / ∞；且 `ConcurrentRunGate` 在进程内**只读一次设置**
   （`SubagentRunner.kt:106-108`），改设置不生效。
3. **取消不齐 + 孤儿**：定时无取消；子代理 run 起的后台命令 `turnOwner` 未传
   （装配点 `SubagentRunner.kt:376-382`，`ChatToolFactory.kt:158-170` 的 KDoc 已说明「null = 无归属」），
   run 取消后进程最长再跑 30 分钟。
4. **审批分叉 + 定时黑洞**：子代理有通知/队列/弹窗；定时路径只有会话卡片。
   **代码链可证**：待批工具只 `break`（`GenerationHandler.kt:1124-1129`）→ job 正常结束 →
   `ScheduledTaskRunner.kt:152-168` 判 quiesced → `:171 producedReply` 为真 → `evaluateRunStatus`
   （`:360-365`）记 **OK**，工具实际从未执行。**不是未核实，是判定路径确定**（真机未复现≠风险不存在）。
5. **结果回传两种哲学**：run 明确否决事后注入（§10.4），定时任务的整个模型恰恰是事后注入。
   合并必须先选主轴，另一条降级为「结果去向」选项，不能双写。

> **2026-11-02 实施状态**：缺口 1 的「一次查询看全设备在跑什么」（任务中心读模型 + 页面）、
> 缺口 2 的「并发闸首读冻结」、缺口 3 的「run 起的 shell 无归属 / 定时无取消」、
> 缺口 1 的「run 永远卡在 running」（启动对账）均已修，见附录 D。
> 本节保留为设计时的现状基线。

### 1.3 既有决议

| 决议 | 本文态度 |
|---|---|
| run 独立表、不复用 `ConversationEntity`（§9.1） | **保留** |
| 信封 = 装配期过滤（§9.2） | **保留并推广** |
| 满了拒绝、不排队（§12.1） | **保留** |
| 阻塞派发为默认（§10.4） | **保留** |
| 定时执行层只做注入 | **保留到 P4** |
| 工作区后台任务不持久化（workspace §二/§八） | **保留** |
| 真无头不立项（proactive §五） | **保留** |
| run 账本只做记录层（§9.3） | **修订**：记录层 + 控制面，仍不抽 Runner |
| 结果绝不事后注入（§10.4） | **部分修订**：仅 background + SYSTEM notice + 不自动续跑 |
| 嵌套暂不做（§10.7） | **保留到 P2**，默认关 |

---

## 二、对照 L3Hub fork

### 2.1 fork 的真实实现（先纠正过期文档）

- **没有 wait 模式**：一律立即返回（`SubAgentTool.kt:28-77`）；`WAIT/BACKGROUND` 枚举已被 `80cd921d` 删除。
- **嵌套不是 4 层**：`MAX_DEPTH=2`（`SubAgentRunner.kt:17-18`），主→子→孙 3 代。`FORK.md:170/174` 过期。
- **子代理 = 普通会话 + `parentConversationId`**（DB v33 三列）：会进侧栏/历史/搜索/统计/备份；
  删除父会话不级联，留悬空父指针孤儿。
- **回投链**：每轮结束 → `reportSubAgentRound` → 进程内 `pendingSubAgentResults`
  （`ChatService.kt:614`）→ 父空闲时合并成一条 `notice=true` 的 **USER 消息** →
  `sendMessage(..., answer=true)` **自动再起一轮父生成**。
- **权限**：`withAutoApproval` 把装配出的全部工具（含 MCP、破坏性 shell）的 `needsApproval` 替换为
  `{ false }`；`ask_user` 被替换成「自己决定」（`ChatToolFactory.kt:31-45,152`）。
- **恢复**：进程内缓冲、无落库、无启动恢复；进程死即断链，DB 留 `running` 僵尸。

结论：fork 是「进程内、无上限、全权限的自动回投链」；价值在投递协议，不在执行模型。

### 2.2 三张清单

| 类别 | 条目 | 理由 / 改造点 |
|---|---|---|
| **直接借鉴** | `isSubAgentNotice` 全链路：写消息时打标记、请求期 framing、UI 卡片、导出 `system`、不参与气泡分组 | 外部结果不能冒充用户；本仓用注解 + SYSTEM 载体承接 |
| **直接借鉴** | 以「轮/终态」为投递边界的钩子位置（挂生成完成回调，而不是派发时 `await` 一次） | fork 早期只 await 一次，孙代理回传触发的第 2 轮起全丢 |
| **直接借鉴** | 任务树/小窗「从轨迹派生工具调用视图」的字段组织 | 纯读、无运行态依赖；本仓用 `agent_run.transcript` + Flow |
| **直接借鉴** | 父链自派检查 + 上行迭代上限 | 纯逻辑、易单测；本仓改造成「不装配派发工具」+ 深度上限 |
| **改造后借鉴** | background 派发 + 缓冲合并投递 | 缓冲**落库**、空闲才投、失败也投、按 `run_id` 幂等、**不自动续跑** |
| **改造后借鉴** | 父指针/状态列 | 只借观测，不把子代理做成会话 |
| **改造后借鉴** | 无头执行意图 | 信封不装配 + 一律拒绝 + 可升级到人；`ask_user` 返回结构化拒绝 |
| **不借鉴** | 全权限直接执行（MCP/破坏性 shell/写文件） | 安全：子代理看不到父会话的用户约束 |
| **不借鉴** | 无并发/轮次/预算上限的自动回投链 | 双计费；每批结果自动触发一整轮父生成 |
| **不借鉴** | 结果以 USER 注入并自动续跑 | 破坏「用户看到的就是模型看到的」；压缩可能把结果总结成用户发言 |
| **不借鉴** | 子代理会话进侧栏/搜索/统计/备份 | 数据面泄漏、计费归属混乱、孤儿 |
| **不借鉴** | 进程内缓冲 + 无恢复 + 失败静默 | 进程死即丢、失败不可见 |
| **不借鉴** | 1s 轮询 DB | 电池/IO、一致性差、不可单测 |
| **不借鉴** | 过期文档当规格 | 必须以 HEAD 代码为准 |

### 2.3 notice 载体：SYSTEM vs USER（两案对比，选 A）

| | A. SYSTEM + `SubagentNotice` 注解（**选它**） | B. USER + 注解 + 请求期 framing（fork 路线） |
|---|---|---|
| provider 兼容 | 复用 `asDowngradedSystemMessage()`（`ProviderMessageUtils.kt:62-88`）：Claude/Gemini/Responses 自动包 `<system-reminder>` 降级；OpenAI Chat 原生承载 | 天然 USER，但需自建 framing，且要覆盖所有请求装配点 |
| 是否算用户发言 | **否**（role 决定） | **是**（UI/标题/轮次都要逐处加守卫） |
| 新增 framing | 无（ai 层已有） | 要新增 transformer/装配逻辑 |
| UI | 需按注解渲染卡片；SYSTEM 消息的渲染路径要补 | 按注解渲染卡片 |
| 导出 | 记 `system`（与 fork 一致） | 需按注解改导出 |
| 风险 | 尾部 SYSTEM 影响 `replyMessageId` 判定（`ChatService.kt:1800-1805`）；会话中段 SYSTEM 的真机表现未验证 | 永久污染用户历史；fork 的自动续跑是额外风险 |

**决定：A。** 理由：复用已验证的 provider 降级路径，从数据语义上就不是用户发言，守卫面更小。
实现时保留 B 作为回退（内部开关 `noticeCarrier=system|user`）——若真机上 SYSTEM 在聊天页渲染/压缩/标题
出现无法接受的行为，切 B 不改投递协议。两者都必须满足：UI 卡片、导出、Web 三端一致标记。

---

## 三、目标与非目标

### 目标

1. **一个地方看见、停下、批准**：任务中心聚合三套；会话内任务树；通知深链。
2. **长任务不阻塞对话**：显式 background 子代理；阻塞语义不变。
3. **无人值守不静默失败**：失败/取消/超时也有回投与通知；无头不出现「永久 pending」。
4. **预算与并发可解释**：设备级分池 + 树级预算；满了如实拒绝并给恢复路径。
5. **每期可独立回滚**：开关关闭时行为回到上一期（不承诺「逐字节一致」，见 §七 P0）。

### 非目标

通用工作流引擎/可视化编排；run 塞进 `ConversationEntity`；全权限自动执行；父会话自动无限续跑；
OS 级真无头；进程重启后续跑生成；WorkManager 化 LLM run；1s 轮询。

---

## 四、统一模型：控制面，不是执行器

### 4.1 五个概念

```text
TaskKey(kind, id)          // kind = AGENT_RUN | SHELL_TASK | SCHEDULED_RUN；toStorageKey() 唯一序列化
TaskState                  // PENDING | RUNNING | WAITING_APPROVAL | COMPLETED | FAILED |
                           // CANCELLED | TIMED_OUT | INTERRUPTED
ExecutionScope             // token: "turn:<token>"（对话轮）| "run:<runId>"（手动/定时/子代理）
TaskBudget                 // 设备池 + 树预算（递归子 run 只能减不能加）
DeliveryMode               // AWAIT | NOTICE | NOTIFY
ApprovalPolicy             // DENY | ASK | ALLOW_LIMITED
```

**不引入公共 `TaskExecutor` 接口。** 控制面只做观察、取消、批准、投递；执行仍由三个 adapter 各自完成。
这样满足统一视图，又不把三套失败语义塞进一个抽象。

### 4.2 数据模型

**`agent_run` v58（AutoMigration 57→58）新增 7 列**：

| 列 | 类型 / 默认 | 语义 |
|---|---|---|
| `parent_run_id` | TEXT NULL | 父 run（嵌套/编排）；顶层 null。**逻辑指针，无 FK**（沿用 `AgentRunEntity.kt:51-58` / `MemoryProposalEntity.kt:32` 的跨生命周期指针惯例） |
| `depth` | INTEGER NOT NULL DEFAULT 0 | 0 = 顶层 |
| `scope_token` | TEXT NULL | 取消/归属令牌 |
| `delivery_mode` | TEXT NOT NULL DEFAULT 'await' | `await` / `notice` / `notify` |
| `delivery_state` | TEXT NOT NULL DEFAULT 'none' | `none` / `pending` / `delivered` / `orphaned` |
| `delivered_at` | INTEGER NULL | 幂等与启动补投 |
| `cancel_reason` | TEXT NULL | 人读；与 `error` 机器前缀分开 |

**v58 作业清单（缺一项就会迁移失败或备份回归）**：

1. 实体上写 `@ColumnInfo(defaultValue = …)`（先例 `ScheduledTaskEntity.kt:85`），否则 AutoMigration 生成失败；
2. 导出 `app/schemas/.../58.json`；
3. 同步 `AppDatabase.SCHEMA_VERSION = 58`（`:233-239` 注释明写「曾漂移」）；
4. `SchemaVersionConsistencyTest` 更新；
5. 备份是**整库文件**（`BackupArchiveEngine.kt:49` BackupItem.DATABASE），旧备份恢复要走 staged 校验与
   降级隔离（`RikkaHubApp.kt:253/300/362-364`）——回归必须覆盖「旧备份 → 新版本」与「新备份 → 旧版本拒绝」；
6. 回滚开关与 DB 版本**解耦**：列可留、功能开关关。

**不新建任务总表。** `TaskCenter` 是读模型聚合：

```kotlin
class TaskCenter(
    private val agentRunDAO: AgentRunDAO,
    private val scheduledTaskRepository: ScheduledTaskRepository,
    private val workspaceTaskRegistry: WorkspaceTaskRegistry,
) {
    fun observeTasks(): Flow<List<TaskCard>>          // combine 三源
    suspend fun cancel(key: TaskKey, reason: CancelReason): CancelResult
    suspend fun resolveApproval(toolCallId: String, approved: Boolean): Boolean
}
```

- **准入不放这里**：仍由 `ConcurrentRunGate` / workspace 槽位各自返回，`TaskCenter` 只聚合与出文案
  （避免出现第三套并发计数）。
- `WorkspaceTaskRegistry` 加 `snapshotFlow: StateFlow<List<WorkspaceTaskSnapshot>>`（状态变更发一次），
  不改执行语义；`observeScope` **P1 不做**，P3 需要时再加。
- `TaskCard` 不可变快照，不暴露实体/可变状态。

### 4.3 状态词表与映射

| 统一态 | `AgentRunEntity.STATUS_*` | `WorkspaceTaskStatus` | `TaskRunStatus` |
|---|---|---|---|
| PENDING | `pending` | （launch 即 RUNNING） | claim 前 |
| RUNNING | `running` | `RUNNING` | `running` |
| WAITING_APPROVAL | `awaiting_approval` | （审批在工具层） | （无） |
| COMPLETED | `completed` | `EXITED` 且 exitCode=0 | `OK` |
| FAILED | `failed` | `FAILED` / `EXITED` 且非 0 | `FAILED` |
| CANCELLED | `cancelled` | `KILLED` | `CANCELLED`（新增） |
| TIMED_OUT | `failed(error=timeout)` | `TIMED_OUT` | `TIMEOUT` |
| INTERRUPTED | **新增常量**：进程死亡/启动对账 | （注册表消失，无法判定） | `finished_at=null` 对账 |

`INTERRUPTED` 要同时进 `AgentRunEntity` 常量、UI 状态映射、统计口径；映射是**纯函数**
（`TaskStateMappings.kt`），不允许 UI 各写一套 `when`。

### 4.4 任务 ID 与通知命名空间

- 统一存储键：`agent:<uuid>` / `shell:<workspaceId>:<taskId>` / `sched:<taskId>:<runId>`；
  `TaskKey` 是唯一内存表达，只在序列化时用字符串。
- 通知 ID **不得用固定值**。现状冲突清单（一次改全，否则 P0 的「命名空间修复」仍会撞）：
  - `SubagentRunner.kt:761` 审批 `4242`；`:676-678` 无条件撤同一 id；`:685-690` `requestCode` 同值；
  - `ScheduledTaskRunService.kt:191` 与 `ScheduledTaskSweeper.kt:91` 都 `40000`；
  - `ChatNotificationManager.kt:101` 固定 `1`、`:126` live update id；
  - `MediaCreationService.kt:527` 用 `record.id.hashCode()`。
- 统一规则：`notificationId = stableHash(namespace + ":" + requestId)`；`PendingIntent.requestCode` 同理；
  **只撤自己的**；审批队列的 key **保持 `toolCallId`**（`SubagentApprovalQueue.kt:52` 已全局唯一），
  不改 `resolve(toolCallId)` 调用面（`SubagentRunner.kt:624/628-629`）——真正需要改的只是通知 id。
- 审批通知**现状已经是「一次 run 一条聚合通知」**（`SubagentRunner.kt:616-617` `registerBatch`、
  `:638-656` 单条列全部待批工具）；P0 只改 id/`PendingIntent`，不重做聚合。

---

## 五、五个关键机制

### 5.1 准入与预算：设备级分池

| 池 | 默认 | 现状 | 改动 |
|---|---|---|---|
| 模型流（agent run） | 2 | `ConcurrentRunGate(2)`，`by lazy` 冻结 | 每次 `tryAcquire` 动态读设置；暴露 `deviceLoad` 快照 |
| shell 进程 | 4 | `DEFAULT_MAX_CONCURRENT_TASKS=4` | 保持；拒绝文案统一由 `TaskCenter` 出 |
| 定时 run | 1 | 无全局上限 | P4 前用 DB claim/进程内 Mutex 限 1；入口仍允许各自触发 |

**树级预算**（P2 才需要；P1 单层时等同单 run 预算）：

```text
TaskBudget(
    maxDepth = 0,             // P1 固定 0（禁嵌套）；P2 可配 0/1
    maxRunsPerTree = 1,       // P1 = 1
    maxShellTasks = 2,
    maxTokens = run 累计 usage 上限,   // 不是 ContextBudgetPlanner 的窗口计划
    maxWallClock = run 超时（手动 30min / 预设 600s）,
    maxSteps = LoopGuard maxSteps,
)
```

- **`maxTokens` 用 `UsageTag(UsageSource.SUBAGENT, …)` 的累计值**（`SubagentRunner.kt:427-434` 已有归属），
  不用 `ContextBudgetPlanner`——后者产出的是单次调用窗口，不是可授予配额。
- 子 run 预算 = 父剩余 ∩ 自己请求；只能减不能加。
- 预算耗尽 → `FAILED(error=budget_exhausted)`，且**必须有通知/回投**。
- 满了**拒绝不排队**；错误文案给恢复路径（对齐 workspace 后台任务的既有做法）。

### 5.2 取消与归属：`ExecutionScope` 树

1. **每次执行开始必须持 scope**：
   - 对话轮发起的 run / shell task：`turn:<token>`（现有 `backgroundTaskOwner` 推广）；
   - 手动/定时 run：`run:<runId>`；
   - run 内起的 shell task：`run:<runId>`（**替代现在的 null**，落点 `SubagentRunner.kt:376-382` 装配点）。
2. **取消按 scope 级联**：`cancelScope(scope)` = 取消该 scope 下所有 run Job + SIGKILL 所有 shell task；
   run 被取消默认级联子 run 与 shell task；显式 `detached=true` 才不级联。
3. **对话轮取消**沿用现有钩子：`ConversationSession` 在 `Job` 完成回调里 `onTurnCancelled(turnOwner)`
   （`ConversationSession.kt:280/48`）——扩成 `onTurnCancelled(scope)`；`ChatService` 同时
   `killByOwner(scope)`（`ChatService.kt:3209-3219` 已有同名逻辑）。
4. **定时 run 取消（P0.5 / P4）**：
   - 给 `MessageQueue.QueuedMessage` 加 `turnOwner: String?`（`MessageQueue.kt:14-29`），
     `ChatService.sendMessage` 透传到 `sendQueuedMessage`，定时注入用 `sched:<runId>`；
   - `ScheduledRunRegistry` 持 generation job；取消走 **`cancelJobs`（`ConversationSession.kt:292`）
     + `killByOwner(runId)`**，**不要用 `stopGeneration`**——它会 `messageQueue.pause()`
     （`ChatService.kt:3253`），把 `EXISTING` 目标会话里用户自己的队列一起暂停；
   - `ScheduledTaskRunService` 已有 `ACTION_STOP`（`:193`）但全仓无发送方；接上即可。
5. **终态写入放在 `NonCancellable` 的 `finally`**；`PENDING` 超期 → 启动对账改 `INTERRUPTED`。

### 5.3 审批：能力信封 + 三档策略

| 档 | 无头行为 | 适用 |
|---|---|---|
| `DENY`（默认） | 需审批工具**不装配**；模型强行调用得策略终态拒绝文本 | background / 手动 / 定时 run |
| `ASK` | 暂停 `awaiting_approval`，通知 + 详情页；超时按拒绝继续 | 用户显式选择的预设 / 对话内阻塞派发 |
| `ALLOW_LIMITED` | 仅白名单只读低风险工具自动放行 | P2，需逐项授权 |

三条硬规则：

1. **未入信封的工具不装配，而不是装配后拒批**（沿用 §9.2）。
2. **无头不允许永久 pending**：`ASK` 前置「通知渠道可用 + 超时默认 5 分钟 → 拒绝继续」；
   通知权限缺失/渠道关闭 ⇒ 派发时降级 `DENY` 并记录原因。
3. **子 run 权限 ≤ 父 run**：子信封 = 父信封 ∩ 子预设请求；嵌套不得放大。

**定时任务的黑洞要按真实落点修，不能用「信封排除」糊**（定时路径没有信封）：
- 给工具装配加「无头轮」上下文参数（先例 `memoryDisabled`：`ChatToolFactory.kt:128/176/183`），
  在 `createToolSegments` 里**不装配** `needsApproval` 的工具与 `ask_user`；
- 或在 `GenerationHandler.kt:1124-1129` 的停等路径：无头轮不发 pending，直接 Deny + 通知；
- `task_manage` 的装配只看 `assistant.taskManageEnabled`（`ChatToolFactory.kt:259-266`），
  所以无头轮要么不装它、要么由任务表单显式授权——**二选一写进实现，不要再写「信封排除」**。
- `ask_user` 另有一条现状缝：它的 pending 会被 `pendingApprovalTools`（`SubagentApproval.kt:43-45`）
  当作审批请求走通知 + 5 分钟超时，语义怪异。**无头路径装配期剔除**（`runToolCatalog` 也需要过滤它，
  `SubagentTools.kt:119-124` 现在只剔 `dispatch_subagent`），别与 ASK 通道叠加两条路径。

### 5.4 结果投递：三种模式，一个投递泵

```text
AWAIT   —— 工具调用阻塞到 run 结束，结果作为工具结果返回（默认，现状）
NOTICE  —— background 子代理结束后，向父会话追加一条 SYSTEM notice 消息节点（不自动续跑）
NOTIFY  —— 手动/无父会话 run：通知 + 运行详情页（不写任何会话）
```

#### 5.4.1 唯一插入入口（S1-1）

**不要直写 DAO。** `ConversationRepository.saveMessageNodes`（`:982-1004`）按「库签名 vs 内存签名」求公共前缀，
`common < existing.size` 时 `deleteFromIndex`——notice 只在库、不在 `ChatService` session 内存态的话，
下一轮正常保存就会把它删掉。同族论证仓库自己写过：`ChatService.kt:2570-2579`。

新增 `ChatService.appendExternalNotice(conversationId, notice): AppendResult`：

1. `getOrCreateSession` + `ensureConversationLoaded`（必须走 session）；
2. `synchronized(session)`：若 `job != null` 或 `hasPendingToolApproval` → 返回 `Busy`（留在 DB `pending`）；
3. 追加 `UIMessage(role = SYSTEM, parts = [Text(...)], annotations = [SubagentNotice(...)])`
   的 `MessageNode`，`saveConversation`；
4. **不调用 `beginNewTurn()`**（不轮换 `backgroundTaskOwner`、不重置本轮护栏），
   **不过 `preprocessUserInputParts`**（`ChatService.kt:1088-1104` 会跑用户正则 + 20 万字符截断，
   notice 必须绕开或先做长度/字符收敛），**不启动生成**。

#### 5.4.2 投递泵

1. **落库先于投递**：run 终态 + `delivery_state=pending` 同事务；投递成功置 `delivered`。
2. **空闲才投递**：父会话正在生成 / 停在审批 / 上下文将满 → 留 DB；在**会话空闲、打开会话、应用启动**
   三个时机 flush。
   - 空闲谓词复用 `ChatService.kt:686-697` 的 `job != null || pending tool` 判定；
   - 「上下文将满」用 `estimateWindowTokens`（`ContextLength.kt:58`）+ `shouldAutoCompress`
     （`CompressionPlanner.kt:110`）现算，阈值公式写进实现（例如剩余窗口 < 本轮预计输出 + 2K 时先压缩再投）。
3. **合并与幂等**：同一父会话一次 flush 可合并多条（头部「N 个子代理已完成」+ 分节）；
   按 `run_id` 唯一，重复 flush 不产生第二条。
4. **失败也投递**：失败/取消/超时也发 notice（带 status/error）。无人值守时「没有消息」比「坏消息」更糟。
5. **默认不自动续跑**：父会话收到 notice 后不自动起新一轮；用户点「让主助手继续」或下一条消息自然带上。
   P2 若做自动续跑（默认关）：每条用户轮最多 1 次、上下文预算检查、拒绝环（notice→续跑→再派发→再 notice）。
6. **孤儿投递**：父会话已删除 → `orphaned`，不重试；运行详情页仍可看结果。

#### 5.4.3 受影响路径（S1-5，必须逐条守卫）

notice 作为 SYSTEM 消息进入 `currentMessages` 后，以下路径会消费它。统一做法：**新增
`UIMessage.isSubagentNotice` 判定 + 一个 `messagesForRequest()`/`messagesForAuxGeneration()` 过滤层**，
所有旁路共用，不逐处加 `if`：

| 路径 | 现状 | 影响 | 处理 |
|---|---|---|---|
| 标题生成 | `ChatService.kt:2251-2259` `takeLast(4)` | notice 会被当用户输入 | 过滤 notice |
| 聊天建议 | `:2355` 同款按消息回写 | 同上 | 过滤 |
| 自动压缩 | `:2528-2551` | 压缩摘要可能把 notice 总结成用户发言 | 压缩时保留 notice 原文、不参与「用户意图」统计 |
| 后台无工具生成 | `:239` `backgroundTextGenerationParams` | 「无工具旁路契约」不可破坏 | 过滤 notice 或显式允许（二选一，写测试） |
| 回复定位 | `:1800-1805` `replyMessageId` 取末条 ASSISTANT | 尾部 SYSTEM 不改变判定，但若 notice 是最后一条，需确认 | 加测试；必要时只在末尾无 assistant 时才追加 |
| 导出 | `Export.kt:601/868-872` | 应记 `system` | 按注解/role 输出 system |
| Web | `web-ui/app/types/annotations.ts:11` 只含 `UrlCitation`；`chat-message.tsx:590` 按注解渲染 | Web 会当用户气泡 | TS 联合类型加 `SubagentNotice` + 渲染卡片；`WebDto.kt:218` 透传 annotations 无需改 |
| 统计 | 已按 `UsageSource.SUBAGENT` 单列 | 不影响计费；只影响 UI/标题/轮次 | 明确写入设计，不重复造轮子 |

#### 5.4.4 预设级执行模式（替代工具 `background` 参数）

不在 `dispatch_subagent` 上暴露 `background`：**执行形态由预设决定**。给 `SubagentPreset` 加
`executionMode: BLOCKING | BACKGROUND`（默认 BLOCKING，Settings JSON 零迁移）。理由：
①模型不该替用户决定「阻塞还是异步烧钱」；②预设作者最清楚这个任务该跑多久；
③fork 删掉 `mode` 参数后就是一律立即返回，同样是配置侧决定。
工具描述写明「若该预设配置为后台，调用会立即返回，结果稍后以系统通知送达，不要轮询」。

### 5.5 观测与通知

1. **任务中心（P0）**：三段（Agent 运行 / 后台命令 / 定时运行），统一 `TaskCard`，支持筛选、取消、批准、
   打开详情；数据全 Flow；shell 用「有任务 2s、空闲 10s」低频轮询（沿用工作区详情页），不引入 1s。
2. **会话任务树（P1b）**：按 `parent_conversation_id` + `scope_token` 查本会话 run 与 shell task；
   数据层用 `observeRun`/`observeTasks`，不递归查库。
3. **完成通知（P1b）**：手动/后台 run 结束发通知（现状手动 run **没有完成通知**）；失败/取消也发；
   点击深链详情页；按 `TaskKey` 分配 ID，结束撤销。
4. **孤儿进程扫描（P3）**：应用启动时若注册表为空但 `/proc` 存在 `rikkahub-task-*` 后代进程 →
   标「孤儿」并报告，是否自动杀由设置决定（默认只报告，避免误杀）。
5. **通知渠道降级矩阵**：`POST_NOTIFICATIONS` 已有（`AndroidManifest.xml:11`）；权限缺失/渠道关闭时
   `ASK → DENY`、完成通知静默（任务中心仍可见），不假装成功。

---

## 六、Android 生命周期与恢复

1. **run 绑定 App 进程。** 进程被杀：`agent_run.running` 不会自己变终态；`WorkspaceTaskRegistry` 内存表消失
   （proot 子进程可能以孤儿继续跑）；审批队列清空。
2. **启动对账（P0.5）**：
   - **需要「本次进程启动时刻」载体**：仓库现在没有。`Application.onCreate` 写内存常量，
     需要持久判据时落 `settings`/DB meta；否则「`started_at` 早于本次进程启动」无法实现；
   - `running/awaiting_approval/pending` 且早于启动 → `INTERRUPTED`（含 `cancel_reason`）；
   - `delivery_state=pending` → flush；
   - 审批通知清理（点击旧通知返回「请求已失效」，不假装成功）。
3. **不承诺后台无限存活。** Android 12+ 后台 FGS 可能被拒、Doze 冻结采集线程、Android 15 `data_sync` 有时限。
   现有 `KeepAliveService`（run 期间）与 `ScheduledTaskRunService` 保留；**不新增长驻服务**。
4. **电池优化入口已存在**，不需要借鉴：权限 `AndroidManifest.xml:20`、
   `KeepAliveService.kt:68-80`（`isIgnoringBatteryOptimizations` / `batteryOptimizationIntent`）、
   设置页引导 `SettingPreferencesGeneralPage.kt:768-804`。本文只沿用，不新增。
5. **不做 WorkManager 化 LLM run**；WorkManager 仅用于定时任务的**调度触发**（现状）。
6. **恢复措辞**：能恢复的是账本与投递状态，不能恢复半截生成；UI 统一用「已中断，可重试」，
   不写「已恢复」。

---

## 七、分期落地

> 每期有开关、可独立回滚。**P0 不再声称「零行为变更」**（原稿被评审判假）：P0 的定义是
> **无新数据模型、不改既有语义、单点可回滚**；逐项列出行为差异与回滚方式。

### P0：统一视图 + 可回滚的缺陷修复

**范围**

1. `TaskStateMappings.kt` 三套状态映射 + 单测（新增 `INTERRUPTED` 常量）。
2. `TaskCenter` + `WorkspaceTaskRegistry.snapshotFlow`；任务中心页（运行列表入口迁移）。
   行为差异：新增只读页面；回滚：删路由/开关 `taskCenterEnabled`。
3. 通知命名空间修复（§4.4 全清单）。行为差异：通知 id/PendingIntent 变化；回滚：开关回旧 id 生成器。
4. `ConcurrentRunGate` 动态读设置 + `deviceLoad` 快照。行为差异：改设置立即生效（修复）；回滚：开关。
5. 审批队列/通知只撤自己的。行为差异：并行审批不再互相覆盖（修复）。
6. `SubagentRunner.kt:376-382` 装配 shell task 时传 scope（不再 null）。行为差异：run 取消会收掉它起的进程；
   回滚：开关 `killShellTasksOnRunCancel`。

**明确不进 P0**：启动对账（写库）、定时取消（改等待模型）——放 P0.5。

**验收**：既有单测全绿；映射/命名空间单测；真机并行 2 run 的审批通知互不覆盖、撤销不误撤。

### P0.5：对账与定时取消（短平快，但是行为变更）

1. 进程启动时刻载体 + `INTERRUPTED` 对账 + pending flush 的空骨架；
2. `QueuedMessage.turnOwner` 覆盖 + `ScheduledRunRegistry` + `ACTION_STOP` 接线；
3. 验收：杀进程重启无假 `running`；定时任务可停且不暂停用户队列。

### P1a：后台子代理（数据 + 派发 + 投递，开关关=现状）

1. DB v58 + DAO（`observeRunsByScope` / `observePendingDeliveries`）+ 迁移作业清单（§4.2）。
2. `SubagentPreset.executionMode`；`SubagentRunner.dispatchBackground`（复用 `executeRun`）。
3. `ChatService.appendExternalNotice` + `NoticeDelivery` 投递泵 + `SubagentNotice` 注解 + 三端标记。
4. 受影响路径守卫（§5.4.3 全表）+ 测试。
5. 失败/取消/超时也投递；父会话删除 → orphaned。
6. 开关 `enableBackgroundSubagent`（默认关）；预设 `executionMode=BACKGROUND` 才生效。

**验收（回答「后台子代理能不能用」）**：后台派发 3 分钟后完成 → 父会话出现 SYSTEM notice、无自动新生成；
父会话生成中完成 → 不打断、空闲补投；杀进程 → 重启补投 + run `INTERRUPTED`；关开关 → 与现状一致。

### P1b：通知 + 任务树 + ASK（体验层）

1. 完成/失败/取消通知（手动 + 后台 run）；任务树（Flow）。
2. 后台 run 的 `ASK`：通知渠道可用才允许，超时降级拒绝；
3. 子信封 ≤ 父信封；`ask_user` 无头剔除。

### P2：嵌套与树预算（可选，默认关）

`MAX_DEPTH` 默认 0、可配 1；子信封 = 父 ∩ 子；树预算（§5.1）；多层任务树；自动续跑（默认关 + 配额）。
**只有 P1 观测数据证明「单层不够用」才做。**

### P3：后台命令并入控制面

`WorkspaceTaskRegistry` 的 owner 改为 `ExecutionScope`（含 run scope）；scope 级联取消；任务中心显示 shell task；
孤儿进程扫描；跨类编排（agent run 起后台命令，现状已支持，补归属）。

### P4：定时任务并入（最后）

定时 run 取消/账本进任务中心；`scheduled → agent_run`（而非会话注入）作为可选项；外部触发复用同一控制面。
**P4 前不碰 `ChatService` 注入路径**（P0.5 的取消除外）。

---

## 八、测试策略

| 覆盖点 | 方式 |
|---|---|
| 三套状态 ↔ 统一态（含非法/未知） | 纯函数单测 |
| 任务/通知 ID 命名空间（并行 8 不碰撞） | 纯函数 + PendingIntent 构造单测 |
| 准入分池、动态设置生效 | 假设置 + gate 单测 |
| scope 级联（run→子 run/shell；turn→全部）；定时 cancel 不 pause 用户队列 | 假 Job + 假 registry |
| v58：实体 `defaultValue` / `58.json` / `SCHEMA_VERSION` / 旧备份恢复 / 降级隔离 | `SchemaVersionConsistencyTest` + 备份测试 |
| `appendExternalNotice` 在 session 内、并发保存不被 `saveMessageNodes` 删除 | 假 Room + 真实 save 路径 |
| notice 不进用户正则/不被 20 万截断；`beginNewTurn` 未被调用 | 单测 + 假 session |
| 4 条旁路 + 导出 + Web 的 notice 处理 | 表驱动 + TS 单测（web） |
| 投递泵：空闲/生成中/审批中/上下文将满/父会话删除/重复 flush | 假会话状态 + 内存 DAO |
| 失败/取消/超时也投递 | 终态表驱动 |
| 启动对账：假 `running→INTERRUPTED`、pending 补投 | 内存 Room + 假时钟 + 假启动时刻 |
| 通知撤销只撤自己的 | 假 NotificationManager |
| 每期开关关闭回滚 | 回归 + 快照 |
| 真机：并行审批、后台完成、杀进程重启、Doze、通知权限关闭 | instrumented / 手工清单 |

---

## 九、已拍板的决定与风险

### 9.1 决定（2026-11-02 拍板，D1–D6 全部采纳）

| # | 问题 | 决定 | 代价 / 约束 |
|---|---|---|---|
| D1 | notice 载体 | **SYSTEM + 注解**（`noticeCarrier=user` 作回退开关） | 需守 4 条旁路 + 真机验证 SYSTEM 渲染/压缩 |
| D2 | 并发单位 | **分池**（模型 2 / 进程 4 / 定时 1） | 需要设备级视图解释拒绝 |
| D3 | 嵌套 | **P1 不做；P2 最多 1 层、默认关**（用户确认） | 不引入孙代理能力；`nestingEnabled` 默认 false |
| D4 | 定时取消 | **P0.5 做**（`turnOwner` 覆盖 + `ACTION_STOP` 接线） | 改等待模型，需回归 |
| D5 | 后台命令是否进 DB | **不进**（内存 + 聚合 + 孤儿报告） | 进程死亡无法精确恢复 |
| D6 | 自动续跑父生成 | **默认不做**；P2 才作为可选项 + 配额（用户确认） | 否则双计费不可控；`autoResumeParent` 默认 false |

### 9.2 风险

1. **双计费**：background + 续跑成倍烧；缓解：默认不续跑、配额、`UsageSource.SUBAGENT` 单列。
2. **上下文污染**：notice 永久在父历史；缓解：SYSTEM 语义 + 压缩器专门处理 + 测试。
3. **提示注入**：子代理结果可能含第三方内容；缓解：系统提示声明不可信、父信封约束工具、通知卡明示来源。
4. **后台限制**：FGS/Doze/Android 15 超时；不承诺「一定跑完」。
5. **进程死亡**：账本可对账、生成不可恢复；UI 说「已中断」。
6. **通知疲劳**：完成 + 审批 + 任务中心；一次 run 一条聚合通知；前台可见不发完成通知。
7. **回归面**：三套都改取消/审批/通知；每期必须有「开关关闭 = 上一期行为」门禁。

---

## 十、明确不做

- 通用工作流引擎、DAG 编排、可视化节点；
- run 塞进 `ConversationEntity`，子代理会话进侧栏/搜索/统计/备份；
- 全权限自动执行；`ask_user` 闭环成「自己决定」；
- 父会话自动无限续跑；无配额的后台回投链；
- OS 级真无头；WorkManager 化 LLM run；进程重启后续跑生成；
- 1s 轮询、1s 递归查库。

---

## 附录 A：与既有设计文档的冲突与修订

| 文档 | 原文/位置 | 处理 |
|---|---|---|
| `design-subagent-context.md` | §9.3「只做记录层，不做通用任务框架」（:246） | **修订**：记录层 + 控制面；仍不抽通用 Runner、不做编排 |
| 同上 | §10.4「不采用先回 run_id、结果稍后再注入」（:340-341） | **部分修订**：仅 `BACKGROUND`；SYSTEM notice；默认不续跑 |
| 同上 | §10.7 嵌套暂不做（:386） | **保留**；P2 才议，默认关 |
| 同上 | §11 ASK、超时即拒 | **保留并推广**：后台默认 DENY；ASK 前置通知渠道 |
| 同上 | §12 并行上限 2、满即拒 | **保留**；补动态读设置与设备视图 |
| `design-workspace-background-tasks.md` | 不持久化/不跨进程/无通知 | **保留**；P3 只加孤儿报告/可杀 |
| `design-scheduled-ai-tasks.md` | 执行层只做注入；任务挂会话 | **保留到 P4** |
| `design-proactive-task-management.md` | §五 真无头不立项 | **保留** |
| 同上 | §3.2 无头走通知审批（:108-109，未实现） | **修正**：无头轮装配期剔除审批工具 + 停等路径 Deny；通知作为可选升级 |

## 附录 B：关键事实来源

- 本仓：`.tmp/notes/neomix-three-systems.md`（A–F 六节）。
- fork：`.tmp/notes/fork-l3hub-subagent.md`（数据/派发/回投/权限/任务树/清单）。
- 评审：`.tmp/notes/design-review.md`（S1 六项 + 事实错误）。
- 代码锚点：`SubagentRunner.kt`、`SubagentTools.kt`、`AgentRunEntity.kt`、`SubagentApprovalQueue.kt`、
  `ConcurrentRunGate.kt`、`ContextBudgetPlanner.kt`、`DenialGuard.kt`、`WorkspaceBackgroundTasks.kt`、
  `ProcessSignals.kt`、`WorkspaceTools.kt`、`ScheduledTaskRunner.kt`、`ScheduledTaskExecutor.kt`、
  `TaskManageTools.kt`、`MessageQueue.kt`、`ConversationSession.kt`、`ChatService.kt`、
  `ProviderMessageUtils.kt`、`UIMessageAnnotation.kt`、`Export.kt`、`web-ui/app/types/annotations.ts`。

## 附录 C：回滚开关矩阵

| 开关 | 默认 | 关闭后 |
|---|---|---|
| `taskCenterEnabled` | 开 | 隐藏任务中心页，旧运行列表保留 |
| `namespacedNotifications` | 开 | 回旧固定 id/PendingIntent |
| `dynamicRunGate` | 开 | gate 回到 `by lazy` 首读 |
| `killShellTasksOnRunCancel` | 开 | run 取消不再收 shell task |
| `enableBackgroundSubagent` | **关** | 无后台派发；投递泵不消费 |
| `noticeCarrier` | `system` | 切 `user`（回退载体，不改投递协议） |
| `autoResumeParent` | **关** | 不自动续跑（P2 才有） |
| `nestingEnabled` | **关** | 深度恒 0 |

**i18n**：任务中心/notice 卡片/停止按钮需三语 strings；按 AGENTS.md「未明确要求可先不做」，
实现时至少给 key 清单（`task_center_*` / `subagent_notice_*` / `scheduled_stop_*`），不阻塞本设计。

## 附录 D：实施进度（P0 / P0.5 / P1a）

> 规则：每项落地后在此打勾并写「提交短哈希 + 验证方式」；**未打勾即未验证**。
>
> 2026-11-02 更新：P0 / P0.5 全部落地，编译通过；全量单测 app 2783 例 + workspace 161 例 0 失败
> （`./gradlew :app:testDebugUnitTest :workspace:testDebugUnitTest`）。P0.5 的「pending 投递骨架」
> 依赖 P1a 的 v58 数据模型，拆到 P1a 实施。

### P0（无新数据模型，可独立回滚）

- [x] `TaskStateMappings` 三套状态映射 + `INTERRUPTED` 常量 + 单测
  —— `692ed04ad`；实现为 `data/task/TaskState.kt`（文件名比设计稿短，避免与映射函数混淆），
  表驱动测试 `TaskStateMappingsTest`（未知 status → FAILED、EXITED 看 exitCode、CANCELLED 不并进 FAILED）。
- [x] 通知命名空间修复（子代理审批 4242/4243/4244 → `NotificationIds` 按 runId+动作生成）
  —— `692ed04ad`；`NotificationIdsTest` 断言同 run 稳定、不同 run 不同、正整数、避开 `{1, 40000, 40010}`。
  **不在 P0 变更面**：`ChatNotificationManager` 的 live id 与 `MediaCreationService` 的
  `record.id.hashCode()` 保持原状（前者取值域按会话派生、后者是媒体记录，均不参与子代理审批路由）。
- [x] `ConcurrentRunGate` 动态读设置 + `deviceLoad` 快照 + 单测
  —— `692ed04ad`（动态读取）+ 后续补测（`load()` / `SubagentRunner.gateLoad()`，
  `ConcurrentRunGateTest` 钉住「活跃数与上限取自同一瞬间」）。
- [x] `SubagentRunner` 起 shell task 传 `turnOwner="run:<id>"` + 取消/超时级联 kill
  —— `692ed04ad`；`executeRun` 的取消与超时分支在 `NonCancellable` 里
  `killBackgroundTasksByOwner("run:<id>")`。**回滚开关未加用户设置**：P0 以「回退提交」为回滚单位，
  `killShellTasksOnRunCancel` 开关列入 P1b 设置面（见附录 C）。
- [x] `WorkspaceTaskRegistry.snapshotFlow` + 任务中心读模型（`TaskCenter`）—— `8df5a214b`；
  `TaskRegistry` 在启动/kill/自然退出/淘汰各发一版不可变快照；`TaskCenter` 聚合
  `agent_run` + shell 快照 + 定时任务行 + 审批队列 + 定时运行注册表，映射为 `TaskCard`
  （状态/能否取消/待批数）；取消按来源转发（Job.cancel / 进程树 SIGKILL / 定时 run），
  不持有第三套并发准入。映射表驱动测试 `TaskCenterTest`、快照流测试在 workspace 模块。
- [x] 任务中心页（含 shell / agent / scheduled 三段 + 取消/批准入口）—— `8df5a214b`；
  `Screen.TaskCenter` + Extensions 入口 + `TaskCenterPage`；筛选、状态、取消、待批跳详情；
  Agent 详情 / 定时任务详情可点入；三语 strings。**回滚**：删路由/入口（`taskCenterEnabled`
  用户开关仍未进设置页，回滚单位是提交）。

### P0.5（对账与定时取消）

- [x] 进程启动时刻载体 + `INTERRUPTED` 启动对账 + 启动接线
  —— `692ed04ad`；`ProcessStartClock`（首标记生效）+ `AgentRunReconciler`（boot<=0 时不动作，
  防把本进程新 run 误标）+ `RikkaHubApp.postReadyInit` 接线；`AgentRunReconcilerTest` 覆盖
  「只标上一进程」「finished_at 已写不碰」「boot 未记录时 0」。
  **pending 投递骨架**：依赖 v58 `delivery_mode/delivery_state`，拆到 P1a，不在 P0.5 假装完成。
- [x] `QueuedMessage.turnOwner` 覆盖 + `ScheduledRunRegistry` + `ACTION_STOP` 接线
  —— `692ed04ad`；`ScheduledTaskRunner` 注入前登记、`finally` 反注册、等待循环并入
  `cancelledRunIds`、取消终态 `CANCELLED` 不回落 OK；`ScheduledTaskRunService` 给 FGS 通知挂
  「停止」动作并处理 `ACTION_STOP`；`ScheduledTaskSweeper` FGS id 改 40010。
- [x] 定时取消不暂停用户队列的回归守卫
  —— `ScheduledCancelIsolationGuardTest`（源码钉住：注入带 `sched:<runId>`、
  `sendQueuedMessage` 用 `queued.turnOwner`、注册表不调 `chatService.`/`messageQueue.pause(`、
  `ACTION_STOP` 真的接到 `runRegistry.cancel`）；行为测试 `ScheduledRunRegistryTest`。

### P1a（进行中：数据与持久化投递状态底座）

以下数据底座于 **2026-11-02 本地提交**，不代表 P1a 整体验收通过。

- [x] DB v58：`agent_run` 新增 §4.2 七列，保留逻辑父指针、无外键；
  `@ColumnInfo(defaultValue)`、AutoMigration 57→58、`58.json` 与 `SCHEMA_VERSION` 同步。
  `SchemaVersionConsistencyTest` 检查七列增量、非空列默认值与无关表不变。
- [x] DAO：`observeRunsByScope`、`observePendingDeliveries`、`finishRun`、
  `markDelivered`、`markOrphaned`。`finishRun` 将终态与 `delivery_state=pending`
  在同一 SQL 内提交；重复终态写入不覆盖已结束结果；投递确认只改投递列，且幂等。
  **调用约束**：后续投递泵须在同一个 Room 事务内保存 notice 消息并确认投递，
  单独调用 `markDelivered` 不提供跨消息表的幂等保障。
- [x] 运行器接入原子终态写入，新 run 记录 `scope_token="run:<id>"`；
  启动对账将上个进程遗留的 notice run 标为 INTERRUPTED + pending，并单列人读取消原因。
  await/notify 模式不进入 notice 队列。
- [x] `SubagentPreset.executionMode` 序列化模型（blocking/background，默认 blocking），
  旧 JSON 与内置预设保持阻塞默认值；后台配置往返保留旧字段。
  **尚未暴露 UI / 接入派发分支**，当前运行入口仍保持阻塞语义。
- [x] 验证：`./gradlew :app:testDebugUnitTest --offline`：2786 例、0 失败、2 跳过；
  `AgentRunDeliveryInstrumentedTest` 在 PJX110 / Android 15 真机 6 例全绿，覆盖：
  v57 库打开并迁移至 v58（保留轨迹/结果，旧行不入投递队列）、v58 库被旧版 v57
  降级隔离、关库重开保留队列/确认、各终态（含 failed+timeout）待投递、幂等确认/孤儿标记、
  scope 隔离、重启对账不误伤本进程 run。测试使用独立数据库与隔离 Application。
  **范围**：旧备份的整库迁移与降级隔离已验证，完整 ZIP 备份导入 UI 未做端到端验证。
- [ ] `dispatchBackground` 复用 `executeRun` + 默认关闭的 `enableBackgroundSubagent` 开关。
- [ ] `appendExternalNotice` + 投递泵（会话空闲/打开/启动 flush、上下文预算、孤儿处理）。
- [ ] `SubagentNotice` 注解、Android/Web/导出标记、辅助生成与压缩等受影响路径守卫。
- [ ] P1a 后台派发与 SYSTEM notice 的整体验收。

### 已知未验证

- Android 真机：后台 run 存活、Doze、通知权限关闭降级、SYSTEM notice 渲染（P1a 前必须补真机清单）。
- 定时取消的真机时序：通知「停止」→ `ACTION_STOP` → Job.cancel → 终态 CANCELLED（单测已覆盖状态机，
  真机需验证服务生命周期与通知动作的竞态）。
- 旧备份 ZIP → v58 的完整导入流程（独立库 57→58 迁移与 58→57 降级隔离已由上方真机测试覆盖）。

