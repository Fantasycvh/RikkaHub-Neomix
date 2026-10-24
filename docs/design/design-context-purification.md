# 上下文净化：清掉「已判定退化的思考残留」（而不只是让它停）

> 状态：**设计草案 revision 3（2026-10-24，未实现，未动代码；本地草稿，未提交）**。
> revision 3（2026-10-24）：删 C3（编码器本就丢空助手消息，该规则是 no-op，只留 dry-run 统计）；§8 因果实验方法修正——脚本桩无法对窗口差异作出不同反应，改为「桩只造历史、响应用真实便宜模型」；开关策略改「默认关起步，数据达标且零误杀才默认开」（净化的失败模式不可见，与护栏不同类）；四项待拍板全部收敛（§12）。逐条差异见 §13 revision 3 表。
> 相对 2026-10-19 初稿，方向与边界不变，**规则表重写**：初稿按「一轮 = 多条助手消息」组织（清 3 条空转消息、成对丢工具对），本仓不是这个模型（§3）——照初稿实现，阈值永不触发。修订把单位下沉到 part，把「删整条」换成「保留最早一份、截掉重复」（§4.2），逐条差异见 §13。
> 相关：`data/ai/ThinkingDegenerationGuard.kt`（护栏家族第四员）、`design-auto-compaction.md`（压缩管「预算」，本文管「质量」）、`design-subagent-context.md` §护栏家族分工、`design-whole-turn-folding.md`（只改显示、不改上下文）。
> 锚点均于 2026-10-23 只读核对（包名已改为 `app.androidrik.*`；初稿行号整体漂移 10–40 行）。

## 0. 一句话

护栏挡住了「继续写」，没挡住「继续看」。本文把「继续看」的那部分撤掉——**只在这次请求的副本上撤，不动落库、不动 UI**。

## 1. 问题：护栏只止损，不清毒

现状（2026-10-23 核实）：

| 事实 | 位置 |
|---|---|
| 护栏只做**停止**：调用内命中抛 `ThinkingDegenerationCut` 断流；调用边界命中则置 `stopNotice` 后跳出步骤循环；**不回滚、不重试、不 fallback** | `ThinkingDegenerationGuard.kt:40-53`、`:94-172`；`GenerationHandler.kt:604-640`、`:1254-1267`、`:988-991` |
| 停止提示**故意不写进对话**，只 `addError` 成一条瞬时错误条 | `ChatService.kt:1773-1790`、`:508-521` |
| 当时给的理由：*「用 addError 而非写进对话：后者会被持久化并在下一轮当上下文发给模型，变成自己教自己的噪音」* | `GenerationHandler.kt:238-243`（`Notice` 的 KDoc）、`ChatService.kt:1732-1734` |
| 但**被判定退化的那一条助手消息本身**照常落库（护栏不删内容），于是下一轮照样被组装进请求 | `ChatService.kt:1699-1702`、`:1707-1719`、`:1864-1867` |
| 落库形态不是「3 条空转消息」，而是**一条消息里累积的一大段推理**（续写追加到同一条） | `Message.kt:273-297`、`GenerationHandler.kt:221-230`、`:663-664` |

也就是说：**护栏挡住了「继续写」，但没挡住「继续看」**。「自己教自己」这句注释预判了机制，却只处理了**提示**这条支路——**痕迹**那条支路没人管。

这正是本设计的出发点：不是再叠一条规则去劝模型别重复（护栏语义），而是**把已经蒸出来的退化残留从上下文里拿掉**。

## 2. 边界：净化能做什么、不能做什么

**能做（客户端可判定）**：字面重复类退化——逐字周期、同一消息内累积/复述的推理段、同签名工具空转。判据与护栏**同源**（`containment` / `minimalPeriod` / `distinctChars` 都是 `internal`，同包直接复用；`reasoningText()` / `hasVisibleTextOutput()` / `hasToolCalls()` 三个扩展也已现成，`ThinkingDegenerationGuard.kt:270-279`），避免两套判据漂移。

**不能做（不假装能做）**：

- **语义**漂移：越写越浅、结论自相矛盾、换了话题——客户端无法判定；
- **不引入低熵比值**：2026-10-09 的标定已经证明覆盖率类指标对合法内容（表格 0.72、日志 0.76、CSS 0.82、JSON dump 0.84）与退化样本（0.92–0.99）只差 0.06，做不了截断边界（`ThinkingDegenerationGuard.kt:24-28`）；
- 不改变**正常内容**的发送：净化只对「字面重复」生效。

**作用域（revision 2 新增）——这个功能只对「会回传推理」的 provider 有意义**：

