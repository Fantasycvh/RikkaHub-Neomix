# 子代理与独立上下文设计稿（讨论稿）

> 状态：**✅ Phase 1+2 全部落地（2026-09-23 初稿；同日 §九四项讨论有结论；09-26 Phase 1 `f7d0c8e4`（agent_run 表/DenialGuard/SubagentRunner/信封派发，真机验证）→ Phase 2 四步：派发工具 `d5472f46` / 实时进度+run 反查 `609019c4` / 升级到人审批 `fd7021d0` / 并行上限 2 + 上下文预算 `c221be9a`）。验证缺口：并行上限拒绝与预算耗尽优雅停仅单测覆盖，真机待验。文中其余条目（Phase 3+）仍是讨论结论，落地前需重新核实代码现状。**
> 背景：用户提出「考虑 opencode / codex / Cherry Studio 式的子代理系统，含独立上下文窗口，并兼顾 Yuihub / rikkahub-agent 等相关二改」，要求先梳理一个初步讨论方案。
> 一句话结论：**本仓缺的不是「上下文隔离机制」（已是显式入参的函数，近乎免费），而是四件事——①无头审批政策 ②run 生命周期 ③transcript 归属 ④预算/防循环。前三项需产品决策，第四项有硬性前置。**
> 文档构成：一（本仓已有底座核实）、二（本仓真正的难点）、三（通用方案映射，含图景）、四（移动端界面）、五（落地建议与顺序）、六（风险）、七（待确认）、八（明确不做）、附（信息可信度）。
>
> **视角修正说明**：常见讨论里的「并行派发 5 个 / CRDT 合并写 / TEE 沙箱 / Merkle 审计账本 / 桌面树状时间线」是**服务端或桌面 agent 平台**的视角。本仓是**手机聊天应用**，这些多数不适用（见 §八）。故本文刻意按「本仓已有底座 → 真正缺口 → 方案映射」重组，而非复述通用框架。

## 一、现状核实：本仓已有的五块底座

子代理在本仓**主要是既有原语的「组装」问题，不是「新建平台」问题**。以下五块都已存在：

| 底座 | 位置 | 对子代理意味着什么 |
|---|---|---|
| **参数化组装** | `buildInternalMessages(assistant, settings, model, messages, memories, tools, conversationSystemPrompt, compressionWindow)`（`PromptAssembly.kt:245`） | **最关键的一条**：上下文已是「显式入参的函数」⇒ **「独立上下文」= 用不同入参调它**，不需要新机制（见 §1.2） |
| **注解式指针** | `UIMessageAnnotation`：`QuotedMessage` 经 `QuoteTransformer` 展开；Tier 4 的 `InjectionSources` 携带来源 id | 父上下文存**指针**、请求期展开成 brief。现成的「引用而非复制」（与 §1.3 同源思想） |
| **指针而非复制** | `workspace_present_file`（`WorkspaceTools.kt:216`）——返回 path/name/size，**不返回内容**；其 description 明写 "do not paste the file content in your reply" | **已是「证据指针」的正确形态**，子代理回传可直接沿用 |
| **会话状态原语** | `ConversationSession` + `updateMetadata(update, persist)`（本会话刚落地的统一元数据原语） | 每个 run 一份独立状态 + 生命周期，已有形状可参考 |
| **逐段归因视图** | `PromptInspectPage`（本会话刚落地的「请求内容」页：逐段来源、工具 schema/工具输出分列、点击跳到条目） | **「查看与管理界面」的现成模板**（见 §四） |

### 1.1 三处需要改动的装配点（已核实）

若子代理成为新的生成发起方，它会撞上本仓**集中式工具装配**的设计——这一点**已有预判**，见 `docs/upstream-analysis.md` 注意点 9「工具装配方盘点」：

- **`ChatToolFactory.createTools`（唯一装配点）**：搜索 / 本地工具 8 类 / 最近对话 / 工作区 / 技能安装 / 已启用技能 / MCP 动态循环。
- **`GenerationHandler.generateInternal`＝第二装配点**：在传入 `tools` 之上再叠 `buildMemoryTools`（含按 action 分档的 `needsApproval`）。
- **注意点 9 原文已预判**：「**Agent/工作流/定时任务入口**……每入口都是独立生成发起方 + 需『无头会话』审批策略」——**子代理正是这里说的那个入口**。

### 1.2 一个关键性质：组装已是纯入参函数

`buildInternalMessages` 的签名已把「上下文是什么」完全参数化。因此：

- **隔离**：子代理用自己的 `messages`（自有草稿轨迹，非父会话历史）+ 过滤后的 `tools` + 作用域化的 `memories` + 可选的独立 `assistant`（即 profile）调同一个函数即可。
- **不需要**：新上下文引擎、独立存储格式、第二套 transformer 链。
- **代价**：每条不同前缀各占一条 prompt cache 线 ⇒ 子代理会**增加 cache miss**（父会话的前缀缓存策略不受影响，但整体缓存命中率下降）。这是设计时要计的成本，不是 bug。

### 1.3 子代理回传的形态：本仓已有先例

「只回结论 + 证据指针，不回全量轨迹」在本仓已有两个可援引的先例：

- `workspace_present_file`：工具输出是**指针**（路径），渲染由 `ToolUIRegistry`（`ToolUI.kt:93`）负责，内容不进对话正文。
- `UIMessageAnnotation.QuotedMessage`：父上下文只存注解，`QuoteTransformer` 在请求期展开——**注解不参与 wire 格式**（这是本仓反复用到的取舍，见 `UIMessageAnnotation.kt` 注释与 Tier 4 的同类选择）。

⇒ 子代理回传建议 = **结构化结果（文本）+ 指针注解**，而非把子代理 transcript 塞进父上下文。

## 二、本仓真正的四个难点

即 §一句话结论 里的四件事。**其中 ①③ 需要产品决策，②④ 是工程问题。**

### 2.1 无头审批（最难，且是产品问题）

手机上后台子代理要写文件 / 跑 shell，**谁来批？** 现状事实：

- `Tool.needsApproval: (JsonElement) -> Boolean`（`ai/core/Tool.kt:12`）——**每工具自定义回调**，三处各自实现：记忆工具按 action 分档 / MCP 透传 / 工作区有 override。
- 审批 UI 绑在**聊天消息**上（`handleToolApproval` + `ToolApprovalState`）——无头 run 没有「那条消息」可挂。

