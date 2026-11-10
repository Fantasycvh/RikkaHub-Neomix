# PurrCat（PurrPod/purrcat）借鉴评估

> **本仓说明**：本文写于私有开发仓的评估流程中，恢复时已按本仓实际改写；`<work>` 等尖括号串是路径占位符。
> 本文**只记录结论与建议顺序，未改动任何代码**（原状态：评估完成、未落地，2026-10-30；**2026-11-03 状态回写：A1 收尾闸门 / A3 交付物契约 / A4.1 idle 门控 / A4.2 会话级目标已在本仓落地，仅 A2 能力生产线仍未动工**）。提交/发布按 `AGENTS.md` 授权规则执行。

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

### A1. 收尾闸门（loop-end gate）—— ✅ 已落地（`ae382c8c1`，2026-10-31；限定场景）

- **需求**：让"没真的交付就不许结束"从提示词自觉变成可判定的闸门。本仓当前靠 `RuleEngine` 的 `budget_near_limit` 提醒（注入式），模型可以不理。
- **本仓现状**：`RuleEngine.remember()` 三条规则全部"只提醒不拦截"；`ToolCallLoopGuard` 只做**优雅停止**（结果照常回填），不做"打回重试"。
- **增量点**：`on_loop_end` 式的判定 + 未通过则追加一条 user 提示继续本轮，上限 N 次后强制放行。
- **落点**：`app/src/main/java/app/androidrik/rikkahub/data/ai/GenerationHandler.kt:544` 的循环出口（`for (stepIndex in 0 until maxSteps)` 的空工具分支）；判定输入复用 `RuleSnapshot` 的显式快照范式。
- **⚠️ 哲学冲突与约束**：本仓刻意"只提醒不拦截"，聊天场景下强制闸门会让用户"发完消息却结束不了"。→ **只允许在"有明确任务"的场景启用**：子代理运行（`SubagentRunner`）、定时任务会话、workspace 工作轮；普通聊天保持注入式。
- **工程量**：小（约 1 天 + 单测）。窗口/阈值沿用三档 `Sensitivity` 惯例，不开放自由数字。
- **验收**：闸门未通过时本轮继续且注入提示措辞可见；达上限强制放行不卡死；聊天模式行为逐位不变（回归测试）。

### A2. 能力生产线（能力 → 校验 → 合并 → 回滚）—— ✅ 设计层吸收（**仍未动工**，2026-11-03 复核无 `ArtifactValidator`/版本表）

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

### A3. 子代理交付物契约 —— ✅ 已落地（`ae382c8c1`：`LoopGate.deliverables` + `TaskManageTools`，2026-10-31）

- **需求**：后台跑飞的子代理最难判"真完成"。文件系统是天然验收器，本仓工作区正好有。
- **本仓现状**：`SubagentRunner.kt:753` `RunOutcome.Completed(summary)` 只要求最终文本非空（`:798` 注释明确"空响应不得记成 COMPLETED"）。
- **增量点**：`SubagentPreset` 增加可空 `deliverables: List<String>`（存在 `Settings.subagentPresets` 的 JSON 里，**无需 Room 迁移**）；完成时若声明了 `deliverables`，逐个校验"存在且非空"，缺失则追加"任务未结束：未检测到 X"给它自己看（不是报错给父代理）。
- **落点**：`app/src/main/java/app/androidrik/rikkahub/data/model/SubagentPreset.kt` + `service/SubagentRunner.kt`。
- **风险**：低。旧 JSON 缺字段 → 可空默认 `null` 即"不校验"，行为不变。
- **工程量**：小（半天–1 天 + 单测）。
- **验收**：声明了 deliverables 但未产出 → 不记 COMPLETED；未声明 → 行为逐位不变。

### A4. 定时任务的两个补充语义 —— ✅ 已落地（`ScheduledTaskExecutor.kt:51` idle 门控；`Conversation.goal` DB v52 + `withGoal` 注入，`2517d47cf`/`792d2cf22`）

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

## 附录 E：2026-11-03 增量评估与状态回写

