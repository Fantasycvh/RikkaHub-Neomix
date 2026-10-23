# 上下文净化：清掉「已判定退化的思考残留」（而不只是让它停）

> 状态：**设计草案（2026-10-19，未实现，未动代码）**。本文只讨论机制与取舍。
>
> 相关：`data/ai/ThinkingDegenerationGuard.kt`（护栏家族第四员）、
> `design-auto-compaction.md`（压缩管「预算」，本文管「质量」）、
> `design-subagent-context.md` §护栏家族分工、`design-whole-turn-folding.md`（只改显示、不改上下文）。

## 1. 问题：护栏只止损，不清毒

现状（已核实，2026-10-19）：

| 事实 | 位置 |
|---|---|
| 思考退化护栏只做**停止**：调用内命中抛 `ThinkingDegenerationCut` 断流，调用边界命中则跳出步骤循环；**不回滚、不重试、不 fallback** | `ThinkingDegenerationGuard.kt:30-33`、`GenerationHandler.kt:606-638`、`:1240-1252` |
| 停止提示**故意不写进对话**，只 `addError` 成一条瞬时错误条 | `ChatService.kt:1452-1455`、`:1494-1511` |
| 当时给的理由：*「用 addError 而非写进对话：后者会被持久化并在下一轮当上下文发给模型，变成自己教自己的噪音」* | `ChatService.kt:1452-1455` |
| 但**被判定退化的那几条助手消息本身**照常落库（护栏不删内容），于是下一轮照样被组装进请求 | `GenerationHandler.kt` 停止路径；`PromptAssembly.kt:260-271` |

也就是说：**护栏挡住了「继续写」，但没挡住「继续看」**。「自己教自己」这句注释预判了机制，却只处理了**提示**这条支路——**痕迹**那条支路没人管。

这正是本设计的出发点：不是再叠一条规则去劝模型别重复（护栏语义），而是**把已经蒸出来的退化残留从上下文里拿掉**。

## 2. 边界：净化能做什么、不能做什么

**能做（客户端可判定）**：字面重复类退化——逐字周期、跨调用复述（containment ≥ 0.8）、连续零产出调用、同签名工具空转。判据与护栏**同源**（`ThinkingDegenerationGuard.containment/minimalPeriod/distinctChars` 都是 `internal`，同包可直接复用），避免两套判据漂移。

**不能做（不假装能做）**：
- **语义**漂移：越写越浅、结论自相矛盾、换了话题——客户端无法判定；
- **不引入低熵比值**：2026-10-09 的标定已经证明覆盖率类指标对合法内容（表格 0.72、日志 0.76、CSS 0.82、JSON dump 0.84）与退化样本（0.92–0.99）只差 0.06，做不了截断边界（`ThinkingDegenerationGuard.kt:24-28`）；
- 不改变**正常内容**的发送：净化只对「本来就会被护栏判停」的东西生效。

## 3. 现状核实：可挂载点

| 层 | 现状 | 位置 |
|---|---|---|
| 请求组装 | `buildInternalMessagesWithPlan` = 压缩窗口 + `limitContext` + system + 尾部合成块；**真实请求与拼装预览共用** | `PromptAssembly.kt:281-356`、`:260-271` |
| 发送前最后一次改写 | `transforms(...)` → `balancedForRequest()`（会丢孤儿 ToolResult、补合成结果，**请求期改写、不落库、不变时返回同一实例**） | `GenerationHandler.kt:1161-1171`、`ToolResultBalancingTransformer.kt:34-39` |
| 上限裁剪 | `limitContext` **按条数**、阶梯滞回、`alignContextStart` 保证不拆工具对；`contextMessageLimit` 默认 **0 = 不裁** | `Message.kt:352-365`、`:373-413`、`Assistant.kt:65` |
| 压缩 | token 驱动、检查点替换、**不动 messageNodes**（「轨迹只追加」） | `Conversation.kt:212-225`、`:231` |
| 推理回传 | OpenAI 兼容路径**回传** `reasoning_content`（`includeHistoryReasoning` 默认 true，部分网关缺字段直接 400）；Claude 只回传**带签名**的块（无签名块丢弃）；Gemini **全部丢弃** | `ProviderSetting.kt:240`、`ChatCompletionsAPI.kt:653-686`、`ClaudeProvider.kt:515-532`、`GoogleProvider.kt:732-774` |
| 可观测 | 拼装预览与真实请求**同函数**，按来源分段；`PromptDebugState` 留最近两次实发列表 | `PromptInspector.kt:279/301`、`PromptDebugState.kt:24` |
| 护栏状态 | 全在 `ConversationSession` 内存里，`generateInternal` **看不到**（要么穿参，要么不依赖它） | `ConversationSession.kt:99/124/137`、`:165-171` |