⇒ 需要**能力信封**（预先授权的工具子集 + 高风险默认拒 + 可升级到父会话/通知），并把三处散落的 `needsApproval` 收敛为**统一策略层**。**这是本设计里唯一没有现成答案的部分。**

### 2.2 run 生命周期 vs Android

- `ConversationSession` 空闲 **5 秒**（`IDLE_TIMEOUT_MS = 5_000L`，`ConversationSession.kt:21`）即回收——子代理要跑数分钟到数十分钟，**必须有显式生命周期**，并挂靠已有的前台服务（fork 已有后台保活）。
- 否则锁屏 / 切换应用即被杀，且「杀」是静默的（无错误、无记录）——属本仓已多次踩到的「静默失败」类。

### 2.3 transcript 归属（需拍板，见 §七.1）

若复用 `Conversation` 表，transcript **会出现在侧栏**。用判别字段（`kind`）的话，需给所有相关查询加过滤——**已量化风险**（逐一核对 `ConversationDAO`，共 25 个 `@Query`）：

- **12 个「列表类」SELECT**（无 `id = :id` 限定）⇒ 漏加过滤即**静默泄漏到侧栏**；
- **另有 3 处聚合**会把 run 计入：`getAllIds`（`ConversationDAO:51`，**用于 FTS 重建**）、`COUNT(*)`（`:97`）、按天统计（`:100`）⇒ 泄漏到统计/FTS 而非侧栏，同样静默。

⇒ 复发面是 **15 处**（12 列表 + 3 聚合），且**每一处的失败都是静默的**。

按本仓教训（「应选让错误不可能发生的形状」，见 `docs/upstream-analysis.md` 注意点），此风险需在 §七.1 拍板时正面权衡。

### 2.4 预算与防循环（~~有硬性前置~~ **前置已满足 2026-09-26**）

**~~`LoopGuard` 与 `ContextBudgetPlanner` 目前都未实现~~**。**LoopGuard 已落地**（`2452f665`：同签名窗口/双阈值/优雅停止）且 **rootfs 单轮总预算也已落地**（`947f92a6`：`WorkspaceTurnBudget`，workspace_* 输出字符 + 步数双上限、256K chars/64 步）——**子代理的刹车已齐**（见 `docs/handoff-2026-09-04.md`）。

⇒ 剩余待做只有 `ContextBudgetPlanner`（usage 锚点估算，peer ②）——它影响的是**预算切分的精度**（子代理该分多少），不是有无刹车；可在 Phase 2（多 profile / 并行）前补，不阻塞 Phase 1 串行单 run。子代理运行实例自建 `ToolCallLoopGuard` + `WorkspaceTurnBudget`（run 自身就是「一轮」的边界，无需挂 ConversationSession）。

## 三、通用方案映射（A–F → 本仓）

### 3.1 图景

```
父会话（主上下文）
  │  派发：profile + 目标 + brief（指针）+ 工具白名单 + 预算
  ▼
Run（独立生命周期：可暂停 / 中断 / 回放）
  ├─ 子上下文 = buildInternalMessages(独立 messages, 子集 tools, 作用域 memories)
  ├─ 能力信封：可写？需审批？升级给谁？
  └─ 账本：token 曲线 / 工具轨迹 / 决策点  ← 与 PromptInspector 同一份数据
  │
  ▼  回传：结构化结果 + 证据指针（文件路径 / 消息 id / run id）
父上下文（只多一条摘要 + 一个指针注解）
```

层级刻意保持**浅**（单层起步）——这是相对桌面方案的简化，理由见 §二.4 与 §八。

### 3.2 逐项映射

| 通用方案 | 本仓落地形态 | 难度 | 说明 |
|---|---|---|---|
| **A. 隔离运行时 + 显式交接** | 独立 `messages` + 工具白名单（过滤 `createTools` 结果）+ 完成契约 | **小** | 组装现成（§1.2） |
| **B. 上下文打包/压缩** | 父→子：复用**注解指针**传 brief；子→父：只回结果 + 指针 | 小 | 先例见 §1.3 |
| **C. 分层记忆挂载** | 记忆已按 `assistant_id` / `GLOBAL` 作用域注入 ⇒ **扩展为命名空间** | 小–中 | 现成作用域语义 |
| **D. checkpoint / rewind** | transcript 可序列化；`message_node` 已是 JSON blob ⇒ **复用**比新建便宜 | 中 | 依赖 §七.1 的存储决定 |
| **E. 最小权限** | `needsApproval` 已存在，但需抽**统一策略层**推广为「能力信封」 | **中–大** | 见 §2.1，**唯一无现成答案项** |
| **F. 可观测性** | **直接扩展 `PromptInspectPage`**（已做逐段归因 + 跳到来源） | 小 | 见 §四 |

## 四、查看与管理界面（移动端）

**不要照搬桌面树状时间线**（手机上不可用）。建议三级，且全部复用已有组件：

1. **聊天内运行卡**（inline）：形态对齐现有的**压缩检查点卡**与**工具卡**——显示状态 / token / 失败原因，可展开、可中断。
2. **运行详情页**：**即「请求内容」页（`PromptInspectPage`）的扩展**——它已在做「这段来自哪、点了跳过去」。子代理版 = 子代理看到的组装 + 消息流 + 工具轨迹三合一。
3. **管理动作收敛为**：中断 / 重跑 / 改预算 / 收紧权限 / 导出。

**这一项是本设计的「便宜收益」**：F（可观测性）几乎是白送的，因为 `PromptInspectPage` 的逐段归因 + 来源跳转刚落地，子代理的账本与它是同一份数据。

## 五、落地建议

### 5.1 硬性前置

**先做 `LoopGuard` + 预算切分，再做子代理**（理由见 §2.4）。

### 5.2 分阶段

**Phase 1（一个 profile，串行，只读）**
- 只做**研究/检索型**（不改文件、不跑 shell）——**刻意避开 §2.1 审批这个最难的坑**
- 串行、不嵌套、单层；预算硬上限 + 早停
- transcript 归属按 §七.1 决定
- UI：运行卡 + 详情页（扩展 `PromptInspectPage`）

**Phase 2**：多 profile（profile ≈ 可复用的 `Assistant` 子集）+ 写入类工具（走审批升级）+ 并行上限 2

**Phase 3**：嵌套——**慎做**，深度硬上限 1，且每层都要预算切分

### 5.3 一条能省掉大量过度设计的判据

**技能 vs 子代理的分界线**：本仓已有技能（`use_skill` 把指令注入**同一个**上下文）。子代理**唯一**的增量是「把过程移出主上下文」。

