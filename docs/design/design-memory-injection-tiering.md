# 记忆注入的分层与位置设计（L0–L3）

> 状态：**第 1 步（L0+L2+L3）已落地**；**第 2 步 L1 已落地一半**——DB/模型/渲染/接线/守卫
> 与「固化记忆」入口均已完成（真机可标记锚点），**尚缺「会话开始冻结」**。2026-10-15。
> 决策来源：真机实测（见 §2）+ 用户拍板（§5 的 L1 刷新触发点）。

## 1. 问题：记忆一变，整个前缀缓存全废

`PromptAssembly` 把记忆块放在**唯一那条 system 消息内部**（`SystemBlockSource.MEMORY`，位于 PERSONA 之后、
TOOL 之前），而 provider 的前缀缓存**从位置 0 起连续匹配** —— system 是最前面那条消息，改它任何一个字节，
整条 system 失配 ⇒ **其后全部不命中**。

`PromptInspectPage.kt:354` 早就写下了这条约束：

> 前缀缓存提示：system 在位置 0，**任何随会话变化的内容出现在 system 里都会让整段缓存失效**

## 2. 真机实测（2026-10-15，DeepSeek-flash，同一会话连续 5 轮）

方法：新建临时会话 → 逐轮发送并读消息下方 NerdLine 的 `(Y cached, Z%)` → 第 3 轮让模型写入一条记忆
（模型实际调用 `memory_tool` 创建成功）→ 第 4 轮读数 → 第 5 轮作**对照组**排除"缓存自然过期"。

| 轮 | 事件 | prompt | cached | 命中率 |
|---|---|---|---|---|
| 1 | 首次请求 | — | 0 | 0%（无前缀可命中） |
| 2 | 稳态 | 7.6K | 7.4K | **98%** |
| 3 | 本轮内写入记忆（**生成开始时的快照**，当轮不可见） | 7.9K | 7.7K | **97%** |
| **4** | **记忆已变** | 8.0K | **0** | **0%** |
| 5 | 恢复稳态（对照组） | 8.0K | 7.8K | **98%** |

日志交叉验证：`memory inject: 1/1 memories ≈278 tokens` → `2/2 ≈301 tokens`（记忆确实变了）；
第 3 轮同一轮出现两条注入日志且条数不变（印证「生成中快照」）。

**结论（比预估更严重）**：一次记忆写入 = **整个 prompt 全量重算**，而不是"记忆之后的部分"。
本次记忆本身仅 **301 token**，却导致 **8.0K token** 全量重算。失效量 = 整个 prompt ⇒
**随会话长度线性放大**（50K 会话写一次记忆 ≈ 50K token 重算），而记忆是**模型主动写**的
（成本 = 会话长度 × 写入次数）。

## 3. 设计：按「层级 → 位置」注入

**判据不是"重要 → 放 system"，而是"可能变 → 必须放尾部"。** 层级与位置一一对应：

| 层 | 内容 | 波动性 | 位置 | 配额 |
|---|---|---|---|---|
| **L0 纪律** | 静态使用纪律文本（现 `buildMemoryPrompt` 的那段英文声明） | **恒定** | system | 固定 |
| **L1 锚点** | 身份/权威类记忆（"我是谁""用户是谁""铁律"） | **冻结刷新** | **system** | 小（3–5 条） |
| **L2 工作集** | 本轮相关记忆全文（**可依赖当前查询**） | 每轮可変 | **tail** | 预算内 |
| **L3 索引** | 其余记忆一行（`id ｜ description ｜ 更新日`） | 写入时变 | **tail** | 预算内 |

### 3.1 缓存代价（可控且显式）

| 层 | 代价 |
|---|---|
| L0 | 0（恒定） |
| L1 | **每次刷新一次全量失效** —— 频率由刷新触发点决定（见 §5） |
| L2 + L3 | **0** —— 在尾部，怎么变都不影响前缀 |

对比现状（§2 实测）：普通记忆写入的代价从「一次全量失效」降到 **0**，只有锚点刷新才失效。