| Provider 家族 | 历史推理是否进请求 | 净化效果 |
|---|---|---|
| OpenAI 兼容 | 默认回传 `reasoning_content`（`includeHistoryReasoning` 默认 true） | **C1 有效** |
| Claude | 只回传**带签名**的 thinking 块，无签名推理在编码时就被丢掉 | C1 **天然无效**（C2 仍有效） |
| Gemini | 推理全部丢弃，签名挂在 Tool/Image part 上 | 同上 |
| OpenAI Responses（`useResponseApi=true`） | 走另一套编码器（`reasoning` item 带 `reasoningId`/`encryptedContent`） | **未测**，P1 不覆盖，需单独 fixture |

结论：Claude/Gemini 在编码层已经天然净化，开关文案不能承诺「对所有模型有效」；§8 的因果实验也只能在 OpenAI 兼容模型上做。

## 3. 消息模型：一轮 = 一条助手消息（revision 2 新增，本文的地基）

### 3.1 证据

| 事实 | 位置 |
|---|---|
| `handleMessageChunk`：**同角色** delta 合并进最后一条消息，只有角色变化才新建 | `ai/.../ui/Message.kt:273-297` |
| `applyToolOutput`：把工具结果**并回最后一条助手消息**（注释直述） | `GenerationHandler.kt:221-230` |
| `UIMessagePart.Tool` 自带 `output: List<UIMessagePart>`：调用与结果**是同一个 part** | `ai/.../ui/UIMessagePart.kt:145-182` |
| 独立的 `ToolResult` part 已 `@Deprecated`，只用于导入兼容 | `UIMessagePart.kt:123-132` |
| 续写（裸断流/撞上限）追加到**同一条**助手消息 | `GenerationHandler.kt:663-690` |

### 3.2 三个推论

1. **一轮的助手产出 = 一条 `UIMessage`**，parts 顺序形如 `[Reasoning?, Text?, Tool, Tool, Reasoning?, Text?]`；工具调用与结果不占两条消息。
2. **「窗口里有 3 条空转助手消息」写不出来** ⇒ 初稿 §4.3 的样例、以及「窗口内标记数 ≥ `DEFAULT_MAX_STALLED_CALLS(3)` 才生效」的门槛，在本仓永不满足；初稿 R3「assistant + TOOL 结果成对丢弃」是 no-op（没有「两条」可丢，part 天然原子）。
3. 真正的残留形态是 **part 级**的：一条消息里，`Reasoning` part 因为续写不断累积（`appendChunk` 把后一次调用的推理接到前一个 Reasoning part 上），末尾可能挂着几十 KB 的循环；跨调用之间可能出现多个 `Reasoning` part，彼此高度重叠。

### 3.3 可挂载点（已刷新）

| 层 | 现状 | 位置 |
|---|---|---|
| 请求组装 | `buildSendWindowMessages` = 压缩窗口 `applyTo` → `partitionCheckpointMessages` → 检查点前置 + `restMessages.limitContext`；**真实请求与拼装预览共用** | `PromptAssembly.kt:260-273`、`:309-313`；`PromptInspector.kt:315-333` |
| 发送前最后一次改写 | `transforms(...)` → `balancedForRequest()`（丢孤儿 ToolResult、补合成结果，**请求期改写、不落库、不变时返回同一实例**） | `GenerationHandler.kt:1168-1183`；`ToolResultBalancingTransformer.kt:34-39`、`:74-83`、`:89-138` |
| 上限裁剪 | `limitContext` **按条数**、阶梯滞回（`CONTEXT_KEEP_RATIO = 0.5`）、`alignContextStart` 保证不拆工具对；`contextMessageLimit` 默认 **0 = 不裁** | `Message.kt:344-376`、`:384-424`；`Assistant.kt:65` |
| 压缩 | token 驱动、检查点替换、**不动 messageNodes**（「轨迹只追加」） | `Conversation.kt:215-237` |
| 推理回传 | OpenAI 兼容回传 `reasoning_content`（部分网关缺字段直接 400，走 `backfillReasoning` 补空串）；Claude 只回传**带签名**的块；Gemini **全部丢弃** | `ProviderSetting.kt:240`；`ChatCompletionsAPI.kt:611-613`、`:714-739`；`ToolReasoningBackfill.kt:13-33`；`ClaudeProvider.kt:531-549`；`GoogleProvider.kt:732-774` |
| 可观测 | 拼装预览与真实请求**同函数**，按来源分段；`PromptDebugState` 留最近两次实发列表 | `PromptInspector.kt:315-333`；`PromptDebugState.kt:24` |
| 护栏状态 | 全在 `ConversationSession` 内存里，**跨轮清零**（`beginNewTurn`），组装期看不到上一轮的标记 | `ConversationSession.kt:137`、`:165-171`；调用点 `ChatService.kt:877`、`:996`、`:1048` |

### 3.4 组装期拿不到的三种信息（决定了判据形态）

1. **上一轮的护栏状态**：`beginNewTurn` 清零，组装期没有「上一轮被停过」这个事实；
2. **落库的停止痕迹**：`Notice` 只走内存 `_errors`，库里没有任何标记；
3. **「被停」与「只是很怪的轮」的区分**：重算只能看内容，看不了护栏的判定过程（而且护栏的停止本身也可能是假阳性，见 §14）。