**推论**：净化不需要护栏状态——它可以只依赖「已落库的消息本身」重算判据。这既省掉穿参，也顺带能处理**导入的旧会话 / 护栏上线前**的存量残留。

## 4. 设计之一（主方案）：发送层「残留净化」

### 4.1 窗口：只清「上一轮的助手残留」

对消息列表按角色定位：

```
u_prev = 倒数第二个真实 USER 消息的索引（isSynthetic == false）
u_last = 最后一个真实 USER 消息的索引
窗口   = (u_prev, u_last) 之间的所有消息   // 上一轮助手产出：assistant / tool 交替若干条
```

为什么是这一段：

1. **它就是护栏刚判退化的那一轮**——同一轮内被停掉后留下的所有输出，全在这个区间里；
2. **它在当前轮内是冻结的**：当前轮无论走到第几步，`u_last` 都是同一条用户消息 ⇒ 窗口内容不变 ⇒ 净化决策每步一致（幂等、不会中途翻转，前缀不抖动）；
3. **它不会误伤在途工具协议**：当前轮的工具调用/结果都在 `u_last` 之后，净化根本不看它们；
4. 首次对话（只有 1 条真实 USER）⇒ **不净化**（没有「上一轮」可清）。

**不做「跨轮历史扫描」作为默认行为**：那会把更早的残留也一起动，前缀缓存整段作废、误删面扩大（见 §6 才作为可选项）。

### 4.2 规则（按顺序，命中即停；全部只依赖窗口内内容）

**门槛与护栏对齐**：先给窗口内每条助手消息打标（判据与护栏**同一份**）。R1/R2 这族只在窗口内标记数 ≥ `ThinkingDegenerationGuard.DEFAULT_MAX_STALLED_CALLS(3)`（即「护栏会判停」的量级）时生效；低于门槛**一条都不动**——护栏没停的轮次，净化也不替它改上下文。R3（工具空转）另有自己的门槛（与 `ToolCallLoopGuard` 同量级：同签名出现 ≥3 次）；R4 不需要门槛（能在单条里出现逐字周期，本身就是判据）。

| # | 名字 | 判据（打标） | 动作 |
|---|---|---|---|
| R1 | 空转条 | 无工具、无正文/图片、推理 ≥ `DEFAULT_MIN_REASONING_CHARS(2048)` | **整条丢弃** |
| R2 | 复述条 | 无工具调用；与窗口内上一条的 `containment(本, 上) ≥ DEFAULT_SIMILARITY_THRESHOLD(0.8)`（与护栏同一函数） | 保留**正文/图片**，**剥掉推理 part**（用户看到的回答不能丢） |
| R3 | 工具空转 | 连续 ≥2 组「纯工具调用的 assistant + 对应 TOOL 结果」，工具名与规范化参数完全相同 | **保留第一组，丢弃之后各组**（成对丢弃，绝不拆散） |
| R4 | 单条逐字周期 | 单条助手消息：`minimalPeriod(尾部窗口) ∈ 2..64`、周期内 ≥2 个不同字符、尾部跨度 ≥256、且**无正文** | **整条丢弃** |

**不变式（必须由单测钉住）**：
- 永不丢弃 `USER` 消息、`isSynthetic` 消息、带 `Image/Audio/Video/Document` part 的消息；
- 工具调用与结果**原子**（丢弃必须成对，或都不丢）；
- 只读窗口内、只依赖窗口内内容 ⇒ 幂等（`purge(purge(x)) == purge(x)`）且同轮各步结果一致；
- 不碰带签名推理（`UIMessagePart.Reasoning.isProviderSigned()`，`7e8b440d` 引入）：Claude 的 `thinking` 块改一个字就 400（`ClaudeProvider.kt:526-532` 的注释系列），这类**只看不碰**，记一条 decision 供预览解释；
- 窗口内全被丢弃时**不补占位消息**：`U_prev` 与 `U_last` 相邻是合法的（OpenAI/Anthropic/Gemini 都接受连续 user），而且语义正确——上一轮本来就没答出来，模型应该去答 `U_prev` 那个问题。

### 4.3 例子（形态 2：复述 + 少量正文）