⇒ 判据：**这项工作留在主上下文会不会污染它？**
- **不会**（短、一两步）→ 用技能/工具，**更便宜**
- **会**（长、多步、试错多）→ 才值得派子代理

**子代理不是「更高级的工具」，是「上下文卫生手段」。** 这条能挡掉大半过度设计。

## 六、风险与注意点

1. **prompt cache 命中率下降**（§1.2）：每个子代理各占一条前缀。父会话自身缓存不受影响，但整体命中率降低——与 fork 一直在保护「前缀稳定」的方向相反，需在预算里计入。
2. **静默泄漏到侧栏/统计**（§2.3）：复用 `Conversation` 表时，12 个列表查询 + 3 处聚合（含 FTS 重建）需加过滤，**漏一个即静默泄漏**。
3. **静默被杀**（§2.2）：Android 回收后台工作，无错误记录。
4. **失控循环倍增**（§2.4）：无 LoopGuard 时风险乘以子代理数。
5. **审批绕过**：无头 run 若无能力信封，等于给工具一个跳过用户确认的入口——**这是安全边界，不是体验问题**。
6. **`toolName` 是双消费方**：装配侧（`ChatToolFactory`）+ 展示侧（`ToolUIRegistry`，`ToolUI.kt:93`）。子代理若引入新命名（profile 前缀等），**两处都要同步**，否则渲染回落。
7. **后台旁路契约不可破坏**：标题 / 建议 / 压缩 / OCR / 翻译走 `backgroundTextGenerationParams`（`ChatService.kt:144`，**刻意不含 tools**）。子代理不应改变这个「无工具」契约，而应另开入口。

## 七、待确认

1. **transcript 归属（~~需拍板~~ **已拍板 2026-09-26：选 (b)**，见 §9.1）**——三选一：
   - **(a) `Conversation` + `kind` 判别字段**：复用一切（会话原语 / DB / 渲染 / 详情页）；代价 = §2.3 的 **15 处**过滤（12 列表 + 3 聚合）与静默泄漏风险。
   - **(b) 独立 run 表**：无泄漏风险；代价 = 复制 node / 序列化机制。
   - **(c) 纯内存、结束即弃**：最简；代价 = 不能回放 / 续跑（Phase 1 其实够用）。
   - *倾向*：(b) 或 (c)——按「让错误不可能发生」的教训；但 (a) 的复用收益很实在，取决于对「回放」的重视程度。
2. **无头审批的产品政策**（§2.1）：预授权信封的粒度？高风险如何升级到用户？是否需要「本次会话内记住」？
3. **是否引入统一 run 账本**：rikkahub-agent 的 `data/agentrun/` 是 Cron / 工作流 / 子代理 / Telegram / 外部自动化**五入口共用**的账本。若本仓未来会有多个无头发起方，**统一账本是对的起点**；若只做子代理一种，可省。
4. **Phase 1 是否值得做**：见 §附「与既有 backlog 的竞争关系」。

## 八、明确不做

（针对**移动端**的取舍，非能力上限）

- **CRDT / MVCC 合并写**：无并发写同一资源的场景。
- **Merkle / append-only 审计账本**：无合规诉求（个人应用）。
- **TEE / 脱敏执行**：Android 端不现实。
- **并行 5 个**：手机 RAM / 电量 / 发热不允许；上限 2 已属激进。
- **桌面式树状时间线**：1080px 屏不可用（§四给了移动端替代）。
- **「上下文虚拟内存 / 分页换入换出 KV cache」**：provider 侧不开放该能力，客户端无从实现。
- **子代理间黑板协作**：Phase 1–3 都不需要；引入即复杂化。

## 九、2026-09-26 四项讨论结论（仍非排期）

用户推动四项讨论：①独立 run 表 ②无头审批深聊 ③run 账本 ④另开后台入口做 Phase 1。四项实为**同一个 Phase 1 形态的四面**：一个独立于聊天路径的后台运行器 + 自有的 run 存储。逐项结论如下；**本节只记录讨论结论，未立项**。

### 9.1 独立 run 表——拍板 §七.1 选 (b)，且比设计稿写的更便宜

§七.1 三选一，**定 (b) 独立 run 表**。比 §2.3 评估时更进一步的理由：

- **run 的轨迹是线性的**：没有重生成分支、没有用户编辑 ⇒ `message_node` 的核心机制（分支候选 / `select_index` / 树形结构）对 run **全是死重**。复用 (a) 等于背 15 处过滤风险去买用不上的能力。
- **run 不是 conversation**：pin / 文件夹 / 注入绑定 / 助手指针这些 Conversation 的元数据丛林对 run 全部无意义。分开存储让两类 UI 面（侧栏 vs 运行列表）**在数据层就不可能混淆**——这正是「让错误不可能发生」的形状。
- **Phase 1 的轨迹量小**：单 run 几百 KB 级 ⇒ **轨迹存 run 行内的 JSON 列**（线性、追加式）即可，无需第二张节点表；若日后运行详情页需要结构化查询（按工具过滤等），再拆表（拆是增量，不迁移）。

run 行的字段草图（Phase 1 够用即可）：`id`、`entry_point`（见 9.3）、`status`、`profile_assistant_id`（或快照）、`goal`、`tool_envelope`(JSON，见 9.2)、`budget`(JSON)、`created/started/finished_at`、`result_summary`、`error`、`transcript`(JSON)。**不含** parent_conversation_id 的外键语义——父指针用 `UIMessageAnnotation` 指针注解形态（§1.3 先例），不强依赖会话存在。

### 9.2 无头审批——把「谁来批」改写成「拒绝怎么传达」

核实后的关键事实：**workspace 工具已有现成的信封雏形**——`resolveWorkspaceToolApproval`（`WorkspaceTools.kt:36`）= 每工作区 `toolApprovalOverrides` 映射 + 默认表 + 兜底 false。审批策略层不是从零建，是把既有形状提升到 run 级。

结论分三条：

