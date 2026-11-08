# 任务完成主动通知模型：收件箱、随机触发与记忆同步整合评估

> 状态：rev 3，**A–D 与 E/F 首期代码已实现；正常 debug 上 18 项设备数据库测试通过，模型/UI 验收未完成；未推送**。
> 2026-11-08 实施范围、分期偏差与验证结果见 [实施记录](../verification/task-notice-inbox-2026-11-08.md)。
> E/F 最新实现见 [步骤消费与可靠同步记录](../verification/task-notice-step-sync-2026-11-08.md)。
> 下文保留目标设计；当前步骤消费接入 workspace shell，原 agent run 泵保留，scheduled 审批仍仅桥接 memory_tool，未建设独立收件箱管理页。
> 日期：2026-11-07。与 [随机触发与记忆同步](design-scheduled-random-trigger-and-memory-sync.md) 联合评估。
> 代码基线：本仓 `2778a55b1`；上游参考为本机 `F:/build/projects/rikkahub` 中的
> `4ebfde221` 历史对象，非该目录当前工作树，也不声称是上游最新实现。

## 一、建议结论

统一的是**外部事件的身份、投递与消费协议**，而不是把所有事件都变成一次新生成：

| 来源 | 何时触发 | 发往哪里 | 建议默认行为 |
|---|---|---|---|
| 后台 shell 终态 | 命令退出、失败、超时、取消 | 启动时记录的原会话/执行归属 | 功能启用后允许唤醒；用户停止造成的取消仅展示 |
| 后台子代理结果 | agent run 终态 | 既有 parentConversationId | 保持只投递，默认不自动续跑 |
| 定时任务 | 固定/随机槽到期 | 现有目标模式选择的工作会话 | 保持调度器认领并发起，禁止 inbox 再触发同一任务 |
| 定时结果摘要（二期） | 运行结束 | 用户明确指定的结果接收会话 | 只投递；不把来源会话自动当成接收目标 |

**建议先实现“空闲后自动续跑”，再按需要增加“工具步骤之间消费通知”。** 前者已经闭合
“后台命令完成后模型主动处理”的缺口；后者才需要完整评估上游 `UIMessagePart.Notice` /
`expandNotices` 的混合消息协议。随机时间窗可独立并行开发，记忆同步先修审批与保留语义。
不建议 cherry-pick 上游整批 67 文件，也不必先把本仓 MessageQueue 全部替换。

## 二、源码核实：已有基础与旧稿偏差

以下路径均相对仓库根目录；这是静态代码核对，未进行新一轮设备行为验证。

| 核实对象 | 当前事实 | 对方案的影响 |
|---|---|---|
| `workspace/.../WorkspaceBackgroundTasks.kt` | 任务注册表内存态；有终态快照、ExecutionScope、尾部缓冲与 onFinished；最多留 20 个已结束任务 | 不能仅订阅 StateFlow 差分保证不漏事件；任务和输出被淘汰前须捕获终态快照 |
| `app/.../data/repository/WorkspaceRepository.kt` | startBackgroundCommand 传 scope，没有显式目标会话 | scope 是取消归属，不能代替路由地址；启动时补会话/执行入口快照 |
| `app/.../data/task/NoticeDelivery.kt` | agent run pending 泵，内存先追加、消息与投递确认同事务；忙/预算不足延后 | 可靠投递模式可以复用，但现有泵不唤醒，也不能直接承接无 agent_run 的 shell |
| `app/.../service/MessageQueue.kt` | 用户消息有引用、编辑、置顶、reply waiter、loopGate、turnOwner | 上游替换会丢本仓字段与队列操作，应保留它作为收件箱的用户输入子队列 |
| `app/.../service/ChatService.kt` | sendQueuedMessage 固定 USER、预处理用户输入、beginNewTurn；生成结束先派发用户队列再 flush notice | 外部通知不能伪装成普通发送；必须新增来源明确的续跑入口与统一派发仲裁 |
| `ai/.../ui/Message.kt` | SubagentNotice 注解、semanticRole、辅助生成过滤及请求 framing 已有 | 首期沿用独立消息节点，将来源注解泛化；旧消息无需批量重写 |
| `app/.../service/ScheduledTaskRunner.kt` | 默认注入正常聊天；可选 headless 构造不带记忆的 run profile | 记忆引导只适用于工具实际可用的注入路径；headless 不承诺记忆同步 |
| `app/.../data/ai/GenerationHandler.kt` | 遇 pending 审批结束本轮，等外部批准续跑 | “普通定时记忆写自动走子代理通知审批”不能由现有代码推出 |
| `app/.../data/task/BackgroundApprovalPolicy.kt` | run 审批有通知可用性判定、批量等待与超时拒绝 | 可提取路由能力供定时注入复用，不能只复用 Receiver 名字就宣称接通 |
| `app/.../data/ai/MemoryConsolidationPrompts.kt` | 外部子代理 notice 被排除出整理素材 | 新 notice 同样默认排除；“模型看到结果”不等于“写入了长期记忆” |