净化前（上一轮实际发过的尾部；窗口内 3 条都打上 stalled 标记 ⇒ 达到门槛 3 ⇒ 整段净化）：

```
user: 把 README 的发行说明改一下
assistant  [reasoning 3.2K，无正文，无工具]                      ← 空转条 → 整条丢弃
assistant  [reasoning 3.1K，复述上一条，正文「好的，我来改。」]      ← 复述条 → 剥推理，留正文
assistant  [reasoning 3.3K，复述上一条，正文「好的，我来改。」]      ← 复述条 → 同上
user: 继续
```

净化后：

```
user: 把 README 的发行说明改一下
assistant  好的，我来改。
user: 继续
```

用户看到的历史（DB/UI）**一个字都不变**；变的只是「模型这一眼看到什么」。这正是「不像护栏那样劝，而是把诱因撤掉」。

### 4.4 缓存账：为什么这个窗口几乎不花钱

压缩设计里已经确立「前缀缓存稳定性」是硬约束（`PromptAssembly.kt:215-222`、`design-auto-compaction.md` §2.2 ③）。逐条算：

| 情形 | 上一个请求的 prompt | 本次 prompt | 公共前缀 |
|---|---|---|---|
| 不净化 | `…，窗口(原样)，U_last` | `…，窗口(原样)，U_last` | 全省 |
| 净化（R1/R3/R4 丢弃整条） | 末尾就是被丢的那些条 | 少了那些条 | 断在**第一个被丢的位置** ⇒ 之后重算，代价 = 窗口大小（一轮产出，通常 2–8K token） |
| 净化（R2 剥推理） | 同上 | 该条推理变空 | 同上 |

结论：**每次「被净化」只付一轮产出的重算，且判据命中本身就是罕见事件**（护栏 20 项单测里合法内容零误杀）。不清的话，那段复读推理**每一轮**都要被重发一次（R1 的条还要按原价整条计费）；净化只付一次重算，之后上下文永久变干净。

> ⚠️ 注意与「压缩」的先后：净化在 `limitContext` **之后**执行，所以它只会让窗口更小，不会改变截断的滞回步点；净化省下的 token 也可能让自动压缩少触发一次（正向副作用）。

### 4.5 落点：`buildSendWindowMessages`（预览零漂移）

```kotlin
// data/ai/ContextPurifier.kt（纯类，无 Android 依赖，JVM 单测）
internal object ContextPurifier {
    data class Decision(val rule: Rule, val droppedIndices: List<Int>, val reason: String)
    data class Result(val messages: List<UIMessage>, val decisions: List<Decision>)
    fun purgeResidue(messages: List<UIMessage>): Result
}
```

调用点：`PromptAssembly.buildSendWindowMessages` 的返回处（`PromptAssembly.kt:267-270`，`ContextPurifier.purgeResidue(...)` 包住 `buildList{}`）。

选这里的理由：
- **预览 = 请求**由构造保证（`PromptInspector.kt:279` 调的正是这个函数）——不需要在预览里另写一份，杜绝漂移；
- 纯函数、无 Android 依赖、无协程 ⇒ 与护栏同款可单测；
- 不经过 transformer 链 ⇒ 不受「用户可配置的注入顺序」影响，语义固定。

**不选** `generateInternal:1161`（transforms 之后）：那里预览要自己镜像一遍（`PromptInspector.kt:301`），是已知的漂移源；也不选新增 transformer：`SubagentRunner.kt:422` 传的是 `emptyList()`，会漏掉无头 run。

### 4.6 存储语义：净化**不是编辑**，正文与推理都原样保存

| 对象 | 净化后 |
|---|---|
| 数据库 `messageNodes` | **不动**：正文、推理、工具调用/结果全部原样在库里（`Conversation.kt:231`「轨迹只追加」） |
| 用户看到的历史（DB/UI） | 不动，与现在完全一致 |
| 这一次请求的副本 | R1/R4 的条不出现；R2 的条去掉推理 part、正文/图片保留；R3 的重复组成对不出现 |

**三条「不按编辑算」的理由**：