### 3.2 顺带解开 relevance 的死结

L2 在尾部 ⇒ **可以依赖当前查询、每轮重算、每轮换集合**，不再与缓存目标冲突。
此前"relevance 与保缓存互斥"的结论，前提是"记忆块留在 system"；位置一变，结论即变：
**前缀稳定归 L0/L1（头部），查询相关性归 L2（尾部），两者不再抢同一块地。**

### 3.3 位置的最优解：追加在尾部，而不是插在用户消息之前

前缀收益对位置敏感（记第 N / N+1 轮请求序列）：

- **追加最末**：`sys, h…, userN, MEM_N` → 下一轮 `sys, h…, userN, asstN, userN+1, MEM_{N+1}`
  ⇒ 命中前缀到 `userN`，**最大**。
- **插在 userN 之前**：`sys, h…, MEM_N, userN` → 下一轮该位置是 `userN`
  ⇒ **在 `h…` 就分叉，白丢一个 userN**。

**实现取向**：把 L2/L3 文本**追加到最新用户消息的 content 上**，而不是新造一条消息 ——
避免 Anthropic「对话中途不允许 system role / 连续同 role」这类 provider 差异。
（若改成独立合成消息，须逐 provider 验一遍 role 合法性。）

## 4. 借自 bigeye 的对照（本轮深读结论，见 `peer-apps-benchmark.md` §11.1/§11.2）

| bigeye 机制 | 出处 | 在本设计中的位置 |
|---|---|---|
| `_anchors(n=3)` 身份锚点：`ORDER BY weight DESC`，30 天内，恒在场极小 | `fragment_store.py:892` | **L1 的原型** |
| `recall_guaranteed(cap=2)` 权威知识：确定性通道、**绕开语义门槛**、**禁 AI 自填** | `:508-546`、`:965-971` | **L1 的入选与提拔纪律** |
| `recall()` 五因子工作集（relevance 是乘数） | `:439-506` | **L2** |
| `IMPORTANCE_PIN_THRESHOLD=7.0` 免衰减免下沉 | `:42, 791, 883` | 锚点的守恒规则（≈本仓 0.7） |
| `layer` / `epistemic` 分类维度 | `:81-92` | 可选，若日后要分类 |

**关键**：bigeye 的「锚点」与「保底」本来就设计成**小配额、恒在场**，天生适合 system；
「工作集」天生每轮变，必须去尾部。本设计是把这套分层**显式映射到注入位置**。

## 5. 决策（用户拍板）

- **L1 刷新触发点 = 会话开始时一次 + 用户手动「固化记忆」**（不做每 N 轮自动刷新）。
  理由：把失效频率变成**显式且可预期**的 —— 每个会话至多一次自动失效，其余由用户主动触发。

### 5.1 冻的是「集合」，不是「文本」（实施中修正，2026-10-16）

原计划把 L1 渲染好的**文本**冻进会话（§8 第 2 步的"或改为传已渲染好的 L1 文本"）。
实施时改成冻**锚点 id 集合**、文本按当前内容实时渲染。触发修正是记忆库里的
**两条自动删除路径**：

- `MemoryConsolidationGenerationAgent`（做梦整理）会 `deleteMemory`；
- `MemoryRepository.enforceMaxCount`（200 条上限）按分数淘汰，**锚点并不在豁免名单里**。

这两条都不会去逐个刷新会话。冻文本的话，一条被删掉的锚点会**永远**留在 system 里
——既忘不掉（模型继续看到它），也改不动（用户改内容也不生效）。而存 id 则：

| | 冻文本 | 冻 id 集合（现方案） |
|---|---|---|
| 新固化一条锚点 | 不影响本会话 ✓ | 不影响本会话 ✓ |
| 删掉一条锚点 | **永远留在 system** ✗ | 立刻消失 ✓ |
| 改写锚点内容 | **不生效** ✗ | 立刻生效 ✓ |
| system 字节稳定性 | 集合与文本都不变才稳定 | **只由集合决定** |