1. **信封 = run 级的派发时快照** `{toolName → allow/deny}`，不是 profile 级全局开关。profile 是模板，run 是实例——快照允许「这一次收紧/放宽」，且派发时对用户可见（信封确认是派发 UI 的一部分）。
2. **未入信封的工具：不装配，而不只是拒批**。这是与聊天路径的本质区别——`needsApproval=true` 的语义是「等人批」，**无人头可等时它拦不住任何东西**。信封过滤发生在 `ChatToolFactory.createTools` 结果之后（装配单点的好处：过滤一处即全覆盖，见 §1.1）。⇒ Phase 1 的安全边界与「有没有审批 UI」完全解耦。
3. **拒绝的传达 = 现有的 denied 语义**：模型收到「被策略拒绝」的文本结果并自行调整——这条路径聊天里已在跑（`ToolApprovalState.Denied`），无头 run 沿用即可。**不存在「pending 永远无人应答」的状态**：Phase 1 没有升级到人的通道，一切非白名单即拒绝并告知模型。

#### 9.2.1 重复拒绝的升级路径（2026-09-26 用户指出的漏洞，必须补）

**漏洞**：上面第 3 条「拒绝传达复用 denied 语义」有一个失控形态——模型被拒后**换参数/换工具继续撞**，
连续多次拒绝后模型放弃填内容，**以空响应结束**（API 侧表现为无回传、对话自然终止）。
这个循环**三个既有护栏都覆盖不到**：

| 护栏 | 为什么拦不住 |
|---|---|
| `ToolCallLoopGuard` | 只拦**同签名**重复；换参数/换工具的连续拒绝每次签名都不同 |
| `WorkspaceTurnBudget` | 只计 `workspace_*` 工具；拒绝可发生在任何工具上，且拒绝结果文本很短 |
| 全局 `maxSteps` | 256 步才兜底，且结束后 run 会被记成「正常完成」 |

在聊天里这个形态的后果轻（用户看到空气泡会自己反应）；在**无头 run** 里它就是一次静默失败——
run 被记成 completed、result 为空、没人知道为什么。这正是本仓反复踩的「静默失败」类。

**三条缓解（都沿用护栏家族的既有模式）**：

1. **拒绝文本契约（源头减少重试）**：信封拒绝的结果文本必须是**策略终态**而非「取消」——
   明说「本 run 不允许此工具，勿重试，改用其他方式或直接收尾」。现在的文本
   （`{"status":"cancelled","error":"Generation cancelled by user..."}`，`ChatService.kt:1219`）
   措辞像一次性取消，等于在邀请重试。run 路径用独立文本：
   `{"status":"denied","error":"Tool 'x' is not permitted in this run by its capability envelope. Do not retry it; proceed without it or finish."}`
2. **重复拒绝计数（护栏家族第三个成员）**：按 **run** 计拒绝次数（**不按签名**——盲区正在于此），
   阈值（建议 3）命中 → 优雅停止 + 独立 notice（「已因权限拒绝中止」，区别于失控循环与预算耗尽），
   run status = `failed(permission_denied)`。形状与 LoopGuard/WorkspaceTurnBudget 同款：
   结果回填 → 停 → 用户可见提示；计数器也是 run 级纯类、跨审批续跑累积。
3. **空响应不得记成「成功完成」**：run 的完成判定加一条——最后一条 assistant 消息文本为空
   （或 `result_summary` 为空）⇒ status = `failed(model_gave_up)`，且若本轮发生过拒绝，
   失败原因带上「N 次拒绝后模型放弃」。这条防的不只是拒绝路径：**任何**「模型空转结束」
   在无头场景都是静默失败，run 有 `status` 字段（9.3），把「看起来完成」和「真的完成」区分开是它的职责。

Phase 2 才考虑「升级到人」（通知 + run 暂停 + 远程批准）——它的前置是 9.4 的后台入口先跑通。**这个提案把 §2.1 从「唯一无现成答案项」降级为「有明确 Phase 1 形态的工程问题」**：Phase 1 的工具集（读文件/检索）在默认审批表里本就低风险，真正的高风险（shell 写操作）根本不进信封。

### 9.3 run 账本——与 9.1 是同一张表，day one 就按账本形状建

§七.3 的「是否引入统一账本」：**建，但它就是 9.1 那张 run 表**——`entry_point` 列 + 状态机（`pending/running/completed/failed/cancelled`），即使 Phase 1 只有子代理一个入口。

理由：账本本身极便宜（一张表一个 DAO）；本仓**已可预见**多个无头发起方（子代理、未来的定时任务、外部自动化——§1.1 注意点 9 预判的「每入口都是独立生成发起方」）；事后把子代理专用表升级成共用账本是一次迁移，day one 多一列的成本 ≈ 0。

**边界**：只做**记录层**，不做通用任务框架——不抽象 Runner 接口、不做调度器、不让标题/建议/压缩这些即抛型后台任务进账本（它们无运行时、无轨迹，记了只是噪音）。第二个真实入口出现时再抽象共用部分。

### 9.4 另开后台入口做 Phase 1——同意，且它反过来简化了 9.2 与 9.1

§六.7 的警告（`backgroundTextGenerationParams` 的「无工具」契约不可破坏）正是这个方向：**契约不动，子代理另开入口**。形态：

```
SubagentRunner（单例，类比 ChatService 但远小）
  ├─ 建 run 行（status=pending → running）
  ├─ ChatToolFactory.createTools(...) → 按信封过滤（9.2）
  ├─ 自持 profile + 独立 messages + 自建 ToolCallLoopGuard/WorkspaceTurnBudget
  │   （run 自身就是「一轮」的边界，不挂 ConversationSession——9.1 决定 run 不进会话体系）
  ├─ 调 generationHandler.generateText → 轨迹追加进 run 行
  └─ 完成/失败/取消 → 写 result + status；活跃期间持有 KeepAliveService 前台窗口（fork 已有）
```

**它简化了另外两件**：后台入口本就没有聊天消息可挂审批 UI ⇒ 信封+自动拒绝（9.2）是自然形态而非妥协；run 与 chat 两条生成路径互不触碰 ⇒ `ConversationSession` 的 5 秒回收 / 消息队列 / 审批 UI 全都不用绕（§2.2 的生命周期问题从「改造会话」降级为「管好前台服务窗口」）。

**遗留的 Phase 1 决策——触发方式**：设计稿图景是「父会话派发」，但那要求父路径支持 dispatch 工具（改聊天生成路径）。Phase 1 建议更简：**手动从 UI 派发**（选 profile + 目标 + 信封确认 → 开跑），完成后可选分享进某会话。⇒ Phase 1 完全不碰聊天生成路径，纯增量。