1. **签名**：Claude/Gemini 的推理块带签名，改写即 400（`7e8b440d` 的 `isProviderSigned()` 警示，以及 `Expected thinking or redacted_thinking, but found tool_use` 前科）——净化若落库成「编辑」，等于永久破坏该块的可回传性。
2. **可撤销**：净化是自动判据的产物，判据会随标定继续调整；request-only 时「关掉开关 = 立刻全部恢复」，落库改写不可逆。
3. **语义污染**：编辑是**用户意图**，现有链路会把编辑后的文本按 `reasoning_content` 回传（`ChatCompletionsAPI.kt:682-686` 的注释）；自动净化若复用这条路径，会把「机器删减」混进「用户改写」的语义里，之后谁也说不清这条消息是人改的还是机器清过的。

**发送副本不会撞 400 的两个兜底**：
- OpenAI 兼容：R2 把推理 part 整个去掉后，需要回填的网关会走 `backfillReasoning` 分支补**空串**（`ChatCompletionsAPI.kt:664-686`，空串对 `api.deepseek.com` 实测 200）；
- Claude/Gemini：签名块**只读不碰**；整条丢弃只发生在「无正文、无工具」的 R1/R4 条上，那种条没有 `tool_use`，不构成「缺 thinking 的 tool_use 消息」这个 400 条件。

**唯一允许改落库的路径**是 §6.2 的手动清理（用户确认后真删，且是用户可撤销的显式操作）。

## 5. 设计之二（可选）：写入侧「源头截断」

护栏在**流中途**断流时（形态 3），被截下的推理尾部**逐字就是那个循环**（「好。执行。好。执行。」），现在会原样落库——一条消息可能就是几十 KB 的复读。发送层净化对它无能为力（R4 只在「无正文」时整条丢；一条 50KB 的复读本身就是个诱因）。

做法：`onDelta` 返回 `Repeating` 时，把该条消息的推理**裁剪到周期起点**（保留前文 + 一个周期），UI 上显示一行「（重复部分已省略 N 字）」。判据现成（`minimalPeriod`），纯函数，无模型调用。

代价与风险：
- **改变用户看到的内容**（折叠提示可缓解）；
- 签名块不能动（Claude/Gemini）⇒ 这两种 provider 下退化为「不裁」；
- 诊断价值下降（想回看原始循环得开日志）。

**默认建议：只对「无正文、无工具」的纯复读条生效**（这种条本来就没有回答价值），有正文的留给 §4 的 R2。

## 6. 设计之三（可选）：深度净化 / 手动清理

### 6.1 深度净化（跨轮扫描）
把窗口从「上一轮」扩到「最近 N 轮 / 整条上下文」。收益：清更早的残留。代价：前缀缓存整段作废（可见 `PromptInspector` 的 STABLE/EVENT/TURN 三段读数，TURN 段会接近全失）、误删面变大。**建议默认关，标题写明「会让缓存失效」**——本仓已有同款取舍的先例（改历史 ⇒ cache 从该点作废，roadmap 2026-10-08 条）。

### 6.2 手动清理（真删，需要确认）
给「这一段明显在打转」的历史一个用户可见的出口：选中若干助手消息 → 折叠/删除（落库）。对齐记忆整理的**提议 → 确认**模式（`MemoryConsolidationScheduler.kt:336-345`），并且是**唯一允许真删**的路径（自动净化绝不落库）。

这解决一个默认净化覆盖不到的场景：**导入的旧会话**、以及用户想「彻底不想再看到这段」。

## 7. 可观测性与开关

- **预览卡**：照 `PromptDebugState` 的做法，记录「上一次请求的净化决策」（规则、被丢索引、省了多少 token），在 `PromptInspectPage` 里单列一张卡（`PromptDebugState.kt:24` 的 8 条 LRU 同款）。**没有这张卡，净化就是黑箱**——本仓对「静默改历史」的既定态度是必须有解释。
- **用量**：净化省下的 token 记进统计账本（`UsageRecorder` 现成），与「压缩省下」并列，让收益可见。
- **开关**：全局一项「净化退化的思考残留」，**默认开**、**不做档位**——与护栏同款理由（`ThinkingDegenerationGuard.kt:32-33`：「真出现误杀再加档位」）。深度净化单独一项，默认关。

## 8. 因果假设的验证（本文最不确定的一环）

本设计的理论前提是「模型看到自己原地打转的痕迹，会更容易继续原地打转」（in-context imitation）。**这是假设，不是已测事实**——仓库既有文档只标定了「判据能识别退化」，没有标定「撤掉残留能降低复发」。

