# Notice、随机调度与定时记忆：首期实施记录

> 日期：2026-11-08；本地实施，未提交、未推送。开始实施时 HEAD：`4e7ba4b14`。
> 设计：[整合稿](../drafts/design-task-notice-inbox-and-proactive-sync.md)。
> 用户要求先暂存再实施：两份评估草稿的实施前版本已进入 index；以下代码与实施记录留在工作区。

## 已实现

### A/B：shell 结果投递与空闲续跑

- DB **60→61**，新增 `external_notice` 表和生成的 Room schema；消息身份使用通用
  `ExternalNotice` 注解，保留旧 `SubagentNotice` 数据和独立的 agent run 投递泵。
- shell 启动前记录会话、助手、工作区、用户锚点与根 scope。完成时保留不可变终态，
  数据库确认接收前不因已结束任务的 20 条淘汰而丢失；秒退、启动失败同样入队。
  短 taskId 复用不会复用事件 UUID，工具回执带 `completionEventId`。
- 投递还需在选中历史找到原始工具回执：同一用户消息的重新生成分支不能接收旧结果。
  切回原分支触发再次投递。消息写入与 delivered 同事务；内存已有消息时仍补持久化，
  避免并发保存后出现“内存看得见，重启却没有”的确认漏洞。
- 有用户消息时优先携带结果；审批等待、用户暂停、编辑占位、运行中和窗口不足时不自动起新轮。
  空闲续跑在 session 锁内预留 LAZY Job，走正常生成管线，不伪造 USER 输入。
- 每个根 scope 最多一次独立自动续跑，后续终态继续可见。用户停止、护栏停止和生成错误抑制原链路；
  重启把 claimed/started/waiting_approval 标为 failed，不重放可能已经执行过工具的生成。
- 通用辅助生成过滤、记忆整理过滤、压缩窗口保留和语义角色已覆盖新旧 notice；Android / Web
  展示独立来源卡片。当前卡片显示 shell 终态与输出快照，**没有单独的收件箱管理页或实时处理进度 UI**。
- rollout 默认关闭，入口为设置→通用的“后台命令完成后自动处理”。开启后只对新启动任务采集路由；
  普通聊天 shell 可续跑，scheduled 注入轮的 shell 仅投递；子代理/headless 内部 shell 不另唤醒父会话。
- 注册表待交付上限 128；持久化待处理上限 2048，超限保留源快照并重试，随后启动端施加背压。
  重启时清理 30 天以前已解决的收件箱行，未处理行不因清理而消失。完整日志仍遵守原有日志保留策略。

### C：每日随机时间窗

- DAILY payload 新增 `RandomWindow`，持久化 seed / algorithmVersion，旧配置默认 null。
  v1 使用 Java Random 明确规定的 48 位 LCG 和部分 Fisher–Yates；固定测试向量防止升级重抽。
- `[startMinute, endMinute)`，不跨零点；支持终点 24:00；1～20 次且不超过分钟容量。
  无放回有限采样；不改既有触发器、认领和错过宽限语义，不集中补齐错过的槽。
- firstRunAt / advance 共用同一计算；DST 转 instant 后排序、去重，回拨重复分钟只执行一次。
  表单编辑保留 seed / version，未知版本阻止保存；列表显示窗口与次数。
- `task_manage` 增加 random_start_minute / random_end_minute / random_slots，三项必须齐全且为 DAILY；
  创建后将实际 payload 返回 list，模型可查看真实配置。损坏的随机 payload 不回落为固定 08:00。

### D：记忆引导与 scheduled 记忆审批桥

- 仅在助手开启记忆、会话未禁用记忆且目标模型支持工具时拼记忆引导；headless 不拼。
  引导位于长期目标之后、任务正文之前，禁止把模型猜测、寒暄、虚构近况和原始日志当用户事实。
- scheduled 输入带 `ScheduledTrigger` 注解和 synthetic 标记，预设指令不进入记忆整理的用户发言素材。
- 本期桥接范围是 **scheduled 注入的 memory_tool 审批**，其他工具的审批行为保持既有语义。
  在原生成 Job 内等候已有通知审批，再经 ChatService 正常工具恢复，不另起 run 执行器。
  原 Job 尚未结束，因此 ScheduledTaskRunner 不会在等待批准时先记 OK 或自动清理源会话。
- 通知与前台卡片共用同一个决定；前台早于通知注册的点击也会记录，防止互相等候。
  通知不可用、拒绝、超时不自动放行；三批审批上限防止连续制造新审批链，等待总预算五分钟。
  取消撤销 waiter / 通知并收尾待批工具；scheduled 超时仅取消自己的 Job，不暂停用户队列。
- 提示词为 best effort，**没有宣称已保存记忆**。可靠 memorySync 状态、持久化运行摘要与清理联动
  仍属于 E；完整步骤间 Notice part 协议属于 F，均未实施。

## 验证

统一使用用户指定的 `GRADLE_USER_HOME=D:/A-projects-relaygradle`、本机 Android Studio JBR 21，
未改项目工具链或依赖版本来绕过构建。