**诚实标注一个定位代价**：没有父会话集成，Phase 1 的「上下文卫生」价值（§5.3 判据）**还不成立**——没有主上下文可保护，它实为「独立的后台 agent」而非「子代理」。Phase 1 验证的是 **run 表/账本/入口/观测** 这层底座（以及 profile+独立上下文组装是否如 §1.2 预期那样近乎免费），不是「父→子」协作本身；后者是 Phase 2 引入 dispatch 工具时才真正被检验的东西。这个定位要在立项时写明，避免用「子代理有没有用」来评判 Phase 1 的成败。

### 9.5 四项合起来的 Phase 1 范围（仍非排期）

- **做**：run 表（含账本列）+ DAO、SubagentRunner（装配过滤 + generateText + 轨迹追加 + KeepAlive 窗口）、信封过滤 + 派发 UI（profile/目标/信封确认）、运行详情页（扩展 PromptInspectPage，§四）、运行列表（账本的 UI 面）
- **不做**：dispatch 工具（父路径）、升级到人的审批、并行、嵌套、profile 管理界面（先用现有助手当 profile）
- **前置已满足**：LoopGuard + 单轮预算（§2.4 已更新）

## 十、2026-09-26 对话内派发落地（§9.5「不做」里的第一项）

Phase 1 已落地（§九）。本节记录**下一步**：把 §9.5 明确推迟的 **dispatch 工具（父路径）**做出来——
让对话里的模型能按名称调度子代理，并为此引入**「按名称调度的模型 + 提示词」配置组合**。
这一步让 §9.4 里那个"诚实标注的定位代价"消失：**有父上下文可保护了，§5.3 的判据真正成立。**

### 10.1 配置组合的形态——用户拍板选 A（预设自带能力开关）

三个候选：A 预设自带能力开关 + 工具白名单；B 预设引用一个基底助手；C 不加新实体、直接用助手当预设。
**定 A。** 理由：

- **B/C 都会把子代理拖进助手的元数据丛林**：头像 / 标签 / 快速消息 / 角色卡零件 / 正则 / 注入绑定。
  其中 **正则与注入绑定是有害的**——它们会把父对话的提示词污染进子代理的上下文，
  正好推翻「独立上下文」这个唯一卖点。C 还多一条：改提示词会连动日常聊天（同一份 system prompt）。
- A 的代价是"能力面比助手窄"：预设只有 **联网搜索** 与 **工作区** 两个能力开关。
  这是**刻意**的——本地工具（剪贴板/日历）、技能、MCP 都不进预设的能力开关，
  因为每加一个开关就多一组"配错了会静默不给工具"的组合。手工派发路径（选助手当 profile）仍保留全量能力。

### 10.2 数据模型：`SubagentPreset`（`Settings.subagentPresets`）

| 字段 | 语义 |
|---|---|
| `name` | **调度名**（模型调用时传入；同列表内唯一，大小写不敏感，允许中文） |
| `description` | 同时给**人**（列表）和**模型**（清单）看：说明它擅长什么，模型据此挑 |
| `modelId` | `null` = **跟随当前对话模型**（内置预设全部留空 ⇒ 开箱可用、换 provider 不静默失效） |
| `systemPrompt` | 子代理**自己**的 system prompt（父对话的 system prompt / 注入不带过去） |
| `enableWebSearch` / `workspaceId` | 两个能力开关（装配 `search` / `workspace` 段） |
| `allowedTools` | 工具信封，三态：`null`=按能力开关 / 空集=不给工具 / 非空=与装配结果取交集 |
| `requireApproval` | 派发前是否要用户批准（默认关：信封已把工具面收窄到预设作者自己配的范围） |
| `timeoutSeconds` | 单 run 超时，默认 **600s**（短于手工路径的 30 分钟——对话在被按住） |

**内置三个**（就是 `Settings` 字段的默认值 ⇒ 零迁移；**没有墓碑机制**，用户可改可删）：

- `research` 深度检索（联网搜索 / 信封 `null`）
- `review` 第二意见（无工具 / 信封**空集**）
- `digest` 要点提炼（无工具 / 信封**空集**）

内置预设的 **id 是固定 UUID**，不是 `Uuid.random()`：随机 id 会让"从未写过设置"的设备每次启动
换一批 id（管理页列表 key 漂移），也无法在文档/测试里引用。

提示词（`data/ai/prompts/SubagentPrompt.kt`）用**英文**——与本仓其它默认提示词一致（给模型看的指令）；
`description` 用**中文**——它是给人看的列表文案。

### 10.3 信封三态：`null` 只覆盖能力开关控制的来源（**本节最关键的一条**）

`createTools` 里有**无条件装配**的来源：MCP 服务器工具、技能安装工具。
若把 `allowedTools == null` 解成"装配出的全部"，那么一个只勾了"联网搜索"的研究型预设会**静默获得**
第三方 MCP 工具（可能是写日历、发消息这类**有副作用**的工具）——用户配的是"能搜索"，
拿到的是"能改我的日历"。⇒ 规则：

- `null` → 只放**能力开关控制的段**（`search` / `workspace`）里的工具；
- 非空集 → 与装配结果取交集（**点名**可以放进 MCP / 技能安装等来源，但要用户亲手写出工具名——有意的摩擦）；
- 空集 → 不给任何工具。

实现上给装配点加了**分段视图**（`createToolSegments` 返回 `List<ToolSegment>`；`createTools` 保持原签名只做扁平化），
段名因此从"日志分类"升级为**对外契约**（改动它会影响信封语义，已写进 KDoc）。
`resolveSubagentEnvelope` / `runToolCatalog` 是纯函数，管理页预览与 run 执行**用同一份逻辑**——
页面上写着"生效 3 个工具"，run 里就真的是那 3 个。

### 10.4 禁嵌套（双层）与阻塞式派发

- **禁嵌套**（第一层，主要一层）：信封解析**恒定剔除** `dispatch_subagent` 自己——不依赖任何开关，
  于是"子代理不得再派发子代理"是构造性成立的；**串行闸**（`activeRunId`）只是第二层兜底。
  手工派发页的候选清单也同口径剔除它（候选里出现它等于承诺一件不会发生的事）。
- **阻塞式**：工具调用等 run 跑完才返回（`dispatchAndAwait`），与手工派发（即发即忘）**共用同一个 `executeRun`**
  ⇒ 账本、护栏（LoopGuard / WorkspaceTurnBudget / DenialGuard）、信封、完成判定完全一致，
  唯一差别是"谁在等结果"。不采用"先回 run_id、结果稍后再注入对话"：那需要往对话树里塞外部消息，
  与 message_node 的分支模型冲突，且会打破"用户看到的就是模型看到的"。