⇒ 所以净化判据必须是**内容自证的字面重复**，不能是「护栏说我停过」。副作用是正向的：导入的旧会话、护栏上线前的存量残留，一样能清。

## 4. 主方案：发送层 part 级净化

### 4.1 窗口：只清「上一轮」

对消息列表按角色定位（**两个判据缺一不可**）：

```
u_prev = 倒数第二个「真实 USER」的索引（isSynthetic == false 且 !isContextCheckpoint）
u_last = 最后一个「真实 USER」的索引（同上）
窗口   = (u_prev, u_last) 之间的所有消息
```

**必须排除 `isContextCheckpoint`**：上游检查点是 `isSynthetic = false` 的 USER（`Conversation.kt:225-227`），不排除它，窗口会变成「自压缩边界以来」，而不是「上一轮」。判法照 `MemoryRelevance.memoryQueryOf`（`MemoryRelevance.kt:150-155`）。

为什么是这一段：

1. **它就是护栏刚判退化的那一轮**——同一轮内被停掉后留下的内容，全在这个区间里；
2. **它不会误伤在途工具协议**：当前轮的工具调用/结果都在 `u_last` 之后，净化根本不看它们；
3. **首次对话（只有 1 条真实 USER）⇒ 不净化**（没有「上一轮」可清）。

> ⚠️ **位置稳定 ≠ 字节稳定**（revision 2 修正）。初稿说「窗口在轮内是冻结的」，只对了一半：每步都对 live 列表重算（`PromptAssembly.kt:250-258` 的注释），而 **输出变换每步会重写每条助手消息**（`GenerationHandler.kt:584-602`；`RegexOutputTransformer.kt:17-37`），`contextMessageLimit > 0` 时 `limitContext` 的截断点还会随列表增长移动、可能把 `u_prev` 移出窗口（`Message.kt:344-376`）。所以：
> - **P1 的启用条件是 `contextMessageLimit == 0`**（默认就是 0，等于默认安全）；
> - 决策里带上「窗口消息 id 序列 + 各 part 内容 hash」，翻转时打日志（P0 统计翻转率）；翻转率高再考虑轮内锁定，而不是先猜。

### 4.2 规则（part 级；按顺序，命中即处理；全部只依赖窗口内内容）

**原则：不丢信息，只丢重复。** 同一份信息保留**最早的一份**，后面重复的截掉或去掉；不再有「整段剥掉推理」这种动作。

| # | 名字 | 判据 | 动作 |
|---|---|---|---|
| **C1a** | 周期截断 | 某个 `Reasoning` part 的尾部窗口 ≥ `DEFAULT_CYCLE_SPAN_CHARS(256)`，`minimalPeriod` ∈ 2..`DEFAULT_MAX_CYCLE_PERIOD_CHARS(64)`，周期内 ≥2 个不同字符（否则长 `====` / `────` 分隔线会被误杀） | 把该 part 截到**周期起点**（保留前缀） |
| **C1b** | 块级去重 | 同一 part 内按 ~512 字符切块，块 `i` 与更早的某块 `containment ≥ DEFAULT_SIMILARITY_THRESHOLD(0.8)`（护栏同款 8-gram shingle） | 把该 part 截到块 `i` 起点（保留前面的内容） |
| **C1c** | part 级去重 | 同一条消息里，后一个 `Reasoning` part 被**更早**的某个 `Reasoning` part 覆盖 ≥ 0.8 | **前面那些一个都不动，后面这个整段去掉** |
| **C2** | 工具空转 | 同一消息内 `(toolName, 规范化 input)` 重复，**且 output 等价**（相等，或都是错误/空结果） | 保留第一个 `Tool` part，丢掉后续重复的 |

**为什么是这几条而不是初稿的 R1–R4**：C1a 覆盖「流中途逐字循环」（初稿 R4）；C1b + C1c 覆盖「续写累积 / 跨调用复述」（初稿 R2，且**保留第一份**，不再出现「推理被剥光、模型失忆」）；C2 覆盖「工具空转」（初稿 R3，改在 part 上，天然原子）。几条规则收敛成「去重 + 截周期」，共同点是**判据只看同一个窗口内的字面重叠，不需要跨轮、不需要护栏状态**。

**C3（空产出轮）已删（revision 3，原待拍板 3）**：初稿 R1 对应的「整条消息无产出就整条丢」，在最终请求上是 no-op——编码器本来就会丢空助手消息。留它只增加误杀面与测试面；空产出轮改由 dry-run 卡**只统计、不执行**。另注：C1c 去掉重复推理 part 后，消息在发送副本里变空同样由编码器兜底，不需要规则补位。

**C2 必须带上 output 等价条件**：同一个工具同参数在不同时刻读出来的结果本来就可能不同（文件被改过），只看 `(toolName, input)` 去重会丢真信息。

**不变式（必须由单测钉住）**：

