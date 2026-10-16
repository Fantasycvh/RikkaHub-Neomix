# 探索/落地守卫 + 声明式规则引擎（设计稿）

> 来源：bigeye（[suanrongqieqiezi/bigeye](https://github.com/suanrongqieqiezi/bigeye)）评估的两个候选
> （`docs/peer-apps-benchmark.md` §11.1/§11.2）。本仓已吸收它的记忆那一支（分层注入 + 锚点纪律），
> 本稿处理剩下的两个：**①「探索/落地」二分循环守卫**、**②声明式规则引擎**。
> 用户对 ② 提出的方向是「用**尾部注入**来做」—— 本稿第 3 节直接回答。

---

## 1. 参考实现的真实形态（已读源码，非印象）

### 1.1 `loop_detector.py`（142 行）

- 把每次工具调用按**效果**分三类：`landing`（写文件/完成任务节点/沉淀记忆/**求助**/建任务/改规则）、
  `explore`（读/搜/执行/查记忆/看 DAG）、`neutral`。
- 环形 buffer 记最近 **12** 次；触发条件：窗口内 `explore >= 10` **且** `landing == 0`，
  且距上次提醒 ≥ **300s** 冷却；**落地类调用出现即视为有进展**（窗口内有 landing 就不触发）。
- 提醒文案的结构值得照抄：**先说现象**（"我注意到你刚才连续做了很多次读取/搜索/执行，但还没有任何实质产出"）
  → **三步自查**（①前提假设是否错了 ②是不是该直接问用户 ③若确认是正常长调研就忽略）
  → **要求不要向用户暴露检测机制**。
- 交付方式（`server.py:7546/7549`）：在**工具调用处理处**记录并检查 ⇒ 提醒进入**进行中的循环**（不是等下一轮）。

### 1.2 `rules_engine/`（4 文件 ~550 行 + rules.json）

- 定位：**状态驱动的提醒层**，不是执行层。`state_probe.py` 把可读状态聚合成快照
  （话题标题/消息数/DAG 节点与边/打开的工具书页/任务状态）→ `rules.json` 声明规则
  （`condition` 是**白名单求值的 Python 表达式**，`hint` 支持 `{变量}` 占位，`severity` 用于排序）
  → `run_rules.py` 匹配 → `rule_worker.py` 在**构建上下文那一刻**事件触发。
- 交付位置：**插到 system prompt 最前**（`━━━ 规则提醒 ━━━`），不写库、不推 APP；
  `fired.json` 记 `(topic_id, rule_id)` 防重复刷屏，**规则不再命中就自动移除**。
- 现有 4 条规则都是**过程纪律**：任务未命名 / 多步任务缺 DAG / 非常驻工具书开着 / DAG 里有黑盒节点。

**一句话**：①是"**光看不干**"的检测器，②是"**把状态映射成提醒**"的通用脚手架 —— 两者共用一个交付通道，
且 ① 完全可以表达成 ② 的一条规则。

---

## 2. 本仓的现状与缺口

### 2.1 现有守卫查的是**另一个维度**

`ToolCallLoopGuard` 抓的是**同签名重复**（同一工具同一参数，成功 3 次 / 失败 4 次后优雅停止），
外加轮询豁免。它**查不出**「12 次调用各不相同、但全都在读」——那正是 bigeye 这条要补的形态
（连续 `workspace_read_file` / `conversation_search` / `search_web` 各来几遍，签名全不同）。
所以这条**不是漏检，是缺一整个维度**：重复 ≠ 无进展。

### 2.2 缺少"给模型的提醒"这条通道

本仓已有的护栏（LoopGuard / `WorkspaceTurnBudget` / 思考退化）走的是 `GenerationNotice` +
**优雅停止**，即"停下来"；**没有**"继续，但换个做法"的通道。bigeye 那条提醒刚好落在这个空档里。

### 2.3 但有一个必须利用好的前提（决定了可行性）

本仓的工具循环是 `for (stepIndex in 0 until maxSteps)`，`generateInternal`（内含
`buildInternalMessages`）**在循环体内**（`GenerationHandler.kt:375`）⇒ **每一步都重新拼装提示词**。
所以"中途发现光看不干 → 下一步就提醒"**在本轮内即可生效**，不必等下一轮。这是本设计成立的地基。

---

## 3. 用户的问题：规则引擎用**尾部注入**来做，如何？

**同意，而且比参考实现"插到 system prompt 最前"更好。** 四条理由，按重要性：

1. **前缀缓存**。本仓把 system 正文 + 历史当成缓存前缀，并为此把记忆分层改成
   「L0 逐字节恒定 / 易变的去尾部」。把提醒插到 **system 最前**会让**每次命中都改变前缀**
   ⇒ 后续整段（system + 全部历史）缓存失效。真机读数里缓存命中是 86%（`7.7K cached`），
   一次误插就是几千 token 的重算。bigeye 是 Python 服务端、不背这个约束；本仓背。
2. **注意力位置**。提醒要影响的是"**下一步怎么做**"，尾部离模型的下一个动作最近。
   bigeye 自己在 README 里写"提醒置顶防注意力分散"，但那是它的经验；本仓在记忆分层里
   已经把"每轮变的内容去尾部"论证过一遍，这里应当沿用同一条既定结论。
3. **天然不落库、自动消失**。本仓已有范例：记忆块走 `PromptAssembly` 的
   `UIMessage.user(...).copy(isSynthetic = true)` **合成尾部消息**（`PromptAssembly.kt:336-342`）——
   每轮算、不写历史、下一轮不再命中就自然没了。于是 bigeye 的 `fired.json`
   "不再命中要清理"这套逻辑在本仓**大半不需要**（只剩防刷屏的去重）。
4. **可观测**。拼装预览与真实发送共用 `buildInternalMessages` ⇒ 注入的规则提醒在预览里
   看得见、可归因（bigeye 是黑盒注入，用户不知道模型被告知了什么）。

**唯一要保留的是它的"置顶"直觉的替代品**：提醒块内自己**自带强调**（`━━━ 规则提醒 ━━━` 这类分隔 +
severity 排序 + 一句话说清"这是本轮的纪律提醒，不是用户的话"），而不是靠物理位置。

---

## 4. 统一设计：A 是 B 的第一条规则

```
                        ┌─ 快照（复用本仓既有状态，不新造）
                        │   消息数 / 是否已命名 · 上下文占用与压缩检查点
   ConversationSession ─┤   工具调用窗口的效果分类计数（本次新增）
   （内存态，含守卫）    │   todo 计划是否存在 · 本轮步数与字符预算 · 后台任务
                        └────────────────────────────────┐
                                                         ▼
                                        RuleEngine.evaluate(snapshot)  ← 纯函数
                                             │  命中（severity 排序）
                                             ▼
                              PromptAssembly.buildInternalMessages(ruleReminder = …)
                                             │  作为 isSynthetic 尾部 user 消息
                                             ▼
                                   下一步的 provider 请求（同一轮内）
```

由此两个提案合成一件事：**规则引擎是脚手架，探索/落地守卫是它的第一条（也是最有价值的一条）规则**。
分开做会得到两套"算状态→拼文案→塞提示"的重复结构。

### 4.1 规则用什么写：**Kotlin 声明式表，不用 JSON + 表达式求值**

| | bigeye | 本仓 |
|---|---|---|
| 条件 | Python 表达式字符串，白名单 `eval` | `condition: (RuleSnapshot) -> Boolean`（Kotlin lambda） |
| 文案 | `hint` 字符串 + `{变量}` 占位 | `hint: (RuleSnapshot) -> String` |
| 好处 | 用户可改、无需发版 | **类型安全、IDE 可查引用、纯函数可直测、无 eval 面** |

理由：本仓是 Android 应用，没有插件运行时也没有规则编辑 UI；引入表达式求值只为"可改"，
而"可改"在本仓当前没有消费者（真要给用户改，也是以后加 UI 时的事）。表放一个文件里，
每条规则 `id / severity / condition / hint`，加规则=加一行 + 两条测试。

### 4.2 快照里"本次新增"的只有一样：工具调用的效果分类

其余字段都从既有状态取。分类函数是纯函数：

```kotlin
enum class ToolEffect { LANDING, EXPLORE, NEUTRAL }
internal fun classifyToolEffect(toolName: String, arguments: JsonElement?): ToolEffect
```

**落地类**（调用即产生持久化/外部可见改变）：`workspace_write_file`、`workspace_edit_file`、
`todo_write`（计划落进对话=可验证产出）、`memory_tool`（**建/改/删**时，见下）、`save_skill`、
`install_skill`、`generate_image`、`workspace_present_file`、`ask_user`（求助=把问题交给用户也算进展，
照抄 bigeye 的名单）、`calendar_create`。
**探索类**：`workspace_read_file`、`conversation_search`、`read_conversation`、`recent_chats`、
`search_web`、`scrape_web`、`search_knowledge_base`、`get_tool_map`、`use_skill`、
`workspace_task_status`、`get_time_info`、`get_screen_time`、`calendar_query`、
`workspace_shell`（执行=未落地，照抄 bigeye 对 `bash`/`run_python` 的处理）。
**中性**：其余（`clipboard_tool`、`text_to_speech`、`chart_display`、`dispatch_subagent`、
`mcp_tool_call`、`eval_javascript` …）—— **不认识的一律中性**，宁可漏检不可误报。

两处需要**看参数**才能定（照抄 bigeye 对 `execute_advanced_tool` 的处理）：
- `memory_tool`：建/改/删=落地，查=探索；
- `mcp_tool_call`：里面装的是任意 MCP 工具，语义不可知 ⇒ **保持中性**（诚实边界：不猜）。

### 4.3 首批三条规则（都与本仓已有纪律对应）

| id | 信号 | 提醒什么 | 对应 |
|---|---|---|---|
| `explore_without_landing` | 窗口内 explore ≥ N 且 landing == 0 | 光看不干 → 三步自查（前提假设/该不该问用户/是否正常长调研） | bigeye ① |
| `plan_missing` | 本轮步数或消息数达阈值 且 无 `todo_write` 计划 | 多步任务先落计划再动手 | bigeye `dag_missing`（本仓有 `todo_write`，是现成对应物） |
| `budget_near_limit` | 本轮字符/步数预算 ≥80% | 预算快满 → 收敛输出、优先收尾 | 本仓既有 `WorkspaceTurnBudget`（现状是到顶才优雅停止，**提前给模型一次机会**） |

（可选第四条 `context_near_full`：上下文接近上限且本轮无压缩检查点 → 建议压缩。先不做，等前三条的
真机观感。）

### 4.4 去重与冷却（照抄 bigeye 语义，但换成确定性的口径）

- **per-conversation 的 `fired: MutableSet<ruleId>`**（放 `ConversationSession`，内存态；
  重启后重发一次可接受——比把提醒状态写库划算）；
- **`condition` 不再命中即从 `fired` 移除**（bigeye 语义）⇒ 同一问题不会每步刷屏，
  但问题复现时会再提醒一次；
- **不用 300s 墙钟冷却**（bigeye）：本仓一轮内步数密集，墙钟在测试里既不稳也不可复现。
  改成**步数口径**：同一规则**每轮最多命中 1 次**，且 `explore_without_landing` 在出现
  landing 调用后**立即重新武装**（对应 bigeye 的"落地即重置"）。

---

## 5. 落点与接缝（尽量小）

| # | 文件 | 内容 |
|---|---|---|
| 1 | `data/ai/RuleEngine.kt`（新） | `RuleSnapshot` / `Rule` / `RuleEngine.evaluate` + 三条规则表（纯函数，零依赖） |
| 2 | `data/ai/ToolEffect.kt`（新） | `ToolEffect` + `classifyToolEffect`（纯函数） |
| 3 | `data/ai/ToolCallLoopGuard.kt`（改） | 记录时**顺带**产出 effect 序列（或抽一个 `ToolEffectWindow` 小类，30 行环形 buffer） |
| 4 | `ConversationSession`（改） | 持 `RuleEngine` 的 `fired` 集合与 effect 窗口（内存态，跨 step 存活，与 LoopGuard 同处） |
| 5 | `PromptAssembly.buildInternalMessages`（改） | 加 `ruleReminder: String? = null` 参数，尾部与记忆块并列追加（`isSynthetic`）；**空串绝不产生消息** |
| 6 | `GenerationHandler`/`ChatService`（改） | 每步拼装前 `RuleEngine.evaluate(snapshot)` → 传 `ruleReminder` |
| 7 | `Settings` / `Assistant`（改） | 助手级开关（默认**开**）+ 灵敏度档位（沿用 `LoopGuardSensitivity` 的既有形态，不新造概念） |
| 8 | `values*/strings.xml` × 6 | 设置项文案 |

**为什么接缝是"传一个字符串"而不是让 `PromptAssembly` 去读会话状态**：预览与实发共用同一个函数，
把状态读取塞进去会让预览变成"会改变状态/依赖会话生命周期"的东西；传块进来则两边都只是渲染。

---

## 6. 测试与反证

- `classifyToolEffect`：**每个工具名至少一条**（32 个名字全覆盖，含两个参数相关的用例），
  未知名字必须中性；
- 窗口判定：窗口未满不触发 / explore 够但中间出现过 landing 不触发 / 达标触发 /
  **落地后重新武装**；
- 规则条件：每条 **正反两例**（命中与不命中），边界值显式（步数=阈值的两侧）；
- 去重：命中后 `fired` 记住 ⇒ 同轮不再重复；条件不再命中 ⇒ 移出 ⇒ 复现时能再提醒；
- **"不命中就不注入"**：无命中时 `ruleReminder` 为 null 且尾部消息数与原来**逐字节相同**
  （这条最容易漏：空块会让每轮白付一段 token，且破坏"L0 逐字节恒定"的既有约束）；
- 注入块与记忆块**共存**时不互相破坏（顺序、角色、`isSynthetic`）。

**反证（每处守卫注入式变红）**：把 `landing` 判定改成恒 false ⇒ 误报测试红；
把 `neutral` 当成 explore ⇒ 未知工具测试红；去掉去重 ⇒ 刷屏测试红；
去掉"空块返回 null" ⇒ 逐字节对比红。

---

## 7. 明确不做 / 风险

- **不做 JSON + 表达式求值**（见 §4.1）；**不做规则编辑 UI**（无消费者）；
- **不做大而全的状态聚合层**（bigeye 的 `state_probe` 是因为它跨进程 HTTP 取状态；本仓所有状态
  都在同一进程的对象图里，复制那层只会多一层间接）；
- **不碰前缀**：提醒只走尾部，绝不动 system（§3.1）；
- **诚实边界**：这是**建议**不是**强制**——模型可以忽略；误报会对"正常的长调研"产生噪音，
  缓解手段是文案里的第三步出口（照抄 bigeye）+ 灵敏度档位；`mcp_tool_call` 的效果不可知 ⇒ 中性（不猜）；
- 窗口/阈值沿用 bigeye 的初值（12/10）但**本仓的工具密度不同**，须真机跑一轮再调（本仓惯例：初值 + 真机定稿）。

---

## 8. 待拍板

1. 范围：**先做 §4.3 三条规则**（含探索/落地），还是一条一条来（先只做 `explore_without_landing`）？
2. 开关默认值：默认**开**（与 `ToolCallLoopGuard` 一致），还是先默认关、真机观察几天再开？
3. `budget_near_limit` 的 80% 是本稿拍的初值，可接受否？

---

## 9. 落地记录（2026-10-16）

**已落地**：`explore_without_landing` 一条规则 + 整条交付链路（**默认开**，三档可配置）。
用户拍板：只做这一条、默认开、同时可配置化。

| 设计稿 | 实际落地 | 说明 |
|---|---|---|
| §4.1 规则表 / `RuleSnapshot` | **暂不抽** | 只有一条规则，抽一个只被用一次的 `Rule` 抽象是投机。第二条规则进来时再抽（这句写在 `RuleEngine.kt` 类注释里，避免下一个人以为漏了） |
| §4.4 冷却口径 | 步数口径（每轮每规则一次） | 与设计一致；"落地即重新武装"不需要额外状态：落地滑出窗口后探索自然重新累积 |
| 文案语言 | **英文** | 本仓注入给模型的文本一律英文（记忆块、工具描述都是）；结构照抄 bigeye：现象 → 三步自查 → 不要暴露机制，第 3 步是防误报出口 |
| 文案开头 | `━━━ Automatic progress check (not a user message) ━━━` | 尾部注入失去了"置顶"的位置优势，就靠块内自带强调与边界顶上（设计稿 §3 末尾那条） |
| 配置 | 开关 + 三档，落在**设置 → 通用 → 护栏与预算** | 与 [LoopGuardSensitivity] 同款"只做档位、不做自由数字"；档位 → (窗口, 阈值)：严格 10/8、标准 12/10（bigeye 原值）、宽松 16/14 |
| 交付 | `PromptAssembly` 尾部合成 USER 消息 | 与记忆块同位置、同款；`isSynthetic`；**null/空白不产生任何消息**（有逐字节断言 + 反证） |
| 求值时机 | **`if (pendingTools.isEmpty())` 之内** | 设计稿只说"每步拼装前"；实现时收紧了一格 —— `reminder()` 有副作用（标记已提醒），**算了不发等于白丢一次提醒** |

**测试**：`ToolEffectTest`（本仓 32 个工具名全覆盖 + 参数相关的 2 类 + 环形窗口）、
`RuleEngineTest`（判据 / 去重 / 重新武装 / 三档 / 禁用 / 中性工具既不进也不出 + 一条**JVM 端到端形状**断言）、
`PromptAssemblyTest`（尾部位置 + 空块逐字节一致）、
`RuleReminderWiringTest`（7 条源码扫描守卫：记录点与 LoopGuard 同处、求值在循环内且在发送路径上、
求值结果确实传下去、reset 与护栏家族**处数一致**、默认开、设置页可配）。

**反证 7 处，全部精确变红**：忽略落地（2 红）／未知工具当探索（1 红）／去掉每轮去重（1 红）／
不再命中也不解除（1 红）／空块也注入（1 红）／少一处 reset（1 红）／默认改关（1 红）。

### 9.1 真机「假上游」复现（2026-10-16，已跑）

方法与既有「假上游复现法」一致（`adb reverse` + 本地 mock SSE），这一次让假上游**发工具调用**而不是只回文本。
用**默认助手**（不新建助手），只把它的聊天模型临时切到 mock、跑完还原。

**干净版结果**（工具已启用）：请求 #1–#12 各回一个 `get_time_info` 调用（参数带区分用的 `{"n":K}`），
12 次**全部成功**（返回真实设备时间）；**请求 #13 —— 同一轮内的下一步 —— 带上了提醒**，
位置是**最后一条**（`role=user`，主进程标为 `isSynthetic`）；全日志中**只出现一次**（每轮去重生效）。
消息数 14 → 16 正好是「工具结果 + 注入的提醒」两条。

**三点值得记下的东西**：

1. **为什么给无参工具塞一个 `{"n":K}`**：`toolCallSignature` 是「工具名 + 规范化参数」，
   若 12 次调用签名相同，会先撞上 `ToolCallLoopGuard`（同签名 3 次即优雅停止本轮），
   窗口根本填不满。**要复现"打转"，必须每次调用都不同** —— 这也正是这条守卫瞄准的形态。
2. **第一轮（工具未启用）也照样触发**，工具结果全是 `Tool get_time_info not found`：
   因为效果记录发生在**发起时**（与 LoopGuard 同处），失败也算一次"没产出"的尝试。
   这与 LoopGuard 的既有语义一致（它判的是"模型反复要什么"，失败由 `markForgotten` 单独处理）。
3. **负对照**：同一日志里 #14–#17 是 App 的标题/建议生成（各自是全新的 2 条消息请求），
   都不含提醒 —— 说明这条注入只跟着**本轮的引擎状态**走，不是全局污染。

（第一轮日志留档为 `_mock_rule_log_run1.jsonl`，干净版为 `_mock_rule_log.jsonl`。）