- **中断语义**：阻塞路径跑在**调用方协程**里，所以用户中断本轮对话 = 取消子代理 run
  （`executeRun` 的取消路径把它落成 CANCELLED，不留假 running 行）。

### 10.5 其余落地决定

1. **全局开关 `enableSubagentDispatch`，默认关**：开启后每次请求多一段工具 schema + 一份子代理清单，
   且派发会按住本轮对话数分钟；这是"模型自己决定再叫一个模型"，不该由更新替用户打开。
   开关与预设管理放在**同一页**（预设是全局配置，放助手设置里会让人以为"每个助手各配一套"）。
2. **`entry_point` 分两个值**：`subagent`（运行页手动派发）/ `chat`（对话内工具派发）。
   两者的信封来源不同（预设 vs 手工勾选），排查"为什么碰不到某个工具"时要先看是哪条入口。
3. **列表摘要截断下移**：`evaluateCompletion` 不再截断，改为在落库处截断（1000 字符）——
   工具路径要把**全文**回给父模型（父模型拿不到全文的话，"回传只带结论"就变成"回传带残缺结论"）。
4. **工具输出 = JSON + 指针**：`{status, run_id, summary|error, note?}`（§1.3 的"结构化结果 + 指针"）。
   对话卡片据此渲染（标题带调度名、摘要复用运行页的状态/错误文案、详情页头部给「查看运行」入口）——
   **子代理的中间过程不进父上下文，要看过程只能去运行详情页**，卡片是唯一路标。
5. **工具名双消费方已同步**（§六.6）：装配侧（`ChatToolFactory`）+ 展示侧（`ToolUIRegistry` 的 `SubagentToolUI`）。

### 10.6 可观测性收尾（2026-09-26 第二步，`609019c4`；嵌套按用户要求不做）

§1.3 的取舍（子代理过程不进父上下文）有个必然推论：**「它现在跑到哪了」只能从运行侧读**。
本次把这条链的两个方向补齐：

1. **工具卡实时进度**：`SubagentRunner.progress: StateFlow<RunProgress?>`（runId / 标签 / 步数 /
   最近工具 / 起始时间），卡片在 `loading` 时显示 `进行中 · 第 N 步 · 4 秒 · search_web`。
   - **敢不带 runId 直接订阅**的理由：串行闸保证同一时刻至多一个活跃 run，而卡片只在
     「本次调用尚未返回」时读它 ⇒ 那时的活跃 run 就是它的那一个（手动派发在活跃期间被拒）。
   - 步数/最近工具由纯函数 `snapshotProgress(currentMessages)` **从轨迹派生**：审批续跑、
     工具结果回填都会重算 messages，自记一份计数必然与它漂移。
2. **run → 对话反查**：`agent_run` 加 `parent_conversation_id`（**可空纯列、无外键**，
   AutoMigration 30→31）——会话被删后残留悬空 id 是**正常情形**，展示层用
   `existsConversationById` 判一下再给入口，而不是靠约束阻止删除、也不级联删 run。
   父会话 id 的**唯一来源是装配点**（只有 `ChatToolFactory` 知道这次生成属于哪个会话）⇒
   `createTools` 加 `conversationId` 透传。详情页在「确有父会话」时给**带标签**的「回到对话」
   （图标-only 入口此前被用户吐槽不可发现）。

**期间修掉一处真缺陷（同批提交）**：`enableSubagentDispatch` / `subagentPresets` 是 Settings
**顶层平铺字段**，上一轮漏了 PreferencesStore 三件套 ⇒ 只在内存里活着、**重启即回默认 false**、
装配条件静默不成立。发现路径=先看装配日志的**段列表**（没有 `subagent` 段）→ 查条件依赖的设置值
→ 拉 `settings.preferences_pb` 发现 key 根本不在文件里。详见 `docs/handoff-2026-09-04.md` 与记忆
`settings-persistence-trio`。**教训**：开关型顶层字段必须做一次「改 → 杀进程 → 重启 → 看效果」。

### 10.7 这一步之后仍缺什么（Phase 2 剩余）

- **并行**（上限 2）、**`ContextBudgetPlanner`**（预算切分精度）。
- **嵌套**：**用户明确暂不做**（原设计给的是「慎做，深度硬上限 1」）。
- **transcript 的结构化查询**（按工具过滤）、**导出**。

> 注：「升级到人的审批」已在 §十一 落地，不再属于缺口。

### 10.8 与 §六 风险清单的对照（这一步的收口情况）

| 风险 | 状态 |
|---|---|
| prompt cache 命中率下降 | 仍在：每个预设的前缀各占一条缓存线（设计成本，非 bug） |
| 静默泄漏到侧栏/统计 | **按构造不适用**：run 走独立表，不碰 `ConversationDAO` |
| 静默被杀 | 账本 + 前台窗口（Phase 1 已有）；阻塞路径另有"中断即取消并落 CANCELLED" |
| 失控循环倍增 | 每个 run 自带护栏实例；父轮次另有自己的护栏 |
| 审批绕过 | 信封 + `autoDenyApproval`；`requireApproval` 是**预设作者**可选的加严 |
| `toolName` 双消费方 | 两处已同步（装配 + 渲染器注册） |
| 后台旁路契约 | **未触碰**：`backgroundTextGenerationParams` 仍是"无工具"契约 |


## 十一、2026-09-26 升级到人的审批（Phase 2 的「写入类子代理」解锁）

§2.1 把它称作「本设计里唯一没有现成答案的部分」。§9.2 把它降级为工程问题并给了 Phase 1 形态
（信封外不装配 + `needsApproval` 一律策略拒绝）。本节是它的 Phase 2 形态：**暂停去问人**。

### 11.1 形态：预设级 `toolApproval`，默认不变

| 取值 | 行为 |
|---|---|
| `DENY`（默认） | 需审批的工具**直接策略拒绝**，模型自行绕开（= §9.2 的 Phase 1 形态） |
| `ASK` | run **暂停**在 `awaiting_approval`，弹通知 + 详情页卡片等人批准；**超时按拒绝处理并继续跑** |

默认取 `DENY` 的理由：`ASK` 会改变**既有预设的行为**（用户没点过任何东西却开始被拦住），
而「要不要让人被叫起来」是使用者的决定。这也让这次升级本身是零行为变更的。

