# 步骤间 Notice、memorySync 与运行摘要

> 2026-11-08，本地实现，未推送。基线 `bba9c1226`。
> 接续 [A–D 实施记录](task-notice-inbox-2026-11-08.md)，对应整合稿 E/F。

## 已实现

### 步骤间 Notice part

- 新增可序列化的 `UIMessagePart.Notice(eventId, text)`。当前运行时生产方为 workspace shell 收件箱；
  旧 agent run 泵继续保持原投递行为，没有顺带迁移其来源路由。
- `GenerationHandler` 在下一次请求前、完成工具批次之后调用消费钩子。等待审批、已有批准/拒绝工具
  尚需执行、用户暂停、历史范围重新生成、原执行 scope 已改变时不消费。
  不打断网络流或工具执行，不创建用户轮，不重置原循环步数、护栏或预算。
- 收件箱使用生成循环的完整 live messages 作为权威基线，校验原工具回执与选中历史，
  在既有 Room 事务中保存 notice 与 delivered；步骤消费将独立唤醒标为 suppressed。
  失败保留源事件重试，内存回滚使用 identity 校验，避免覆盖并发修改。
- 请求统一装配点 `PromptAssembly` 调用 `expandNotices()`，原位展开为有来源注解的 SYSTEM 数据消息。
  混合消息的前后段采用确定性且不同的 ID；不改持久化消息，不拆开已有 Tool 内部的调用/结果。
  原来源注解保留。provider 不会收到不认识的 Notice part。
- 外部通知排除出标题/建议等辅助请求；步骤消费记录进入 fallback 的已提交基线，防止切换模型时丢掉
  已确认投递的结果。Notice 文本计入文本估算，迁移排序器不重排跨 Notice 的语义位置。
- Android / Web 支持独立卡片与混合消息中的 Notice 展示、复制及列表预览。继续由原 rollout 开关控制，默认关。

### memorySync 状态与证据

- DB **61→62**，`scheduled_task_runs` 新增 `memory_sync_status`、`run_summary`、`memory_receipts`。
  存量行默认 `legacy`，不把历史上未知的结果迁移成 saved。
- 新执行初始为 unassessed；未启用为 disabled，headless 为 unsupported。实际调用根据回执区分
  writing、pending_approval、denied、failed、proposed、saved、no_candidate。缺少工具调用仍是 unassessed。
  成功与失败混合时采取保守状态，不用一次成功掩盖未解决写入。
- memory_tool 的真实变更回调成功后生成 `memory_sync=saved` 回执；提案的 applied=false 不计已保存。
  新增只读动作 `no_candidate`，供模型明确记录“已评估，无合适候选”；不绕过任何写入审批。
- 流式状态与回执身份同一次 SQL 更新；只在状态/回执变化时写库。证据保存工具调用 ID、动作、
  记忆 ID、revision 和应用结果，不重复保存记忆正文。
- 按 ScheduledTrigger 截取本 run 的消息，并在下一条真实 USER 输入前结束，避免借用旧轮工具成功或新用户回复。
  用户重启后手动完成原待批工具时，只要仍位于该任务轮，也能更新其真实回执。
- 启动对账将旧进程 pending_approval / writing 标为 interrupted，不恢复审批或生成；进程时间边界排除本次启动的新等待。已批准但尚未取得回执的操作显示 writing，终态仍无结果则为失败/中断。
  同步状态可靠指的是可审计的执行证据，不保证模型总能识别需要记住的事实。

### 持久化摘要与清理

- 注入执行结束时，将同步状态、回执和有界运行快照与 finished_at / status 同一个 SQL 语句写入。
  取消也保存可取得的快照；headless 将返回摘要/错误持久化并明确 unsupported。
- 快照保存本轮最后的 assistant 正文（最多 12,000 字符）、截取标记、工具数量和交付物声明索引。
  交付物索引不是文件校验结果；不额外调用模型生成摘要，也不向其他主会话隐式回写。
- 运行历史页展示同步状态与可选中复制的快照，源会话被清理后仍可查看保留的账本记录。
- 只有摘要已持久化且 memorySync 为 saved / no_candidate / disabled / unsupported，才允许源会话参与自动清理。
  同一源会话绑定的所有运行行都需安全；原“用户接手/运行未完成不删”的保护仍保留。
