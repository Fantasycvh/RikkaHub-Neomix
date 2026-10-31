# ask_user 待答期间的新消息与队列滞留（设计稿）

> 状态：**已落地（2026-10-26，`29715e296`）**。与设计偏差：无实质项——工具结果卡片仍走
> `ToolApprovalState.Denied(reason)` 展示（复用既有审批卡机制，文案区分「已跳过」），
> status 字段才是模型面的 `skipped`；源码守卫测试同步钉住三态。
> 真机验收项：AI 反问 → 跳过提问并发送 → 提问卡收尾、新消息开新轮、队列滞留消息放出。

## 1. 问题

AI 反问用户（`ask_user`，HITL 工具 `needsApproval=true`）待答期间，用户发新消息没有任何可见出路：

- **普通发送石沉大海**：`sendMessage`（`ChatService.kt:766`）正常入队，但
  `dispatchNextQueuedMessage` 的闸门（`:883-885`）「有活跃 Job 或任何 `isPending` 工具 →
  return null」把队首消息无限滞留，且无提示。
- **逃生口不可见**：「插队发送」入口（`sendInterrupting` :742）的 UI 条件是「**生成中** +
  输入非空」（语义留档 :776-782）。ask_user 待答时生成协程已正常结束（`isGenerating=false`、
  无活跃 Job）⇒ 入口不出现。
- **已排队消息同样被饿死**：4 处 dispatch 触发点（轮次结束 :593、入队 :761/:785/:803、
  队列操作 :813-845、派发任务完成 :958）全过同一道 pending 闸。用户答了 → 审批续跑原轮次
  → 结束后按序发出（语义正确）；永远不答 → 滞留且无提示。

## 2. 方案判定

**「新消息与待答问题并存」不成立**：pending 的 ask_user 必须在同一条会话时间线里给出
tool result；新用户消息插在其后而工具无结果，请求协议破损。真要做也得先合成结果——
塌缩成「跳过回答」。单线程会话里唯一的真选择是：**跳过时给 pending 的合成结果是什么、
以及用户以什么方式触发**。

## 3. 修复包（4 项）

1. **新增 `InterruptReason.SkippedQuestion`**：走既有 `finishInterruptedPendingTools`
   （:2002）收尾；合成工具结果用 `{"status":"skipped", ...}`（「用户跳过了这个问题，
   去处理新消息」）而非 `Denied`——模型知道用户没答、该转向新消息，后续行为比
   「已拒绝」更对路。参考 `QueueJumped` 的定制 approvalReason 文案（:181-183）。
2. **放宽插队入口 UI 条件**：`生成中` → `生成中 || 有 pending 审批/提问`。与 2026-10-13
   「两条路径分开、不悄悄替用户决定」的留档决策一致——不偷改普通发送语义，
   而是让打断入口在正确状态下可见；提问场景下入口文案标「跳过提问并发送」。
3. **普通发送的滞留可见化**：pending 存在时，新入队消息在队列 UI 上给出提示
   （「等待回答后再发送」级文案）。参考既有不对称：语音模式遇 pending 是显式报错
   （:798 `check` + `chat_page_voice_tools_before_resume`），文本模式静默。
4. **文案**：入口/提示字符串 3 语言（locale-tui）。

不改队列一行代码——pending 被跳过后，既有 dispatch 触发链会自动放出滞留消息。

## 4. 验证

- 单测：pending ask_user 下 sendInterrupting → 工具收尾为 skipped 结果文本、新消息发出；
  队列滞留消息在 skip 后被派发。
- 真机：AI 反问 → 直接打字发送（普通发送）→ 观察队列滞留提示；点「跳过提问并发送」→
  提问卡收尾为跳过、新消息立即开始新一轮；答了再发的既有路径不回归。

## 5. 明确不做

- 不把普通发送偷改成打断（留档决策）。
- 不做「携带 pending 问题进入新轮次」（协议不成立，§2）。
- 不做队列内消息与待答问题的优先级重排（队列重排本就未立项）。

## 6. 追加：审批/提问卡片上的显式「跳过」按钮（2026-10-26 第二批，`cf485b79a`）

输入行的跳过入口需要先打字；用户把跳过放到了**卡片本位**：

- `handleToolApproval` 增 `skip` 参数：pending 工具结果直接写
  `{"status":"skipped",...}` 合成输出（与 cancelToolByUser 同形态）+
  卡片置 `Denied(用户已跳过此步骤)`——output 已填 ⇒ isExecuted ⇒ 续跑不执行、直接推进
- **普通工具审批卡**（拒绝/批准之间）与 **ask_user 提问卡**（提交按钮旁）都有跳过；
  `SkippedQuestion` 文案泛化为「用户已跳过此步骤」（提问/工具通用）
- `onToolSkip` 管线：ChatPage → ChatList → ChatMessage → ChatMessageTools；
  浮窗两面（ChatFloatingContent/Windows）默认 null 不显示（最小面）
- 字符串 `chat_message_tool_skip` 3 语言