- 永不丢弃 `USER` 消息、`isSynthetic` 消息、带 `Image/Audio/Video/Document` part 的消息；
- 永不改动 `isProviderSigned()` 的 `Reasoning` part（Claude 的 `thinking` 块改一个字就 400，`ClaudeProvider.kt:531-549`；`MessageMetadata.kt:95-97`）——这类**只看不碰**，记一条 decision 供预览解释；
- 只读窗口内、只依赖窗口内内容 ⇒ 幂等（`purge(purge(x)) == purge(x)`）；
- 净化只产**新的副本**：元素是共享实例（`limitContext` 可能返回 `this` 或 `subList` 视图），改 part 必须 `copy(parts = ...)`，绝不原地改；
- 窗口内全被清空时**不补占位消息**：`u_prev` 与 `u_last` 相邻是合法的；而且本仓**已经**天天在发连续 USER（记忆块 L2+L3、规则提醒都是尾部合成 USER，`PromptAssembly.kt:345-365`），所以这不是新风险。

### 4.3 例子（按真实形态：一条消息 + 两个工具 part）

净化前（上一轮实际发过的尾部）：

```
user: 把 README 的发行说明改一下
assistant  [一条消息]
  Reasoning 3.2K  「先读 README 的发行说明段，再改版本号与日期…」   ← 最早那份，保留
  Tool workspace_read_file(README.md) → output 4.1K               ← 保留
  Reasoning 3.1K  （与上面重叠 ≥0.8：续写累积/复述）              ← C1c 整段去掉
  Text 「好的，我来改。」                                          ← 保留
user: 继续
```

净化后：

```
user: 把 README 的发行说明改一下
assistant
  Reasoning 3.2K  「先读 README 的发行说明段，再改版本号与日期…」
  Tool workspace_read_file(README.md) → output 4.1K
  Text 「好的，我来改。」
user: 继续
```

用户看到的历史（DB/UI）**一个字都不变**；变的只是「模型这一眼看到什么」。

### 4.4 缓存账：为什么这个窗口几乎不花钱

前缀缓存稳定性是硬约束（`PromptAssembly.kt:215-222`、`design-auto-compaction.md` §2.2 ③）。逐条算：

| 情形 | 上一个请求的 prompt | 本次 prompt | 公共前缀 |
|---|---|---|---|
| 不净化 | `…，窗口(原样)，U_last` | `…，窗口(原样)，U_last` | 全省 |
| 净化（C1c / C2） | 末尾就是被去掉的 part | 少了那些 part | 断在**第一个被改的位置** ⇒ 之后重算，代价 = 窗口大小（一轮产出，通常 2–8K token） |
| 净化（C1a / C1b 截断） | 同上 | 该 part 变短 | 同上 |

结论：**每次「被净化」只付一轮产出的重算，且命中本身就是罕见事件**（护栏 20 项单测里合法内容零误杀）。不清的话，那段复读**每一轮**都要被重发一次；净化只付一次重算，之后上下文永久变干净。

> ⚠️ 与「压缩」的先后：净化在 `limitContext` **之后**执行，所以它只会让窗口更小，不会改变截断的滞回步点；净化省下的 token 也可能让自动压缩少触发一次（正向副作用）。

### 4.5 落点：`buildSendWindowMessages`（预览零漂移）

```kotlin
// data/ai/ContextPurifier.kt（纯类，无 Android 依赖，JVM 单测）
internal object ContextPurifier {
    enum class Rule { CYCLE_TAIL, BLOCK_DUP, PART_DUP, TOOL_IDLE, EMPTY_TURN }
    data class Decision(val rule: Rule, val messageRole: MessageRole, val detail: String)
    data class Result(
        val messages: List<UIMessage>,
        val decisions: List<Decision>,
        /** 仅诊断用：被去掉的字符数（不是账单口径） */
        val savedChars: Int,
    )

    fun purgeResidue(window: List<UIMessage>): Result
}
```

调用点：`buildSendWindowMessages` 的 `buildList{}` 外层（`PromptAssembly.kt:269-273`），即 `limitContext` 之后、调用方 `.map { withoutUiNotices() }`（`:313`）之前。

选这里的理由：

- **预览 = 请求**由函数同一性保证（`PromptInspector.kt:315-333` 调的正是这条链）——不需要在预览里另写一份，杜绝漂移；
- 纯函数、无 Android 依赖、无协程 ⇒ 与护栏同款可单测；
- 不经过 transformer 链 ⇒ 不受「用户可配置的注入顺序」影响，语义固定；也无头 run（`SubagentRunner.kt:421-423` 传 `emptyList()`）天然覆盖。

**三条落点约束（revision 2 新增）**：