- **状态回写**：A1 收尾闸门 + A3 交付物契约 → `ae382c8c1`（`LoopGate.kt` + `TaskManageTools`，2026-10-31）；A4.1 idle 门控 → `ScheduledTaskExecutor.kt:51`；A4.2 会话级目标 → `2517d47cf`/`792d2cf22`（`Conversation.goal` + DB v52 + `withGoal` 注入）。A2 能力生产线仍空（全文 grep `ArtifactValidator`/版本表零命中）。
- **2026-10-26 → 10-31 新增 6 提交（`efc9fb6..adbba56`）**：`53f5847` 会话级 `plan.md`（BrainStorm 每次调用整体覆盖刷新总计划，`@PLAN` 作为注入别名在任意位置展开）→ **候选**（本仓 `Conversation.goal` 是用户手填；缺「模型工具自动刷新」与注入别名，落点 `ConversationEntity.kt:52` + `ScheduledTaskEntity.kt:79`）；`adbba56` 插件后端子进程生命周期跟随面板标签 → 不适用（本仓插件是编译期 Kotlin + QuickJS，无后端子进程形态）；其余 4 条为 UI/CDP 回落/前端修复 → 不适用（平台面）。
- 证据级别：本地源码（commit `efc9fb6` 起），逐条读 diff。

## 附录 F：2026-11-10 增量评估（`adbba56..7cd7134`，约 61 提交）

> **同步点推进**：10-31 `adbba56` → 11-09/10 `7cd7134`（origin/main，2026-11-10 19:01 fetch）；61 提交逐条 `git show` 读 diff，本仓侧凡判「已有等价」均 grep 到 `file:line`（本轮未改任何源码、未 commit）。
> **判决计数**：**吸收 0 / 候选 3 项（P1 一项、P2 一项、P3 一项）/ 已有等价 8 / 不适用 29 / 记账 20 / 修正旧结论 1**（共 61 提交；F1/F2 共享 `6a95539` 来源）。
> **一句话**：这批的**方法论含金量显著高于 10-26 批**——作者用 10-09→11-10 连续 8 个提交把「typed 模块 + CI 产物比对 + 测试分层 + 静态红线 + 文档-实现对齐 + 删功能即删用例」做成了一套可执行纪律，恰好指着本仓唯一真正的门禁缺口（有 382 个单测却零 CI 测试）。功能线多数已被本仓更强实现覆盖；Electron/ONNX/RapidOCR/安装器类运行时形态不可移植。

### F.1 候选（按优先级）

| # | 来源 | 项 | 判决 | 本仓落点 | 量级 |
|---|---|---|---|---|---|
| **F1** | `6a95539` + `bb33169` | **CI 门禁接线**：PurrCat 新增 `python-tests` job 跑 `pytest -m "l0 or l1"` 与 `-m l3`，electron job 跑 `tsc --noEmit` 并重建产物后 `git diff --exit-code` 比对；`preflight` 变更检测把 `tests/`、`docs/api/` 也算进触发面 | **P1** | 本仓 **382 个 JVM 单测（app/src/test）+ 100 个 androidTest + 9 个 `scripts/audit_*.py`/`verify_*.py`，但 CI 只 `assembleRelease`、零测试门禁**（`.github/workflows/daily-build.yml:132-133`、`release.yml:115-116`；且 daily-build 仅在手动 dispatch 时触发）。落点：新增 `.github/workflows/` PR/推送 job 跑 `./gradlew testDebugUnitTest` + `python scripts/audit_ime_trap.py`（可选加 web 路由↔文档一致性脚本） | ~0.5–1 天 |
| **F2** | `6a95539`（AGENTS.md + `tests/l3/test_red_lines.py` + `conftest.py`） | **测试卫生规范件**：①**目录即层级**——`conftest` 按路径自动打 `l0/l1/l2/l3` marker，新增用例不必记得标注；L2 桌面 E2E 永进 PR 门；②**无因挂起配额**——`skip/xfail` 必须带 `reason=` 且含任务号，且总数 ≤5（AST 静态扫描，同批把 70 行“红线”变成用例）；③**新增功能先写 spec**（背景/行为/AC 清单/测试矩阵/非目标/风险回滚）；④**功能删除清单**——代码+路由+配置+用例+fixture+spec+文档同一次改动删净，「删完测试仍绿说明它没测到行为边界」 | **P2**（规范件，无运行时改动） | `AGENTS.md` 增「测试分层与删除纪律」节（已有 TextField Host Rules 同款位置）；静态检查扩展 `scripts/audit_test_hygiene.py`（本仓 `audit_ime_trap.py` 已是「A/A' 失败、C 告警」同款范式）。spec 部分本仓已有等价（`docs/design/completed/` 36 篇带验收、`docs/design/verification/` 21 篇实施记录），只补「AC ↔ 测试矩阵」对照 | ~0.5 天 |
| **F3** | `016a8a7` + `da78e53` | **巨型文件拆分判据**：5696 行 `host.js` 拆成 `ui/web-minimal/src/host/*.ts` 20+ typed 模块（`types.ts` 是契约唯一来源、`main.ts` 依赖链即入口、相对导入统一 `./x.js`），esbuild 打包 + sourcemap 入库 + CI 重建比对；`da78e53` 再 `.gitattributes` pin LF 让 sourcemap 跨平台可复现 | **P3**（观察期，不预设动手） | 本仓最大文件：`service/ChatService.kt` **3262 行**、`data/ai/GenerationHandler.kt` **1722 行**（PurrCat 最大源文件 1026 行）。**观察期触发点**：下次对 `ChatService` 做跨功能改动时再按职责拆，禁止为拆而拆；拆时照抄「契约类型单点 + 跨模块可变绑定只读/写走 setter + 入口依赖链」三条 | 3–5 天（触发后） |

