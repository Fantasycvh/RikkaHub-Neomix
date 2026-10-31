# 设计：工具步骤执行耗时 + token 计数修正（上游 `8bb488e3a` / `e7f2c04f7` / `660b5d65f`）

- 日期：2026-10-26
- 状态：已落地（全量 2461 项测试 0 失败，真机验收待做）。A1 审计结论：**每步新起一条 assistant 消息**（`createdAt`=该步流式开始、`finishedAt`=该步结束，`GenerationHandler.kt:652`），工具执行/审批等待发生在步与步之间 ⇒ **本仓每消息 TPS 分母天然只含本步生成，无需 `generationMillis`**（与上游单消息跨全程的病灶不同构）；仅存续写（continue）追加同消息的极小缝差，忽略。`stepCount` 语义 = runLoop 实际发起的 LLM 请求数（置于各 break 之前防 off-by-one；审批恢复是新一次 runLoop 各自计数）
- 来源：上游 `8bb488e3a`（工具耗时显示）、`e7f2c04f7`（多轮 usage 覆盖 + TPS 分母）、`660b5d65f`（统计行改步数/速度/上下文占用 + 详情弹窗）；`9f7a7854c`（cached tokens 方言兜底）**本仓已有**（`ChatCompletionsAPI.kt:1016-1018` 兜底链），不再做

## 现状核实（本仓）

- **usage 落点**：`generateInternal` 流式 chunk 的 usage merge 进当步**最后一条消息**（`GenerationHandler.kt:1429-1438`）；工具循环每步新起 assistant 消息 ⇒ 每条消息的 `usage` = **该步最后一次请求**的用量（不是累加，不是覆盖错对象）。
- **缺口 ①**：没有**轮级累计**——多轮工具调用一轮的总消耗/步数无处可看；单条消息视角丢失「这轮一共烧了多少」。
- **缺口 ②（TPS 分母）**：`ChatMessageNerdLine.kt:97-99` TPS = `completionTokens / (createdAt→finishedAt 全程)`。横跨工具执行/审批等待的消息（待实现时核实具体是哪条消息横跨——审计项 A1），分母被工具等待灌水，TPS 偏低且不可比。
- **缺口 ③**：工具步骤无耗时显示（`UIMessagePart.Tool` 无时间字段）。

## 批次 1：工具步骤执行耗时（`8bb488e3a`，~2h）

1. **模型**：`UIMessagePart.Tool` 加 `startedAt: LocalDateTime? = null`、`finishedAt: LocalDateTime? = null`（可空默认 ⇒ 历史消息 JSON 反序列化兼容，无需迁移）。与 `UIMessage.createdAt/finishedAt` 同型（LocalDateTime），序列化随既有格式。
2. **写入**：`GenerationHandler` 工具执行入口（`executeToolWithTimeout` 调用侧，含审批等待**之后**的实际执行窗口——语义：**执行耗时**不含审批等待，与上游一致；审批自身时长已可见于审批卡）。`commitToolResult` 落 `finishedAt`。
3. **UI**：`ChatMessageTools` 步骤行右侧显示 `2.3s`；执行中（有 startedAt 无 finishedAt）**实时计时**（本地 `LaunchedEffect` 每 200ms 重compose，不进消息流）。
4. **web 模块**：若 `WebDto` 镜像 Tool part 则同步加字段（实现时核对）。

## 批次 2：token 计数修正（`e7f2c04f7` 核心 + `660b5d65f` 最小面）

### 2.1 模型（`UIMessage`）

- 加 `stepCount: Int = 0`（本轮步数，落在**最后一条** assistant 消息上；旧消息缺省 0 不显示）；
- usage 保持「每条消息 = 该步最后请求」语义不动（与上游 `lastUsage` 对齐，**不引入**累计字段——累计视图由 stepCount + 详情承载；`660b5d65f` 里被替换掉的 `contextTokens` 本仓本就没有）。

### 2.2 TPS 分母修正

- **审计项 A1（实现时第一步）**：核实工具循环里每步 assistant 消息的 `createdAt`（流式首 chunk 时？）与 `finishedAt`（步末？轮末？）——确定哪条消息的时长被工具等待灌水。
- 修法（按 A1 结论二选一）：
  - 若每步消息时长天然只含本步生成 ⇒ TPS 分母已对，只修「跨步横跨」的展示口径；
  - 若存在灌水 ⇒ `UIMessage` 加 `generationMillis: Long = 0`（handler 在流式起止累加，排除工具/审批段），nerd line TPS 分母改用它，无值回退 createdAt/finishedAt。

### 2.3 UI（最小面，`660b5d65f` 砍法）

- nerd line：`stepCount > 1` 时显示「N 步」徽标；prompt tokens 的语义标注已是「最后请求」（与上游最终形态一致）；
- **详情弹窗/占用条不做**（`660b5d65f` 的 300 行 UI 是产品面；本仓已有 `ContextUsageReadout` 容量面板承载占用视图，重复建设不跟——若后续要用量详情，应并入 usage_record 流水线，那里已有设计稿）。

### 2.4 测试与验证

- JVM：`stepCount` 写入（多步循环、单步、旧消息缺省）、`generationMillis` 累加（含工具段排除）、Tool part 新字段序列化/旧 JSON 兼容反序列化，~8 项；
- 真机：多轮工具调用一轮 → 步数徽标、每步耗时、TPS 不再被工具等待拉低；旧会话消息显示无异常。

## 落地顺序

批次 1（独立可交付）→ 批次 2（A1 审计先行）。两项都不阻塞彼此。