1. 钩子在 `.withoutUiNotices()` **之前** ⇒ 会看到 UI 中断横幅 part：判「有没有正文」只认真实 Text/Image，决策里的索引要映射到最终请求索引（预览卡用）；
2. 钩子看不到之后才追加的合成尾部（记忆块 / 规则提醒，`PromptAssembly.kt:345-365`）——将来若要净化它们，这里不是那个点；
3. 预览是**函数**同一性，不是**输入**同一性：regen 走 `messageRange` 子列表 + `compressionWindow = null`，工具循环中预览还缺当步消息 ⇒ 预览卡要注明「本卡是空闲态的重演」。

**不选** `generateInternal:1168`（transforms 之后）：那里预览要自己镜像一遍（`PromptInspector.kt:341` 单独跑一条链），是已知的漂移源；也不选新增 transformer：无头 run 会漏。

### 4.6 存储语义：净化**不是编辑**，正文与推理都原样保存

| 对象 | 净化后 |
|---|---|
| 数据库 `messageNodes` | **不动**：正文、推理、工具调用/结果全部原样在库里（`Conversation.kt:215-237`「轨迹只追加」） |
| 用户看到的历史（DB/UI） | 不动，与现在完全一致 |
| 这一次请求的副本 | C1a/C1b 的 part 变短；C1c 的重复 part 不出现；C2 的重复工具不出现 |

**三条「不按编辑算」的理由**：

1. **签名**：Claude/Gemini 的推理块带签名，改写即 400（`MessageMetadata.kt:95-97` 的 `isProviderSigned()`，以及 `Expected thinking or redacted_thinking, but found tool_use` 前科）——净化若落库成「编辑」，等于永久破坏该块的可回传性。
2. **可撤销**：净化是自动判据的产物，判据会随标定继续调整；request-only 时「关掉开关 = 立刻全部恢复」，落库改写不可逆。
3. **语义污染**：编辑是**用户意图**，现有链路会把编辑后的文本按 `reasoning_content` 回传；自动净化若复用这条路径，会把「机器删减」混进「用户改写」的语义里。

**发送副本不会撞 400 的兜底**：

- OpenAI 兼容：C1c 去掉推理 part 后，需要回填的网关会走 `backfillReasoning` 补**空串**（`ChatCompletionsAPI.kt:714-739`，空串对 `api.deepseek.com` 实测 200）；
- Claude/Gemini：签名块**只读不碰**；而且这两家在编码时本来就会丢掉全部无签名推理，净化对它们近乎 no-op（§2 作用域表）；
- **OpenAI Responses 路径未覆盖**：`useResponseApi=true` 走另一套编码器（`reasoning` item + `reasoningId`/`encryptedContent`，`ResponseAPI.kt:410-428`），P1 先跳过并留 fixture。

**唯一允许改落库的路径**是 §6.2 的手动清理（用户确认后真删，且是用户可撤销的显式操作）。

## 5. 已否决：写入侧截断（原 §5）

初稿的「流中途断流后把推理裁到周期起点、落库」这条，**revision 2 决定不做**，理由：

1. **同一个操作放请求侧就够**：C1a 已经在请求副本上完成同样的截断，而「50KB 复读进上下文」这个危害被完整覆盖；
2. **不可逆**：截断落库后，判据调整 = 数据已损坏；request-only 关掉开关即恢复；
3. **与「轨迹只追加」冲突**：`Conversation.kt:215-237` 明确检查点不动 messageNodes，写侧截断会破例；
4. **签名块**：Claude/Gemini 下必须退化为「不裁」，等于同一个功能一半不可用；
5. 残余价值只剩**磁盘占用与 UI 观感**——这两件事的正确归宿是「整轮折叠（只改显示）」或 §6.2 手动清理，不是改库。

> 若将来确实要处理磁盘/观感：优先做「折叠显示 + 原始文本保留」，即 `design-whole-turn-folding.md` 的路子。

## 6. 设计之二（可选）：深度净化 / 手动清理

### 6.1 深度净化（跨轮扫描）

把窗口从「上一轮」扩到「最近 N 轮 / 整条上下文」。收益：清更早的残留。代价：前缀缓存整段作废（`PromptInspector` 的 STABLE/EVENT/TURN 三段读数里 TURN 段接近全失）、误删面变大。**建议默认关，标题写明「会让缓存失效」**——本仓已有同款取舍的先例（改历史 ⇒ cache 从该点作废）。

### 6.2 手动清理（真删，需要确认）

给「这一段明显在打转」的历史一个用户可见的出口：选中若干助手消息 → 折叠/删除（落库）。对齐记忆整理的**提议 → 确认**模式（`MemoryConsolidationScheduler` 的确认流），并且是**唯一允许真删**的路径（自动净化绝不落库）。

这解决默认净化覆盖不到的两个场景：**导入的旧会话**；**用户想「彻底不想再看到这段」**。优先级高于 §5（P2）——成本低、收益直接、风险由用户确认承担。

## 7. 可观测性与开关

