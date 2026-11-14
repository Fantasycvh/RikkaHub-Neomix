# 任务等待与自动恢复：验证记录

日期：2026-11-08。本地记录，未推送。实现见 [设计草稿](../completed/design-task-completion-wait-and-resume.md)。

## 最终结果

| 检查 | 结果 |
| --- | --- |
| app JVM 全量 | 3169 项，3167 通过、2 跳过，0 失败 |
| ai JVM 全量 | 516 项通过 |
| 后台命令注册表回归 | 40 项通过 |
| Web `pnpm typecheck` | 通过 |
| IME 审计 | A=0、A'=0；既有 B/C 告警 |
| 正常 debug 设备验证 | 最终合计 30 项通过 |
| 原有业务数据复核 | 33 张表的共同字段逐行指纹一致 |

设备 PJX110 / Android 15，目标 `app.androidrik.rikkahub.debug`，使用 `-PmemoryIsolatedTests=true` 和 `MemoryIsolationTestRunner`。业务验收创建独立 `runtime-acceptance.db` 和独立设置文件；模型服务是本地合成夹具，没有请求付费模型。

## 真实运行场景

`TaskRuntimeAcceptanceTest` 通过生产 Koin 图、SettingsStore、ChatService、GenerationHandler、SubagentRunner 和两层持久化收件箱执行以下场景。预设原本为 BLOCKING，旧后台子代理开关关闭，统一自动处理开关开启。

1. `auto`：子代理进入工具屏障，父模型调用 `await_background_tasks`，父 Job 正常结束；此时子代理仍 running。放行子代理后，完成事件投递一次，父模型自动产生 `AUTO_RESUME_OK`。无需新的用户消息。
2. `auto-priority`：父模型结束等待后发新问题，新问题停在独立工具屏障上。旧子代理完成时仍为待投递，当前会话没有插入旧结果；新问题完成后，旧结果才投递并自动续跑。
3. `auto-stop`：等待结束后主动停止，再放行子代理。结果仍投递一次，wakeState=suppressed，不重新启动父生成。
4. `auto-handoff`：主动保留旧任务并发送新问题。旧任务继续，结果只经统一收件箱投递一次，保持 suppressed，不切回旧通知泵双投。
5. `handoff`：关闭统一自动处理开关，复核上一阶段的阻塞子代理手动让路流程；新回复先完成，旧任务继续，旧通知路径仍正常工作。

组合运行 26 项包括：AgentRunDeliveryInstrumentedTest 11 项、WorkspaceNoticeInboxInstrumentedTest 12 项、设置持久化 2 项、`auto` 场景 1 项。其余 3 个自动场景及旧 `handoff` 各单独执行 1 项，最终合计 30 项通过。

本地夹具观察到的关键请求顺序：

```text
auto: dispatch → parent-yield → worker-barrier → old-task-result → automatic-resume
priority: dispatch → parent-yield → worker-barrier → new-message-barrier
          → old-task-result → new-reply → automatic-resume
stop: dispatch → parent-yield → worker-barrier → old-task-result
handoff: dispatch → parent-yield → worker-barrier → new-message-barrier
         → old-task-result → new-reply
```

子代理 worker 和父模型第二步是并发请求，因此 parent-yield 与 worker-barrier 的观测顺序可交换。

## 迁移、设置与边界

- 真实 Room 验证 62→63：旧 external_notice 保留正文并默认 sourceType=workspace，旧 agent 的 notice_target=NULL、deliveryMode=await，不被赋予新唤醒权限。既有 57→最新、60→61、61→62 迁移用例继续通过。
- DataStore 写入并复制 protobuf 文件重新加载，确认自动处理开关可持久化。首轮真实运行暴露此前缺少该字段的读写 key；补齐后重新验收通过。
- 真实事务验证：会话消息、Notice 确认、agent 确认一起提交；收件箱重建后，停止标记仍能抑制尚未产生的 agent 完成事件。
- JVM 真实生成循环验证：等待工具结束前提交同批全部结果，不额外请求模型；任务已经完成时返回正常 ready，继续处理结果。
- 同 root 后续独立完成可以继续触发一次；8 个完成事件限制自动续跑预算，第 9 个结果仍保留可见。不同 root 不混入首次续跑上下文。
- 原分支、assistant/workspace、暂停、待审批、上下文容量与旧 Notice 兼容用例继续通过。

最终 APK 的组合运行曾有一次 `Process crashed`，退出信息为 EXIT_SELF/status=1，未取得该次 Java 异常栈。相同 APK 随后两次组合复测各 26 项全部通过，3 个自动边界场景与旧手动流程也通过；未将原因归于 ROM 或实现中的某个模块。原始失败与复核日志均保留。

## 用户数据与设备状态

测试前、后均保存了保护文件的 tar 快照。最终文件哈希检查发现：debug 库由 62 升到 63，WAL/SHM 发生变化；DataStore 仅 launch_count 改变，共享偏好仅 lastConversationId 改变。测试期间发生了普通启动状态变化，不能宣称所有文件逐字节未变。

在前后数据库副本上，比较 33 张业务表共同字段的全部行（排序后逐表 SHA-256），无差异、无行数变化；Room 身份表和 Android 元信息不属于业务比较。原有聊天、消息、记忆及任务业务数据保持一致。没有为追求哈希相同而回滚实际启动状态或数据库版本。

原始证据位于被忽略的 `.tmp/task-auto-resume-2026-11-08/`：`summary.json`、`business-data-comparison.json`、前后快照、最终构建/设备日志。Android 侧 `files/runtime-acceptance/*.passed` 保存各场景回执；判断是否自动恢复以测试中的 wakeState 和生成结果断言为准。

## 复核

启动 [本地夹具](../../../scripts/verification/task_auto_resume_fixture.py)，执行 `adb reverse tcp:19090 tcp:19090`。使用上述隔离 Application runner，传入 `runtimeProtocol=openai`、`runtimeBaseUrl=http://127.0.0.1:19090`、`runtimeScenario=auto|auto-priority|auto-stop|auto-handoff`。

旧手动流程使用 [handoff 夹具](../../../scripts/verification/task_handoff_fixture.py)，映射 19089 端口，传入 `runtimeScenario=handoff`。两套夹具均只返回合成响应，不读取外部模型凭据。
