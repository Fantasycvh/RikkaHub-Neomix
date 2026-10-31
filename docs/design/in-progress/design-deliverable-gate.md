# 设计稿：交付物契约 + 收尾闸门（loop-end gate）

> 状态：**rev 2——P1 已实现（2026-10-31，本地未提交/未推送）**。落点：
> `data/ai/LoopGate.kt`（DeliverableChecker / LoopGateConfig / evaluateLoopGate）、
> `GenerationHandler.kt` 空工具分支出口、`SubagentRunner.buildSubagentLoopGate`、
> `ScheduledTaskRunner.buildTaskLoopGate`（v55 `deliverables` / `gate_retries` 列）；
> 表单「交付物」字段与三语文案已落。单测 `LoopGateTest` + 守卫 `LoopGateWiringGuardTest` 全绿，
> 真机安装/迁移/启动通过。rev 1 修正一处：§3.1 原说「两处出口是同一路径的表里两面」——
> 实际 `:1070 executedTools.isEmpty()` 是**待审批等待**出口（模型没有宣告结束），不是自然出口；
> 实现只挂 `:756` 空工具分支，守卫测试已钉死。原 rev 1 草稿见文末附注。另立设计，不挂在
> `design-scheduled-ai-tasks.md` 名下；该稿 §十 P3 只留指针。
> 来源：`docs/purrcat-analysis.md` §五 A3（交付物契约）+ A1（收尾闸门），吸收语义、不吸收 PurrCat 的 YAML DSL。
>
> **一句话**：让「没真的交付就不许结束」从提示词自觉变成可判定的闸门——
> 声明了交付物就查文件（确定性），没声明就出口自检（提示式），上限 3 次强制放行。

## 〇、决议记录（2026-10-31 会话拍板）

| 分叉 | 决议 |
|---|---|
| 判定者 | **混合**：先确定性（deliverables 文件校验），后自检（追加 user 提示） |
| 场景限定 | 子代理运行 / 定时任务会话 /（workspace 工作轮 P2 再议）；普通聊天恒注入式 |
| 重试上限 | 固定 3（`loop_end_max_retry` 原味），不开放自由数字 |
| 排序 | A3 先行（半天），A1 踩在 A3 的校验器上 |

## 一、哲学约束（为什么必须限定场景）

本仓刻意「只提醒不拦截」：`RuleEngine` 三条规则全部走注入（`RuleEngine.kt`），
`ToolCallLoopGuard` 只做优雅停止、不打回。这是聊天场景的正确取舍——强制闸门会让
用户「发完消息却结束不了」。

闸门是**独立的 opt-in 机制**，不碰 `RuleEngine`：

- 允许挂闸门的场景 = 「有明确任务、结束由模型宣告、跑完没人盯着」的三者交集：
  **子代理运行**（`SubagentRunner`）、**定时任务会话**（`ScheduledTaskRunner` 经
  ChatService 的生成）。workspace 工作轮 P2 再议（它已有 `workspace_turn_budget`，
  边际收益存疑）。
- 普通聊天**永不挂闸门**——以源码守卫测试钉死（§六）。

## 二、A3：子代理交付物契约

### 2.1 声明侧

`SubagentPreset` 增加可空字段（`app/src/main/java/app/androidrik/rikkahub/data/model/SubagentPreset.kt`）：

```kotlin
/** 交付物契约：相对 workspace 根的路径列表；null = 不设闸（默认，行为不变） */
val deliverables: List<String>? = null,
```

- 存 `Settings.subagentPresets` 的 JSON，**无需 Room 迁移**；旧 JSON 缺字段 → null → 行为逐位不变。
- 内置三个 preset（research / review / digest）暂不预置 deliverables——等有真实诉求再加，避免拍脑袋路径。

### 2.2 校验器（纯函数，A1 复用同一份）

```kotlin
object DeliverableChecker {
    /** 逐个校验「存在且非空」；返回缺失清单（空 = 通过） */
    fun missing(root: Path?, deliverables: List<String>): List<String>
}
```

