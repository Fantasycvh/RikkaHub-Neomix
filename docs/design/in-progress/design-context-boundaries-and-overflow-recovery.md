# 上下文管理：安全切点与输入超窗恢复

> 状态：定稿待实施（2026-11-21 拍板，见 §6；原讨论稿 2026-11-18）。
> 对应 [同类项目台账 §三 #19、#20](../../peer-apps-benchmark.md)。

## 1. 目标与现状

先让压缩和截断保留完整的请求历史，再处理服务商明确拒绝输入超窗的情况。沿用消息树、压缩检查点和稀疏加载。模型收到的是「检查点摘要 + 激活路径上的近期原文」，生成结果仍按会话消息写回。

| 参考 | 对本仓的启发 |
|---|---|
| [OpenHands](../../references/other%20projects/openhands.md) | 先求合法切点，再按预算挑选。服务商确认超窗后，可以用新投影恢复当前生成步骤。 |
| [Yuihub §四.8 B](../../references/other%20projects/yuihub.md) | 工具输出限幅和卸载已接入，可先释放工具结果占用。已有文件投影可以保留回读方式。 |
| [billion-context](../../references/other%20projects/billion-context.md) | 检查点推进时应考虑实际节省和缓存前缀稳定性。出站体积与 token 容量分别计量。 |
| [NMoi D143 在本仓的评估 §1.5](../completed/design-auto-compaction.md) | 普通会话窗口优先从真实用户消息开始。中途续跑按工具批次的完成状态选边界。 |

当前 `CompressionPlanner.estimateCompressionPlan` 按 token 和保留条数切分，`limitContext` 有独立的切点回退逻辑。`balancedForRequest()` 在发送前能补缺失工具结果、移除孤儿结果，已经失去的真实结果无法靠配平恢复。自动压缩覆盖轮间和工具批次结束后的主动检查，读取 `autoCompressEnabled`，默认关闭。服务商拒绝请求后的路径目前交给通用错误处理和备用模型策略。

## 2. 第一阶段：统一安全切点

### 2.1 判定规则

以选中的激活路径为准，切点用「保留窗口的第一条节点」表达。这个节点之前的一段可由检查点覆盖，检查点记录被覆盖的最后一个节点。跨节点依赖的两端如果分别落在切点两侧，该切点无效。整个依赖可以随历史一起进入摘要。

- **同节点内容。** 现代 `UIMessagePart.Tool` 内嵌输出，完整推理块及其签名也在消息 part 内。切分以整条消息为单位，保留侧的 part 顺序和签名按原请求协议回传。遇到已证实的跨节点签名依赖或分段批次，按同一依赖区间处理。
- **跨节点内容。** 旧版或导入的 `ToolCall` / `ToolResult` 按 `toolCallId` 和该路径上的配对关系识别，批次根据已知归属整体保护。用工具名或「前面有一个未执行工具」推断配对会误关联。对本来就缺一端或配对不明确的历史，沿用出口配平，必要时回退切点。
- **轮次形态。** 手动压缩、轮间压缩和 `limitContext` 先按各自的保留比例、条数下限和滞回确定候选，再向历史更早处回退到合法边界。优先选可用的真实 `USER` 轮次起点，合成检查点与通知不视作轮次起点。若只能找到较近的安全节点边界，由协议完整性决定是否可用。
- **进行中的工具。** 待审批、执行中或结果尚未完整写回的批次留在原文侧。中途压缩可在整批结果回填后收束当前回复，沿用现有续跑检查点与新回复机制。生成器手里的快照是这一时刻的权威来源。

候选回退后，如果没有能压缩的前缀，或连加入摘要后的预计用量都无法明显减少，直接给出无变化结果。节省以 token 估算和剩余空间衡量，OpenHands 的事件条数比例仅作参考。相同路径、内容和预算应得到相同切点，以保留条数截断的确定性和前缀稳定性。

### 2.2 沿生成与保存流程接入

1. `CompressionPlanner` 和 `limitContext` 共用安全判定，各自保留现有候选计算和滞回。已有检查点、导入检查点和稀疏窗口使用各自的路径解析；持久化压缩沿用现有 hydrate 流程。
2. `ChatService.compressToCheckpoint` 规划时记录激活路径、切点两侧相关节点及被摘要内容的版本依据。摘要生成可能跨过编辑、分支切换或重生成，保存前从当前会话重新确认边界与摘要来源仍匹配。内容在相同 ID 下变更时也要识别。复核与提交应受同一会话状态保护，避免在两步之间发生新的编辑。
3. 发送窗口仍按 live 消息重算，后续工具结果继续进入模型请求。请求出口配平保留为存量历史兜底，并可用其补结果、丢孤儿的计数检验新切点是否引入退化。
4. 对现有物理清理选项，在检查点复核通过后沿用清理守卫，清理前再次确认保留子树和边界关联。过期计划以无变化结束。

## 3. 第二阶段：输入超窗后只重发当前步骤

### 3.1 触发与恢复

首版沿用自动压缩开关，覆盖普通会话发送和符合条件的工具续跑。历史重生成继续使用其指定范围。恢复次数绑定到同一逻辑用户回合，审批恢复和模型内部重试共享这个计数。候选方案只在服务商明确指出**输入上下文超窗**、失败请求**没有产生文本、推理或工具调用增量**时进入。

优先从 provider 错误中保留结构化 code/type，再为确有样本的兼容网关增加窄文本匹配。输入超窗单独路由。输出 `finish_reason=length` 走现有续写，HTTP 413 走请求体体积诊断，工具配对错误按历史结构问题处理。普通 400、网络失败、限流和用户取消走各自的现行路径。

1. 在 `GenerationHandler` 内捕获当前模型步骤的零增量输入超窗，记录该次请求所用模型、预算、请求基线和原错误。先于现有整轮 fallback 消费一次恢复机会。
2. 复用 `ChatService` 的压缩规划和摘要能力。当前用户输入、仍需使用的已执行工具结果与待审批状态受到保护，优先从较早历史腾空间。一个工具结果独占窗口时，优先评估现有可回读的工具结果卸载；固定提示词或受保护的近期内容已经用尽窗口时，直接返回原因。
3. 使用相同模型、相同逻辑步骤和实时消息快照重新装配候选。以经过输入转换和配平的消息比较前后用量，将工具 schema、输出预留和有效模型上限计入预算。服务商明确给出可解析的上限时纳入判断。仅当候选确实缩小、有可用余量、且分支与原步骤仍匹配时重发一次。
4. 首版建议把紧急检查点先作为当前步骤的请求投影，模型接受新投影后再按现有检查点契约考虑落盘。恢复尝试期间保留原始消息和工具执行状态。压缩失败、投影无收益、重发仍超窗时，保留原超窗错误和已有草稿，附恢复结果。取消继续向上传播。

需要在实现评审时确认流式请求的检查点切换时点。首个增量到达可证明新请求已被接受，但流式回写和检查点发布必须基于同一聊天快照。可先将自动恢复限定为请求期投影，在生成成功边界提交检查点；之后的工具步骤继续看到最新回填结果。无论采用哪一种提交边界，**已执行工具的调用次数保持一次**，恢复入口位于模型请求处，避开外层整轮入口。

### 3.2 对齐预算与结果

统一「有效输入预算」的参数来源。当前工具限幅、装配前后估算、UI 环和自动压缩读取的窗口或消息阶段有所不同。例如请求前卸载发生在文档展开和 OCR 之前，`enforceContextBudget` 直接取模型有效长度，而部分调用点另传全局覆盖。保留运行中的快速估算，恢复判定以同模型、同口径重新组装后的候选为准。无服务商计量时，对外标为估算。

已有容量环、容量面板压缩入口和阶段进度。后续可在原入口补「估算减少 X token」「无明显收益」「失败」，前后统一估算口径。检查点写入和窗口减少是两个独立信号；比较时要固定相同的请求基线和配置。HTTP 413 的体积限额另用出站字节数诊断。

## 4. 摘要质量后续

默认摘要已有八段，工具、文件与媒体痕迹也已有确定性附录。可以在既有结构内补任务状态迁移，要求保留有明确来源的任务 ID、最后确认状态、验证结果和下一步。检查点之后仍可能需要一手证据时，优先保留已有可回读句柄。用连续两次压缩的同一任务对比续跑行为，观察是否丢失用户纠正、把已完成的任务标成待办，或重复执行工具。 [多步压缩设计](../in-progress/design-multi-pass-compression.md) · [Senera 分层上下文 §7.4](../../references/other%20projects/senera.md)

## 5. 验收

| 场景 | 预期 |
|---|---|
| 旧版跨节点结果、并行工具批次、带签名的工具续跑 | 压缩与 `limitContext` 均保留完整依赖；保留侧签名、part 顺序通过 provider 编码检查。 |
| 最近 N 条的候选切点落在配对中间，或回退后前缀过短 | 安全回退仍满足保留下限；没有有效收益时不调用摘要服务。 |
| 摘要生成中切分支，或在原消息 ID 上编辑内容 | 不使用过期计划写入检查点或清理历史；当前分支按最新状态组装。 |
| 服务商在首个请求或工具后的请求报输入超窗 | 新投影确认有收益后只重发失败步骤一次，已执行工具计数为一次。 |
| 流式半截、待审批、第二次超窗、固定内容超窗、取消 | 保留现场和相应错误或中止结果；回合内恢复上限有效。 |
| 输出长度终止、413、普通 400、限流、工具配对错误 | 分类正确，保有当前各自的处理路径。 |

交付顺序：先做共享切点和切后验证，再接错误分类、同一步重试和预算核对。压缩反馈与摘要质量独立验证。

## 6. 实施拍板（2026-11-21）

- **范围**：两阶段主项（§2 统一安全切点 + §3 溢出恢复）+ §4 摘要模板任务状态迁移（#3）搭车；#4 完成报告、#6 净化兜底脱敏后续另起（#5 计量环已基本在位）。
- **§3.1 落盘边界评审项定案：立即落盘。** 复用 midTurn 提交机制（快照回写 + NonCancellable + 指纹复核），不做「请求期投影」——理由：投影方案要新造无指纹的临时窗口状态并自建延迟提交的边界复核，实现面大一个量级；立即落盘在重试再失败时历史已压缩是净收益，原错误照常透传。
- **代码核对补充（2026-11-21 全量探查，与 §2/§3 假设逐条对上）**：
  - 零增量旗标已存在：`GenerationHandler.kt:1662` `receivedAssistantContent` + `hasGenerationContent()`（1863）；流式 catch 失败回滚到快照（1753），零增量失败后消息列表与请求前逐字节一致；`GenerationPartialFailureException`（partial 保草稿路径）豁免恢复。
  - 插入点：runLoop 内镜像 `ContextGateHardStop` catch（684-701）；`stepsExecuted++`（759）仅在成功后执行，恢复重试不涨步数；整轮 fallback（1450-1461）对恢复成功的一步不可见（异常不出 runLoop）。
  - 400 现被 `isRetryableGenerationError` 判不可重试直落 fallback——溢出分类（SDK `LONG_PROMPT_PATTERNS` 8 条移植，不限状态码）是纯新增，无回归面。
  - 跨节点依赖面收窄：现代 `Tool` part call+output 内嵌同 part、推理签名在 part metadata 内均自洽；**唯一跨节点依赖 = 旧版 `ToolCall`/`ToolResult` 按 `toolCallId` 配对**（含物理清理守卫六与 `alignContextStart` 扩展的判定对象）。
  - 恢复压缩不用 `midTurnCompressionPlan` 压全窗语义：保护区尾部（当前用户轮起）走现有 `keepRecentMessages` 下限直达 `effectiveMinKeep`；需给 `compressToCheckpoint` 的 `midTurn` 标志拆分「压全窗计划」与「快照回写 + NonCancellable 提交」两件事。
  - 两阶段零 DB 迁移（DB 仍 78）；一次性旗标 v1 = 生成内一次（`visibleAnswerRecovered` 模式），跨审批恢复共享计数留接口。

## 7. 阶段一实施记录（2026-11-21，本地未提交）

**落地件**：
- **共享判定** `ai/.../ui/ContextCutSafety.kt`（新）：`alignCutIndex(candidate, preferUserTurn)` 三判据 R1/R2/R3 + `hasUnfinishedToolCalls()`；`limitContext` 接入并退役旧启发式 `alignContextStart`。
- **planner** `CompressionPlanner.kt`：`estimateCompressionPlan` 候选过共享判定（骑线回退 + 轮对齐）+ 新增**进度闸** `COMPRESS_MIN_PROGRESS_RATIO = 0.1`（OpenHands minimum_progress 同域，可压缩前缀不足一成判无变化）；`midTurnCompressionPlan` 改为「压到窗口末尾、但**未收束工具批整批留原文侧**」（正常触发点行为不变）。
- **守卫六** `ConversationLoadWindow.hasLegacyToolPairAcrossPruneBoundary`（新，双向配对检查、扫全部候选消息）+ `pruneCompressedPrefix` 接线（新增必传 `nodeBodies` 参数，候选缺正文即整体放弃）+ ChatService commit 传入会话节点。

**实施中定案的语义细节**（相对 §2.1 的细化）：
- **R1 扫描范围 = 整个保留区 `[aligned, size)`**：R3 对齐会把候选后的区段纳入保留区、可能新引入骑线，边界局部检查兜不住——区域扫描一次修完（跳转目标 = 最早涉事调用所在消息）。`candidate` 之外的既有暴露面仍归出口 `balancedForRequest` 兜底（本判定之前也从未检查过，无回归面）。
- **现代 `Tool` part 自含性已实锤**（`ChatCompletionsAPI` 同 part emit `tool_calls` + `role:tool` 结果）⇒ 跨节点骑线只剩旧版 `ToolCall`/`ToolResult` 与「同 id 双写残留」（call/result 分处两条消息的形态，`MessageTest` 既有契约）。
- **R2 大半被 R3 掩映**（轮对齐目标 = 同一个 USER 起点），独立价值仅在「对齐点自身带未收束调用」的病态形态；`preferUserTurn = false`（中途压缩）只跑 R1。
- **轮对齐会改变 limitContext 阶梯精确期望**：`MessageTest` 四处期望同步更新（上界放宽到 `limit`，tiny-limit 放宽到 `limit+1`）；前缀稳定性保持（常规追加不翻转判定）。
- **进度闸发现**：`estimateTokenCount` 只数 Text/Tool 输出——纯 `ToolResult` 节点计 0 token、token 驱动循环永不把它们切出保留区；测试 fixture 需给 result 节点配文本段撑 token 量。

**验证**：`:ai` 全量 + `:app` 全量单测绿（唯一失败 `ImportFormatTest` 为干净树上的既有失败，与本线无关——stash 对照确认）。未做真机验证；未提交。

## 8. 阶段二实施记录（2026-11-21，本地未提交）

**落地件**：
- **溢出分类** `GenerationErrorPolicy.kt`：`isInputContextOverflowError` + `CONTEXT_OVERFLOW_MARKERS`（SDK `LONG_PROMPT_PATTERNS` 8 条全量移植，小写子串、不限状态码）；工具配对类 400 判据存档注释不进 v1（守卫六已从源头堵）。
- **恢复编排** `GenerationHandler.kt`：`generateText` 新增 `contextOverflowRecover` 回调 + `ContextOverflowRecoveryRequest`（messages/stepsExecuted/modelId/windowTokens/errorMessage）+ `contextOverflowProtectedStart` 纯函数（保护区起点）；runLoop 内 `ContextGateHardStop` catch 之后镜像加三个 catch（Cancellation / GenerationPartialFailure / 分类命中编排，其余透传），一次性旗标 `overflowRecoveryTried`（镜像 `visibleAnswerRecovered`）；零增量判据 = 步级 `sawRequestDelta`（取自 onUpdateMessages 的 coalescible 旗标）。恢复成功 → 刷新压缩窗口 → `continue` 重试同一步（`stepsExecuted++` 在成功后执行，失败步不进步数徽标；异常不出 runLoop ⇒ 整轮 fallback 对恢复成功的一步不可见）。
- **ChatService 回调**：`recoverContextOverflow` 闭包——闸门 `autoCompressEnabled`；保护区 = 最后真实 USER 轮起点起全部消息；`compressToCheckpoint(midTurn = true, compressAll = false, keepRecentMessages = protectedCount)` **立即落盘**（拍板 2026-11-21）；`followModel` 按 request.modelId 解析；窗口口径 = `effectiveContextLength(settings.contextLengthOverride)`（§3.2 口径统一）。重新生成路径 null（与 midTurnCompress 同款理由）。
- **compressToCheckpoint** 新增 `compressAll: Boolean = true`：false 时走 `estimateCompressionPlan`（`keepRecentMessages` 为保护区下限）而非压全窗——midTurn 的「快照回写 + NonCancellable + MID_TURN 摘要指令」语义保留，仅计划形状切换。
- **#3 搭车**：`DEFAULT_COMPRESS_PROMPT` Rules 补任务状态迁移强约束（任务 ID/最后确认状态/验证结果/下一步逐字带过边界）。

**实施中定案的语义细节**：
- **零增量判据实现**：不比消息实例（回调会重assign 外层列表），用回调 `coalescible` 旗标——它只在增量类 chunk（Text/Reasoning/ToolCall/ServerTool 增量 + Usage）为真；超窗 400 是「无任何 chunk 直接抛错」形态，Usage 误标实际不可达。增量后失败（即便被回滚）保守不恢复。
- **#1812 半截通路显式豁免**：`GenerationPartialFailureException` 在恢复 catch 之前单独重抛——恢复重发会把已计费内容再生成一遍。
- **测试基建**：`ContextOverflowRecoveryTest` 的 `ScriptedProvider` 脚本为 `suspend (callIndex, messages, emit)` 三参签名（部分增量场景要「emit 后再抛」，emit 必须在 flow 作用域内）；七用例覆盖恢复/二次超窗/无收益/非溢出 400/部分增量豁免/null 回调/保护区起点。

**验证**：`:ai` + `:app` 全量单测绿（3672 项，唯一失败仍是既有 `ImportFormatTest`）。未做真机验证（挂账：真实供应商超窗实弹 + 旧数据遗留配对压缩/清理）。未提交。