**只有 `needsApproval == true` 的工具才会问**。注意本仓这个判定是**两层**的：
工作区工具已经有**工作区级**的 `tool_approvals` 覆盖表（`resolveWorkspaceToolApproval`），
预设的 `ASK` 是在它之上「要不要问人」的开关——**工作区把某工具关掉了审批，预设就不会问它**
（真机验证时正是踩到这一条：测试用的工作区把 `workspace_shell` 的审批显式关成了 false，
于是 run 直接执行、看起来像「审批没生效」——实际是工具本来就不需要批）。

### 11.2 机制：复用聊天路径的「答复后续跑」

无头 run 的审批**没有新的执行模型**，就是把聊天路径的手法搬进 `executeRun` 的循环里：

```
while (true) {
    collect(generateText(messages))                 // ASK 时 handler 把需批工具置 Pending 并 break
    pending = pendingApprovalTools(messages)        // 扫**全部**消息（只看最后一条会漏 → 表现为「卡住」）
    if (pending.isEmpty()) break                    // → 走正常完成判定
    status = awaiting_approval                      // 账本可见「它在等我」，而不是像在慢慢跑
    decisions = awaitHumanDecision(pending)         // 登记队列 + 弹通知 + 等（上限 5 分钟）
    messages = applyApprovalDecisions(messages, decisions)   // 回填 Approved / Denied
    status = running
}                                                   // 回填后的同一份 messages 再喂回 generateText
```

四个关键点：

1. **护栏实例提到循环外**（`loopGuard` / `workspaceBudget` / `denialGuard`）：每次批准都是一次
   新的 `generateText`，建在调用内会每次重置——与聊天路径把护栏挂 `ConversationSession` 同源
   （LoopGuard 真机踩过的坑，这里预先避开）。
2. **一条队列、两个入口**：`SubagentApprovalQueue`（进程内静态对象 + `CompletableDeferred`）。
   通知按钮走 `SubagentApprovalReceiver`（`exported=false` + 显式组件 Intent：审批是**权限动作**，
   不给外部留入口），详情页卡片走 `SubagentRunner.resolveApproval`。两者落到同一处，
   所以判定逻辑只有一份。
   - **为什么进程内静态对象就够**：run 本身不跨进程存活（Phase 1/2 都还没 work-manager 化），
     队列与 run 的生命周期**同界**。进程被杀后再点旧通知 ⇒ `resolve` 返回 false ⇒ 什么都不做
     （那次 run 其实也没了），UI 侧如实用「这个请求已经超时」提示，而不是假装成功。
3. **超时按拒绝、不让 run 失败**：`timeoutSeconds` 是给「跑」用的预算；人不在却把整个 run 判死，
   等于把一次没看见的通知升级成任务失败。超时的措辞与用户手拒**分开**（`APPROVAL_TIMEOUT_REASON`
   vs `USER_DENIED_REASON`）：前者要说清「只是没人应答」，否则模型会把一次错过当成永久禁令。
   两条都延续 §9.2.1 的拒绝文本契约（明说勿重试、勿绕路）。
4. **同时只等一批**：一轮里 Pending 的工具一起问（一条通知 + 卡片逐条按钮）。

### 11.3 UI 与账本

- 新运行状态 `awaiting_approval`（**新取值不需迁移**，列本身存字符串）；列表/详情按「需要动作」上色。
- 通知：专用渠道 `subagent_approval`（IMPORTANCE_HIGH，它会阻塞一个正在跑的 run，必须弹得出来），
  标题「子代理 <名> 需要批准」，正文逐条列 `工具名 + 入参摘要`（换行压平、截断），
  两个动作「批准 / 拒绝」= **对整批生效**（小屏没法逐条），点通知主体 → 运行详情页
  （`RouteActivity` 支持 `runId` extra）。
- 详情页卡片：**逐条**列出工具名 + 入参 + 批准/拒绝——一批里常混着不同风险的动作
  （读一个文件 + 写一个文件），逐条才是精确控制的地方。
- 手动派发页的 run 恒为 `DENY`：那条路径本来就是用户当场挑工具/勾信封，再回头逐个批准同一批
  工具是重复确认。

### 11.4 验证（真机 <device>）

- 预设 `research` + 绑定工作区 + `ASK` ⇒ 派发后 run 停在 `awaiting_approval`；通知栏出现
  `子代理 research 需要批准`（渠道 `subagent_approval`、2 个动作）；详情页卡片列出
  `workspace_write_file` + 批准/拒绝。
- **批准**（详情页卡片）⇒ 用真实结果继续：`/workspace/approval-test.txt` 写入成功、run 记 completed。
- **批准**（通知动作，run 详情页从未打开过）⇒ 同样以 `approved` 继续并写入成功。
- **拒绝** ⇒ 模型收到拒绝文本、**绕开该工具继续**，并把「这次被拒绝」如实写进结果（不是静默失败）。
- 单测：`SubagentApprovalTest` 12 项（队列对上号 / 待批筛选扫全消息 / 回填只碰匹配 part /
  摘要压平截断）+ 预设默认 `DENY` 的回归。
- 期间把两处诊断留成**常开日志**：`SubagentRunner: run <id> toolApproval=ASK` 与
  `GenerationHandler: approval check: tool=… state=… autoDeny=…`——审批类问题（该问没问 /
  不该问却问）只看结果无从判断，这两行同时给出「工具定义在不在、要不要批、当前策略」。

### 11.5 仍未做（刻意）

- **「本次会话内记住同意」**：一旦引入就要回答「记多久、跨不跨 run、撤销入口在哪」，
  而手机上一次 run 本来就是一次性的动作单元。
- **并行**、**`ContextBudgetPlanner`**：已在 §十二 落地；**嵌套**按用户要求暂不做。

## 十二、2026-09-26 并行上限 2 + 上下文预算规划器（Phase 2 收尾）

§10.7 / §11.5 剩下的两项。两者是一对：**并行**决定"同时能跑几个"，
**上下文预算**决定"每个跑的时候能吃多少窗口"——并行把这两件事从"隐含的串行假设"里暴露出来。

### 12.1 并行上限 2

- **闸**：`ConcurrentRunGate`（纯状态机：`tryAcquire` / `release` / `canAcquire` / `isActive` / `activeIds`）
  替代原来的单个 `activeRunId` 字段。**满了就拒绝，不排队**——排队会让模型在派发工具里无限等，
  而手机上一次 run 可能跑十几分钟。
- **上限 2 的理由**（§八）：手机 RAM / 电量 / 发热；2 的意义是"**一个在等审批时另一个能跑**"，
  不是吞吐量。5 个并行是桌面 agent 平台的常态。