### F.2 已有等价核对（8 条，均 grep 本仓）

| PurrCat 提交 | 机制 | 本仓等价证据（file:line） | 结论 |
|---|---|---|---|
| `4570ea7` | 可注入 `ChatModel` seam：`Protocol`（runtime_checkable，只声明 `chat`/`bind_task`/`unbind`，`vision` 走 `getattr` 可选读取）+ `Agent(model=...)`；L3 用 `inspect.signature` 断言测试替身与真实实现签名一致 | `ai/src/main/java/app/androidrik/ai/provider/Provider.kt:16`（`interface Provider<T : ProviderSetting>`）；`data/ai/GenerationHandler.kt:265`（构造注入 `providerManager`）；测试侧 `app/src/test/.../GenerationToolLoopTest.kt:162`、`EmptyGenerationResponseTest.kt:28` 的 `ScriptedProvider` 假实现 | **已有等价且更强**：seam 在 provider 层（生成/额度/目录/多模态），不是只抽 chat；替身已用于真跑整条工具循环 |
| `7cd7134` | 工具注册契约：schema 工具名必须可解析到 callable、`TOOL_FUNC_MAP` 与模型可见集合一致、schema properties ⊆ 函数形参（或 `**kwargs`）、必填形参必须可由模型或「系统注入白名单」填充；白名单冻结断言 | `ai/src/main/java/app/androidrik/ai/core/Tool.kt:26`（`Tool` 是具名 data class + `execute` lambda，**没有「名字→函数」字符串解析面**）；`ChatToolFactoryTest.kt`（已有 ToolSource 段名 frozen contract 断言） | **结构性更强**：PurrCat 需要运行期契约测试是因为工具靠 `importlib` 按字符串解析；本仓装配期就是对象，整类「解析不到/注册漏暴露」不可能发生。仅剩「schema 键与 execute 读取键漂移」残留面，静态不可判 → 不立项 |
| `7e32ecc`（+`b0b1218` 文档） | 项目 = 会话分类器（`projects/index.json` + `<project>/MEMORY.md`）；会话归属双写（`meta.json` 为权威、全局索引允许滞后）；项目内新建会话**只注入记忆文件路径**不注入正文；`b398de1` 把项目记忆文件加入 Agent 读写放行（严格匹配 `<PROJECTS_DIR>/project_*/MEMORY.md`） | 会话分组：`db/entity/FolderEntity.kt:11-32`（助手内文件夹）+ `ConversationEntity.kt:61`（`folderId`）+ `web/routes/FolderRoutes.kt`、`ConversationRoutes.kt:68-107`（REST 按 folder 过滤/分页/搜索）；记忆作用域：`MemoryEntity.kt:14`（`assistantId`——**助手级记忆是「项目级记忆」的超集**）；路径安全：workspace 走 fd 级锚定而非目录白名单 | **已有等价**：分组 + REST + 归属齐备；「项目记忆文件」无独立等价物，但助手级记忆已覆盖同一需求且本仓更成熟（评分/预算/纪律/回收站）→ 记账，不立项 |
| `ab54a9a` | `cron.json` 原子写（tmp + `os.replace`，防独立 System Clock 进程读到半截）+ `armed_at`/`last_fired_at` 武装时刻（一次性闹钟从武装起算、重新启用重武装） | 原子写：`data/files/AtomicFileWrite.kt:36`（唯一名临时文件 + `ATOMIC_MOVE`，失败返回 false 不留半截/不毁旧内容）；调度权威：`db/entity/ScheduledTaskEntity.kt:11-12`（`nextRunAt` 唯一权威 + 乐观锁认领）`:60-63`（`next_run_at`/`last_run_at`） | **已有等价且更强**：本仓单进程 Room 权威状态，无跨进程共享 JSON 问题；认领即原子推进已覆盖「武装时刻」语义 |
| `7c7a20c`（+`b74b979` 落点重构） | 图片附件：历史只存会话副本路径（`<会话>/attachment/1.png`），发送前还原 base64；顺带 `Task` 直注前按请求体积上限判定，超限改视觉顾问转述 | `data/files/FilesManager.kt:405`（`convertBase64ImagePartToLocalFile`：base64→采样压缩 PNG→会话文件，超限/失败换可见占位）；`ai/.../util/FileEncoder.kt:105`（`encodeBase64`）+ `providers/openai/ChatCompletionsAPI.kt:744/801/849`（发送时按 `file:` 读盘编码）+ `ai/.../util/AttachmentLimits.kt:45`（逐附件上限） | **已有等价且更强**：同「历史存路径、发送才编码」形态，且多出压缩/采样/占位/逐附件限额。PurrCat 额外的「超限降级为视觉描述」**不吸收**（隐性二次模型调用、花销不可见，本仓选择显式压缩/占位/报错） |
| `c5fc53a` | 视觉：删 OCR 兜底、只信视觉模型（先前 `cd2e4d5` 把 `easyocr` 换 `RapidOCR`，一天后又整体删除，installer 因此瘦身） | `data/ai/transformers/OcrTransformer.kt:43`（OCR 走**用户配置的 OCR 模型 + 3 天缓存**，非本地 OCR 引擎）；`TransformerChainRegistry.kt:145` 注册 | **方向一致**：本仓从未引本地 OCR 引擎，OCR 本身就是「视觉模型转述」，天然无重依赖；`cd2e4d5` 判不适用 |
| `9689163` | 搜索直接抓 Bing、砍 `ddgs`（国内不可达 + 多引 19MB 依赖），`curl_cffi` chrome 指纹兜反爬 | `search/src/main/java/app/androidrik/search/` 已多 provider（Bing/Brave/Exa/Jina/Firecrawl/…）；`data/ai/tools/SearchTools.kt:47` `search_web` / `:94` `scrape_web` 按当前服务真实能力动态出 schema | **已有等价**：本仓搜索是 provider 抽象而非硬编码抓取，无「换一个抓取库」的问题 |
| `9de8ae2` | 分支会话未命名时沿用源会话名 +「（分支）」 | 本仓分支是 `MessageNode` 树内切换（同一会话内），不存在独立会话别名概念 | **不适用/已内化** |