- 路径解析：一律相对 workspace 根；拒绝绝对路径与 `..` 越界（复用 workspace 既有 canonicalPath 校验）。
- 「非空」= 普通文件且 size > 0。目录型交付 v1 不支持（声明成目录下的具体文件）。
- **判定是确定性的**：文件系统是天然验收器，模型没有解释权。

### 2.3 执行侧（SubagentRunner）

现状：`SubagentRunner.kt:753` `RunOutcome.Completed(summary)` 只要求最终文本非空
（`:798` 注释明确「空响应不得记成 COMPLETED」）。

增量语义（PurrCat 原味：检查类动作的唯一产出是往历史追加一条 `role=user` 提示）：

1. 子代理生成循环自然结束（无新工具调用）且 preset 声明了 deliverables 时，跑
   `DeliverableChecker`；
2. **全部通过** → 静默，按现行路径记 COMPLETED；
3. **有缺失** → 向子代理会话追加一条 user 提示（措辞见 §五），让它继续补——**不是**
   把错误报给父代理（父代理只在乎最终结果）；
4. 累计打回达 **3 次**仍缺 → 强制放行：记 COMPLETED，但 summary 尾部追加
   「交付物缺失：X、Y（已强制放行）」——宁可带瑕疵交付，不可无限烧钱；
5. 声明了 deliverables 但 workspace 不可用（root 为 null）→ 降级为自检提示（§四.2 同款），不硬失败。

## 三、A1：生成循环出口闸门

### 3.1 挂载点

`GenerationHandler.kt:544` 工具循环的唯一「自然出口」：`:756-757` 「no tool calls, break」
（以及与之等效的 `:1070-1072` `executedTools.isEmpty() → break` 收尾分支——两处是同一条
路径的表里两面，实现时以「空工具分支」一处为准，守卫测试钉死）。

**只有这个出口触发闸门**。以下出口一律**不触发**（模型没宣告结束，是环境掐的）：

- `stopNotice` 各分支（思考退化 / 工具循环停止 / 拒绝超限 / 上下文闸 A/B）；
- `ContextGateHardStop` 提前 return（`:622-639`）；
- 审批挂起等待（`:794` 一带，本轮根本没结束）；
- `maxSteps` 耗尽（`:544` 循环走完）——这是异常退出，闸门打回只会雪上加霜。

### 3.2 配置载体：谁有权挂闸门

| 场景 | 闸门配置来源 | 落库 |
|---|---|---|
| 子代理 | `SubagentPreset.deliverables`（§二，确定性）+ 缺失时同轮自检 | Settings JSON，无迁移 |
| 定时任务 | `ScheduledTaskEntity.deliverables`（同语义，TEXT 存 JSON 数组，NULL=不设闸） | DB v55（与隐形会话的 v55 合批，见 design-proactive-task-management.md §二） |
| workspace 工作轮 | P2 再议 | — |

定时任务侧不新增 per-task 开关布尔：**`deliverables` 非空即挂闸**，空即不挂——少一个
没人理解的开关，语义与子代理侧完全一致。任务的注入 prompt 里同步声明交付物要求
（runner 拼 prompt 时带上），让模型一开始就知道验收标准。

### 3.3 出口语义（GenerationHandler）

自然出口处，若本轮上下文携带有效闸门（经参数显式传入 `LoopGateConfig?`，**不是**
在 handler 里反查场景——handler 保持场景无关）：

1. 跑确定性校验（有 deliverables 时）：通过 → 照旧 break；缺失 → 追加 user 提示 +
   计数 +1，`continue` 循环；
2. 无 deliverables 但场景允许自检（定时任务会话）：注入自检提示（§五措辞），模型的
   下一步响应若宣称未完成则继续循环——**自检的「不过」信号 = 模型在响应里宣告未完成
   并发起实质动作**；纯文本「完成了」即视为通过（见 §七 诚实边界）；
2.5. **打回时重置 LoopGuard**（2026-10-31 用户决议）：打回提示是一条新的用户指令，
   打回后模型重复上一步调用（如重读同一文件确认）是合理复查而非失控——不重置滑动窗口
   会被 `ToolCallLoopGuard` 误判成工具循环提前掐轮（「工具调用重复，已结束本轮」），
   任务侧则记 FAILED。闸门与护栏各管各的：护栏管「同参数空转」，闸门管「交付缺失」，
   打回瞬间清一次护栏窗口让两者不打架。
