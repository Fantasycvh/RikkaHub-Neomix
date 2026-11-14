# 保留任务并立即发送：验证记录

日期：2026-11-08。仅本地记录，未推送。

实现设计见 [保留任务并立即处理新消息](../completed/design-task-handoff-and-immediate-message.md)。

## 结果

| 检查 | 结果 |
| --- | --- |
| `:app:testDebugUnitTest` 全量 | 3163 项，3161 通过、2 跳过，0 失败 |
| `WorkspaceBackgroundTasksTest` | 40 项通过 |
| Web `pnpm typecheck` | 通过 |
| `scripts/audit_ime_trap.py` | A=0、A'=0；其余为既有告警 |
| 正常 debug 设备 instrumentation | 21 项通过，最终输出 `OK (21 tests)` |
| 原 debug 数据保护检查 | 数据库、WAL/SHM、配置等 8 个文件 SHA-256 一致 |

正常 debug 包为 `app.androidrik.rikkahub.debug`，使用 `-PmemoryIsolatedTests=true` 与 `MemoryIsolationTestRunner`。测试只创建独立测试库、独立设置文件，不初始化生产 Application，不打开用户数据库。设备为 PJX110 / Android 15；没有使用此前 PM 绑定失败的隔离包。

## 真实执行链路

新增 `TaskRuntimeAcceptanceTest` 的 `handoff` 场景通过生产 Koin 图、ChatService、GenerationHandler、SubagentRunner、Room 账本与 NoticeDelivery 执行：

1. 自动后台子代理开关关闭；预设为 BLOCKING。父模型调用 `dispatch_subagent`，子代理进入测试工具屏障，父会话仍显示生成中。
2. 调用 `sendRetainingTasks` 发送新消息。等待新消息收到 `NEW_MESSAGE_OK` 且父生成结束。
3. 此时原 run 仍为 running、finishedAt=null，deliveryMode 已为 handoff，原工具仅执行一次；父会话保存原 run 的工具回执。
4. 放行旧任务工具，原 run 完成，结果只投递一次；没有自动启动新的父生成。

本地模型夹具记录的请求顺序为 `dispatch-blocking → worker-barrier → new-reply → old-task-result`。夹具为 [task_handoff_fixture.py](../../../scripts/verification/task_handoff_fixture.py)，只返回合成内容，不调用外部模型。

设备测试还包含 `AgentRunDeliveryInstrumentedTest`（10 项）和 `WorkspaceNoticeInboxInstrumentedTest`（10 项），验证 handoff 与终态的两种提交顺序、窄进度更新不覆盖投递状态、跨 scope 隔离、旧 await 结果不重复投递，以及进程重启对账。

## 单元回归覆盖

- 保留 A 后取消其等待者，A 继续运行，B 可以独立执行；普通取消仍等待 worker 清理。
- 保留仅作用于选定 scope 和已登记任务；新一轮停止不触发旧轮任务清理。
- 新输入优先派发，原队列和正在编辑的队列项保留。
- 未启动工具不生成虚假后台回执；已启动但尚未回填的 shell 复用原 completionEventId。
- dormant shell 目的地只在主动保留后激活；已完成任务可以补终态投递，不重新启动命令。
- 新轮次期间推迟旧 root 的 shell Notice，空闲后投递且不唤醒；handoff 子代理要求原工具分支仍选中。
- 后台完成通知覆盖 handoff，前台可见时保持静默。

全量回归初次发现旧 `TurnBackgroundTaskCancelGuardTest` 未覆盖已有 Notice 参数和第四个轮次入口，以及新增的主动保留条件；已同步断言。`ImageToolWiringTest` 的原始文本读取受 CRLF 影响，现统一换行后检查，未改动生图装配代码。

## 复核命令与证据

先运行 `python scripts/verification/task_handoff_fixture.py`，再执行 `adb reverse tcp:19089 tcp:19089`。设备测试参数为：

```text
am instrument -w -r
  -e class app.androidrik.rikkahub.data.db.AgentRunDeliveryInstrumentedTest,app.androidrik.rikkahub.service.WorkspaceNoticeInboxInstrumentedTest,app.androidrik.rikkahub.data.task.TaskRuntimeAcceptanceTest
  -e runtimeBaseUrl http://127.0.0.1:19089
  -e runtimeProtocol openai
  -e runtimeScenario handoff
  app.androidrik.rikkahub.debug.test/app.androidrik.rikkahub.data.repository.MemoryIsolationTestRunner
```

本地原始日志、状态哈希与备份位于被忽略的 `.tmp/task-handoff-2026-11-08/`；测试在独立库写入 `files/runtime-acceptance/handoff.passed` 作为行为回执。没有承诺跨进程恢复执行，也未把任意前台工具转成后台任务。