- 账本裁剪也保留不确定状态的源记录；已解决快照仍遵循最近 60 行的历史保留策略，明确删除任务仍清理其账本。
  每任务达到 60 条未解决源记录时，暂停任务并记录 capacity_blocked 原因，阻止继续创建工作台。
  状态不确定的历史需要用户处理待批项或手动管理任务，不会静默升级为同步成功。

## 验证

使用用户指定的 `D:/A-projects-relaygradle` 与 Android Studio JBR 21。

| 检查 | 结果 |
|---|---|
| app 相关 JVM 回归（Scheduled / Notice / RunEvidence / Memory / SchemaVersion / PromptAssembly） | 565 项通过 |
| ai 模块完整 JVM 回归 | 516 项通过 |
| Web `pnpm typecheck` | 通过 |
| `:app:compileDebugAndroidTestKotlin` | 通过 |
| Room 生成的 61→62 SQL 在本机 SQLite 执行 | 存量默认 legacy、摘要/回执为空；待审源保留、已解决历史可裁剪 |
| `git diff --check`、IME 审计 | 通过；A=0、A'=0 |

新增回归覆盖：Notice JSON 往返、混合顺序与稳定 ID、统一请求装配无残留 Notice part、
忙碌步骤消费不另开轮、暂停/审批边界、无工具不等于 no_candidate、提案不等于保存、
不跨用户轮借用证据、摘要长度和正文脱敏、旧进程审批对账。

新增真实 Room 用例覆盖 61→62 迁移与历史保护、结果字段同事务保存及裁剪。
**本轮只确认设备用例编译通过，未确认设备执行通过**；此前隔离 instrumentation 启动故障仍是设备验收缺口。
本机 SQLite 验证不替代 Android Room 设备验证。未调用付费模型，未进行新 UI 的真机视觉验收。

日志位于 `.tmp/task-notice-step-sync-2026-11-08/`（Git 忽略目录）。

## 同日补验：正常 debug 包已跑通设备测试

根据用户提供的设备信息，改用已安装的正常 debug 包 `app.androidrik.rikkahub.debug`，
不再创建 backupverification 新包。使用 `-PmemoryIsolatedTests=true`，仅把测试中的 Application
替换成普通 Application；包名仍为正常 debug。这样不会启动生产 DI、真实数据库迁移、记忆整理或定时任务。

- 当前代码与安装包对应的 Room 版本、`SCHEMA_VERSION` 都为 **62**；版本一致性 JVM 用例 4 项通过。
  用户报告的 61/60 是此前 WIP 的漂移，不是本次正常 debug 测试仍存在的错误。
- `WorkspaceNoticeInboxInstrumentedTest`：**10 项通过**，含 60→61、61→62 真实 Room 迁移，
  未解决源记录保护、摘要字段落库、投递事务回滚、重复投递和下一次仓库保存。
- `AgentRunDeliveryInstrumentedTest`：**8 项通过**，含 v57 旧备份迁移到当前版本、
  transcript/defaults 保留、终态入队和事务投递兼容。
- 旧备份用例最初写死 `expected=58`，而 Room 正确迁移到 62；现改为断言 `AppDatabase.SCHEMA_VERSION`，
  其余旧数据完整性断言不变。修正后两组一起执行，最终输出 **`OK (18 tests)`**，不是 0 tests。
- 安装前备份正常 debug 的数据库、DataStore 和偏好设置；测试后核对原数据库、WAL/SHM、
  DataStore 和偏好设置共 **7 个原有文件**，内容 SHA-256 全部一致。备份仅保存在本地 Git 忽略目录。

复现命令（PowerShell，Gradle 使用 `D:/A-projects-relaygradle` 和 Android Studio JBR 21）：

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PmemoryIsolatedTests=true
adb install -r -t app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class 'app.androidrik.rikkahub.service.WorkspaceNoticeInboxInstrumentedTest,app.androidrik.rikkahub.data.db.AgentRunDeliveryInstrumentedTest' app.androidrik.rikkahub.debug.test/app.androidrik.rikkahub.data.repository.MemoryIsolationTestRunner
```

最终设备日志、原状态指纹与备份位于 `.tmp/notice-debug-device-2026-11-08/`。
此前新隔离包的绑定失败不代表设备的 instrumentation 整体不可用；其具体 PM/ROM 原因仍未确证，
不再作为本轮数据库验收阻塞。以上关闭了真实 Room 数据库用例的缺口，
**不等于生产 Application 启动、真实模型端到端和 UI 视觉验收已完成**；这些需要常规 runner 或人工验证。
