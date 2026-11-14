# 保留任务并立即处理新消息

日期：2026-11-08。第二档实施记录；仅本地草稿。

## 用户可见行为

原生聊天在生成中或等待交互、输入非空且未编辑历史消息时，显示“保留任务并立即发送”。Web 在生成中提供同名操作，调用发送接口的 `preserveTasks: true`。普通发送继续排队，原有停止与取消插队保持原语义。

操作后，当前父回复保存半截内容并让路，新消息优先于已排队消息执行。已经启动的子代理和 `workspace_run_background` 继续运行，保留原来的 run/task ID、scope、能力信封、截止时间和审批入口。尚未执行的工具、父回复正在执行的普通前台工具按插队规则收尾。此功能不迁移任意前台工具，不同时运行两个会改写同一会话的父生成循环。

## 执行与持久化

- `TaskHandoffRegistry` 按 scope 登记正在等待的子代理。实际 worker 在 AppScope 执行；普通取消显式取消并等待 worker 收尾，保留操作先标记当前条目，再取消等待者。
- `ConversationSession.cancelJobs(preserveTasks = true)` 仅抑制这些旧 Job 的后台 shell 清理回调。新轮次创建新 owner，之后停止新轮次不会误杀旧任务。
- 被转换的子代理使用已有字符串列 `delivery_mode = handoff`，无需升级数据库版本。终态与 pending 标记仍由同一 SQL 更新提交。进度更新只写进度列，不能覆盖并发的 handoff、投递确认或终态。
- worker 与等待者同时完成时，按 run ID 补 handoff 并将终态置为待投递。整 scope 的补偿只转未完成的 await 和 notice 任务，避免重新投递已经同步返回的结果。
- 后台 shell 总是捕获原分支目的地；自动通知关闭时只保存 dormant 目的地。主动保留才激活这些目的地。启动已完成但回执尚未进入父会话的 shell，由真实 task 快照补回原 completionEventId。
- 主动保留是一次明确操作，结果投递不依赖自动后台预设开关或自动 shell Notice 开关；没有改动这些开关的默认值。

## 新意图与旧任务结果

让路期间设置 session fence，收件箱不能在恢复工具结果时追加消息。用户新消息先入保留队列；持久化失败时输入仍留在暂停队列并显示错误。

被保留 root 的 shell Notice 不独立唤醒模型，也不注入新轮次的步骤边界；空闲后再投递。子代理结果沿用空闲投递泵，不自动续跑父生成，包含原始 goal 和 run ID，并要求继续优先处理最新用户请求。handoff 结果要求当前选中分支仍含对应工具回执，切换分支不会把结果挪到无关分支。

任务审批继续走运行记录和任务中心。进程死亡不假装任务仍能执行：已有启动对账将未完成 agent run 记为 interrupted，并保留 handoff 的结果投递；shell 保持原有进程与日志回收边界，不承诺跨进程恢复执行。

## 验证入口

详见 [2026-11-08 验证记录](../verification/task-handoff-2026-11-08.md)。设计承接 [Notice/收件箱方案](design-task-notice-inbox-and-proactive-sync.md) 与 [定时触发及记忆同步方案](design-scheduled-random-trigger-and-memory-sync.md)，复用现有运行账本和收件箱，不再引入第二套任务系统。