旧随机稿的估时仅适用于随机参数和提示词小改。加入可靠收件箱、唤醒、审批与恢复后，不能再
沿用“总共 2～4 天”的判断。旧文中 2026-10-31 的上游生态调研结论，本次未重新核验。

## 三、吸收上游的范围

已读取上游 `4ebfde221` 的 `ConversationInbox.kt` 与 `ai/.../agent/Notices.kt`：

- 用户输入每条单开一轮；运行事件在生成步骤之间消费，空闲时可单独开一轮。
- notice 按 key 替换未发送项，并支持 withdraw；暂停挡住新轮，但不挡当前轮消费 notice。
- `expandNotices` 将助手消息中的 Notice 原位拆成 synthetic USER，保留工具前后顺序。

吸收事件与用户输入分型、来源 key、明确消费边界的思路。**不直接继承**其暂停语义、
USER 身份和内存 take-and-remove 行为：本仓已有“用户停止不被自动唤醒”、SYSTEM notice、
事务确认以及更丰富队列字段。尤其不可把未落库的 takeNotices 当作可靠确认。

首期用独立节点即可，无需添加 sealed part。若二期确需把通知嵌入同一 assistant 消息，
再单列 `Notice` part 的序列化、分段 ID、工具配对、token 计数、压缩与各 provider 编码测试。
未知 part 对旧版本的兼容性也须评估，不能因 Json 的 ignoreUnknownKeys 就假定未知子类型可读。

## 四、建议结构与状态机

### 4.1 两层结构

`ConversationInbox` 做会话派发仲裁，内部保留现有 MessageQueue；外部事件放入持久化
`external_notice` 表（拟议名）。不把用户草稿、附件与 reply waiter 一并搬进 DB。
新表使用实施时下一可用 DB 版本，不引用旧稿的 v55/v56 作为当前版本。

拟议最小字段：

| 字段 | 用途 |
|---|---|
| id / dedupe_key | 稳定事件 ID；唯一键含来源类型、来源实例和终态，不只用 shell 的短 taskId |
| source_type / source_id | shell / agent / scheduled_summary 的来源寻址 |
| conversation_id / assistant_id | 启动时记录的投递地址与助手快照，投递时重验 |
| scope_token / root_event_id | 取消归属与自动续跑链路根，路由不能仅靠 scope 反查 |
| target_anchor_id | 目标分支锚点；切分支后不向另一分支静默投递 |
| payload / created_at | 结构化终态、exitCode、有界摘要/尾部；不可仅保存易淘汰日志路径 |
| delivery_state / message_id | pending → delivered；无目标为 orphaned，过期为 expired |
| wake_policy / wake_state | append_only 或 resume_when_idle；pending → claimed → started/failed/suppressed |

投递状态与生成状态必须分开：写进历史不代表模型已读，更不代表处理完成。卡片文案也分别
显示“已投递／等待处理／处理中／处理失败”，不能用一个 delivered 冒充完成。