- **每 run 一个 Job**：`activeJobs: Map<Uuid, Job>`。聊天路径的 run 挂**调用方 Job 的子 Job**
  （`Job(coroutineContext[Job])` + `withContext(runJob)`）⇒ 既能"只取消这一个 run"，
  又能让"用户中断本轮对话"照旧级联取消它（子 Job 取消不反向影响父生成）。
  `cancelRun` 按 id 精确取消——**旧实现只有一个 `activeJob` 字段，并行下"取消 A"会打到 B 身上**（静默取消错对象）。
- **进度从"单条"变"列表"**：§10.6 那种"串行闸保证活跃的就是我这一个 ⇒ 卡片可以不带 id 直接订阅"
  的推论在并行下失效。卡片改为按 **(预设名, 任务目标)** 认领自己那一条——两者都在卡片的入参里，
  无需给卡片额外接线；认领不到且只有一条时退回那一条（有些 provider 会规整 goal 文本），
  多条且认领不到时只显示聚合态「N 个子代理正在并行运行」。**宁可少说，也不要把别人的步数当成自己的。**
- **UI**：运行页 FAB 的门槛从"有无活跃 run"改成 `canStartRun()`（= 活跃数 < 2），
  提示带活跃数：「已有 N 个运行在进行中（上限 2）」——满员要说清是几个在跑。

### 12.2 上下文预算规划器（`ContextBudgetPlanner`）

**动机（run 侧的真实缺口）**：无头 run 的轨迹是**单调增长**的（目标 + 每一步的工具输出），
而它**既没有压缩、也没有 `contextMessageLimit`**（合成 profile 里那个值是 0 = 不限制）——
撞满窗口时 provider 直接报错，一次长 run 就这样白跑。聊天路径有自动压缩兜底，run 没有。

**"planner"而不是又一个 guard**：它算的不是"累计输出够不够多"，而是**"这个窗口里还剩多少给下一步"**
——把窗口切分成「固定开销 + 轨迹 + 留给回答的余量」。判定依据优先取 **provider 真实回传的
`usage.promptTokens`**（上次请求实际发出去的完整上下文 token 数）⇒ 这就是 peer ② 说的 **usage 锚点**；
没有锚点时退化为本地估算（`utils/ContextLength.kt` 的同口径 `estimateTokenCount` + 工具 schema 估算，
**刻意复用而不是各写一份**——三方各写一套启发式会让「记忆预算 / 占用条 / 本预算」量纲漂移）。

三条判定：

| 判定 | 条件 | 动作 |
|---|---|---|
| `Ok` | 剩余 ≥ 4K 且占用 < 80% | 照常 |
| `Warning` | 占用 ≥ 80% | **只记日志**——run 的产物是"已经攒到的结论"，能继续就该继续 |
| `Exhausted` | 剩余 < 4K（[`MIN_REPLY_TOKENS`]） | **优雅停止**：本轮工具结果照常回填，run 记 `failed(context_budget)` |

- **检查位置=执行工具前**（与另外两个护栏的"执行后判定"不同）：撞满窗口是**硬墙**，
  provider 会直接报错，先停能保住已经攒到的结果。
- 固定开销（system 正文 + 工具 schema）**每轮只算一次**，且**有锚点时忽略**（锚点已包含它们，叠加会虚高）。
- 聊天路径**不传** `contextGuard` ⇒ 零行为变更（它有压缩 + 条数上限）。
- 账本新增失败细分 `context_budget`，与 `budget_exhausted`（workspace 工具输出体量）分开记——
  两者防的不是同一件事，排查时看细分就知道该调哪个预算。

**三个护栏家族的分工**：`ToolCallLoopGuard` 防"反复撞同一个"，`WorkspaceTurnBudget` 防"工具输出堆成山"，
`ContextBudgetPlanner` 防"窗口被轨迹吃满"。互补，不重叠。

### 12.3 验证状态（诚实记账）

- **单测**：`ConcurrentRunGateTest` 5 项（上限/拒绝/释放复用/重入/id 一致性）+
  `ContextBudgetPlannerTest` 13 项（锚点优先且忽略开销 / 无锚点退化 / 非助手消息的 usage 不作锚 /
  阈值三条边界 / 零窗口不除零 / `describe` 区分 anchor 与 estimate）+ 守卫薄封装；
  全量 **1305 项 0 失败 3 跳过**。
- **真机（<device>）——部分验证后用户中止**：
  - 已确认：启用状态下**两次手动派发在并发窗口内都被接受**（`SubagentRunner: run …` 两条）；
    UI 侧「新建运行」对话框在有 run 活跃时仍可打开（旧行为会直接被忙提示拦住）。
  - **未在真机确认**：①"第 3 个被拒绝"（单测覆盖 `tryAcquire` 的拒绝语义）；
    ②上下文预算的逐步日志与"耗尽优雅停止"（单测覆盖判定，真机日志未看）。
  - 附带发现（与本轮无关）：手动派发用 `探讨和实现` 助手时 run 立刻失败，
    原因是该助手绑定的 `[Ant-c]gemini-3.7-flash` 在用户的 relay 上「无可用渠道（distributor）」——
    既有模型/渠道问题，不是本轮代码。

## 附：信息可信度说明

- **本仓事实**：均经代码核实，含行号引用。
- **rikkahub-agent**：来自**我们自己的笔记**（`docs/peer-apps-benchmark.md` §一：`subagent/` = `SubAgentProfile`/`Request`/`Run` 三层 + `maxTrips` + `timeout` 双限制；`docs/roadmap.md:398-403`：5 内置 profile + 嵌套 + 并行派发 + 进度可视化；`data/agentrun/` 五入口共用账本）。**属二手**（读其源码树所得），比通用框架具体，但未逐行核对。
- **opencode / codex / Cherry Studio**：**本文未引用其实现细节**——我没有核实过它们各自的子代理实现，只知其通用形态（父派任务、独立上下文、结构化回传）。相关论述一律停留在「通用方案」层面，以免编造。若需具体借鉴，需先实地核查。
- **与既有 backlog 的竞争关系**（诚实评估）：本仓待办里已有终端 ③⑤⑦⑧、rootfs 四项改进、LoopGuard + forget、ContextBudgetPlanner、备份 P0 等**用户直接可见**的项。子代理在本仓属「锦上添花」而非「填坑」——若与之竞争排期，**优先级应低于上述各项**。