验证办法（护栏 E2E 用过同款思路：靠测试桩确定性复现）：
1. 测试桩 provider 返回一段**固定的退化历史**（形态 2：3.2K 复述 ×3，达到门槛），落库；
2. 同一句「继续」，A 组走未净化组装、B 组走净化组装，各发 N=20 次（temperature 固定、同一模型）；
3. 指标：再次命中护栏判据的比例、首条回复与残留的 containment、同义复述率；
4. **失败判定**：若 B 不显著优于 A，则本设计降级为「省 token / 省缓存」，不再宣称「修复思维退化」——文档与 UI 文案都要跟着降级。

另一条已核实的读数支撑作用面：OpenAI 兼容路径默认**回传** `reasoning_content`（`ProviderSetting.kt:240`），所以推理确实是「会被下一轮看到的」；若用户关了 `includeHistoryReasoning`，R1/R3/R4 仍有效（丢整条），R2 退化为无操作。

## 9. 风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| provider 合法性 | 工具对拆散、Claude 签名块被改 → 400（已有前科：`Expected thinking or redacted_thinking, but found tool_use`） | 成对丢弃 + 签名块只读 + 组装后校验器 |
| 误删有效信息 | 工具结果可能被用户下一句引用（「结果里那个数」） | R3 只丢**重复**组、保留第一组；P0 先只记录观察一周再启用 |
| 缓存抖动 | 一次前缀失效（一轮产出量级） | 只清「上一轮」+ 命中罕见；深度净化单独开关且提示代价 |
| 每步不一致 | 若判据依赖「当前步」状态，同一轮内前缀会来回翻 | 判据只依赖冻结窗口 ⇒ 单测钉「同轮多步结果一致」 |
| 与压缩互相打脸 | 压缩把窗口摘要掉后，净化窗口可能落在检查点区间内 | `partitionCheckpointMessages` 已把检查点隔离在前；净化只处理其后的消息，空窗口即 no-op |
| 无头 run / 子智能体 | `SubagentRunner` 走空的 transformer 链 | 净化在 `buildSendWindowMessages`，两条路径共享，天然覆盖 |

## 10. 测试计划

- **纯函数单测**（`ContextPurifierTest`）：
  - 每条规则的命中/不命中；**四类合法内容零误杀**（markdown 表格 / 日志 / CSS / JSON dump，直接复用护栏测试里的样本）；
  - 不变式：不丢 USER / 签名块 / 带附件消息；工具对原子；幂等；同轮多步一致；
  - 边界：只有一条用户消息、窗口为空、窗口全是用户消息、检查点存在。
- **Wiring 扫描测试**（`ContextPurifierWiringTest`，照 `ThinkingDegenerationWiringTest` 的源码扫描型）：`buildSendWindowMessages` 确实调用净化、预览路径复用、`generateInternal` 未另起一份。
- **真机验证**（装 debug）：
  - 测试桩造退化历史 → `PromptInspect` 里看「净化卡」与最终分段，确认预览 = 实发（`PromptDebugState` 的对比卡）；
  - 请求日志对比净化前后发送条数/token 数；
  - 真机跑一周：命中率、误伤（用户点「撤销」/抱怨）、缓存命中率（STABLE/TURN 比例）。
- **因果实验**：§8 的 A/B。

## 11. 分阶段

| 阶段 | 内容 | 风险 |
|---|---|---|
| P0 | 只记录：纯函数 + 预览卡 + 日志，**不改发送** | 零行为变更 |
| P1 | 执行 R1 + R3 + R4（整条丢弃，最安全） | 低 |
| P2 | 执行 R2（剥推理留正文） | 中（改变上一轮模型所见） |
| P3 | 写入侧截断（§5）、手动清理（§6.2） | 中（改落库内容，需确认 UI） |
| P4 | 深度净化（§6.1） | 高（缓存+误删），默认关 |

## 12. 已定 / 待拍板

**已定（2026-10-21）**：
- 范围 = 只清「上一轮残留」；
- **净化不落库、不按编辑算**：正文与推理都原样保存在库里，只有这一次请求的副本被删减（§4.6）。

**待拍板**：
1. **R2 的接受度**：历史里「只剩正文、推理被剥」——这会让模型看到的上一轮内容变少（但正文一条不少）。接受吗？
2. **写入侧截断**要不要做（§5）——它会改**落库内容**，是唯一会动用户可见历史的地方。
3. **开关**：默认开 + 无档位（与护栏一致），还是先做成默认关让用户自己开？
4. **因果验证**（§8）做不做：需要真机 + 真模型调用约 40 次；不做的话，设计只能以「省 token / 去噪音」立论，不能宣称改善退化。