### 4.2 生产与可靠性

1. 启动 shell **之前**准备路由信息和唯一来源实例，贯穿仓库、manager、registry；
   完成回调直接携带不可变终态快照，覆盖秒退和启动失败。保留既有 onFinished 资源释放职责，
   不把有 IO 的投递逻辑塞进持有 registry 锁的区域。
2. 使用带确认的终态待交付记录：DB 入队成功前不因 20 条淘汰删除该快照；只看 StateFlow
   可能合并快速变更，不足以作为可靠来源。DB 暂时失败须可重试，队列满时降级为明确错误，不能静默丢。
3. inbox 以 dedupe_key upsert；session 的内存追加和持久化沿用现有 notice 的回滚方案，
   在同一个 Room 事务写消息与 delivered。失败重试不再追加第二张卡片。
4. agent_run 仍是其终态事实源；改为生成通用 envelope 时，入 inbox 与旧投递状态交接须同事务。
   迁移期间每个来源只能有一个写消息的泵，禁止新旧双投递。
5. shell 进程死即任务死的边界保持：**已入 DB 的事件可恢复；进程死亡前尚未交接的终态不保证恢复**。
   若将来要求所有 shell 启动均能在重启后得到 INTERRUPTED 结果，须另加持久化启动账本，不能声称本表已解决。

### 4.3 仲裁规则（首期）

统一入口在生成结束、入队、事件到达、打开会话与启动对账时运行，单会话串行：

| 会话状态 | 动作 |
|---|---|
| 正在 streaming / 工具执行 | 仅收件；首期等当前生成完整收尾 |
| 工具等待审批、用户暂停、错误暂停、队首编辑中 | 不开启 notice 新轮，不绕开暂停；仍显示待处理状态 |
| 有可发送用户消息 | 先在事务中提交可用 notice，再发用户输入；此次输入自然携带结果，不再重复唤醒 |
| 空闲且无用户消息 | append_only 仅落卡；resume_when_idle 校验后生成一次 |
| 窗口不足 | 留 pending；允许用户压缩后重试，不自动压缩并反复唤醒 |
| 目标删除、助手更换、工作区解绑、分支切换 | 标明无法按原路处理或等待原分支；不重定向到“最近会话” |

`sendExternalContinuation` 必须绕过用户正则/宏、USER 落消息与用户统计；创建独立的生成请求，
在同一 session 锁/串行调度边界内检查空闲并登记 Job，防止事件与用户输入同时起两轮。
新的执行 scope 关联原 scope 作为来源，但不能修改旧 scope 身份；预算与取消归属显式传入。

启动恢复时，对已 claimed/started 但无活跃 Job 的唤醒记为 interrupted/failed，提供手动重试；
默认不自动重放可能执行过工具的生成。承诺“卡片幂等 + 唤醒不并发”，不承诺付费请求或工具副作用恰好一次。

### 4.4 自动续跑控制

- 建议 rollout 开关默认关；启用后适用于新启动的直接聊天 shell，不追溯旧任务。每条来源事件
  不另弹确认，但沿用工具审批。自动唤醒属于可能收费的模型请求，设置文案应明确。
- 同一批终态合并唤醒；建议初值：一批最多 8 个事件、每事件最多 2K 字符，总正文最多 8K 字符，
  再用实际 token 预算校验。大日志继续按需查询，禁止把完整 stdout 自动灌回。
- 每个用户/定时发起链建议最多 1 次独立自动续跑；续跑再起后台任务时仍可收结果，但不递归唤醒。
  新的真实用户输入或下个调度 run 才建立新根。不是收到一条 notice 就重置预算。
- 停止当前轮或定时 run 的取消级联先抑制其链路唤醒，再杀进程；KILLED 结果可见，但不使模型复活。
- 子代理/headless 内部 shell 归执行 run 消费，首期不另唤醒父会话；由既有 agent run 结果汇总上报。
  无活跃消费者时只保留结果。不能把 run 内 shell 与 agent 最终结果各唤醒一次。