「改写立刻生效」看着像削弱了冻结，其实是回到 §5 的原意：用户按「固化记忆」按钮表达的是
**"以后哪几条算数"**，而不是"顺便把当时的措辞也钉死"。被冻住的是集合 ⇒ 新固化/取消固化
不会悄悄冲掉所有会话的前缀缓存，这正是 §5 要买的东西。

## 6. 数据模型改动

1. `is_anchor: Boolean`（或 `authority_level: Int`）—— L1 入选依据。
   **照抄 bigeye 的安全约束：禁止 AI 自填，只由整理器/用户提拔。**
2. `pinned: Boolean`（或复用 importance 阈值，bigeye 7.0/10 ≈ 本仓 0.7）—— 免衰减 + 免淘汰。
3. 可选：`layer` / `epistemic` —— 暂不加，等有明确消费者再说（避免"预留字段变技术债"，
   这是 bigeye 自己的教训：`MAX_OBS_PER_PAGE` 零引用且与注释矛盾）。

## 7. 风险与缓解

| 风险 | 缓解 |
|---|---|
| **L1 冻结会陈旧**（会话中途用户改口，system 仍断言旧版本） | L1 只放**低变动率**内容（身份/铁律）；"用户改口"由 L2 覆盖 |
| **L2 降到尾部后遵循度下降**（system 权重高于历史） | A/B 验证；这正是 L1 存在的理由——把真正需要权威感的几条留在 system |
| **契约测试要改** | `PromptAssemblyTest` 锁着 `joinToString("\n")` 的逐字节契约，属预期成本（用户已确认可接受） |

## 8. 实施顺序

**第 1 步（L0 + L2 + L3）**：`buildMemoryPrompt` 拆成「静态纪律」与「动态数据」两段；
动态段在 `buildInternalMessages` 追加到最新用户消息；更新 `PromptAssemblyTest` 契约；
单测钉住「记忆数据不在 system 内」与「尾部位置」。**不动打分与选取逻辑** ——
立即拿到 §2 那 98%→0% 的收益。

**第 2 步（L1）**：加 `is_anchor` 字段 + 迁移 + 提拔纪律（禁 AI 自填）+「固化记忆」入口
+ 会话开始冻结；`PromptInspector` 补 L0/L1/L2/L3 分段归因。

- ✅ **已完成（批 1）**：`is_anchor` 列（36→37 AutoMigration）、`AssistantMemory.isAnchor`（`@Transient`）、
  `MemoryRepository.setAnchor`（**唯一**锚点写入口）、`buildMemoryAnchorPrompt`（配额 5）、
  `SystemBlockSource.ANCHOR`（**无锚点不产生该块** ⇒ 输出逐字节不变）、记忆页「固化为锚点」开关、
  `MemoryAnchorWiringTest` 源码守卫（`addMemory` 不得接受锚点参数）。
- ⬜ **未做（批 2）：会话开始冻结。** 触发点按 §5 = 会话开始时一次 + 用户手动「固化记忆」。
  实现要点（供下次接手，勿省）：
  1. `ConversationEntity` 加 `frozen_anchors`（`@ColumnInfo(defaultValue = "")`，与邻近的
     `custom_system_prompt` 同款可空/默认列 ⇒ **AutoMigration 无需 spec**），DB **37 → 38**；
  2. `Conversation` 模型加同名字段 + `conversationToConversationEntity` / 反向映射两处补齐；
  3. **难点在这一步**：`buildInternalMessages` 目前**拿不到 `Conversation`**，需要把冻结快照
     从 `ChatService` 一路传进来（或改为传已渲染好的 L1 文本）——**新增参数而非从 messages 反推**，
     避免又长出第二个装配真相源；
  4. 会话创建时写入快照；用户在记忆页点「固化/取消固化」时**重写该会话的快照**（这就是
     §5 的手动刷新点）；无锚点时必须写空串而非 null，保证「无锚点 ⇒ 无块」不变式仍然成立；
  5. 测试：会话中途改锚点 ⇒ 该会话 system 块**不变**；点固化 ⇒ 块更新；新会话 ⇒ 取当前锚点。

