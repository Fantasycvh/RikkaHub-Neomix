# PurrCat（PurrPod/purrcat）借鉴评估

> **本仓说明**：本文写于私有开发仓的评估流程中，恢复时已按本仓实际改写；`<work>` 等尖括号串是路径占位符。
> 本文**只记录结论与建议顺序，未改动任何代码**（状态：**评估完成、未落地**，2026-10-30）。提交/发布按 `AGENTS.md` 授权规则执行。

> **系列位置**：同类应用借鉴评估之一；系列索引与跨应用汇总见 [`peer-apps-benchmark.md`](peer-apps-benchmark.md)（其余应用的评估原文在 [`references/other projects/`](references/other%20projects/)）。
>
> **分析对象**：[PurrPod/purrcat](https://github.com/PurrPod/purrcat)（"PurrCat Agent"，Python + Electron + Docker 的 local-first Agent 宿主）。
> **本地克隆**：`F:\build\projects\purrcat`，commit `efc9fb6`（2026-10-26），tag `v1.1.0`。
> **评估方式**：全仓 319 文件通读，分三路（运行时内核 / 外围系统 / 本仓现状基线），**结论逐条源码核验**；凡来自其 README 的说法一律标注为"自述"。
> **许可证**：MIT（代码摘取在法律上无障碍）。但本评估的**最终结论是不摘代码**，只取设计。
> **评估原则**：结合本仓定位（Android/Compose 个人客户端、隐私优先、已有 `workspace` + `terminal` + 子代理 + 记忆 + MCP + 插件），**先去重再评估**——凡本仓已有等价实现的机制不列为建议（清单见附录 D）。

---

## 一、结论摘要

### 1.1 三句话

1. **代码复用价值 ≈ 0**：Python + FastAPI + Electron + Docker 的技术栈与本仓（Kotlin/Compose/单进程）没有交集，运行时假设（常驻进程、容器沙盒、任意子进程）在 Android 上一条都不成立。
2. **方法论价值很高**：它的母题不是"加更多工具"，而是**「信道 + 编排 + 能力生产线」**——恰好是本仓在工具/记忆/MCP/子代理全部饱和之后，剩下没填的那几个格子。
3. **但它不是"更成熟的同类"**：单人主导（956 提交中约 869 出自同一人，另两套 git 身份 + 自建 CI 机器人）、约 7.5 个月、v1.1.0，想法密度高而工程完成度中等，且存在**文档先于实现**的硬伤（见 §七）。定位是**调研样本**，不是上游。

### 1.2 决策表

| PurrCat 机制 | 结论 | 一句话理由 |
|---|---|---|
| 回合策略即数据（PARADIGM.yaml，阶段 × 钩子） | 🟡 **条件吸收** | 注入侧本仓已有世界书/注入位；**唯一缺的是"收尾闸门"**，只吸收这一块，不吸收 YAML DSL |
| 能力生产线（Evolve：沙盒起草 → 双跑校验 → 人工合并 → git 回滚） | ✅ **吸收（设计层）** | 本仓有技能/插件，但**没有"生产 → 校验 → 合并 → 回滚"的管线**；正补 roadmap 的"外部能力生态"缺口 |
| 端口信封 + 大内容自动落盘 + checkpoint 续跑 | 🟡 **条件吸收** | 本仓已有尾部截断/落盘/窗口读取；信封语义**只在本仓启动 DAG 编排时才值得抄** |
| 两级记忆预算 + 语义遗忘（置信度图谱） | 🚫 **不吸收** | 本仓记忆注入预算、打分、纪律、渐进披露均更成熟；向量检索已被显式否决 |
| 子代理：上下文快照 + **交付物契约** | ✅ **小件吸收** | 本仓完成判定是"文本非空"，PurrCat 是"文件存在且非空" |
| Heartbeat + `GOAL.md`（idle 门控的主动注入） | 🟡 **两点语义并入** | 本仓 `design-scheduled-ai-tasks.md` 已更完整；只补"idle 门控"与"目标文件"两个语义 |
| Graph/DAG 画布 + 16 种节点 | 🚫 **不吸收** | 手机端 ROI 低；同类 `rikkahub-agent` 的"触发器→条件→动作"更贴合移动场景 |
| UI 插件宿主（槽位 + iframe） | 🚫 **不吸收**（只记 `configSchema`） | 其 iframe 同源且无 `sandbox` 属性，隔离名不副实（§六.2） |
| 工具信封 / 中断伪造 tool result / 命名空间工具 / schema 压缩 / MCP 折叠 | 🚫 **已覆盖** | 见附录 D |

---

## 二、事实基线

### 2.1 规模与活跃度

| 指标 | 值 | 说明 |
|---|---|---|
| 提交数 / 时间跨度 | 956 / 2026-03 ～ 2026-10 | 约 7.5 个月 |
| 作者分布 | `Sukice` 831、`purrcat-bot` 73、`suk!ce` 38、其余 14 | **一人主导 + 自建 CI 机器人** |
| 版本 | `v1.0.0-beta.1` … `beta.4` → `v1.1.0` | 8 月内 5 个 tag，长期只在源码分发 |
| 代码量 | `src/` 36,507 行 Python；`ui/`+`tui/`+`electron/` 10,873 行 | 另有 26KB CHANGELOG |
| 声量 | 86★ / 13 fork（2026-10-30） | 尚未形成社区规模 |

活跃度按提交密度看相当高（9 月 220 提交），但**单点依赖极重**——这决定了它对"可依赖的上游"不成立，只对"想法来源"成立。

### 2.2 形态与技术栈

```
Electron 壳（contextIsolation，preload 暴露 window.purrcat）
  └─ uvicorn/FastAPI 后端（127.0.0.1:8000）
       ├─ 主 Agent（src/agent/agent.py，1012 行，线程 + asyncio 混用）
       ├─ Harness 图引擎（src/harness/process.py，1026 行）
       ├─ 工具层（11 个命名空间工具，src/tool/）
       ├─ 记忆（SQLite FTS5 + ChromaDB + NetworkX，src/memory/）
       ├─ 进化工厂（src/evolve/，6 类产物）
       ├─ 极简 Web UI 插件宿主（ui/web-minimal/assets/host.js，3288 行）
       └─ Docker 沙盒（agent_computer 容器，Bash 全程 docker exec）
```

关键点：**它的"沙盒"是容器，"UI"是插件宿主，"能力"是沙盒里生产出来的配置与子进程**。这三件事与本仓的假设（PRoot、Compose、编译期插件 + QuickJS）逐条不同。

### 2.3 成熟度判断（可验证证据）

| 维度 | 观察 |
|---|---|
| 设计抽象 | **强**。信封协议、阶段钩子、校验器单一事实来源都是可复用的抽象 |
| 代码组织 | **弱**。`Agent` 1012 行、`process.py` 1026 行、`hook_handler.py` 591 行；`except Exception` 大范围吞异常 |
| 并发模型 | **弱**。线程里 `asyncio.run`、手搓 `new_event_loop` + 守护线程、`sleep` 轮询、跨线程 `call_soon_threadsafe` 取消协程 |
| 文档 | **强但不准**。"文档先行"（`docs/api/*` 与路由由 CI 校验一致），但**能力清单/字段说明与实际实现有偏差**（§七） |
| 契约纪律 | **中**。信封类型硬校验、校验器双跑——但 `PARADIGM` 的 `trigger`/`path` 字段无消费方 |

结论：**读它的设计，别读它的实现**。

---

## 三、机制拆解（六项）

### 3.1 回合策略即数据（PARADIGM）

**它解决什么**：Agent 的"纪律"（先规划、必须归档记忆、没交付不许结束）历来硬编码在循环里，改一次要发版、且无法按会话切换。

**机制**：一份 YAML 声明"每一轮在哪个**阶段**注入什么、执行什么**检查**、不通过怎么打回"。

- 6 个阶段：`on_build_system_prompt` / `on_input_received` / `on_loop_start` / `on_loop_epoch`（每次模型调用前）/ `on_tool_calling` / `on_loop_end`
- 8 种动作：`injection` / `memo_injection` / `skill_info` / `search` / `tool_use_check` / `keyword_check` / `file_operation` / `command_run`
- 修饰符：`delay: N`（仅第 N 个 epoch）、`interval: N`、`expect: "fail"`（反向判定）

**最关键的语义约定**：检查类动作的唯一产出是**往历史里追加一条 `role=user` 的提示**；`on_loop_end` 不通过就把整轮打回重试（`loop_end_max_retry` 默认 3，超限强制放行）；**通过时 `successed_prompt` 不生效**（静默放行）。于是"收尾闸门"不需要任何新机制——它就是一次"追加 user 消息 + continue"。

**证据**：`src/agent/system_rules/PARADIGM.yaml`（内置默认范式仅 46 行）、`src/agent/hook_handler.py:216`（重试上限）、`docs/plugins/agentloop.md`（§4.5 明确写"不写提示词的检查等于白写"）。

**对本仓的意义**：本仓的注入侧已经很完整（世界书 6 个注入位、`PromptInjectionTransformer`、`RuleEngine` 三条提醒、15 个 transformer），**真正缺的是"检查/闸门"这一半**。但要警惕哲学冲突：本仓 `RuleEngine` 刻意"只提醒不拦截"（`app/src/main/java/app/androidrik/rikkahub/data/ai/RuleEngine.kt` 的三条规则全部走注入），而 PurrCat 是**拦截 + 打回**。→ 见 §五 A1 的分场景方案。

### 3.2 端口信封 + 可续跑 DAG

**它解决什么**：多步骤编排里"节点之间传什么、大内容怎么不进上下文、跑到一半崩了怎么续"。

**机制**：
1. 节点间只传自描述信封 `{type, data, mime, meta}`，类型 8 种（`string`/`jsonstring`/`number`/`boolean`/`list`/`file`/`MessageList`/`any`），引擎按端口声明**硬校验**，不兼容直接失败。
2. **声明成 `file` 的端口，内容 > 4096 字节自动转成 `purrcat://` URI 落盘**，节点代码零感知（`src/harness/process.py:423`）。
3. 端口状态只存红绿灯（`PENDING`/`HAS_DATA`/`VOID`），载荷落 `checkpoint/nodes/<id>/outputs.json`；`load_checkpoint` 可从目录恢复整个 Task。
4. 汇聚语义：上游"全部 resolved 且**至少一条有数据**"才可运行（分支汇聚）；"全部 VOID"就级联 SKIP 并把下游输出置 VOID——**不需要 if/else 节点就实现了路由短路**。

**证据**：`src/harness/envelope.py`（181 行，含类型兼容矩阵与 `purrcat://` 路径穿越防护）。

**对本仓的意义**：本仓没有 DAG，所以**整块暂不需要**。但其中两条是通用件：(a) "超阈值自动落盘 + 返回 URI"（本仓已有尾部截断与 `.tasks/` 落盘，思路相同）；(b) **checkpoint 目录即任务状态**——对本仓"后台任务表是内存态、进程死即全丢"是个正面参照（见 `workspace/src/main/java/app/androidrik/workspace/WorkspaceBackgroundTasks.kt`）。

### 3.3 能力生产线（Evolve / KernelUpgrade）

**它解决什么**：让 Agent 自己产出新能力（技能/MCP/传感器/流程/UI 面板），但要**可校验、可评估、可回滚、人类把关**。

**机制**（这是全项目最值得学的工程手法）：

```
KernelUpgrade(action × type)           # 唯一入口：create/upgrade/install/merge/test × 6 类产物
  → UUID 沙盒目录（纯拷贝，不挂真实库）
  → 工厂写骨架 + GUIDE.md（给人看也是给模型看的施工规范）+ evals/validate.py
  → 模型在沙盒里改、跑 uv run evals/validate.py 自测
  → KernelUpgrade(merge) → 宿主侧跑【同一份】校验器 → 弹人工审批
  → 人类点一下 → shutil.copy 落正式区 + 自动 git init/add/commit（可 checkout HEAD~1 回滚）
```

三个真正的巧劲：

1. **校验器单一事实来源**：宿主校验器的源码被逐字拷贝成沙盒里的自测脚本（`src/evolve/agentloop/factory.py:52` `_sandbox_validator_source()`）——沙盒里过的和宿主侧审的是同一份逻辑，不会漂移。
2. **权限用"看不见"实现**：Agent 的视野里过滤掉 `human_intervention` 节点，`inject_instruction` 里再二次拒绝 agent 来源的注入。
3. **产物类别各自一个 git 仓库**（`paradigms/.git`、`graphs/.git`、`skills/.git`），不与主仓混；回滚 = `git checkout HEAD~1 -- <name>` + 一个 rollback commit。

**对本仓的意义**：**这是最有价值的一块**。本仓已有 `SkillManager`（`save_skill`/`install_skill`）、`SkillFrontmatterParser`、实验性插件体系（`plugin-api` + `plugin-manager` + JS 规则插件），但：

- 生产能力的是模型自己，**`save_skill` 直接落正式区**，没有隔离起草；
- 校验只有 frontmatter 解析，**没有"同一份校验器双跑"**；
- 没有版本与回滚（技能改坏了只能手改），没有评估。

→ 见 §五 A2。

### 3.4 记忆：两级预算 + 语义遗忘

**机制**：
- 三层库：事件（SQLite+FTS5）/ 向量（ChromaDB）/ 知识图谱（NetworkX），检索走 **RRF 倒数排名融合**（`k=60`，`src/memory/purrmemo/core/search_tool.py:16`）。
- **两级预算**：全量库无限增长，注入 system 的 `MEMORY.md` 硬约束 **≤15 条、一进一出、每条 ≤80 字**，由 LLM 裁决淘汰（`src/tool/memo/memo_operations.py:71`）。
- **显式遗忘**：矛盾 → `weaken_relation`（置信度 −0.2，地板 0.3）再 `add_relation`；每日任务按 `0.05 × 超期天数` 衰减（7 天不强化开始掉）；90 天事件硬删。
- 写入走**文件队列 + 后台 Worker**（5s 轮询），嵌入模型未就绪就留在 `pending/` 重试。

**对本仓的意义**：🚫 **不吸收**。本仓记忆体系在"该记什么/记多少/怎么给模型"三层都比它成熟：

- 注入预算：`settings.memoryInjectBudgetTokens`（默认 8K）+ `importance × 时效衰减` 打分 + 分档量化保前缀缓存（`docs/design/completed/design-memory-scoring.md`，已实现）；
- 写入纪律：`[stated]` 测试、永不记录清单、`MemoryContentGuard` 代码级兜底（`docs/design/completed/design-memory-discipline.md`，V1/V2 已落地）；
- 检索：**明确不用 embedding**，走词元重叠 + 只抬不压（`app/src/main/java/app/androidrik/rikkahub/data/ai/MemoryRelevance.kt`）；
- 生命周期：`MemoryKind` 三型 + 整理器 + 回收站。

PurrCat 的"全量库 + 少量注入"与本仓"记录 + 清单 + 按需读"是**两种解法，后者更适合手机**。**唯一值得记一句的**是它的"矛盾检测"动作分类（全新/一致/矛盾 → add/reinforce/weaken），可作为本仓 `MemoryConsolidator` 的候选规则（当前是去重/合并/消费标记）。

### 3.5 子代理：上下文快照 + 交付物契约

**机制**：
- 子代理**深拷贝主历史快照**做起点（因此不需要用自然语言转述需求），内部 ID 用 `{6位批次}_{b1}` 命名空间隔离，Bash 的 `session_id` 拼成 `{main}_{branch}` 防并发批次串台。
- **完成判定不是模型自述，而是"交付物文件存在且非空"**：缺文件就继续跑，并把"未检测到 X"追加给它自己看；出稿后仍调工具则计数拦截（`src/agent/sub_runner.py:477-484`）。
- 拓扑依赖用 `asyncio.Event` 做（`depends_on` 满足才启动）。

**对本仓的意义**：本仓子代理已相当完整（独立上下文、能力信封、禁嵌套、并发 2、升级到人、`agent_run` 账本），**只有"完成判定"这一处弱**：`SubagentRunner.kt:753` 的 `RunOutcome.Completed(summary)` 只要求最终文本非空。→ 见 §五 A3（小件）。

### 3.6 Heartbeat：idle 门控的主动注入

**机制**：`heartbeat.json` 存 `{interval ≥60s, active}`，管理线程按 **mtime 热加载**；**只有 Agent 回到 idle 才开始倒计时**，到点把 `GOAL.md` 内容注入会话（为空则注入兜底文案 `"当前没有待办事项"`）。181 行，`src/agent/heartbeat.py`。

**对本仓的意义**：本仓已有更完整的 `docs/design/completed/design-scheduled-ai-tasks.md`（单 Sweeper + Room 权威状态 + 幂等认领 + 错过策略，**更强**），所以**不重复设计**。但 PurrCat 有两点是设计稿里没有的：

1. **idle 门控**：只在 Agent 空闲时才开始计时/注入，天然避免"定时任务撞上进行中的生成"（本仓设计稿用 `sendMessage` 的 FIFO 排队解决，代价是消息排队而非择时）。
2. **`GOAL.md` 语义**：注入的不是一次性 prompt，而是一份**会话级长期目标文件**，使 Agent 的每次自唤醒都围绕同一目标（本仓设计稿是 per-task prompt）。

→ 见 §五 A4：作为设计稿的**两个可选补充语义**，不是替代方案。

---

## 四、与本仓的能力对照（去重）

| PurrCat 机制 | 本仓现状（证据） | 结论 |
|---|---|---|
| 阶段钩子注入 | 世界书 6 注入位 + `PromptInjectionTransformer` + `RuleEngine` 三条提醒 | 已有，**缺"检查/闸门"** |
| 收尾闸门 + 打回重试 | 无（`RuleEngine` 只注入提醒，不拦截） | **增量** |
| 端口信封 / 落盘 / checkpoint | `.tasks/` 落盘 + 8KB 尾部 + `ReadFileWindow` + `workspace_turn_budget` | 已有等价物 |
| DAG / 工作流编排 | 无（仅单层 `dispatch_subagent`） | 缺口，但**不建议现在抄** |
| 能力生产线（沙盒 + 双跑校验 + git） | `save_skill`/`install_skill` + 插件体系，**无管线** | **增量** |
| 记忆预算 / 纪律 / 渐进披露 | 已有且更成熟（`design-memory-*` 三篇） | 已有 |
| 语义遗忘 / 置信度图谱 | 无（明确否决 embedding；整理器只做去重合并） | 不吸收 |
| 子代理上下文/信封/账本 | 已有（`SubagentRunner` + `agent_run`） | 已有 |
| 子代理交付物契约 | `RunOutcome.Completed` 只校验文本非空 | **增量（小）** |
| 定时/周期任务 | 无实现（`docs/design/completed/design-scheduled-ai-tasks.md` 设计稿；`androidx.work` 已声明但全仓 0 个 Worker） | 已有更优设计，**只补两点语义** |
| 中断 → 合成合法 tool result | `ToolResultBalancingTransformer`（论证更细，含"不暗示工具失败"） | 已有 |
| 命名空间工具 + action 枚举 | `memory_tool` 已多 action | 已有 |
| 工具 schema 压缩 / MCP 按需折叠 | `ToolSchemaCompactor` + `get_tool_map`/`mcp_tool_call` | 已有 |
| 外部事件信道（Sensor） | 无通知监听/外部注入通道 | 方向可参考，另立设计 |
| UI 插件宿主 | `web-ui` + 插件体系（另有 Compose 原生插件） | 只记 `configSchema` |

---

## 五、值得吸收（按价值排序）

### A1. 收尾闸门（loop-end gate）—— 🟡 限定场景

- **需求**：让"没真的交付就不许结束"从提示词自觉变成可判定的闸门。本仓当前靠 `RuleEngine` 的 `budget_near_limit` 提醒（注入式），模型可以不理。
- **本仓现状**：`RuleEngine.remember()` 三条规则全部"只提醒不拦截"；`ToolCallLoopGuard` 只做**优雅停止**（结果照常回填），不做"打回重试"。
- **增量点**：`on_loop_end` 式的判定 + 未通过则追加一条 user 提示继续本轮，上限 N 次后强制放行。
- **落点**：`app/src/main/java/app/androidrik/rikkahub/data/ai/GenerationHandler.kt:544` 的循环出口（`for (stepIndex in 0 until maxSteps)` 的空工具分支）；判定输入复用 `RuleSnapshot` 的显式快照范式。
- **⚠️ 哲学冲突与约束**：本仓刻意"只提醒不拦截"，聊天场景下强制闸门会让用户"发完消息却结束不了"。→ **只允许在"有明确任务"的场景启用**：子代理运行（`SubagentRunner`）、定时任务会话、workspace 工作轮；普通聊天保持注入式。
- **工程量**：小（约 1 天 + 单测）。窗口/阈值沿用三档 `Sensitivity` 惯例，不开放自由数字。
- **验收**：闸门未通过时本轮继续且注入提示措辞可见；达上限强制放行不卡死；聊天模式行为逐位不变（回归测试）。

### A2. 能力生产线（能力 → 校验 → 合并 → 回滚）—— ✅ 设计层吸收

- **需求**：让"AI 自己产出的技能/插件"有隔离起草、可复核、可回滚的路径。这是 `docs/roadmap.md` 里"外部插件包生态"缺口的正解。
- **本仓现状**：`save_skill` 直接落 `skills/`（有 `saveSkillFilesAtomically`，无隔离/无校验/无版本）；插件为编译期 + QuickJS 规则，导入靠 `JsPluginInstaller`（安装前逐项确认能力，但无"生产"环节）。
- **增量点**（按 PurrCat 三件套裁剪）：
  1. **沙盒起草**：把产物写到 `workspace` 的临时目录（本仓已有 PRoot 工作区，天然满足），不直接落正式目录；
  2. **同一份校验器双跑**：校验逻辑写成纯函数，**沙盒侧的 `validate` 脚本由同一份 Kotlin 代码生成/或导出 JSON Schema**，宿主合并时再跑一遍（Kotlin 侧不能像 Python 那样"逐字拷贝源码"，可退化为"同一份校验器 + 沙盒侧只放它的输入样例"）；
  3. **合并 + 版本**：落正式区前弹一次审批（复用既有工具审批通道），落库时记版本行；回滚用 Room 版本表或直接 `git`（本仓有 `terminal`，可跑 git，成本低于引 JGit）。
- **落点**：`app/src/main/java/app/androidrik/rikkahub/data/files/SkillManager.kt` + `SkillInstallTools.kt` + `plugin-manager`；新增 `ArtifactValidator` / `ArtifactVersionStore`。
- **风险**：这是本仓插件路线的边界问题（`docs/roadmap.md` 2026-08-14 曾写"QuickJS 插件系统确定不做"，但代码后来实现了自有插件体系）——**动工前先以代码为准重定路线**，别按已过时的 roadmap 结论走。
- **工程量**：中（3–5 天：校验器 + 版本表 + 合并审批 UI + 单测）。
- **验收**：坏产物在合并前被拦且给出可操作原因；合并后可一键回滚到上一版；旧数据无迁移风险（版本表新增，不动既有 schema）。

### A3. 子代理交付物契约 —— ✅ 小件

- **需求**：后台跑飞的子代理最难判"真完成"。文件系统是天然验收器，本仓工作区正好有。
- **本仓现状**：`SubagentRunner.kt:753` `RunOutcome.Completed(summary)` 只要求最终文本非空（`:798` 注释明确"空响应不得记成 COMPLETED"）。
- **增量点**：`SubagentPreset` 增加可空 `deliverables: List<String>`（存在 `Settings.subagentPresets` 的 JSON 里，**无需 Room 迁移**）；完成时若声明了 `deliverables`，逐个校验"存在且非空"，缺失则追加"任务未结束：未检测到 X"给它自己看（不是报错给父代理）。
- **落点**：`app/src/main/java/app/androidrik/rikkahub/data/model/SubagentPreset.kt` + `service/SubagentRunner.kt`。
- **风险**：低。旧 JSON 缺字段 → 可空默认 `null` 即"不校验"，行为不变。
- **工程量**：小（半天–1 天 + 单测）。
- **验收**：声明了 deliverables 但未产出 → 不记 COMPLETED；未声明 → 行为逐位不变。

### A4. 定时任务的两个补充语义 —— 🟡 并入既有设计稿

- **不新增设计**，只建议把两条并入 `docs/design/completed/design-scheduled-ai-tasks.md`：
  1. **idle 门控**：per-task 可选"仅当该会话无进行中生成时才注入"（当前设计是 FIFO 排队）。价值：避免周期性任务把用户的即时对话挤到后面。
  2. **会话级目标文件**：per-conversation 的 `goal.md`（或助手级），定时任务注入"目标 + 本次关注点"，使多次自唤醒围绕同一长期目标而非孤立 prompt。价值：这是"主动 agent"与"定时 prompt"的分界。
- **工程量**：小（都是注入内容/查询条件的增量，不改调度核心）。
- **验收**：不影响既有 §五 认领幂等与 §八 诚实边界。

### B1. DAG 编排（若启动）—— 🟡 届时再抄

- **现状**：本仓无工作流引擎。同类 `rikkahub-agent` 已给出更贴移动端的形态（"20 触发器 → 13 条件 → 动作"），**优先级高于 PurrCat 的节点画布**。
- **若真要做**：只抄 PurrCat 的 **信封类型校验 + 大内容自动转 URI + checkpoint 目录即状态 + 汇聚/短路语义**，不抄 16 种节点、不抄画布、不抄 `purrcat://`。
- **前置**（比抄什么更重要）：先解决"后台任务状态是内存态、进程死即全丢"——否则 DAG 一崩全丢。参照本仓 `WorkspaceBackgroundTasks` 与设计稿的 Room 权威状态范式。

### B2. 外部事件信道（Sensor 思想）—— 🟡 方向记录

- **它做的事**：任意外部 IM（飞书/微信/RSS/闹钟）经 ACP 协议接入 → 推送成会话输入，回复可回发；网关会把 Agent 回复里提到的本地文件自动 base64 推给外部渠道（"提到文件"＝"交付文件"）。
- **对本仓的映射**：通知监听 / 分享入口 / 快捷方式 / Telegram bot → 注入既有会话。本仓已有 Ktor + SSE，可复用它"词汇层唯一实现、传输可插拔"的结构（`src/server/acp/{dispatch,bus}.py`）。
- **不建议**移植其实现（`uv run` 任意 Python 子进程在 Android 不可行）。→ 另立设计，不列为本期建议。

---

## 六、不建议吸收

### 6.1 代码与运行时

- **完整 DAG 画布 + 16 种节点**（手机端 ROI 低，选择器/画布在触屏上是负资产）。
- **Python 式"自进化写代码插件"**（Android 无任意 Python 沙盒；本仓路线是编译期插件 + QuickJS）。
- **Electron/极简 UI 插件宿主**（形态不同，且见 6.2）。
- **线程 + asyncio 混用、`sleep` 轮询、每轮全量 `save_state()` 写盘**（桌面可接受，手机闪存与电量吃不住）。

### 6.2 三条"安全/隔离"认知纠正（防误读其宣传）

| 它的说法 | 源码事实 | 结论 |
|---|---|---|
| "Agent 在专属 Docker 容器中执行，安全隔离" | `src/tool/bash/docker_env.py:262-263`：`cap_add: ["SYS_ADMIN"]` + `seccomp=unconfined` | 这是**隔离的干活环境，不是安全边界**；安全性依赖"用户别把危险路径挂进来" |
| "UI 插件可扩展、可热插拔"（隐含隔离） | `ui/web-minimal/assets/host.js:2983-2987`：`iframe` **无 `sandbox` 属性**，`src` 与宿主同源 | 插件**能拿到 `window.parent.document`**；起作用的是 `postMessage` 能力白名单，不是沙箱 |
| "RSI 自进化" | 不生成新的 Python 节点类、不注入新工具（`TOOL_FUNC_MAP` 与节点白名单都是静态的） | 它的"自进化"**只是产出配置/子进程插件/提示词资产**，不碰自身代码 |

### 6.3 工程债（读代码时用来识别，不要学）

- 单类 1000+ 行、`except Exception` 吞异常（不崩但难排障）；
- `sleep + 轮询` 取状态（0.5s sensor、10s 预搜、`/status` 轮 live reasoning）；
- 每轮 `save_state()` 全量 JSON + `os.replace`；
- 图谱 `pickle` 全量 dump（`reinforce_relation` 内部就触发一次），规模一大会抖。

---

## 七、文档与实现偏差清单（核验记录）

> 这一节是**反面教材**，也是"为什么本仓评估必须源码核验"的实证。

| 文档说法 | 源码事实 | 影响 |
|---|---|---|
| `PARADIGM.yaml` 的 `trigger: [{cron: ...}]` 可定时注入 | 全仓无消费方（`src/` 内无读取该字段的代码）；实际定时能力由 `heartbeat.py` 提供 | 按其文档写"闹钟范式"不生效 |
| `PARADIGM.yaml` 的 `path: agent_vm` | 无消费方，仅占位 | 无害但误导 |
| `docs/plugins/agentloop.md` 示例 `parameter_check: "summary"`（字符串） | `src/agent/hook_handler.py:469-486` 只支持 **list[dict]**；传字符串会逐字符迭代后 `.items()` 抛 `AttributeError` | 照文档抄必崩 |
| README "RSI 自进化 / Agent 自主构建插件" | 只能产出 6 类配置/子进程产物，不能生成节点类或核心工具 | 高估其自进化程度 |
| README "缓存命中率稳定 99% 以上"（自述） | 未验证；机制上靠"key 绑定会话 + 紧凑 schema + 工具/技能按需检索" | 当作目标而非事实 |

---

## 八、建议顺序

依赖关系（→ 表示"先做左边"）：

```
A3 子代理交付物契约（小，独立）
A4 定时任务两点语义（小，并入现存设计稿）
A1 收尾闸门（小，需先定"哪些场景允许拦截"）
A2 能力生产线（中，需先以代码为准重定插件路线）
B1 DAG 编排（大，前置：后台任务状态持久化）
B2 外部事件信道（另立设计）
```

| 优先级 | 动作 | 工程量 | 前置 |
|---|---|---|---|
| P0 | A1 收尾闸门（限定子代理/定时任务场景）+ A3 交付物契约 | 小 | 无 |
| P1 | A2 能力生产线（校验器 + 版本表 + 合并审批） | 中 | 先核验插件路线现状 |
| P2 | A4 并入定时任务设计稿；B1/B2 另立设计 | 小～大 | 设计稿先行 |

**明确不做**（写进本评估作为决策记录）：不摘 PurrCat 任何代码；不做 DAG 画布；不引入 YAML 作为本仓配置格式（一律 `kotlinx.serialization` + 表单，本仓既有惯例）；不引入 embedding 记忆。

---

## 附录 A：关键文件地图（供复核）

| 主题 | 路径（PurrCat 仓内） |
|---|---|
| 回合策略 / 钩子 | `src/agent/system_rules/PARADIGM.yaml`、`src/agent/hook_handler.py`、`docs/plugins/agentloop.md` |
| 图引擎 / 信封 | `src/harness/process.py`、`src/harness/envelope.py`、`src/harness/node/extensions/*` |
| 主循环 / 子代理 / 心跳 | `src/agent/agent.py`、`src/agent/sub_runner.py`、`src/agent/heartbeat.py` |
| 能力生产线 | `src/evolve/{agentloop,graph,mcp,sensor,skill,ui_plugin}/`、`src/tool/kernelupgrade/` |
| 记忆 | `src/memory/config.py`、`src/memory/purrmemo/core/{search_tool,memory_worker}*`、`src/tool/memo/` |
| 工具层 | `src/tool/utils/route.py`、`src/tool/filesystem/{history,text_ops,checker}.py`、`src/tool/bash/docker_env.py` |
| 服务 / 插件宿主 | `src/server/acp/*`、`src/sensor/bridge.py`、`ui/web-minimal/assets/host.js` |

## 附录 B：关键数字

| 项 | 值 |
|---|---|
| 大内容落盘阈值 | 4096 字节（声明为 `file` 的端口） |
| 收尾闸门重试上限 | `loop_end_max_retry` 默认 3 |
| 记忆注入上限 | `MEMORY.md` ≤ 15 条 / 每条 ≤ 80 字 |
| 图谱参数 | 初值 0.5、reinforce +0.1、weaken −0.2、地板 0.3、衰减 0.05/天、7 天阈值、事件 90 天硬删 |
| RRF | `k=60`，候选各 200，取 top 5 |
| 心跳 | `interval ≥ 60s`，默认 1800s，idle 门控 |
| 工具 schema | 主 Agent 11 个命名空间工具；子代理/图内 loop 5–6 个 |

## 附录 C：复现命令

```bash
git clone https://github.com/PurrPod/purrcat.git F:/build/projects/purrcat
cd F:/build/projects/purrcat && git log -1 --format='%h %ad %s' --date=short   # 应为 efc9fb6
grep -n "FILE_STRIP_THRESHOLD" src/harness/process.py                          # 423
grep -n "_sandbox_validator_source" src/evolve/agentloop/factory.py            # 52
grep -n "parameter_check" -A 18 src/agent/hook_handler.py                      # 469 起（只支持 list[dict]）
grep -rn "get(\"trigger\")" src/agent/                                        # 无输出＝该字段无消费方
```

## 附录 D：已被本仓覆盖、不要再作为"建议新增"的机制（去重清单）

function-calling 循环（256 步）· 逐工具审批与挂起续跑 · `ToolCallLoopGuard`/`DenialGuard`/`WorkspaceTurnBudget` · 8 个 workspace 工具 · PRoot 沙箱 + fd 级路径锚定 · 后台任务三件套（4 并发 / 30min / `.tasks/` 落盘 / 8KB 尾部）· 子代理（独立上下文 / 能力信封 / 禁嵌套 / 并发 2 / 升级到人 / `agent_run` 账本）· 记忆三类 + L0–L3 注入 + 评分预算 + 整理器 + 纪律兜底 + 渐进披露 · RAG 知识库 · 世界书/mode/preset/ST 宏 · 15 个 transformer · `SKILL.md` 技能（内置、AI 自装、AI 自存）· MCP（SSE / StreamableHTTP / OAuth / mTLS / schema 折叠）· `ask_user` · `todo_write` · 自动上下文压缩（80% / 40% / 2000 token）· 真 BPE tokenizer · 提示词拼装诊断 · Ktor Web + React SPA + SSE · 实验性插件（JSON 规则 + QuickJS）· 中断 → 合成 tool result（`ToolResultBalancingTransformer`）。

**本仓真正空着的方向**（PurrCat 之外的独立结论）：定时/周期任务（有设计稿无实现）、跨任务编排、外部能力生态、守护态后台进程、per-tool 网络与资源配额。