- 先保留现有 SubagentNotice 旧数据与兼容 carrier；新增统一来源判定，禁止把已有注解整个改名导致历史失读。

## 五、与随机触发的组合

随机化只改变调度器算出的 `nextRunAt`；AlarmManager / WorkManager / sweep / 认领去重仍在
scheduled 层。**到期提示不是“任务完成 notice”**，不能让 inbox 和 ScheduledTaskRunner 各发起一次。

建议保留旧稿 DAILY + randomWindow 的方向，补齐这些条件后实施：

- 采用 `[startMinute, endMinute)`，不跨零点；`slotsPerDay <= min(20, end-start)`。
  从有限分钟集合无放回采样，禁止无限 retry 直到凑够互异槽。
- 固定并测试种子与 PRNG 算法版本；建议持久化 `randomSeed` 与 `randomAlgorithmVersion` 到 payload，
  使用固定算法从日期和种子推导槽位。不只依赖 Kotlin Random 跨升级行为或易碰撞的 taskId.hashCode。
- firstRunAt、advance、编辑预览与 task_manage 使用同一计算入口；保存 seed 后不得每次预览重抽。
  旧 payload 没有 randomWindow 完全保持原行为；非法组合不得静默降级成每天 08:00。
- 本地时区与现有任务一致；春季 DST 空洞移动后的槽仍需去重，秋季重复分钟只运行一次；
  时区/时间改变须重新计算下一槽。随机窗口允许被系统延迟，不能承诺实际执行必在窗口内。
- 延用错过宽限与不追帧语义；晚到只补当前认领的一次，不集中补齐当天 N 次。
- task_manage 的 schema、校验和返回说明与表单同步，否则 AI 创建不了随机任务。
  多字段表单遵守仓库 CardGroup / 常驻滚动节点规则。

## 六、与记忆同步的组合

### 6.1 先修正“同步”的定义

分三件事验收：**结果可见**（notice）、**下轮能读**（目标历史或上下文）、**长期保留**（记忆）。
notice 不能代替 memory_tool；助手记忆也不能代替任务完整日志/交付物归档。

任务提示应表达“这是预设任务触发；模型自己生成的推测不是用户事实”。仅当
`assistant.enableMemory && !conversation.memoryDisabled && 本执行入口实际挂载记忆工具` 时加引导：
只沉淀有来源的稳定事实、用户明确偏好或已核实结果；不要把主动寒暄、虚构用户近况、原始日志写成事实。
同时保持提案、打分、内容守卫与已有 create/modify/delete 审批规则。

### 6.2 后台审批必须补桥

普通注入路径的 pending 工具现状是退出生成等批准。需要为 **scheduled 来源轮**接入已有
后台审批能力：按 toolCallId 关联会话与 scheduledRunId，批准后仍走正常 ChatService 工具恢复，
不调用 run 的工具执行器替它执行。foreground 卡片与通知争抢同一决定时只接受一次。

通知不可用/权限关闭/超时：明确拒绝并在本 scheduled run 的等待生命周期内收尾，避免记成
无差别 OK；收到批准前不能先写。停止、队列撤销和会话删除须撤销 waiter 与通知。
审批拒绝导致记忆没写不必判整个任务失败，应分别记录任务执行结果与 memorySync 状态。
此桥先服务 scheduled 注入，不扩大为所有普通聊天工具的后台审批改造。

headless 路径本期继续显示“不支持记忆同步”，不为它拼记忆工具提示，也不悄悄改它的能力/审批边界。

### 6.3 自动清理不能靠提示词担保

提示词只是引导，不能把生成完成等同于记忆已保存。建议分期：

1. 首期 B1 是 best effort，UI 写明；不宣称“主动消息已同步”。现有自动清理规则可保留，
   但在同时开启自动清理与记忆引导时说明：未写入记忆的内容仍可能随清理消失。
2. 若用户要求可靠保留，再增加显式 `memorySync` 结果：disabled / unsupported / no_candidate /
   pending_approval / saved / denied / failed。no_candidate 必须来自明确的提取结果，不能由“没调工具”猜出。