- **P0 先 dry-run**：净化照跑但**不改发送**，只在 `PromptInspectPage` 单列一张「如果开净化会怎么改」的卡（规则、被改的 part、省了多少字符）。**没有这张卡，净化就是黑箱**——本仓对「静默改历史」的既定态度是必须有解释。
- **实装后的卡**：照 `PromptDebugState` 的做法，记录「上一次请求的净化决策」（`PromptDebugState.kt:24` 的 8 条 LRU 同款），并带窗口 hash，翻转时标红。
- **用量**：初稿说「净化省下的 token 记进统计账本」——**不成立**：账本记的是实发 usage，省下的量没有口径。改为「诊断数字：被改动的字符数 / 估算 token」，只进预览卡，不混进账单。
- **开关**：全局一项「净化退化的思考残留」，**默认关起步（revision 3 改，原待拍板 2）**——与护栏不同类：护栏只影响停止时机，早停一轮用户当场可见（错误条）；净化是静默改写请求内容，误删时用户与模型都无从察觉。对「静默且不可见」的改写，默认关是更稳的默认值。决策门：P0 数据达标（§12 已定 1）且**零误杀样例** ⇒ 默认开；P0 出现任何误杀样例 ⇒ 默认关，且只解禁未出事的规则子集（C1a 周期截断最保守先行，C1b/C1c 依赖 0.8 阈值后行）。深度净化单独一项，默认关。
- **文案**：要写明作用域（§2）——「仅对会回传推理的提供方有效；Claude/Gemini 已在编码层丢弃历史推理」。

## 8. 因果假设的验证（本文最不确定的一环）

本设计的理论前提是「模型看到自己原地打转的痕迹，会更容易继续原地打转」（in-context imitation）。**这是假设，不是已测事实**——仓库既有文档只标定了「判据能识别退化」，没有标定「撤掉残留能降低复发」。

初稿的办法（同句「继续」、A/B 各 20 次真机真模型）**成本高、方差大、且前提没锁住**。revision 2 改成：

1. **`ScriptedProvider` 只负责造历史，不当被试（revision 3 修正）**：用桩驱动 `generateText` 产出含残留的真实窗口（`GenerationToolLoopTest` 已有这套桩），A/B 各存一份（未净化 / 净化后）。**revision 2 原写法（「A 组喂未净化窗口、B 组喂净化后的窗口」用桩跑）不成立**：脚本化响应是预先写死的，不会因窗口内容不同而改变行为，A/B 之间不可能产生差异——因果差异必须来自真模型；
2. **真模型响应，选便宜模型**（deepseek / glm-flash 一档）：A、B 两组窗口各发 10 次请求，同一模型、同一 system prompt；成本核算：20 次调用、每次数 K token，总额可忽略；
3. **temperature 固定 0**：输入差就是全部差，方差大幅下降，N 可降到 10/组；
4. **主指标用连续量**，不用二值「是否再次退化」：首条回复推理与残留的 `containment`、推理长度、再次触发 C1a 的比例；
5. **预注册判定**：例如「B 组首条回复对残留的 containment 中位数至少低 0.2」才算有效，否则按失败处理；
6. **失败即降级**：若 B 不显著优于 A，本设计降级为「省 token / 去噪」，不再宣称「修复思维退化」——文档与 UI 文案都要跟着降级。即使不做实验，也按失败处理（§12 已定 4：做，但失败路径同样成立）。

**必须承认的一点**：本仓最致命的形态（流中途逐字循环，初稿形态 3）是**单次调用自激**，与「看到残留」无关。所以这个实验真正回答的只是「清掉上一轮残留能否降低复发」。另外，护栏的停止本身存在假阳性（§14），P0 统计命中率时要先排除掉那部分。

## 9. 风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| provider 合法性 | Claude 签名块被改 → 400；OpenAI Responses 编码器未测 | 签名 part 只读不碰；P1 跳过 Responses；组装后校验器 |
| 误删有效信息 | C2 丢掉的工具结果可能被用户下一句引用（「结果里那个数」） | C2 要求 output 等价；保留第一组；P0 先只记录观察一周 |
| 缓存抖动 | 一次前缀失效（一轮产出量级）；上游轮内重写窗口会让决策翻转 | 只清「上一轮」+ 命中罕见；P1 限定 `contextMessageLimit == 0`；P0 统计翻转率 |
| 逐条不一致 | 判据只依赖窗口内容 ⇒ 同轮多步结果一致；**但窗口字节本身可能变** | 单测钉「同输入同输出」；P0 记窗口 hash 并在翻转时告警 |
| 与压缩互相打脸 | 压缩把窗口摘要掉后，净化窗口可能落在检查点区间内 | `partitionCheckpointMessages` 已把检查点隔离在前；净化只处理其后；空窗口即 no-op；`u_prev/u_last` 排除 `isContextCheckpoint` |
| 无头 run / 子智能体 | `SubagentRunner` 走空的 transformer 链 | 净化在 `buildSendWindowMessages`，两条路径共享，天然覆盖 |
| 门槛形同虚设 | 初稿「≥3 条标记消息」永不满足（§3.2） | 已改：单条消息内命中即处理，不再有跨消息计数 |
| 连续 user | 清空窗口后 `u_prev/u_last` 相邻 | 本仓已在发连续 USER（记忆块 / 规则提醒），无新增风险；仍留一条编码器 matrix 测试 |
| 预览误导 | 预览是函数同一性、非输入同一性 | 卡上注明「空闲态重演」；regen / 工具循环中不展示 |