**其余待收尾**（第 1 步遗留）—— ✅ **均已收尾**（`7f32ef1da` / `5c7dc47dd`）：
`PromptInspector` 已给尾部记忆块单独分段归因（`PromptSegmentKind.MEMORY_TAIL`，
按 `MEMORY_TAIL_PREAMBLE` 识别）；`ContextCapacitySheet` 已为尾部记忆**单开一行**
（`ContextCapacity.memoryTailTokens`，从系统记忆里做减法以保持分段和不变），
6 个 locale 补齐。

### 8.1 批 2 实施记录（2026-10-16）

- ✅ **第 1 段（机制层，`8967fc8bf`）**：`frozen_anchors` 列 + DB 37→38 AutoMigration、
  模型字段与三处映射、`buildSystemBlocks`/`buildSystemPromptText`/`buildInternalMessages`
  的 `frozenAnchorIds` 参数（默认 null ⇒ 行为零变更）。
- ✅ **第 2 段（接线，本次）**：
  1. **穿透**：`ChatService` → `GenerationHandler.generateText` → `generateInternal`
     → `buildInternalMessages`，全程新增参数、不从 messages 反推。
  2. **会话开始冻结**：`ChatService.ensureFrozenAnchors` 在首次生成**之前**写下锚点 id 集合
     （见 §5.1：冻 id 不冻文本）；无锚点写 `""` 而非留 null，保证不变式。
  3. **手动刷新**：`ChatService.refreshFrozenAnchorsForAssistant` —— **先同步活跃 session 的
     内存态、再一条批量 UPDATE**（顺序反了会留下「库已换档、内存态仍旧」的窗口，
     同 `moveConversationToFolder` 的教训）。由记忆页 `AssistantDetailVM.updateMemory` 调用，
     且只在「锚点翻转 || 本条本来就是锚点」时调用——改一条非锚点记忆的内容不该白废
     该助手每个会话的前缀缓存。
  4. **换助手即清空**：`moveConversationToAssistant` 一并置 `frozenAnchors = null`，
     否则上一个助手的锚点会跨助手串味。
  5. **写入点唯一**：`frozen_anchors` **移出** `updateMetadata` 的列清单，改由两条专用
     单列语句写（与 `sort_order` 同款）。理由：冻结发生在「会话开始」与「记忆页固化」两处，
     而那两处之后调用方手里的 `Conversation` 可能还是旧副本 ⇒ 留在列清单里会被
     **静默写回 null**。护栏：`ConversationSchemaGuardsTest`（豁免名单 + 显式断言列清单
     里没有它）。**注**：只数 `SET frozen_anchors` 抓不到这个形态——注入式反证实测过，
     只数写入点的版本在该扰动下**依然全绿**，故补了决定性的"不得出现在列清单里"断言。
  6. **顺带修掉第 1 步的一个真实回归**：`PromptInspector` 的 `buildInternalMessages` 调用
     漏传 `memoryDisabled` ⇒ 隐身对话的拼装预览会凭空多出一段尾部记忆块（实发里没有）。
     守卫：`MemoryAnchorWiringTest`「preview passes the same memory flags as the real request」。
  7. **测试**：`PromptAssemblyTest` 新增冻结集合语义 6 条（含「删掉锚点立刻消失 / 改写立刻
     生效」——§5.1 那个设计决策的可执行版本）；`MemoryAnchorWiringTest` 新增 4 条源码守卫
     （冻结→透传、手动刷新的顺序、预览同参、`setAnchor` 仍是唯一写入口）。
- ⬜ **仍未做**：真机复测冻结行为（§2 同款方法）；`relevance` 维度（§3.2 已解锁，未实施）。

两步各自带单测与真机复测（用 §2 同款方法给出对照数字）。