3. 计数达 3 → 强制放行 break，发一条 `GenerationChunk.Notice`（`LoopGateForcePassed`，
   含缺失清单）供 UI 与账本消费；
4. 追加的 user 提示走正常消息管线（transformer / 压缩窗口照常），UI 里**可见**——
   不做隐藏消息（诚实原则：用户看到的是真实发生的事）。

### 3.4 账本可见性

`scheduled_task_runs` 加一列 `gate_retries INTEGER NOT NULL DEFAULT 0`（随 v55 迁移）：
runner 从 `GenerationChunk` 侧累计闸门打回次数，收尾写入。

- OK 判定不受影响：`evaluateRunStatus()` 的「新增 assistant 消息」必要条件在打回路径
  天然满足；强制放行也记 OK（瑕疵交付也是交付），缺失清单进通知正文；
- 运行历史详情页（二级页）显示「闸门打回 N 次」——「一轮实跑了 4 次」必须看得到。

## 四、注入措辞（三语，走 locale-tui）

### 4.1 确定性缺失（子代理与定时任务共用）

> 任务未结束：未检测到声明的交付物「{paths}」。请补齐后再结束；若确实无法产出，
> 明确说明原因。

### 4.2 自检（定时任务会话）

> 结束前自检：本轮任务是否已实际完成并产出可用结果？若已完成，直接给出结论性回复；
> 若没有，继续执行。

措辞约束：不打回整轮重跑（模型已有进度全在上下文里），只要求「继续或明确收束」；
不暗示工具失败（与 `ToolResultBalancingTransformer` 同一纪律）。

## 五、分期

- **P1（本稿）**：`DeliverableChecker` + `SubagentPreset.deliverables`（A3，先行）；
  `ScheduledTaskEntity.deliverables` + `gate_retries` 列（随 v55）+ GenerationHandler
  出口闸门（确定性 + 定时任务自检）+ 运行历史显示打回次数。
- **P2（另议）**：workspace 工作轮挂闸；自检判定强化（结构化输出代替文本宣告）。

## 六、测试策略（沿用源码守卫 + 纯函数惯例）

| 覆盖点 | 方式 |
|---|---|
| `DeliverableChecker`：存在/缺失/空文件/绝对路径拒绝/`..` 越界拒绝/目录拒绝 | 纯函数单测 |
| 闸门只在「空工具分支」触发；stopNotice / 硬限 / maxSteps 耗尽 / 审批挂起路径**必须**不触发 | `GenerationHandlerGateGuardTest`（源码守卫，仿 `ScheduledTaskGuardTest`） |
| 普通聊天路径**不得**传入 `LoopGateConfig`（ChatService 用户入口恒 null） | 同上守卫 |
| 打回计数达 3 强制放行、通知含缺失清单 | 纯函数 + Fake |
| runner 写 `gate_retries`；强制放行记 OK | 纯函数 |
| 旧 JSON 缺 `deliverables` → null 行为逐位不变 | 序列化回归 |

## 七、诚实边界

1. **自检档可被自欺**：模型宣称完成即放行——自检只是「把结束变成一个显式动作」，
   不是验收。真正的验收只有确定性档。这也是混合档存在的理由：能用文件验收的场景
   都应声明 deliverables。
2. **确定性档只覆盖文件**：汇报/总结类任务没有文件可查，只能靠自检或任务 prompt 里
   自带的结构要求（如「结论必须写进 report.md」——把非文件任务改造成文件任务）。
3. **打回 = 烧钱**：每打回一轮多一次 LLM 调用，上限 3 是硬顶；任务 prompt 质量差
   导致的连环打回靠管理页的 `gate_retries` 暴露，不做自动熔断（v1 观察期）。
4. **强制放行不是失败**：达上限的交付带缺失清单记 OK——「没交付」的信号已经通过
   三轮打回给过模型机会，再挂下去只浪费钱。
5. **闸门提示进上下文**：追加的 user 消息占用窗口、参与压缩，与「用户追问一句」
   同成本，不做特殊豁免。