## 10. 测试计划

- **纯函数单测**（`ContextPurifierTest`）：
  - C1a / C1b / C1c / C2 各自命中与不命中（C3 已删，空产出轮只出现在 dry-run 统计断言里）；
  - **零误杀**：复用护栏的合法样本（markdown 表格 `ThinkingDegenerationGuardTest.kt:252`、JSON dump `:265`、日志 `:278`、长 `====` 分隔线 `:243`）——注意库里**只有 3 个合法样本 + 分隔线**，初稿说的「四类含 CSS」不成立，**需补一个 CSS fixture**；
  - 不变式：不丢 USER / 合成消息 / 带附件消息；不碰签名 part；幂等；同输入同输出；
  - 边界：只有一条用户消息、窗口为空、窗口全是 USER、窗口内含 upstream checkpoint。
- **Wiring 扫描测试**（`ContextPurifierWiringTest`，照 `ThinkingDegenerationWiringTest` 的源码扫描型，4 个用例）：`buildSendWindowMessages` 确实调用净化、预览路径复用、`generateInternal` 未另起一份。
- **真实形态 fixture**：用 `ScriptedProvider` 造「一条消息 = Reasoning + Tool + 重复 Reasoning + Text」，跑真 `generateText` 后取窗口验证（`GenerationToolLoopTest` 的现成桩）。
- **编码器 matrix**（轻量，各 1 例）：净化后的窗口分别过 OpenAI 兼容 / Claude / Gemini / Responses 编码器，断言不产生非法结构（连续 user、空助手消息、缺 reasoning 的 tool 组）。
- **真机验证**（装 debug）：测试桩造退化历史 → `PromptInspect` 看 dry-run 卡与最终分段，确认预览 = 实发；对比净化前后发送条数 / 字符数；真机跑一周看命中率、误伤、缓存 STABLE/TURN 比例。
- **因果实验**：§8。

## 11. 分阶段

| 阶段 | 内容 | 风险 |
|---|---|---|
| **P0** | 纯函数 `ContextPurifier` + dry-run 预览卡 + 日志（**不改发送**）；同时验三条前提：纯推理历史消息是否真的被回传、Claude/Gemini 是否已天然净化、真实命中率与决策翻转率 | 零行为变更 |
| **P1** | 执行 C1a + C1b + C1c + C2（part 级、保留最早一份），默认值按 §7 决策门（数据达标且零误杀 ⇒ 开）；限定 `contextMessageLimit == 0` | 低 |
| **P2** | §6.2 手动清理（多选删除，对齐记忆回收站） | 中（真删，需确认 UI） |
| **P3** | §6.1 深度净化（跨轮） | 高（缓存 + 误删），默认关 |
| 不做 | 写入侧截断（§5） | — |

## 12. 已定 / 待拍板

**已定（2026-10-21，revision 2 保留）**：

- 范围 = 只清「上一轮残留」；
- **净化不落库、不按编辑算**：正文与推理都原样保存在库里，只有这一次请求的副本被删减（§4.6）。

**已定（2026-10-23，revision 2 新增）**：

- 净化单位是 **part**，不是消息（§3.2）——初稿的消息级规则在本仓不可实现；
- 原则是**保留最早一份、截掉重复**，不做「整段剥推理」——初稿待拍板 1 因此消解；
- **不做写入侧截断**（§5 否决）；
- 判据只允许「内容自证的字面重复」，不允许以「护栏停过」为触发（§3.4、§14）。

**已定（2026-10-24，revision 3 新增，原待拍板 1–4 全部收敛）**：

1. **P0 观察期两周，决策门双口径**：命中会话占比 < 1% ⇒ 不上 P1，只留 dry-run 卡；同时看体量——命中会话中被判残留的字符量中位数 ≥ 8K ⇒ 即使低频也值得上（低频高收益）。统计须先排除 §14 的护栏假阳性会话，否则命中率虚高。
2. **P1 默认关起步**；数据达标且零误杀 ⇒ 默认开；出现误杀样例 ⇒ 默认关 + 只解禁未出事的规则子集（§7）。与护栏「默认开」不冲突：两者失败模式不同类。
3. **C3 砍掉**，空产出轮只由 dry-run 统计（§4.2）。
4. **因果实验做**，按 §8 修正法：桩只造历史，响应用真实便宜模型、温度 0、每组 10 次；预注册判定失败（或放弃实验）即以「省 token / 去噪」立论，不宣称改善退化。

**待拍板**：无。

## 13. 变更记录（revision 2 相对 2026-10-19 初稿）