本地日志与计数保存在 `.tmp/task-notice-verification-2026-11-08/`（Git 忽略目录），
包括 package-and-app-tests.log、unit-verification.log、summary.json、设备启动失败与 0 tests 的原始日志。

| 检查 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | 通过 |
| app 相关 JVM 回归（Notice、随机窗口、ScheduleMath、Scheduled、TaskManage、记忆整理与 Approval） | 191 项通过 |
| `:workspace:testDebugUnitTest --tests '*WorkspaceBackgroundTasksTest'` | 38 项通过 |
| `:ai:testDebugUnitTest` | 514 项通过 |
| `web-ui`：`pnpm typecheck` | 通过 |
| `:app:assembleDebug :app:assembleDebugAndroidTest -PbackupIsolatedTests=true` | 隔离 target / test APK 构建通过 |
| IME 审计 | A=0、A'=0；已有 B/C 告警，未新增 TextField 的 Lazy/对话框宿主 |

收件箱 JVM 测试覆盖：忙碌/待审批、用户优先、停止与冷启动、事务回滚、上下文不足、
切分支、同根第二个任务不再次唤醒，以及已有内存卡片仍须原子补落库。
随机调度覆盖固定向量、容量、跨日不追帧、DST、表单 seed 稳定和 AI 参数校验。
审批桥覆盖 Job 等待、批准、通知不可用、取消撤销和批次上限。

## 设备阻塞解除（2026-11-08 晚，续）

隔离 runner 启动错误的两级排查与处置：

- **SCHEMA_VERSION 漂移（已修，`AppDatabase.kt`）**：实施漏同步常量（`@Database version=61` 但 `SCHEMA_VERSION=60`），常规包启动哨兵必抛 `SCHEMA_VERSION drifted`（control 组实测命中）。常量已同步 61，`SchemaVersionConsistencyTest` 复跑通过；常规包覆盖安装后真机启动无 FATAL，Room AutoMigration 60→61 静默完成。
- **隔离包 `backupverification` 的绑定失败（设备侧问题，绕过）**：注册正常（`pm list instrumentation` 可见）、目标 APK 加 `<queries>` 声明测试包也无效，`am instrument` 仍在**目标进程** `prepareInstrumentation` 报 `Unable to find instrumentation info`；同一时刻常规包 `app.androidrik.rikkahub.debug.test` 的 instrumentation 绑定与执行正常 ⇒ 非全局故障，是该包特有的 PM 过滤（疑似 Thanox/ColorOS 对新装包的可见性干预；Android 15 AOSP 语义下测试包对 target 自动可见，不应命中）。**处置：设备验收改走 `-PmemoryIsolatedTests=true`（常规包 + 隔离 runner）**——与 2026-11-02 复核文档「环境坑 #4」记载的既验证路径一致；隔离测试自建临时库，不触用户数据。
- **真机结果：`WorkspaceNoticeInboxInstrumentedTest` 8/8 通过**（迁移 60→61 保留旧数据、事务回滚、待投递去重/一次性消费、冷启动标记 failed 等真实 Room 路径）。
- 设备验收清单剩余：真实后台命令完成唤醒、通知批准/拒绝/权限关闭/停止——需真实模型轮次或人工点击，待用户真机操作（rollout 默认关，入口：设置→通用→「后台命令完成后自动处理」）。

## 未通过或尚未完成的设备验证（实施当日基线，状态以上节为准）

- workspace 全量 JVM 测试有 1 项失败：`WorkspaceFileMutationTest` 的
  `imports may land in an in-root directory alias but never escape`，预期 `usr/bin/tool.txt`、实际入口别名路径。
  该导入路径代码及该测试不在本次改动范围；本次未修复，也不将 workspace 全量记为通过。
- 设备：PJX110 / Android 15。隔离包为 `app.androidrik.rikkahub.backupverification.debug`，
  使用 `MemoryIsolationTestRunner`，没有替换用户现有的应用包。
  `connectedDebugAndroidTest` 曾返回构建成功，但 XML 是 **0 tests**，不能当作测试通过。
- 手动安装并核实 runner 注册后，直接 instrumentation 仍在启动时报
  `Unable to find instrumentation info` / `Process crashed`；`--force-queryable` 重试也未解决。
  因而新建的 `WorkspaceNoticeInboxInstrumentedTest`（真实 Room 事务、60→61 迁移等）
  **只确认编译通过，未确认在设备执行通过**。不将其列入以上 JVM 通过数。
  （上节已解除：常规包 + `-PmemoryIsolatedTests=true` 路径真机 8/8。）
- 尚无真实模型端到端执行、通知按钮人工点击或新 UI 的真机视觉验收；没有为验证调用付费模型。

后续设备验收必须先解决 runner 启动并确认测试数非零，再验证迁移、事务回滚与去重、真实后台命令
完成唤醒、通知批准/拒绝/权限关闭/停止。当前 rollout 默认关，方便在验收前保持旧行为。