### F.3 不适用（29 条）

- **桌面宿主 UI 重组与样式（20 条）**：`c20353a` `300985d` `21d0e68` `9ef5544` `a1de4ef` `3a495a4` `976f35f` `37c968f` `2d5bcb6` `501e40e` `22bacc7` `699488d` `962e4f8` `44da5d6` `98dfa14` `d09ee4e` `647ecf1` `a8ecc20` `7082fdd` `255c91b`。rail/slot/panel/会话面板是 Electron iframe 宿主形态，本仓是 Compose 原生导航，无对应需求（审批在工具卡内、待办在后台任务页、搜索在会话页）。
- **运行时形态不可移植（9 条）**：`fb3a3bc` `2b04968` `89de365`（Electron 隐藏窗渲染 + `curl_cffi` 回退——Android 没有第二个浏览器内核供后端代理；本仓 `scrape_web` 是第三方 reader 服务）；`4496adf`（ONNX embedding——本仓明确不做 embedding，见 `peer-verdicts-2026-11-08.md` §一.9）；`cd2e4d5`（RapidOCR 本地引擎）；`665256d`（图形安装器——Android 走 APK/商店）；`2b9bd16`（插件后端 npm 依赖自动安装——本仓插件无子进程后端，只有 QuickJS 规则与编译期插件）；`b398de1`（目录白名单放行——本仓 workspace 是 fd 级锚定，形态不成立）；`2a0699f`（循环 import 用模块 `__getattr__` 惰性化——Kotlin 无此形态）。

### F.4 记账（20 条，不立项）