| # | 初稿 | revision 2 | 依据 |
|---|---|---|---|
| 1 | 窗口内容 = 「assistant / tool 交替若干条」 | **一轮 = 一条助手消息**；残留是 part 级的 | §3.1 三处代码证据 |
| 2 | 门槛 = 窗口内标记数 ≥ 3（抄护栏 `DEFAULT_MAX_STALLED_CALLS`） | **取消跨消息门槛**：单条消息内命中即处理 | 护栏数的是「调用」，落库是「消息」，两个单位对不上 |
| 3 | R1 空转条整条丢 / R2 复述条剥推理 / R3 工具对成对丢 / R4 单条周期整条丢 | **C1a 周期截断 / C1b 块级去重 / C1c part 级去重 / C2 工具去重（要求 output 等价）/ C3 空产出轮** | 消息级动作会连带丢掉工具进展；part 天然原子 |
| 4 | R2「剥掉推理 part，保留正文」 | **只截重复段，保留最早一份** | 回答初稿待拍板 1（模型失忆顾虑），信息不再丢 |
| 5 | §5 写入侧截断（P3，改落库） | **否决**，原因见 §5 | 请求侧已覆盖；不可逆；破「轨迹只追加」 |
| 6 | 「窗口在轮内冻结」 | **位置冻结 ≠ 字节冻结**；限定 `contextMessageLimit == 0`；P0 统计翻转率 | 输出变换每步重写 + `limitContext` 截断点移动 |
| 7 | 未提 checkpoint 的角色歧义 | `u_prev/u_last` 必须排除 `isContextCheckpoint` | 上游检查点是 `isSynthetic=false` 的 USER |
| 8 | 未提 provider 作用域 | 新增 §2 作用域表：仅 OpenAI 兼容族会回传推理 | Claude/Gemini 编码层已天然净化 |
| 9 | 「净化省下的 token 记进用量账本」 | 改为**诊断数字**，不混进账单 | 账本口径是实发 usage，没有「省下」这个量 |
| 10 | 因果实验 = 真机 A/B 各 20 次 | **ScriptedProvider 桩 + 温度 0 + 连续指标 + 预注册** | 成本、方差、可复现性 |
| 11 | 测试「四类合法内容」 | 库里只有 3 个（表格 / JSON / 日志）+ 分隔线，**需补 CSS** | 实测 fixture 清点 |
| 12 | 行号锚点（10-19） | 全部刷新（包名 `app.androidrik.*`） | 只读核对 |

### revision 3（2026-10-24）

| # | revision 2 | revision 3 | 依据 |
|---|---|---|---|
| 1 | C3 空产出轮整条丢 | **删 C3**，dry-run 只统计不执行 | 编码器本就丢空助手消息，规则在最终请求上是 no-op（原待拍板 3） |
| 2 | §8 用 `ScriptedProvider` 跑 A/B | **桩只造历史**，响应用真实便宜模型、温度 0、各 10 次 | 脚本响应写死，不会对窗口差异作出反应，原方法测不出因果（原待拍板 4） |
| 3 | 开关「默认开、不做档位」（引护栏先例） | **默认关起步**；数据达标且零误杀 ⇒ 默认开；误杀 ⇒ 默认关 + 规则子集解禁 | 净化是静默改写、失败不可见；护栏失败（早停）当场可见，先例不可类比（原待拍板 2） |
| 4 | P0 决策门单口径（会话占比） | 双口径：占比 + 残留字符量中位数；排除 §14 假阳性会话 | 低频高收益场景单看占比会低估价值（原待拍板 1） |
| 5 | §14「建议单独立项」 | 占位文档已建：`design-thinking-guard-rewrite-fix.md` | 假阳性直接影响 P0 命中率口径 |

## 14. 顺带发现（不属于本设计，建议单独立项）

核实护栏时发现：**续写路径上的「复述」判据不稳**，两个方向都会错。

1. **假阳性**：续写追加到**同一条**消息（`GenerationHandler.kt:663-690`），于是 `reasoning` 是累积的，`containment(累积_N, 累积_{N-1}) ≡ 1.0` ⇒ 每次续写都被判成「复述」。一个长回答遇到 3 次裸断流（`finishIncomplete` 默认自动续写，`CONTINUE_LIMIT = 3`），就会以「思考退化」被停掉并显示错误原因。
2. **假阴性**：`shingles()` 只取最后 4000 字符（`ThinkingDegenerationGuard.kt:229`），单次续写若新增 > ~4000 字符，containment 归零、`stalledCalls` 清零。

建议：续写分支只拿**新增段**做判据（或在 `onCallFinished` 里区分「同一消息的续写」与「新的一次调用」）。已单独立项占位：`design-thinking-guard-rewrite-fix.md`（2026-10-24，仅问题与方向，未展开）。

对本设计的影响：**不能把「护栏停过」当作净化触发条件**——事实上一轮可能是被误停的健康轮。这也是 §4.2 坚持「判据只依赖内容字面重复」的原因。
