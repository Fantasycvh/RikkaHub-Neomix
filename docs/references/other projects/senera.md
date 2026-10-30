# senera 借鉴评估

> 评估：**2026-10-02**（原 §七）+ **2026-10-26**（10-13→10-21 增量，原 §7.5）。**仅远端仓库调研，本地无源码**。
> 定位：宿主型 TS Agent 工作台；核心命题是「工具执行可审计」（校验→授权→执行→观测→证据），也是本仓工具 schema 三档加载 / 动态工具检索的**成熟参考实现**。
> 返回 [索引](../../peer-apps-benchmark.md)。

## 七、senera 评估（2026-10-02，仅远端仓库调研，本地无源码）

**仓库**：[yuanplussfive/senera](https://github.com/yuanplussfive/senera)（⭐14，TypeScript strict，Node 24，Web 工作台 + Electron 桌面 + Docker/gVisor 沙箱；自述「可观测、可校验、可扩展的 Agent 工作台」；内核=Pi Coding Agent 会话与多步工具循环）。~3250 文件的大系统，架构层面对移动端聊天 fork 不可移植，但有三个机制值得精读。

### 7.1 动态工具检索/元工具协议（最有价值——peer⑤③ schema 三档缩水版的成熟参考实现）

`sensem/ToolSearch` 模块（30+ 文件 + 9 个行为测试 + 独立 benchmark）正是我们 09-26 设想的「MCP 轻量化 + 元工具」的成品形态：

- **工具分加载模式**：`ToolLoadingModes.Dynamic`（不进上下文）vs `Always`（核心工具常驻）。fork 对应=内置工具常驻、MCP 工具全部 Dynamic。
- **四个元工具**：`ToolSearch(query)`（检索）→ `ToolDescribe(tools≤12)`（看 schema）→ `ToolLoad(tools≤12)`（加载进本轮上下文）→ `ToolUnload(tools)`（卸载）。工具注册表对模型暴露的只有这 4 个 + Always 工具 ⇒ **上下文占用从「全部工具 schema」变为「常驻子集 + 4 个元工具」**，正是 MCP 33K 问题的解。
- **排序管线**：BM25/RRF/MMR 三段 + 能力索引（由工具名+描述+schema 构建文档）+ **SQLite 记忆反馈**（EpisodeScorer：历史使用证据提升排序——用得多的工具排名靠前）。fork 若做：BM25 在 Kotlin 里可用 SQLite FTS 或手写倒排；记忆反馈可从 agent_run/工具执行历史取材。
- **catalogRevision**：工具目录版本号贯穿 Search/Describe/Load 参数——**mid-run 工具集变化可检测**，这直接回应我们 09-26 评估的最大顾虑「mid-run 换 schema 作废 Claude 前缀缓存」（至少可感知、可决策）。
- **会话级缓存 + Retain/Replace 策略**：已加载工具集跨轮保持（Retain=默认保留，Replace=整体替换）⇒ 前缀缓存只在真正换集时失效。

**结论**：我们排期 schema 三档缩水版时，此仓库是唯一见过的完整参考实现（含测试与 benchmark），设计要点可直接对照，省一轮设计踩坑。

**7.1.1 源码级补注（2026-10-02，本地 `<work>\projects\senera` 已克隆，`Source/AgentSystem/ToolSearch/` 40 文件）**：

- **修正一处远程评估的误读**：`ToolLoad/ToolUnload` 只作用于 **BAML 规划链路**的 exposure snapshot。**native 工具链路更激进**——动态工具**从不进 provider schema**，模型经固定 `ToolCall(toolName, contractRevision, args)` 桥调用（`ToolDescribe` 拿到 TS-like 视图后），provider schema 恒为「Bootstrap 工具 + ToolSearch/Describe/ToolCall」。fork 全部走 native tool calling ⇒ 对应形态=**schema 恒不增长，元工具桥是唯一入口**（比我们「三档加载」设想更彻底）。
- **ToolSearch 不做 top-K 截断**：返回**全部**合资格动态工具、确定性排序——命中项带 evidence，未命中项压缩为 name/title/summary/零分，让模型可发现而不塞 schema。
- **lexical-first，远程层默认全关**：BM25 精确召回 + `fuzzysort` 只对**工具名/能力 ID/标题/别名**做容错（不扫长描述、无硬编码同义词）；embedding 召回仅在**词法召回为空**时启用；远程 rerank 只在有限召回集上跑。默认值（`AgentDefaultCatalog.ts:141`）：Fuzzy{Enabled:true, MinScore:0.25, CandidateLimit:8}、Embedding{Enabled:false}、Rerank{Enabled:false, CandidateLimit:24}、Ranking{RrfK:60, MmrLambda:0.72, MmrCandidateScoreRatio:0.92, **MaxResults:6**}、记忆反馈{MaxEpisodes:5000, **HalfLifeDays:30**（使用证据按 30 天半衰衰减）, Expansion:Fallback 模式 MinConfidence:0.8/MinEvidence:3}。
- **缓存身份内容寻址**：目录身份=文档 stable ID + revision + 完整 semanticText 的规范哈希；embedding 身份=（模型, docId, SHA-256(semanticText)）哈希 ⇒ **生产者忘 bump revision 也不会脏缓存**；LRU 有界。这对我们的 Claude 缓存断点顾虑是更稳的形态：失效由内容变化触发而非标签。
- **已确认能力复用（argument reuse）**：每 durable session + logicalCacheScope 保存有界证据，**仅只读/无副作用/免审批工具**可自动复用旧参数（写状态/联网/审批类只留诊断不复放）；复用前按目录身份+契约 digest 重校验；显式参数恒优先。证据不因索引刷新清除 ⇒ 稳定前缀缓存。fork 的 MCP 检索类工具（search_web 等）将来可借鉴此形态。
- **安全与排序分离**：审批/OPA/资源投影在执行期强制，搜索排序不得复制安全策略——与 fork DenialGuard 同哲学，写设计稿时值得引用为原则。
- **MCP `tools/list_changed`**：经 SDK 协商处理，按 server 事务性替换 + 失效索引；非法更新保留旧目录。
- 学习（episode）是**可观测旁路**：每条 episode 终态=learned/skipped/failed，**不改变主任务结果**；歧义多 Skill 归因直接跳过而非复制到每个 Skill。

### 7.2 工具结果 → Artifact/证据投影（次优先）

工具执行结果发布为完整 **Artifact**（带完整性 receipt），回写给模型的只是声明式 **Observation v3 投影**（摘要+事实+URI）；历史轮的大输出沉淀为证据投影，需要原文时用 artifact 工具按 URI 取回；Pi 压缩删旧消息但留隐藏索引，每请求只在剩余 token 预算内注入「最近且尚未可见」的 URI。

fork 现状对照：我们已有**文件版近似**（tool_outputs 落盘 + 32KB 截断 + 4KB 预览 + workspace_read_file 取回 + 压缩检查点），形态不同、思想同源。差距在「投影」是结构化摘要而非字符截断——但结构化摘要要么靠模板（信息损失可控性差）要么靠 LLM 再加工（每步多一次调用）。**暂不跟**，等 L2 上下文预算立项时一并评估。

### 7.3 其他值得记的（小）

- **宿主生成工具调用 ID + 依赖投影**：并行工具调用时 call_id 由宿主生成（`call_xxx`）、工具间依赖由宿主侧校验投影——不押在模型自己编 ID 上。与我们 ResponseAPI toolCallId 归一（`3f54240f`）同主题的更激进形态；fork 目前工具串行、子代理并行由宿主管，暂无消费点。
- **CallTools 动作协议**（FinalAnswer/AskUser/CallTools 三分 + CallTools 先出自然语言预回复再规划参数）：「边说边做」的 UX；fork 流式本就支持文本与工具交错，元工具落地时若做「先检索后调用」的提示词工程可参考其动作拆分。
- **执行目标合同**：工具注册时声明网络能力/工作区权限/资源语义/artifact 策略；沙箱不可用 → **明确拒绝该执行目标而非悄悄改本机执行**——与 fork DenialGuard 同哲学，可用于将来 workspace 工具分级。
- **BAML 双链路**（native tool calling / BAML 结构化规划二选一并显式声明，运行中不静默切换）：让不支持工具调用的普通模型获得同等的结构化工具语义。Kotlin 侧无可直接移植的 BAML 运行时，成本大，仅记录思想。
- **Skill 发布前工具引用校验**：SkillManage 创建/更新时验证引用的工具已注册（防悬空）；fork 技能体系无工具引用概念（allowedTools 已删），不适用。

### 7.4 分层上下文与上下文策略（2026-10-02 追加；与上游 #972 同题）

7.1～7.3 只覆盖了工具检索/Artifact/杂项。本节补的是**分层上下文与上下文策略**——因为上游 **#972（无限上下文和分层记忆重构）** 的三问（无限上下文 / 分层记忆 / **懒加载消息**）与 senera 这套机制是同一道题，而 senera 是目前见过**唯一把三件事都落地**的实现。源码位置：`Source/AgentSystem/Prompt/`（上下文编译器）、`TemporalMemory/`（时间摘要）、`Continuity/`（按需展开）、`Memory/`（artifact 投影）。

**分层上下文（`Prompt/`，源码级）**

- **11 层**：`kernel / persona / profile / lore / facts / graph / world / scene / memory / workflow / task`（`AgentPromptContextLayerTypes.ts`），每层带 manifest：`source`（runtime/preset/profile/continuity/request）、`stability`（**stable / turn / event**）、`included`。
- **3 档 + revision 身份**：`stable / context / volatile`，每档一个 revision；定义里写明用途——**区分「可复用的会话上下文」与「必须每轮重建的数据」**。
- **核心手法=稳定前缀与易变状态分离**：stable 档（kernel + 人设 + voice 示例 + 执行环境）**跨会话逐字节一致**；turn 档持久化为**一条隐藏的、仅追加的 `senera.turn_context` 消息，紧跟在所属用户消息之后**（旧快照留作历史证据，最新者权威）⇒ **provider 前缀保持字节稳定**，同时状态变化对模型可见。
- **渲染即校验**：`AgentPromptContextWireRenderer` 让每个块**声明允许的无损编码**（同类数组走 TOON、异构状态留 JSON），**严格解码后与源值比对，回环失败直接报错**（不降级成散文）；每块用**当前模型的 tokenizer** 计量。
- **只缓存稳定档**：`AgentPromptTierRenderCache`——"reuses rendered stable prompt tiers while keeping volatile turn data out of the cache"；cache scope 是宿主侧标识，**不作为指令**给模型。

**上下文策略（记忆/连续性/证据）**

- **TemporalMemory**：digest **层级 = `segment → day → month`**，状态机 `open/pending/sealed/failed/stale`；每条带 `summary / topics / openLoops / uri / childCount`，成员按 `ordinal` 归并 `episode|digest` ⇒ **时间维度的摘要树 + 可寻址 URI**。
- **Continuity 的按需展开**：`AgentContinuityEpisodeWindow` 用 `before/after` 做邻域展开——**模型选范围，宿主保序/分组/来源完整性**；再叠事实与条件求值（authority policy / condition evaluator / entity alias）。本质是**回传派生状态而非原始消息体**。
- **证据句柄**：工具结果发布为 artifact（带 receipt），给模型的只是投影 + URI，且投影时剥掉内部路由字段（宿主路径、manifest 等）。

**对上上游 #972 的映射**（#972 = re-ovo 2026-03-16 自记备忘、label enhancement、**至今零实现**）

| #972 的三问 | senera 的答案 | 我们的位置 |
|---|---|---|
| 分层记忆 | 11 层 + 3 档 + 时间摘要树 | 记忆有「显式可编辑 + 衰减打分 + 预算截断」，但是**单层**（无时间维度层级） |
| 无限上下文 | 摘要层级 + 追加式 turn_context + 字节稳定前缀（不是真无限，而是**可寻址的压缩历史 + 按需回取证据**） | 已有压缩检查点（token 驱动、区域指纹失效）——同一问题的另一条路线 |
| 懒加载消息 | ①不回传原始消息体，只回传派生状态 + 句柄 ②episode 窗口按需邻域展开 | 已有文件版近似（`tool_outputs` 落盘 + 截断 + `workspace_read_file` 回取） |

**对我们的借鉴（按性价比，2026-10-02）**

- **P1（值得做，半天~1 天）：易变状态搬出 system 块 + 隐藏追加式「轮次上下文」。** 我们的 system 块里混着**每轮会变**的内容（记忆注入段 `MEMORY`、注入条目段、渲染提示），于是「记忆刚更新」那一轮会**从 system 起整段前缀失效**——而隐式前缀缓存（OpenAI/Gemini 自动缓存）只看前缀**逐字节**是否相同。senera 的做法在我们这边**有现成先例**：压缩检查点本身就是「合成消息 + 前导标记」的追加式实现，照它加一层轮次上下文合成消息即可。**不依赖 #1971**（该字段已因主力端点 New API 不透传而搁置；隐式缓存不需要它）。**已实测（2026-10-02，真机 debug；见下表）**：稳态 99–100%，**一次记忆写入后掉到 72%**（6.9K 中 5.0K 命中），下一轮立刻回到 99% —— 证实「易变项变动只影响那一轮」，但**那一次的代价是「记忆块之后的全部内容」**。风险=把一部分 system 内容改成消息角色可能影响遵循度，需 A/B 与真机。
- **P2（小纪律，随时可做）**：①给拼装预览/容量面板的分段加 **stability 分组**（stable/turn/event）——正是刚落的「上下文组成明细」的自然延伸，一眼看出「哪些内容每轮都在变、因而不利缓存」；②把「投影必须无损回环校验、失败即报错」借到我们已有投影的两处（工具结果投影、拼装预览分段）。
- **P3（只记账、不排期）：时间维度摘要树**（segment/day/month）。价值是「很久以前仍能粗粒度召回」，但我们是**显式可编辑记忆**、senera 是**自动派生**——哲学不同，引入等于新增一类「日记/时间线」产品面（摘要作业、存储、召回、UI）。等「长期陪伴/时间线回溯」出现真实需求再评估。
- **维持现状**：证据句柄/artifact 投影我们已有文件版近似（见 §7.2）；TOON 那类表格化编码对我们意义有限（易变块不大）。

**实测数据（2026-10-02，同一会话；`glm-4.5-air` / 智谱AI开放平台；隐式前缀缓存）**

| 轮次 | 情况 | prompt | cached | 命中率 |
|---|---|---|---|---|
| M1 | 首轮（跨会话共享前缀） | 6.4K | 6.4K | **100%** |
| M2 | 稳态 | 6.5K | 6.4K | 99% |
| M3 | 稳态对照 | 6.6K | 6.6K | 100% |
| **M4** | **写入一条记忆后** | 6.9K | **5.0K** | **72%** |
| M6 | 记忆不变、恢复正常 | 7.0K | 6.9K | 99% |

- **失效范围＝记忆块之后的一切**：由「缓存量 5.0K ≈ 工具 schema 4.3K + 人设 0.6K」可反推序列顺序为 `工具 schema → 人设 → 记忆 → 引导/提示 → 历史`，所以记忆一变，**其后整段对话历史都要重算**（本次历史约 1.9K，故整体只掉 28%）。
- **放大规律（推断，按前缀缓存语义必然成立，但未实测长会话）**：单次失效量 ≈ 记忆块 + 历史，≈ **历史长度** ⇒ 在 5 万 token 的长会话里，同样一次记忆写入就是约 5 万 token 重算；而记忆是模型**主动**写的，成本按「会话长度 × 写入次数」计。
- **两个附带发现**：①最大的固定块（工具 schema，≈4.3K）排在可变部分**之前**，因此长期保住；②人设+工具这段**跨会话**也命中（M1 首轮即 100%），比预想更好。
- **P1 因此精确化为一句话**：把**记忆注入从 system 块挪到历史之后**（尾部合成消息，沿用压缩检查点「合成消息 + 前导标记」先例）⇒ 记忆变动只重算尾部，而不是整段历史。**未做**：长会话实测（当前结论基于一次小会话 + 前缀语义推断）。

**一句话结论**：senera 这套里**唯一值得现在动手的是「稳定前缀不被易变状态污染」**（且必须先量 cached 比例再定），其余两条是小纪律，第四类（时间摘要树）与我们的记忆哲学不同、只记账；#972 若上游真立项，本节映射表可直接当设计输入。

### 7.5 增量评估：10-13 → 10-21（v1.16.0 → v1.20.0，2026-10-26）

11 个功能提交（`99834b90`/`01703d67`/`aedb9a40`/`9d3a0f4d`/`de6c0d41`/`3602057a`/`af53923a`/`859cadeb`/`5e804083`/`e6a51461`/`ab011ee0`/`4a83dae1`/`db34914c`/`c5b691d5`，去除 release bump 后实质 4 个）。**总评：无可吸收项**——全部是既有路线的延续（v2 runtime/桌面多进程化/多工作区服务端/浏览器工具换驱动层/Web 首屏优化），未触及 §7.1 ToolSearch 协议或 §7.4 P1 相关代码，与 §5 规则引擎/工具预算无冲突。

| 提交 | 实质 | 判定 |
|---|---|---|
| `3602057a` agent-browser | 删 Playwright 驱动（-975 行），改官方 `agent-browser` CLI 原生驱动（`AgentNativeBrowserDriver.ts:62`，宿主保留会话所有权/URL 准入/网络路由拦截）；工具面本体 26 个窄工具未变：严格 schema 带长度上限、readOnly 标记、审批分级（导航=allow/交互=ask，:749-755）、输出 `trust:"untrusted_browser_content"`（:53）、全文进 artifact 只回摘要、导航使旧 snapshot 引用失效 | **不适用（平台）**：Android 无 CDP/Playwright 执行体，WebView 无法被后台工具循环无头驱动。**记账**：其工具 schema 规格（读写分离+审批分级+artifact 承载+不信任内容标记+引用失效语义）是将来立项受控浏览器工具的现成设计参照 |
| `859cadeb` 模型路由/设置所有权 | 发送前校验持久化选中模型仍在目录（失效置 undefined 交后端解析）、重试不继承失败 run 的模型回执（「回执是历史证据不是路由选择」）、默认连接禁止禁用 | **已有等价**：fork 发送时统一从 SettingsStore 读当前设置（Mutex 串行化，§8.1 E 已记），无 per-message 路由持久化，「陈旧 ID 随消息重放」路径不存在 |
| `db34914c` 懒加载消息虚拟化 | 名不符实：MessageList 早已 react-virtuoso，本次只是 `React.lazy`+Suspense 切出首屏 bundle | **不适用**：fork ChatPage 用 LazyColumn，虚拟化内建于 Compose |
| `e6a51461`/`4a83dae1` workspace/桌面 runtime | 多 workspace 服务端宿主、Electron 内嵌 server 子进程、交互面重构 | **不适用（架构/基础设施）** |
| `2298b67c` 等 | MCP stdio 隔离、gVisor CI、测试门、依赖加固 | **不适用**（无机制） |