- **工程/文档纪律（13）**：`40e1ae5`（AGENTS.md 缩为入口 + 新增 `docs/develop/{backend,frontend,installer}.md` 分册——本仓 AGENTS.md 已是同类入口，可留观）；`4b81292`（文档去冗余 + 修正 host dispatch 引用）；`f7a57d7`+`002ba63`（CONTRIBUTING + 双 README 链接）；`1f48c05`+`5f8da4b`+`63b38a3`（删残留配置 / 删 TUI 1576 行 / 删 TUI 配置——「功能下线同一次改动删净」的正面样本）；`6275da1`（依赖瘦身）；`6a419a1`（ruff 格式化）；`96dabbe`+`4290ee9`（API 文档修正 / 请求理由措辞约束）；`af299fa`（过时注释）；`da78e53`（`.gitattributes` pin LF——判据见 F.5）。
- **路径与实现细节（3）**：`d72c5af`（`/agent_vm` 沙盒视角绝对路径映射——**同一路径语义多入口必须收敛到一个映射函数**，本仓 workspace 已单点锚定）；`b74b979`（附件快照从 Agent 迁入 Task，纯落点重构）；`01fd2d0`（OCR 构建排除项清理，随 `c5fc53a` 一起失效）。
- **同批 revert 样本（2）**：`7a462e9`+`00c8d9f`（过滤 AdGuard/AdBlock 占位文本，9 分钟后 revert）——**兜底启发式不可靠就整块回滚**，不记账为功能。
- **形态不符（2）**：`7a40867`（sensor 配置由 `fields` 声明渲染表单，含 kv/list 子编辑器 + 原始 JSON 切换保留——本仓插件自管 `PluginKeyValueStore` + 插件自绘 UI，宿主不渲染 schema 表单；若将来做插件市场再取「声明式字段 + 原始 JSON 兜底」）；`b0b1218`（projects API 文档，随 F.2 记）。

### F.5 修正旧结论（1 条）

- **`cb00658`**：§七 第 1 行「`PARADIGM.yaml` 的 `trigger` 字段无消费方」→ **上游已于 11-09 自己删除该字段**（默认范式 + `docs/plugins/agentloop.md` 同步删干净）。「文档先于实现」的旧结论仍成立（这正是它被删的证据），但该具体条目已消解，后续评估不要再引用。
- **状态回写（非判决）**：§二.3「代码组织弱：`host.js` 3288 行」已过时——现为 20+ typed TS 模块 + esbuild 产物 + `tsc --noEmit` strict + CI 重建比对；但 **Python 侧未见拆分**（`agent.py`/`process.py` 一类大文件问题仍在），Round 2 的判断只在「前端宿主」这一段作废。

### F.6 判据沉淀建议（供 `peer-verdicts-2026-11-08.md` §二/§三）

- 静态红线脚本必须**接进 CI**才算门禁；`AGENTS.md` 写了跑法但没人跑 = 不存在（purrcat `6a95539`/`bb33169`；本仓 `scripts/audit_ime_trap.py` 现状即反例）。
- 测试分层按**目录**归类、`conftest` 自动打 marker；L2 E2E 永进 PR 门。
- 挂起用例必须带 `reason=` + 任务号，且**无因挂起总数设上限**；禁止为变绿删用例、放宽断言（purrcat `test_red_lines.py`）。
- 功能下线同一次改动内删净（代码+用例+fixture+spec+文档）；**删完测试仍绿 = 该用例没测到行为边界**（purrcat AGENTS.md 删除清单）。
- 生成产物入库必须 CI 重建 + `git diff --exit-code` 比对，并 pin 行尾（LF）保证跨平台可复现；**更优解是产物不入库、构建期生成**（本仓 `web/build.gradle.kts` 的 `buildWebUi` preBuild 即此形态，天生无偏移面）。
- 巨型文件拆分以「契约类型单点 + 入口依赖链」为骨架，跨模块可变绑定只读、写走 setter。
- 同一路径语义（沙盒视角 / 宿主绝对路径）只能有一个映射函数；多入口各自实现必错（purrcat `d72c5af` 与 `9bc2d58f4` 同族）。
- 接口文档与路由由 CI 校验一致（purrcat `scripts/check_api_docs.py` 进 L3）——本仓 `web/routes/` 目前无此校验，随 F1 可选落地。

### F.7 证据与复现

```bash
git -C F:/build/projects/purrcat log --oneline --reverse adbba56..origin/main   # 61 条
git -C F:/build/projects/purrcat show 6a95539 -- .github/workflows/ci.yml AGENTS.md
git -C F:/build/projects/purrcat show 7cd7134 -- tests/l3/test_tool_registry_contract.py
git -C F:/build/projects/purrcat show 016a8a7 --stat    # 5696 行 host.js → 20+ typed 模块
```

证据级别：本地源码（PurrCat `adbba56..7cd7134` 逐条 diff；本仓 `file:line` 现场 grep，未改源码）。