3. 可靠模式下，清理前保存有界运行摘要/交付物索引到持久化结果记录；待审批或保存失败时跳过该源会话，
   超过保留额度则显式提示并暂停继续创建可清理工作台，不能无限积压或悄悄丢弃。
   这需要扩展 scheduled run 结果与清理判断，不是一个提示词补丁。
4. 主会话摘要回写是可选独立开关，用同一 inbox 的 append_only 投递。只有用户指定的接收目标可用，
   `source_conversation_id` 仅是来源审计字段，不自动授予 AI 向原会话写入结果的权限。

记忆整理默认跳过原始外部通知；若需沉淀其中的任务事实，应由有来源的摘要/记忆候选进入既有写入审批，
不能为“同步”直接取消 skip 规则。定时 synthetic 输入也应标注触发来源，避免被整理器认作用户当天新陈述。

## 七、实施顺序与验收门

| 阶段 | 范围 | 必须验证后才能进入下一阶段 |
|---|---|---|
| A：事件投递基础 | 通用 envelope、新表、shell 路由/终态交接、独立 notice 卡片与历史兼容；先只投递 | 秒退、启动失败、20 条淘汰、重复终态、事务回滚、重启补投、源/目标删除、切分支不串线 |
| B：空闲自动续跑 | 仲裁器与外部续跑入口、暂停/审批门、合并/预算/取消/根链限额 | 用户并发发送只起一轮；停止后不复活；无 USER 伪输入；子代理不重复唤醒；失败不自动重放 |
| C：随机调度（可独立） | payload + 固定 PRNG + ScheduleMath + UI + task_manage | 同种子向量、边界/容量、跨日/DST/时区、错过不追帧、旧配置兼容、三层触发仍只认领一次 |
| D：记忆引导与审批 | 注入入口能力判定、B1、scheduled 通知审批桥、状态说明 | enableMemory/memoryDisabled/headless 组合；批准/拒绝/权限关/超时/取消；无确认不写；模型推测不成用户事实 |
| E：可靠保留（按需求） | memorySync 状态、持久化摘要、清理联动、显式目标摘要投递 | 待批不删、失败保留、容量可控、源会话删除后摘要可读、主会话不被隐式注入 |
| F：步骤间通知（可选） | Notice part + 请求展开 + generation loop hook | 所有 provider 工具配对、分段消息 ID、缓存前缀、压缩/token/恢复；审批与护栏停止不能被通知绕开 |

A/B 是本次缺口的核心；C 不依赖 Notice part；D 不能再按“零开发审批”排期。
建议 A/B、C、D 分别交付，E/F 不绑进首个版本。实施排期在 A 的字段与桥接点原型确认后估算，
不以旧稿的小特性估时覆盖整项。

跨端验收清单：Android / Web 的来源卡片、任务中心状态、导出、备份往返、标题/建议/
压缩/记忆整理的过滤。新事件默认不参与用户意图/标题/建议；压缩保留有界事实或来源索引，
但不能令已投递尚未处理的结果因窗口裁剪消失。导出以语义来源标明，不伪装为用户发言。
旧 carrier 与旧 SubagentNotice 的回归测试必须继续通过。

2026-11-08 已按用户要求开始实施，当前首期落地与验收缺口见上述实施记录；不以设计目标代替已实现能力。

## 关联

- [任务完成后自动恢复对话](design-task-completion-wait-and-resume.md)
- [保留任务并立即处理新消息（第二档实施）](design-task-handoff-and-immediate-message.md)
- [随机触发与记忆同步原稿](design-scheduled-random-trigger-and-memory-sync.md)
- [Agent task runtime](../in-progress/design-agent-task-runtime.md)
- [隐形会话与任务管理](../in-progress/design-proactive-task-management.md)
- [定时任务既有语义](../completed/design-scheduled-ai-tasks.md)
- [上游评估台账](../../upstream-analysis.md)
