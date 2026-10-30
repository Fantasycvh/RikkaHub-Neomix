# bigeye（suanrongqieqiezi/bigeye）借鉴评估：记忆机制 / 三件套 / 反思回路

> 评估：**2026-10-14**（原 §十一；记忆深评 11.1，逐模块精读 11.2）+ **2026-10-16**（三件套 11.3）+ **2026-10-26**（10-18→10-24 增量，原 §11.5）。
> 定位：Python 桌面/服务端项目，「让现有大模型获得无限上下文」，服务的是**模型当下关注的内容**（而非上下文体积）。本地克隆 `F:\build\projects\bigeye`，`e0f0b2d`。
> 编号说明：原 §11.4 属另一应用 billion-context（已单独成文 → [`billion-context.md`](billion-context.md)），故本文件章节号从 11.3 跳到 11.5，系沿用原文编号。
> 返回 [索引](../../peer-apps-benchmark.md)。

## 十一、bigeye（suanrongqieqiezi/bigeye）评估（2026-10-14，仅远端源码调研）

**仓库**：[suanrongqieqiezi/bigeye](https://github.com/suanrongqieqiezi/bigeye)（Aria-EQ）—— **Python 桌面/服务端**项目，
67★，2026-08-18 建仓、**2026-10-13 仍在推**。定位：**让现有大模型获得「无限上下文」**，面向长航时大型任务。
README 原话概括其思路：**「往大模型里塞聊天记录并只压缩 ✗，往大模型里塞它希望关注的内容 ✓」** ——
服务对象不是上下文，而是「模型当下关注的内容」。

### A. 结构（资源而非代码可借鉴）

顶层即架构图：`llm.py`/`llm_cache.py`/`local_llm.py`（模型层）、`attention_metrics.py`（注意力度量）、
`loop_detector.py`（目标级打转检测）、`guardian.py`（进程守护）、`rules_engine/`（声明式规则）、
`memory/`（**17 个模块、最大单文件 76KB**：`fragment_store`/`reflection_loop`/`summary_tree`/`entity_store`/`relation_store`/`vec_index`）、
`knowledge_base/`、`file_search/`、`skills_scanner.py`。

### B. 可借鉴（按价值，均为「设计思路」层面）

**① 工具循环守卫改用「探索/落地二分」判据（最有价值）** → ✅ **已落地（2026-10-16）**：落成「规则提醒」`explore_without_landing`（窗口 12 / 探索 ≥10 / 零落地 ⇒ 尾部注入自查提醒），默认开 + 三档可配。**交付位置刻意与 bigeye 相反**（它插 system 最前、本仓走尾部）：理由见 [`design-rule-engine.md`](../../design/design-rule-engine.md) §3，其中第一条是**前缀缓存** —— 插 system 最前会让每次命中都改前缀，真机读数里缓存命中 86%，一次误插就是几千 token 重算。设计稿 §9 有落地记录（含 7 处反证与"规则表暂不抽"的决定）

`loop_detector.py`（5.7KB）做的不是查重，而是**按工具的「效果」分类**：维护两张名单——
「落地类」（写文件/完成节点/沉淀记忆/求助）与「探索类」（读/搜/执行）；环形 buffer 记最近 **12** 次调用，
**探索 ≥ 10 且 落地 == 0** 就注入提醒，冷却 **300s**，且**任何落地调用立即重置冷却**（代表有实质进展，不打断）。
注入的提醒文案也值得借：先说现象、再给三步自查（①前提假设是否错了、②是否该直接问用户、③确认是正常长调研则忽略），
并明确要求**不要向用户暴露检测机制**。

本仓对照：[`ToolCallLoopGuard.kt`](../../../app/src/main/java/app/androidrik/rikkahub/data/ai/ToolCallLoopGuard.kt)（窗口 12、
轮询类调用豁免、成功阈值 3 / 失败阈值 4），但判据是 **`signature` 签名查重**，只能捕「**同一个**调用重复」；
而 bigeye 捕的是「**一直在看、从不产出**」——即使每次调用都不同（换关键词反复搜、换路径反复读），签名查重也看不见。
**两者不同维度、可相加而不冲突**，且落地名单可直接用本仓现有工具名填充。

**② 声明式规则引擎（把硬编码守卫变成可检视的表）**

`rules_engine/`：`state_probe.py` 把系统全部可读状态聚合成一份 **state snapshot**，`rules.json` 用**条件表达式**匹配，
命中则输出带实况的提醒。规则格式极简：`{id, name, desc, severity, condition, hint, action}`，
例如 `condition: "msg_count >= 10 and dag_nodes == 0"`，`hint` 里可用 `{extra_pages_open}` 这类占位符填实况。

关键是**命中后的处理方式**：提醒**插到 system prompt 最前**（防注意力分散）、**不写库不推 APP**；
无需清理，**条件不再命中提醒自然消失**；并用 `fired.json`（topic_id, rule_id）**防重复刷屏**。
它的 README 还记了一条经验：**后台常驻轮询已停用**（曾导致 APP 刷屏），改为**事件触发**。

本仓对照：守卫逻辑均为 Kotlin 硬编码（`ToolCallLoopGuard`/`ThinkingDegenerationGuard`/`DenialGuard`/`ContextBudgetPlanner`），
**无声明式规则表**（已 grep 确认）。引入一张声明式表的好处不在「能做更多」，而在：规则可直接读、可用数据对比调参、新增规则不需改编译产物。

**③ 「指标化」的诊断视角**

`attention_metrics.py` 计算五个指标并加权成 0–100 健康度：M1 工作记忆占用率（**30% 为舒适区**，偏离即扣分）、
M2 上下文增速、M3 记忆存量与平均权重、M4 任务纪律（心智锚点/DAG/黑盒节点）、M5 工具书页面占用（理想=2 页）。
**注意：这不是选择性注入的打分**（与本仓记忆打分作用不同），而是「系统健康度」诊断——
但「对上下文占用设一个舒适区、并把偏离度当信号」这个视角本仓没有。作为诊断面板/日志输出成本很低；作为自动措施则需慎重（容易误伤）。

**④ 「为什么没发」的可解释性**

它把守卫/规则命中写成**可读的「为什么没做某事」记录**（例：为什么没发消息、为什么报错）。
这与华灯 `b0dc91aff`「执行日志——界面里直接看上次为什么没发」是同一个主张：
**自动化越多，「没发生」的可解释性越值钱**。本仓已在实践这一点（请求日志、记忆整理候选跳过原因），但尚未系统化。

### C. 不适用

- **平台不同**：Python 桌面/服务端，与本仓 Android/Kotlin 无代码可复用性；其「上下文管理」设计只能在**思路/策略**层借鉴。
- **无 License ⚠️**：仓库未声明许可（`license: null`，无 LICENSE 文件）⇒ 默认版权保留、不授予任何权利。
  **本仓不得复制其代码**；以上借鉴均为「思路」层面，实现必须自行重写。
- **守护进程（`guardian.py`）**：健康探测每 5s、最大重启 5 次、冷却 60s，服务与控制平面分端口（8765/9891）。
  本仓是 Android 单进程 App，无此类长时运行服务进程可守（WorkManager 负责重试即可）。
- **记忆网络/反思回路**（最大单文件 76KB）：体量远超本仓记忆模块，且定位不同（它解决「无限上下文」，本仓解决
  「日常对话 + 选择性记忆」），**不建议整体跟进**；其中三项（保底通道 / 钉住豁免 / 批次成本记账）值得吸收 —— **已于 §11.1 完成深评**（本地克隆 `e0f0b2d`）。

### D. 建议

**按价值排序**：① 循环守卫增加「探索/落地」维度（与现有签名查重并存，改动小、可单测）> ② 声明式规则表
（工程改造量大一些，但让守卫可检视、可调参）> ③ 指标化诊断（成本最低，作为调试面板）。
前两项相加约半天，且**均需自行实现、不可抄代码**（无 License）。

### 11.1 记忆机制深评（2026-10-14，本地克隆 `F:\build\projects\bigeye`，commit `e0f0b2d`）

§十一当时只做了远端粗看、并留了「记忆网络尚未深读」的尾巴。本节补齐，**只评记忆机制**。

**规模**（`find memory -name "*.py" | wc -l` 实测）：**16 个模块、8410 行**。（初稿此处写「约 7000 行」是 PowerShell `Measure-Object -Line` 计行偏低的误差，已更正。）\n`fragment_store.py`（1724 行）、`reflection_loop.py`（1406）、`file_crystal_store.py`（1123）、`knowledge_pages.py`（583）、`cmn_retriever.py`（582）、`summary_tree.py`（446）、`relation_store.py`（398）、`embedder.py`（366）、`cmn_viz.py`（358）、`file_slicer.py`（265）、`reflection.py`（256）、`entity_store.py`（250）、`msg_vectors.py`（245）、`extractor.py`（225）、`vec_index.py`（182）、`__init__.py`（1）。\n\n以下旧的数列残留一并更正：

#### A. 数据模型（`memory_fragments` 一张宽表 + 三张边表）

| 表 | 用途 |
|---|---|
| `memory_fragments` | 记忆主体。除正文/时间/embedding 外有 **weight、importance、layer、entity_ids、epistemic、authority_level、confidence_decay、node_type、crystal_parent_id、raw_source_id、summary_hash、raw_count、dirty** 等 20+ 列 |
| `memory_archive` | 衰减下沉区（`move_to_archive` 单向落档，可 `move_to_core` 捞回） |
| `memory_links` | 碎片间边（`link_type` + `weight`，含 causal 因果边） |
| `memory_reflection_log` | 反思批次日志：`input_chars / output_fragments / cost_usd` —— **把「整理成本」当一等数据记录** |

几个字段值得单独指出：
- `epistemic`（`experience` / `world` / `opinion`）—— **按「知识来源性质」分类**，不是按主题；
- `layer`（`core` / `archive` / …）—— 分层而非单一池；
- `authority_level`（0 普通 / 1 权威）—— 权威知识走**保底召回**（见 B③）；
- `raw_count` + `weight` —— 同一件事被反复观察到会累加，参与打分；
- `confidence_decay` + `last_hash_verified_at` —— 置信度带**可验证衰减**；
- `node_type`（`self` 自传晶体 / `file` 文件晶体）—— 自传记忆与文件记忆**同表**，靠类型分流。

#### B. 召回链路（`recall()`：候选 → 硬门槛 → 六因子 → MMR → 因果扩散）

**① 六因子相乘 + 一个「硬门槛」（本节最值得看的部分）**

```
score = recency × relevance × importance × weight × entity_bonus × topic_bonus
recency = max(0.5^(age_days/30), 0.3)          # 半衰期 30 天，有地板
importance = importance/10                      # 1–10 → 0.1–1.0
weight = weight × (1 + 0.1×min(raw_count,10))   # 复现次数加成
entity_bonus = 1.5（命中查询实体）              # ENTITY_BONUS
topic_bonus = 1.2 同任务 / 0.85 他任务 / 1.0 无归属
MMR_LAMBDA = 0.7
```

关键在**打分之前**还有一道 `RELEVANCE_HARD_FLOOR = 0.30`：余弦相似度低于它的候选**直接淘汰**。
其注释把动机写得很具体（原文）：

> 这是防「元晶体霸榜」的关键——元晶体因结构化文本 embedding 跟任何查询都有点相似（0.1–0.3），
> 但实际不相关，靠 importance=7/weight=3 综合分顶上来。

也就是说：**「重要性」不能把「不相关」救回来**。这条与 §十一 B③ 记的 `attention_metrics` 无关，是纯召回纪律。

**② topic_bonus 的非对称设计带算术论证**

同任务 ×1.2、他任务 ×0.85、无归属 ×1.0，且注释给出了边界验证：
`1.5 × 0.85 = 1.275 > 1.0` —— **实体强证据的跨任务碎片仍高于无实体命中者，不误伤真相关**。
不是拍脑袋的系数，而是先算过最坏情况再定值。该设计源自一次真实串台事故（注释：2026-09-04 串台修复）。

**③ MMR 多样性（λ=0.7），且用「真 cosine」而非综合分**

`_mmr_select` 明确用 `_relevance`（真余弦）算重复度，注释说明理由：综合分里含 importance/weight，
拿它做去重会让「同类高重要度碎片」互相压制、又让「不同类但同分」的混进来。
另有字段清晰化处理：检索结果同时给出 `relevance`（真余弦）与 `score`（综合分），并**显式删掉一个
曾误导下游的旧字段 `similarity`**。

**④ 因果扩散（一跳，置信度 ≥ 0.8）**

召回的碎片若命中查询实体，就顺着 `memory_links` 的 causal 边找对端实体、把对端碎片补进来
（最多 3 条，额外预算 `top_k + 2`，再按 id 去重）。即**不只召回「像的」，还召回「因此的」**。

**⑤ 保底召回 `recall_guaranteed`（对「永远该在场」的记忆给确定性通道）**

选 `layer=knowledge 且 authority_level ≥ 1` 的行为准则类记忆，按 `weight × importance` 排序、
候选间用余弦去重。原文动机（这是本条最有价值的地方）：

> 这类「永远该在场」的教训（如回复风格、验证纪律）**不能赌语义召回**——用户间接表达时
> （如「你明白了吗」暗示啰嗦）向量相似度过不了硬门槛。

即：**语义召回的硬门槛会误杀「用户没明说但应该在场」的记忆，所以必须给权威记忆一条绕过语义的确定性通道。**

**⑥ 钉住（pin）与永不过期**

`IMPORTANCE_PIN_THRESHOLD = 7.0`：重要度 ≥ 7 的碎片**永不参与衰减**、也不会被下沉到 archive。

#### A2. 深读复核（2026-10-14，逐条回查代码后的更正）

逐行精读后，**两条要下调措辞、两条要强调**，避免后续照抄错误结论：

- **下调**：`file_crystal_store.py:843` 的 `while current_layer < MAX_PYRAMID_LAYERS` **不是写错**，
  它是层数上限；真正的问题是 `TOP_LAYER_TOKEN_BUDGET = 5000` 字符的预算**对 layer 0 也适用**（855），
  而 layer 0 摘要总长通常远低于 5000 ⇒ 循环**第一次判断就 break**，金字塔对普通文件基本不触发。
  性质是「阈值使机制近乎不激活」，**不是「条件写错」**。
- **下调**：`fragment_store.py:348` 的 `best_id, best_sim = None, 0.0` 虽在 `if layer == "core":` 分支内，
  但**当前无调用点触发**（唯一暴露 `parent_id` 的 `remember` 工具用默认 `layer="core"`；`reflection_loop`
  的三处 `link_type` 走 `link_stories()`、不经过 `add()`）⇒ 它是**潜伏缺陷 + 死代码**，不能写成「会崩」。
- **强调（决定是否值得抄）**：`file_crystal_store.py:289` 在 INSERT 时把 `last_hash_verified_at`
  **直接绑成 `now`** —— 全新晶体**从未校验过文件，却已被记为「已验证」**。于是 `_compute_file_decay`
  对刚建的内容给出**虚假的 freshness**。**要抄 hash 校验，必须先改掉这一行**（留 NULL，或把字段拆成
  `createdAt` / `lastVerifiedAt`）。
- **强调（真丢数据）**：`fragment_store.add()` 的去重把软删除行也算候选（344-346 含
  `dirty=0 AND created_at > now-3600`），命中后只 UPDATE 权重、**不把 `dirty` 置回 1**，再
  `return best_id`（371）⇒ 调用方拿到一个**不可见**的 id，新记忆**静默丢失**。这是本轮唯一确认的
  「静默丢用户记忆」级缺陷。

#### C. 与本仓记忆机制的对照（结论性）

| 维度 | 本仓（`GenerationPrompts.memoryScore`） | bigeye（`fragment_store`） |
|---|---|---|
| 是否依赖当前查询 | **否**——注入与查询无关 | **是**——每轮按 `context_text` 现算 |
| 打分因子 | `effectiveImportance × (0.55 + 0.45×activity)`；importance 与**读取信用**（readCount 对数饱和）合成，activity 取「写入时效/最近读取」二者较大值 | `recency × relevance × importance × weight × entity × topic`（六因子相乘） |
| relevance 的地位 | **没有这一维**（记忆注入不做语义检索） | **是乘数因子，且有 0.30 硬门槛** |
| 读取反馈 | `readCount` 对数饱和 → 抬升 effectiveImportance（**读得多 ⇒ 更重要**） | `recall_count`/`last_recalled` 只记录，另有 `reinforce()` 显式加权 |
| 多样性 | 无 MMR（按分数分桶 + sortKey 排序） | MMR λ=0.7 |
| 时间衰减 | 半衰期 30 天，**且区分「时间戳未知」两侧语义**（写入未知=不衰减；读取未知=按 0） | 半衰期 30 天 + 地板 0.3，重要度 ≥7 钉住免衰减 |
| 「永远该在场」 | **无显式保底通道**；靠 importance 高 + `MIN_MEMORY_INJECTION_SCORE = 0.2f` 下限筛除 | 有 `recall_guaranteed` 确定性通道 |
| 提示词缓存稳定性 | **显式设计**：入选后按 `sortKey` 输出而非分数序，避免前缀每轮漂移废缓存 | 未见等价处理 |

**两边各有对方没有的东西**：本仓的「读取信用」与「sortKey 稳定前缀」是 bigeye 没有的；
bigeye 的「relevance 硬门槛」「保底通道」「MMR」「分层 + 权威级」是本仓没有的。
**这不是谁抄谁，而是两种取舍**：本仓选择了「不依赖查询的注入」（换来缓存稳定与实现简单），
bigeye 选择了「依赖查询的召回」（换来精度，代价是每轮现算 + 前缀漂移风险）。

#### D. 值得本仓吸收的（按价值，均可独立于「是否上语义检索」而成立）

> **⚠️ 2026-10-16 更正：本块不可再据此排期。** 写于 2026-10-14，其中 **①② 已在本仓落地**、
> **③ 经复核不建议做**、**④ 仍未做（本块唯一仍成立的一条）**；上表「没有 relevance 这一维」
> 一行也已失效。逐条状态与理由见 **§11.3 C**。

**① 「永远该在场」的保底通道（最值得）**
本仓现在只有「打分低于下限就**不**注入」这条**减法**（`MIN_MEMORY_INJECTION_SCORE`），没有**保底加法**。
后果是：一条被标为低重要度、或久未更新且已老化的行为准则（如「回答前先验证」），会**静默消失**，
恰恰在需要它的时候不在场。bigeye 的做法（权威级 + 确定性排序 + 组内去重）可以移植为本仓的
「**必注入**」名单，且**不需要引入 embedding**——它本来就走非语义通道。
这条即便本仓永远不上语义检索也成立，是本次评估里**唯一无条件推荐**的一条。**精读后进一步证实**：它也是 bigeye 这三条相关机制里**唯一真正通电**的一条 —— 实体加成 `ENTITY_BONUS=1.5` 因无人传 `query_entities` 而生产恒为 1.0，因果扩散首句即 `if not query_entities: return []` 属死代码（详见 §11.2 分组 D）。

**② 相关性硬门槛的「减法思想」已有、但可更彻底**
本仓已有下限筛除，方向一致（注释也写明借自华灯 RAG 相关性下限）。bigeye 更进一步的是**明确写下
「重要性不能把不相关救回来」这条纪律**——本仓若将来上语义检索，这条应先立。

**③ 衰减的「钉住」豁免**
本仓对「时间戳未知」做了双侧语义处理（比 bigeye 更细），但**没有「高重要度免衰减」**。
`IMPORTANCE_PIN_THRESHOLD` 是个便宜且直观的补充（配合 ① 使用更佳）。

**④ `memory_reflection_log` 的成本记账**
把整理批次的 `input_chars / output_fragments / cost_usd` 落表。本仓已有记忆整理（「做梦」四批）与
成本护栏，但**未把批次成本落库成可查数据**——这与「为什么没发」的可解释性是同一主张（见 §十一 B④）。

#### E. 不适用 / 不建议跟进

- **六因子相乘本身**：其 relevence 维依赖「每轮对全库算余弦」，本仓记忆注入刻意不做语义检索
  （见 `KnowledgeBaseEntity.kt` 注释：「与已否决的『注入时语义检索』不同物种」），故**该结构不适用**；
  可借的是 ①③ 这类**不依赖 relevance 的部分**。
- **CMN 晶体网络**（`file_crystal_store` 966 行 + `crystal_parent_id`「思维血统」+ `raw_source_id` 溯源）：
  为「文件切片 → 晶体 → 派生晶体」的长链溯源设计，体量与定位都超出本仓记忆模块，
  **不建议跟进**；其「派生关系可溯源」的思想与本仓知识库（文档 → 分块 → 向量）有重叠，可择日单独比对。
- **无 License ⚠️**（同 §十一 C）：**不得复制其代码**，以上均为思路层面，实现须自行重写。

### 11.2 逐模块精读（2026-10-14，16 文件 / 8410 行，本地克隆 `e0f0b2d`）

§11.1 是**跨模块结论**（数据模型、召回链路、与本仓对照）。本节是**逐模块清单**，供回查函数名与行号。

**方法与诚实边界**：8410 行由 5 路并行精读完成（分工见下表），**每条结论都带 `文件:行号`**。
其中以下几条我自己**回查过源码并复现**（其余为精读结论，未逐条复核）：`fragment_store` 的六因子/硬门槛/
`best_sim` 作用域/去重吞数据、`file_crystal_store` 的 `last_hash_verified_at=now` 与金字塔阈值、
`knowledge_pages` 的 list-绑-TEXT 崩溃（本机 sqlite3 复现）、行数实测。

#### 分组 A：碎片核心（`fragment_store.py` 1724 + `vec_index.py` 182）

**`fragment_store.py`** —— 自传体碎片的存储与检索核心，49 个函数，四表
（`memory_fragments` 35 列 / `memory_archive` / `memory_links` / `memory_reflection_log`，schema 62-149）。
关键函数：`add` 282、`recall` 439、`recall_guaranteed` 508、`_fetch_candidates` 547、`rebuild_index` 585、
`_five_factor_score` 609、`_mmr_select` 648、`_fetch_causal_neighbors` 688、`recall_archive` 758、
`move_to_archive` 813、`move_to_core` 841、`prune` 860、`_anchors` 892、`_effective_weight` 937（死代码）、
`reinforce` 942、`update_cmn_fields` 961、`promote_to_authority` 1002、`link_cores_to_knowledge` 1006、
`link_materials` 1113、`find_story_by_topic` 1139、`update_story` 1209、`find_similar_knowledge` 1268、
`update_observation` 1306、`bump_observation` 1372、`mark_contradiction` 1400、`link_stories` 1425、
`get_story_chain` 1449、`get_unconsolidated_cores` 1480、`count_unconsolidated_cores` 1501、
`get_by_hash` 1514、`edit` 1529、`stats` 1558、`get_chain` 1607、`get_graph` 1653。

**`vec_index.py`** —— `sqlite-vec` 的薄封装（12 个定义），扩展缺失时由调用方回退全表扫。
`load_vec_ext` 46、`_get_conn` 91、`ensure_table` 104、`insert` 126、`delete` 141、`query` 152。
作者最有用的踩坑记录（97-98）：

> sqlite-vec 是连接级扩展：每次新连接都必须重新 load，只在第一次加载会导致后续连接 `no such module: vec0`

#### 分组 B：记忆整理 / 反思（`reflection_loop.py` 1406 + `reflection.py` 256）

*（本组读数回报后补全。）*

#### 分组 C：分层摘要与消息向量（`summary_tree.py` 446 + `msg_vectors.py` 245 + `file_slicer.py` 265 + `embedder.py` 366）

**`summary_tree.py`** —— RAPTOR 式「月→季→年」递归摘要树 + Merkle 增量短路。`add_leaf` 142、
`_recompute_upward` 302、`_maybe_promote_to_parent_layer` 357。常量 `MAX_LAYER=2`(54)、
`PROMOTE_THRESHOLD=3`(55)、父摘要硬上限 `[:2000]`(390)。
- **实测全链路**（本机跑通）：喂 2025-01/02/03 → 自动建 `2025-Q1 [1,2,3]`；3 个季度后立即生成
  `2025` 年度节点；`stats={monthly:9,quarterly:3,yearly:1}`，跨年不串。
- ⚠️ **但它的 embedding 列从 Insert 到合并始终是 `'[]'`** ⇒ **这棵树不依赖语义检索**，
  与 README「只压缩 ✗」的自我批判正相反讽。
- ⚠️ **`_layers_below(period, layer)` 与调用点参数语义错配**（101-113 vs 371）：函数期望「子层」、
  调用传 `parent_lyr`；父层=1 时返回月份，而 SQL 绑定 `[node["layer"]]=0`（376）⇒ **凑巧对上**。
  实测两条晋级路径都跑通，但属巧合，任何后续改动会静默错配。
- ⚠️ **唯一调用点 `task/reflection.py:263-265` 没传 `llm_prompt_fn`** ⇒ 月度叶子只是 `"\n"` 拼接；
  「压缩」只发生在父层，而父层是 `sibling_texts[:2000]` **硬截断且无告警**。
- 作者原话（325-327，全文最值得抄的一条注释）：`LIKE` 单独用会命中子串（`id=5` 命中 `[15,25]`），
  只能当粗筛，再用 Python 精确比对。

**`msg_vectors.py`** —— 给 `role='user'` 消息建向量缓存，跨话题召回「用户别处说过的原话」。
`index_user_message` 85、`_load_cache` 108、`recall_related_dialogs` 130、`recall_with_context` 182。
- 作者的容量论证（109）：`2039 条 × 512 dim ≈ 4MB，可接受` —— 先算内存账再选暴力方案的范例。
- 表是 KV（`user_msg_vectors(message_id PK, vec BLOB, ts)`），**无维度列、无外键、无级联**。
- 实测（本机 TF-IDF 后端）：相关句 0.6472 / 无关句 0.0769，**与默认阈值 0.30 同量纲**。
- 🔴 **与本仓方向相反**：它在 `db.py:700-703` 的**写消息热路径上同步 embed**，且在 ONNX 路径全程持
  `_onnx_infer_lock`（`embedder.py:99-108`）⇒ 每条用户消息串行一次推理。

**`file_slicer.py`** —— 按类型分派切片：代码按顶层定义边界（14 条行首正则，64-80）、文档按标题/空行、
其他定长 `2000 + 200 重叠`。常量 `DEFAULT_SLICE_SIZE`/`DEFAULT_OVERLAP`/`CODE_MAX_SLICE=4000`/
`DOC_MIN_SLICE=200`（30-33）。
- 实测：5000 字符 → `(0,2000)(1800,3800)(3600,5000)`，重叠恰 200；**但单标题文档因
  `len(matches)>=2` 不成立而整体落到 generic**（实测 `[(0,'<chunk 0>',0,529)]`）。
- 🔴 **`index` 是位置序号却被下游当「内容同一性」**：文件开头插一个函数 → 后续 index 全平移 →
  `cmn_retriever:435-460` **误报「内容变了」并重置 decay**。
- 作者自标质量（62-63）：`# 简化版：匹配行首的 def/class/…` —— 实测 java 正则**匹配不上
  `public void foo(`** 这种最常见写法。

**`embedder.py`** —— 全项目 embedding 唯一入口，四级链 + 结果缓存 + 余弦 + 零向量降级。
- **实际链条**：`0.ONNX(BGE-small-zh, 512)` → `1.哈希 TF-IDF(512)` → `2.Ollama(768)` → `3.OpenAI(1536)`。
  ⚠️ **docstring（5-10）写的编号与实现整体不符**，且 `_st_embed`/`_load_st_model` **根本不在链上**。
- 🔴 **维度割裂事故（作者识别了、只修了一半）**：两处原话互证 —— 75-76「静默 fallback 到 TFIDF(256)
  会导致维度割裂（**历史根因之一**）」+ 123「= 512，对齐向量表/零向量维度，**避免 fallback 后余弦恒 0**」。
  但 Ollama 是 768、OpenAI 是 1536 且 model 硬编码（262-268），而 `cosine_sim` 长度不等
  **直接返回 0.0**（348-349）⇒ 这些向量写进库后**永久无法召回且零报错**；`embed()` 出口**无维度校验**。
- 🟠 **IDF 子系统是死代码**：`_tfidf_add_document`（183-192）全项目零调用 ⇒ `idf` 恒 1.0，
  注释承诺的「IDF bonus for rare terms」是空话；`cache_stats`/`clear_cache` 亦零调用。
- ✅ **最有价值的一条（`:11`）**：`Backend 0 guarantees memory recall works without any external
  dependencies.` + `:335` 的降级语义 `zero vector → memory system degrades to anchor-only` ——
  **「降级不消失」**：没有可用模型时应退回纯本地、零依赖、亚毫秒的哈希 TF-IDF（512 维 float32
  仅 2KB/条），而不是让召回整体失效。
- 作者实测结论（91-92 / 38）：HuggingFace tokenizers 的 `encode()` **不是线程安全**的，
  故用 `_onnx_infer_lock` 串行化推理（cache 命中不加锁）。
- 离线教训（209-210）：`# 网络差时这些检查每次超时 20s×重试，会阻塞服务启动数分钟。`
#### 分组 B：记忆整理 / 反思（`reflection_loop.py` 1406 + `reflection.py` 256）

**`reflection_loop.py`** —— 「反思回路」：**无调度、纯批处理**，一次 `run()` 顺序跑 **11 步**
（backfill_entities → extract_file_thoughts → build_weak_associations → emerge_meta_crystals →
prune_entropy → detect_gaps → promote_authority → link_knowledge_cores → narrative_consolidation →
build_story_weak_assocs → consolidate_observations，115-171，每步独立 try/except 只记 errors）。
关键函数：`build_weak_associations` 179、`emerge_meta_crystals` 252、`prune_entropy` 379、
`detect_gaps` 416、`promote_authority` 462、`link_knowledge_cores` 515、`narrative_consolidation` 548、
`consolidate_observations` 754、`build_story_weak_assocs` 960、`extract_file_thoughts` 1104、
`backfill_entities` 1266。

**触发与调度（跨文件取证）**：本文件**自身无任何触发器**。6 个入口中只有一个是自动的 ——
`server.py:7686-7713` 的 `_lightweight_narrative`（每次 `/api/chat` 收尾起 daemon 线程），门控为
**「距上次 ≥7200s 且 `count_unconsolidated_cores() >= 30`」**，且只跑 `narrative_consolidation()`。
作者原话（server.py:7681-7685，整段注释）记录了**为什么把自动反思关掉**：

> 2026-08-17 已禁用：自动反思产出的碎片平时不加载，**对行为零约束**，且每次对话后烧 LLM 调用；
> 行为修正改由重要事项第 12 条承担，需要时手动调 reflect 工具/API

- **阈值全部按自身 embedding 维度标定，且注释说明了为什么不能照抄**：弱关联 0.65
  （「256 维 embedding 区分度有限，**0.75 太严**」32）、元晶 0.65（「**0.80 太严**」36）、
  故事聚类 0.72（41）、故事弱关联 0.70（46）。
- ✅ **元晶降权防霸榜**（328-335）：元晶 `importance` **刻意从 7.0 降到 5.0**，注释
  「不靠加成霸榜」。这是一条**给派生/聚合记忆的纪律**。
- ✅ **每动作独立配额**：20 边 / 5 元晶 / 3 故事 / 10 文件思考 / 30 观察 / 50 实体 ——
  保证每类动作都有机会、也都各有天花板。
- ✅ **先时间分桶再 O(n²) 聚类**（575-581）：`BUCKET_SECONDS = 24*3600`，注释记下了教训 ——
  原全局混窗口会把相隔数天的碎片配成「**横跨数天的杂烩故事**」。
- ✅ **长事务禁忌**（1189）：`conn.close()  # ← 关键：查完就关，不持 conn 调 LLM`。
- ✅ **组向量均值匹配聚合体**（652-653）：用簇内 embedding 均值去匹配 story，而不是用 topic 短文本，
  理由是「同维度对同维度」。
- 🔴 **`update/merge/contradict` 缺 `target_id` 时 → skipped 但素材仍被标 `dirty=0`**（886-897）——
  **静默丢弃**，本组最实质的数据损失风险。
- 🔴 `PRUNE_STALE_DAYS = 30`（51）**代码中从未引用**（死常量），「30 天未验证的边降权」功能不存在。
- ⚠️ **元晶与文件思考未指定 `layer` ⇒ 默认 `core`**（331-340、1236-1245），会回流进弱关联/故事/观察管线。
- ⚠️ 弱关联**单向建边**（233-241）⇒ 对端仍被判定「孤立」、每轮重选重建；且建边前不查重，
  跨运行重复插入靠 `prune_entropy` 擦屁股。
- ⚠️ `promote_authority` 的「多源印证」SQL 与注释不符（493-503）：注释说用 `entity_ids`，实际用
  `text LIKE`，且统计**含自身** ⇒ 「≥3」实际只有 2 个外部来源，还是 O(N²) 扫描。
- ⚠️ `consolidate_observations` 解析失败默认 `create`（928-931），与注释宣称的「有相似观察→merge」不符。
- ⚠️ 一次全跑最坏 LLM 调用 ≈ 10+5+3+30 = **48 次**，叠加 embed 上百次；
  **真正的护栏只有调用方的 5 分钟节流**。

**`reflection.py`** —— 「深度反思 agent」（手动/工具触发）：把各话题最近 15 条消息拼成**一个大 prompt**
交 LLM，按 `<<<碎片记忆>>>` / `<<<因果>>>` 成对分隔符切分落库（`source=deep`/`causal`），随后自动跑一次
`reflection_loop.run()`。`trigger_deep_integration` 110、`_run_deep_integration` 125、`_store_fragments` 210、
`_format_messages` 224。
- ✅ **checkpoint 增量 + 「纯机械判断不改 prompt」原则**（139）：`msgs[-1]["id"] <= last_id` 就跳过，
  输入不变则输出质量不变 —— 这是**便宜且正确的增量游标**。
- ✅ 提示词外置（`data/reflection_prompt.txt`）+ 内置 fallback（37-45），热改无需重启。
- 🔴 **反面教材（本组最该记的一条）**：`_llm_prompt` 异常时返回 `""`（92-94），
  而 checkpoint **在失败路径上照样推进**（156 → 186）⇒ **这些消息永远不会再被反思**。
  **「失败绝不能推进增量游标」** —— 这是可以直接写进本仓纪律的一条。
- 🔴 `any(v for k, v in report.items() ...)` 因 `observations_consolidated` 是非空 dict **恒为真**，
  守卫失效（176）。
- ⚠️ **`msgs` 升序 + 超预算 `break` ⇒ 只包含最旧 2-3 条，最新消息反而看不到**（226-236），
  与「回顾刚才的对话」的意图冲突。
- ⚠️ 全话题混进一个 prompt 且落库 `topic_id=None` ⇒ **话题信息永久丢失**；两源共用同一 `ts`，
  因果句与碎片句相似度 >0.85 时被 dedup 成同一条，**causal 语义丢失**。
- ⚠️ `<<<因果>>>` 的提示词示例无闭合但解析要求成对（26-27 vs 157）⇒ 因果静默丢失。
- ⚠️ 提示词文件可被整文件替换而**解析器写死**（40-42 vs 156-157）⇒ 自定义格式即 0 条且无告警。

#### 分组 D：实体与关系（`entity_store.py` 250 + `relation_store.py` 398 + `extractor.py` 225 + `knowledge_pages.py` 583）

**⚠️ 本组最重要的结论是「通电率」——三条机制里只有一条真的在生产路径上。**

| 机制 | 公式/实现 | 是否通电 |
|---|---|---|
| 实体加成 `ENTITY_BONUS = 1.5`（44、625-631） | 需要调用方传 `query_entities`（439） | ❌ **恒为 1.0**：唯一传入点是调试接口 `server.py:5318`；真实注入路径 `server.py:6612` 只传 `recall_ctx/threshold/top_k/current_topic`。佐证：`EntityStore.search_by_embedding` 全项目零调用 ⇒ **「消息→实体」这一步从未实现** |
| 因果扩散（688-756） | 第一句就是 `if not query_entities: return []`（697） | ❌ **死代码**；且 SQL 是 `subject IN OR object IN`（709-716）**方向不可区分** ⇒ 提到「结果」会召回「原因」的**逆因果** |
| 权威/保底召回 `recall_guaranteed`（508-546） | `layer='knowledge' AND authority_level>=1`，`ORDER BY weight*importance`，`cap=2`，家族去重 `cos>0.70` | ✅ **唯一完整落地**：受控提拔（965-971 禁止 AI 自填）、**真接到注入**（`server.py:6654-6657`），注入层另有 `authority_bonus 1.5`（6637） |

**最严重的 5 个实证缺陷**：

1. 🔴 **`knowledge_pages.py:200/215-218` 把 list 绑给 TEXT 列** —— `content = obs_texts`（list），
   注释 201 承诺的「LLM 不可用时用原文拼接」**从未实现**（应是 `"\n".join`）。
   **本机 sqlite3 复现**：`ProgrammingError: Error binding parameter 1: type 'list' is not supported`。
   无参 `KnowledgePageStore()` 成文必崩。
2. 🔴 **`extractor.py:28-30` 先绑定 `api_key`/`base_url` 局部变量，44 才调 `_try_load_config`（只改 dict）**，
   48/53 用的是**过期局部变量** ⇒ `model_config.json` 里的凭证**永远不生效**，
   请求以空 Bearer 发出，**抽取全部静默返回空**。
3. 🔴 **`entity_store.py:105-110` upsert 不重置 `status`，而 `reactivate`（230）全项目零调用**，
   检索一律 `status='active'` ⇒ 180 天未提及的实体被 `deactivate_stale` 后**永久退出召回集且不可恢复**。
4. 🔴 **`entity_store.py:159-161` `get_or_create` 命中即返回**，不递增 `mention_count`、不更新 `last_seen`、
   不写 `aliases`，而 `task/reflection.py:320/348/464` 是实体主写入路径 ⇒ 热度与活跃时间失真，
   连锁导致**误停用**。
5. 🔴 **`relation_store.py:150-155` 顶替键只有 `(subject_id, predicate)`** ⇒ 多值谓词被误顶替
   （「养猫」被「养狗」顶掉），且**重复提及同一事实也产生 `superseded` 垃圾行**（无幂等路径）。

**其他成组发现**：`MAX_OBS_PER_PAGE=12`（47-48）零引用且与 167「不截断」注释矛盾；
`RELATION_CONFIDENCE_MIN=0.5`/`CAUSAL_CONFIDENCE_MIN=0.7`（27-28）零引用而 `task/reflection.py:373-374`
硬编码同值；`relation_store.py:53` 的 `embedding` 列**只写不读**；`max_hop` 参数（208）在 SQL 中未使用；
`aliases` 只写不查；`knowledge_pages` 零向量护栏失效（141 只判 `not emb`，对比
`fragment_store.py:452` 有 `all(v==0)` 的正确写法）；`extractor.py:145/172` 无 markdown 围栏剥离
（同仓 `knowledge_pages.py:489` 已有正则）；`text[:3000]` 丢弃长对话尾部（143/170）。

**量纲可直接对齐（落地时最省事的一点）**：bigeye 的 `importance` 同为 **1–10**
（`extractor.py:197-199` 的锚点提示词、`fragment_store.py:641` 除以 10），且它用的是**乘性结构**
（`fragment_store.py:644` 六因子连乘）—— 所以 `ENTITY_BONUS=1.5` 这类 bonus 可以**直接再乘到本仓的
`effectiveImportance × (0.55 + 0.45×activity)` 上，不破坏量纲**。但必须**补上 bigeye 缺的那一步**：
把「用户消息 → 实体名匹配」接在注入前（纯名字精确/前缀匹配即可，不要依赖向量），
否则会复刻它「公式就绪、输入恒空」的装饰性代码。

#### 分组 E：材料侧记忆（`file_crystal_store.py` 1123 + `cmn_retriever.py` 582 + `cmn_viz.py` 358）

**`file_crystal_store.py`** —— 材料侧记忆全生命周期：切片 → 算 hash → 可选 LLM「三问压缩」→ 金字塔分层与血统
→ 以 hash 为准检失效。31 个函数。`build_file_crystals` 225、**`build_slices_only` 316（无 LLM 版）**、
`get_file_status` 430、`get_thoughts_for_path` 511（血统桥）、`detect_hash_changes` 587、`recall` 668、
`build_pyramid` 817、`drill_down` 945、`drill_up` 962、`get_pyramid` 979、`read_top_layer` 1018、
`propagate_stale_upward` 1056。常量 `MAX_PYRAMID_LAYERS=5`(813)、`DEFAULT_PACK_SIZE=4`(814)、
`TOP_LAYER_TOKEN_BUDGET=5000`(815)。
- ✅ **`build_slices_only` 的三阶段拆分**（340/371/400/404），作者原话：
  「阶段 1：查重 + 算 embedding（**不持写锁**，避免 embed 耗时阻塞其他写入）」→「查完就关，不持锁调 embed」
  →「**先提交主事务，释放写锁**」→「阶段 3：更新 vec_index（**commit 之后**，避免跨连接死锁）」。
  配套立场：**「vec 索引可随时 rebuild，失败只影响 KNN 加速，不影响主数据写入」** ——
  把派生索引定位为**可重建的缓存**。
- ✅ **作者把 hash 主动划出「晶体」范畴**（320-322）：

  > 切片+hash 是基础设施（定位+变化检测），**不是晶体**；真正的晶体（理解/结论）由 AI 思考产生，
  > 存在 `memory_fragments` 表；`raw_source_id` 桥将自传晶体指向切片

- 🔴 **`recall()` 的 KNN 分支绕过 `status` 过滤**（684-691 vs 712-713），且 **KNN 路径无相似度下限**
  而 fallback 路径有 `sim > 0.1`（706）⇒ 两路结果语义不可比。
- 🔴 **`recall` 硬编码 `layer=0`**（698）⇒ **金字塔上层永不参与召回**，`build_pyramid` 的产物
  只能靠 `read_top_layer`/`drill_*` 使用，「压缩上下文」的设计意图**未在召回路径兑现**。
- ⚠️ `len(pack) < 2` 时 `continue`（872-874）⇒ 这些晶体**永远没有 parent（孤儿）**；
  上层 `crystal_parent_id` 又硬写 NULL（905）⇒ **中间层也成了「根」**。
- ⚠️ 查重限定 `status='active'`（259-263）⇒ 同 hash 的 stale 晶体不复用，文件改了又改回会重复插入整套。
- ⚠️ `get_pyramid` 的 `build_node`（1003-1004）双层线性扫 ⇒ **O(N²)**。

**`cmn_retriever.py`** —— CMN 统一检索**门面**（自己不做向量计算）：聚合自传/文件/消息三路 + 关系边走边扩展。
`retrieve` 78、`walk_edges` 215、`_get_neighbors` 244、`_expand_via_edges` 294、`_enrich_with_decay` 335、
`_compute_file_decay` 361、`refresh_decay_all` 376、`verify_hash` 410、`_verify_file_crystal` 421、
`_verify_self_crystal` 476、`stats` 521。
- 🔴 **三路结果从不归一化**，只按「**消息 > 自传 > 文件**」的顺位拼接（消息靠 `insert(0)` 抢名额，125-129），
  `top_k` 截断语义含混。
- 🔴 **`_enrich_with_decay` 判别缺陷**（335-359）：`_compute_file_decay` 读 `last_hash_verified_at`，
  碎片行没有该键 ⇒ `.get()` 返回 None ⇒ **decay = 0.3**，随后被打上
  `_decay_reason = "长时间未验证 hash"` ⇒ **自传晶体被误报 decay 警告**，且 0.3 写回 dict 影响下游。
- ⚠️ `refresh_decay_all` **只覆盖 file_crystals**（388）⇒ 自传记忆永不衰减；
  而 docstring（8-10）宣称自传 decay 由「被多少新晶体引用/否定过」驱动 —— **注释与实现不符**。
- ⚠️ `stats()` 的 `self_authority` 无 `dirty=1` 而 `self_total` 有（529-534）⇒ **分子可能大于分母**。
- ⚠️ `m["id"] = m.get("message_id")`（128）与碎片/文件 id 空间**可能数值撞车** ⇒ 去重时误删碎片命中。
- ⚠️ `walk_edges` 对每个邻居 `get_by_id` 新建连接（236-240）⇒ **N+1 查询 × hops**。

**`cmn_viz.py`** —— 把 CMN 数据构造成 **X6 图 JSON**（纯读、无 LLM、无向量），
`build_network_graph` 27、`build_relations_graph` 182、`get_pyramid_tree` 286、`pyramid_to_x6` 292、
`list_indexed_files` 337。**价值是人肉调参与 debug**（作者的阈值就是这么调出来的）。
- 🔴🔴 **这张图最容易骗人**：节点查询是 `ORDER BY layer DESC, slice_index LIMIT 100`（52-58）
  ⇒ **优先取金字塔高层，layer 0 切片被挤出**；而所有边都要求两端都在节点集内（111/126/148），
  父子不齐即 `continue` **且无提示** ⇒ 规模稍大的库退化成「一堆孤立节点」。
- 🔴 **`crystal_parent_id` 有三种 id 形态**（`str(knowledge_id)`、story id、`fc_*`），而 self 节点加了
  `frag_` 前缀 ⇒ **只有 `fc_*` 能命中** `node_ids`，**derive 边多数画不出来** ⇒
  **不能据这张图认为「bigeye 没有血统」** —— 血统在数据层（`link_cores_to_knowledge`）是真实写入的。
- ⚠️ `build_relations_graph` 不过滤 `valid_to IS NULL`（194/211）⇒ 已被取代的事实照样上色。

#### 关于 `hash` 校验机制（跨文件完整链条，本组最高价值的一条）

| 阶段 | 位置 | 行为 |
|---|---|---|
| 算法 | `file_crystal_store.py:139-141`；`fragment_store.py:386-388` | `sha256(utf-8)[:16]`（64bit）；文件用**切片原文**，自传用 `text` |
| 写「验证时间」 | **289 / 397** | ⚠️ **INSERT 时 = 建设时间，不是真验证** |
| 被动校验① | `get_file_status:467-484` | read_file 时重切比对；**只报告不落库** |
| 被动校验② | `detect_hash_changes:597-652` | 比对后把旧晶体置 `stale` |
| 主动校验 | `_verify_file_crystal:421-474` | 一致 → `decay=1.0`；不一致 → **`stale` + `decay=0.0`** |
| 自传校验 | `_verify_self_crystal:476-517` | 不一致 → **只 `decay=0.5`，不置 stale** |
| 向上传播 | `propagate_stale_upward:1056` | 沿 parent 链全部置 stale（**只改 status**） |
| decay 衰减 | `_compute_file_decay:361-372` / `refresh_decay_all:376` | `0.5**(age/30)`，下限 0.1，从未验证=0.3；**仅 file_crystals** |

**诚实的结论：没有后台定时扫描。** `refresh_decay_all` 只重算数字、**不重读文件**。真正读盘校验只有两处：
①再次 `read_file` 同一路径 ②显式调 `/api/cmn/verify`（`server.py:5515-5523`）。
所以「原文变了但没人读」期间 stale 标记**不会自动出现，但 decay 会随时间下降** ——
**这才是 decay 存在的真正意义：用时间代替扫描，近似表达「可能已过期」**。

### 11.3 三件套评估：反思回路 / 记忆网络 / 连续思维（2026-10-16）

> **起因**：用户问「反思回路、记忆网络与连续思维能力等我们考虑自己的实现？」。§11.1/§11.2 评的是
> bigeye 的 `memory/`；本节把 README/CHANGELOG 里那串名词（工作记忆层、注意力窗口、连续思维链、
> 记忆网络、心智锚点、反思回路、任务执行层）**还原成具体代码**，再逐条判「本仓要不要自己实现」。
>
> **一句话结论**：三件里**两件不该新建子系统**（本仓已有等价物、且定位更正确），**第三件只缺一个很小的真缺口**；
> 而顺着「尾部注入后能否重上语义检索」这一问做**真机度量**后，进一步发现**判据不是杠杆、预算是杠杆**。

**方法与诚实边界**

- bigeye 侧：本地克隆 `F:\build\projects\bigeye`（`e0f0b2d`）+ §11.1/§11.2 已有取证；本节**新增**读 `task/`（**3669 行**）。
- 本仓侧：两路**并行只读代码调查**（推理链路 / 记忆子系统）产出，**本节引用的关键行号我逐条回查过源码**；
  少数标 ⚠️ 的是未逐条复核项。
- §11.3 E 是**真机度量**：`adb` root 转储 `rikka_hub` + `-wal`，本机 sqlite3 读；选取逻辑按生产函数**逐行复刻**，
  token 用真 `cl100k`（非启发式）。脚本与转储在 `_measure/`（**已 gitignore，绝不入库**——含用户私人记忆）。
- bigeye **无 License**（同 §十一 C）：本节全部为**思路**层面，落地必须自行重写，**不得复制其代码**。

#### A. 还原：名词 → 代码 → 本仓对应物

| bigeye 的名词 | 代码实体（行数实测） | 本仓对应物 | 判定 |
|---|---|---|---|
| 连续思维链 | `task/thought_chain.py`（**仅 154 行**：每任务一条只追加 jsonl + 热层最近 5 条）；`server.py:6512` 每轮注入热层；`tools/task_v4_tools.py` 暴露 `append_thought_step`/`get_thought_chain` 让 **AI 自己写** | 推理块**回传**（`includeHistoryReasoning`）+ 压缩检查点 | 传输层已够，见 D |
| 工作记忆层 | `task/work_memory.py` 382 行（`open/solved/escalated/closed` 的 question/blocker/uncertain 队列） | `todo_write` 计划——**只进 UI 与规则引擎，不进提示词** | ⚠️ **真缺口（唯一）**，见 D |
| 注意力窗口 | `task/attention_focus.py` 268 行（Cowan 4±1 → 5 blocks + slide_in/out） | `ContextBudgetPlanner`（**仅无头 run**）+ 自动压缩 | 形态不同，不跟 |
| 任务执行层 | `task/dag.py` 1182 + `planner.py` 304 + `executor.py` 595（含 X6 流程图导出） | `todo_write` + 子代理 | 不跟（见 E） |
| 反思回路 | `memory/reflection_loop.py` 1406 + `memory/reflection.py` 256 + `task/reflection.py` 701 | `MemoryConsolidation*`（「做梦」） | **不做**，见 B |
| 记忆网络 | `memory/` 16 模块 **8410 行** | `MemoryEntity` 单表 + 打分（**无任何记忆间关系**） | **不做网络**，见 C |
| 心智锚点 | anchor block（`server.py:6500` 一带） | ✅ L1 锚点 + `Conversation.frozenAnchors`（2026-10-15/16 落地） | 已有，且边界更严 |

#### B. 反思回路：不做

**决定性证据不是我的判断，是作者自己的注释**（`server.py:7681-7685`，2026-08-17）：

> 自动反思产出的碎片平时不加载，**对行为零约束**，且每次对话后烧 LLM 调用；行为修正改由重要事项第 12 条承担

也就是说：他把「修正行为」交给了**规则/纪律**——正是本仓上一轮 RuleEngine 在做的事；把「整理」降级为手动
`reflect` 工具/API。**这条路他自己走过并退回来了**，我们没有必要再走一遍。

本仓的等价物 `MemoryConsolidation*` 定位**更正确**：产物是**显式可编辑、参与注入打分的记忆**，不是不加载的碎片。
且它的框架（调度策略 + worker 接口 + 审批 + 水位）2026-10-09 已落地。

该吸收的是**三条纪律**，不是回路（逐条核对结果）：

| 纪律 | bigeye | 本仓现状 | 判定 |
|---|---|---|---|
| **失败绝不推进增量游标** | 🔴 `reflection.py:156→186`：`_llm_prompt` 异常返回 `""`，而 checkpoint **在失败路径照样推进** ⇒ 这些消息**永远不会再被反思** | ✅ `MemoryConsolidator.kt:115-116` 注释「agent 抛异常时根本走不到这里（内容不丢）」，且 `MemoryConsolidationAgent` **全程无 catch** ⇒ 异常直接抛出；失败还会把该记忆库放进 `failed`、不再自动重试 | **已符合**——但**这是注释不是保证**，建议补一条证伪测试 |
| 派生/聚合记忆不能靠加成霸榜 | ✅ 元晶 `importance` 刻意 7.0 → 5.0（注释「不靠加成霸榜」） | ⚠️ 整理产物与人写记忆**同走一个 `memory_tool`**，importance 可自填到 1.0，**不分「观察」与「再概括」** | 可做，成本低 |
| 批次成本记账 | ✅ `memory_reflection_log(input_chars / output_fragments / cost_usd)` | ⚠️ 整理有成本护栏，但**未把批次成本落库成可查数据** | 可做，收益=可解释性 |
| 先时间分桶再 O(n²) 聚类 | ✅ `BUCKET_SECONDS = 24h`（教训：全局混窗会配出「横跨数天的杂烩故事」） | n/a（本仓不做跨对话聚类） | 不适用 |
| 长事务禁忌（查完就关，不持连接调 LLM） | ✅ `reflection_loop.py:1189` | ⚠️ 未核（本仓不用该连接模式） | 不适用 |

**额外记账（第二条风险面）**：本仓整理**会删记忆**（`MemoryConsolidationGenerationAgent` 的 `onDelete →
memoryRepository.deleteMemory`），而**提案只活在进程内存**（`MemoryConsolidationScheduler.kt:126-135`，
重启即失），**没有审计轨迹**。即：删了没有账。这条与「失败不推进水位」是同一族问题的另一半。

#### C. 记忆网络：不做网络（并更正 §11.1 D）

**理由不是体量（8410 行），是通电率。** §11.2 分组 D 已取证：这套网络的生产路径**只有 1/3 真通电**——

- 实体加成 `ENTITY_BONUS = 1.5` 因**无人传 `query_entities`** 而生产恒为 1.0；
- 因果扩散首句即 `if not query_entities: return []`，**死代码**；
- 「消息 → 实体」这一步**从未实现**（`EntityStore.search_by_embedding` 全项目零调用）。

再叠 5 个实证缺陷（`knowledge_pages` 无参构造必崩、`extractor` 凭证永不生效 ⇒ 抽取静默全空、
实体停用不可恢复、`get_or_create` 不更新热度、关系顶替键过窄）。**抄一个 2/3 没通电的系统不划算。**

**更正 §11.1 D（本块写于 10-14，之后两天内三项里的两项已落地）**：

| §11.1 D 的推荐 | 10-14 的判断 | **10-16 实测状态** |
|---|---|---|
| ① 「永远该在场」保底通道（当时称**唯一无条件推荐**） | 缺 | ✅ **已落地**：L1 锚点（`is_anchor` → system 块 + 会话内 `Conversation.frozenAnchors` 冻结），2026-10-15/16（`design-memory-injection-tiering.md`）⇒ **该推荐已失效，勿重复立项** |
| ② 相关性硬门槛 | 「若将来上语义检索」 | ✅ 已落地为**词法** relevance（`MemoryRelevance.kt`，**非 embedding**；2026-10-16） |
| ③ 高重要度免衰减（`IMPORTANCE_PIN_THRESHOLD`） | 建议补 | ❌ **建议不做**：与本仓**用户显式「固化」**功能重叠，且自动 pin 会**削弱**「只有用户能指定永远在场」这条边界——`MemoryRepository.addMemory` 刻意不暴露 `isAnchor`，`data/ai` 与 `service` 全目录 grep **零命中** ⇒ AI 无自填路径。（bigeye 自己也把 `promote_authority` 做成**受控提拔、禁止 AI 自填**，两边同向，本仓的形态更明确。） |
| ④ 批次成本记账 | 建议补 | ⚠️ **仍未做**——记忆网络这一支**净可动项只剩这一条** |

**「重上语义检索」这一问的重新评估（用户 2026-10-16 提出）**：用户的论点成立——**缓存那一半理由已经失效**，
而且本仓**已经兑现了它**：设计稿原话「**为什么尾部就能用相关性**：尾部怎么变都不动它前面的前缀，于是选取
可以依赖本轮查询、每轮重算」（`design-memory-injection-tiering.md` §3.2），词法 relevance 即其落地。
但当初否决 embedding 有**三条腿**，缓存只是最早失效的一条：

| 腿 | 10-16 状态 |
|---|---|
| 前缀缓存 / 前缀抖动 | ❌ **已失效**（尾部注入） |
| **可用性被绑成有条件的** | ⚠️ **仍成立**：`generateEmbedding` 全仓**只有 `OpenAIProvider` 实现**，基类直接 `error("Embedding generation is not supported")`。记忆注入是**核心功能**，让它依赖「你的提供商恰好有 embedding 模型」＝给核心功能加一个静默降级面 |
| **换 provider ⇒ 相关性静默归零** | ⚠️ **仍成立**：`cosineSimilarity` 维度不匹配**直接返回 0**，注释写明「不同 embedding 模型的向量不可比，按不相似处理」——**无告警** |
| **量到的瓶颈不是排序** | ⚠️ **仍成立且更强**：见 E —— 覆盖度由预算决定、与判据几乎无关；语义检索改善的是「选得准不准」，**不会让不可见的那 23 条出现** |
| 同源先例 | ⚠️ Yuihub（同源 fork）上线语义检索后**第二天整套删除**（`a8c33b2d`→`fbd15331`）——非决定性，但同源同量级，该先问「它删的原因在本仓成立吗」 |

**结论：embedding 的触发条件应重写成可证伪的形式**（例：同一查询集、同一 top-k 下，词法 relevance 的 recall
**明显低于**一个可用 embedding 模型，且用例集含跨语言/同义改写），**而不是继续用一条已经失效的缓存理由当门闩**。

##### C.1 「语义 + 字面叠加、分配权重」该怎么判（2026-10-16，用户提问）

> 用户问：「（二者叠加？语义+字面，分配权重？）」。**先更正前提**：本节 E3 原先据以说「词法在真实用法下
> 常空转」的那批查询**是测试语料**（用户 2026-10-16 指出），该结论已撤回 ⇒ 现在**没有任何证据**说词法在本仓
> 实际用法里不够用，也**没有任何证据**说它够用。**当前最大的未知不是「怎么加权」，而是「词法准不准」——
> 度量只回答了「判据是否影响覆盖度」，没回答这个。**

**① 两种信号的失败模式是互补的，这决定了叠加方式**

| 信号 | 强在 | 弱在 |
|---|---|---|
| 词法（现有一切：`MemoryRelevance.kt`） | 同语言、共享实词、精确串（「TikZ」「咖啡」「涉痕觅隙」这类专名尤其强） | 跨语言、同义改写、缩写、指代（存「忌辛辣」查「不吃辣」查不到） |
| 语义（embedding 余弦） | 上面那一列 | 精确串/数字/标识符（短文本正是嵌入区分度最差处，见 `design-auto-compaction.md` §6.3）、以及 provider 条件与维度不可比 |

**② 放哪一层，决定要不要付那三项代价**（C 表里的三条腿只对「每轮都算」成立）

| 层 | 付费方式 | 判断 |
|---|---|---|
| 每轮注入（L2 全文层选取） | 每轮一次 query embedding + 每 provider 可用性 + 换 provider 静默归零 | ❌ **不建议** |
| 清单层判据 | 同上，且 E 已证「判据不是杠杆」 | ❌ **不建议** |
| **`search` 动作（按需）** | **每次检索一次 embedding**——由模型主动发起 ⇒ 成本有界且自愿；**零缓存交互**；不可用时回落词法 | ✅ **真要上就上这里** |
| 用户侧记忆搜索 UI（**尚不存在**） | 同 `search` | 可与 `search` 同批 |

**③ 权重怎么分（直接回答）**

- **先别分。** 两种信号的量纲不同（词法是「覆盖查询词元的**比例** ∈ [0,1]」，语义是**余弦 ∈ [-1,1]**），
  直接加权平均会把两件不同的事混成一个数，而**系数没有来源可依**（本仓没有语料可校准，
  硬凑一个只会引入没人能验证的假设——这与 `MemoryRelevance.kt` 里拒绝硬凑停用词表是同一条理由）。
- **第一步应该是并集召回 + 各自打分，不做加权和**：`relevance = max(lexical, semantic)`。
  理由是两者的错误是**互补**的（词法漏改写、语义漏精确串），而**加权和会让两个弱信号「互相补」成一个
  看起来很强的分数**（各 0.4 相加成 0.8，但两路其实都没把握）。
- **要加权，前提是两侧都给出可比且校准过的分数**，并且系数必须来自**在真实库上量**（同一查询集、
  同一 top-k，看 recall/precision 曲线），不是拍的。
- **必须继承那条已有的纪律：语义信号同样「只抬不压」**。现在的词法增益是乘性的
  （`1 + RELEVANCE_BOOST × relevance`），因为身份型记忆（「我是素食者」）本来就不该与查询有词汇重叠——
  用相关性去**淘汰**，第一批被淘汰的正是最该留住的那批（`MemoryRelevance.kt` 文件头第 2 条）。
  **换成语义也一样**：它不能变成闸门。
- **让「是哪一侧命中」可观测**：混合检索的回执里带一个 `via` 字段（`lexical` / `semantic` / `both` / `none`）。
  这样「该调哪个」是可查的而不是猜的；同时它也是**降级诚实性**的机制——provider 没有 embedding 模型时
  静默退回词法，回执上必须看得见。

**④ 立项的触发条件（可证伪，别写「召回更好」）**

> 构造一个含**跨语言与同义改写**用例的查询集；在同一真实库上比较「词法 top-k」与「语义 top-k」的
> recall@5；**若词法 recall ≤ X 且语义 recall ≥ Y（X/Y 在看数前先定死）**，则立项。
> 在跑到这一步之前，**先做一件更便宜的事：量词法在当前库上的 recall**——它同时回答「现在的 relevance
> 维度到底有没有用」（V2 上线后 `read` 实测 0 次，说明这条链路的**使用**证据为零，
> 但那是「模型不去用」，与「判据准不准」是两件事）。

**⑤ 一句话**：语义与字面的叠加**本身不是问题**（互补信号，值得做）；问题在**放在哪一层**与**拿什么当系数**。
按上面 ②，答案是把语义放进**按需检索**（那里代价有界、可降级、可观测），而不是放进每轮注入；
按 ③，第一步用 `max` 而不是加权和。

#### D. 连续思维：传输层已落地，缺的是「工作记忆」

**先破除「我们缺」的错觉。** 传输层不只落地，还**真机修过**（`440a4c037` 思考模式回传思维链 /
`5e933572c` Responses 400 第二半 / `ded548d93` 真机复验 400→200）。逐 provider 矩阵：

| 路径 | 历史推理是否回传 | 取证 |
|---|---|---|
| OpenAI Chat Completions | ✅ `reasoning_content`；无推理时**填空串**兜底；开关 = 提供商级 `includeHistoryReasoning`（**默认 true**，有 UI） | `ChatCompletionsAPI.kt:663-688`、`ProviderSetting.kt:240`、`ProviderConfigure.kt:304-310` |
| OpenAI Responses | ✅ 每条 Reasoning 回传一个 reasoning item；有 `encrypted_content` 时**不回放明文** | `ResponseAPI.kt:361-376, 496-530` |
| Claude | ✅ 但**只回传带签名的块**（无签名会被 Anthropic 以 Invalid signature 拒整请求）；`redacted_thinking` 原样 | `ClaudeProvider.kt:519-531` |
| Gemini | ❌ **不文本回传**（`toGooglePart()` 无 Reasoning 分支）；只随 Tool/Image 带 `thoughtSignature` | `GoogleProvider.kt:732-772` |
| 工具循环内（step N → N+1） | ✅ 同一条 assistant 消息被复用、工具结果**就地补进同一条消息**，故推理 part 保留 | `GenerationHandler.kt:337, 381, 822-829` |

⚠️ Gemini 那一格**未必是缺陷**（可能本就无「回放思考文本」的契约）；**未核 Google 文档**，标为未核实。

**已知边界（记账，不是待办）**：OpenAI 兼容路径每个内容组**只回传第一个** Reasoning part
（`ChatCompletionsAPI.kt:595 firstOrNull()`）；**推理不进 token 统计**（`ContextLength.kt:80-91` 只算 Text 与
Tool 输出文本）⇒ 占用条与自动压缩触发都看不见推理；**压缩摘要不含推理**（`Message.kt:196-204` 的
`summaryAsText` 只取 Text）⇒ 被压掉的最早几轮**推理先于正文消失**，而「计划」通常正在那里；
`ThinkingDegenerationGuard` **刻意没有用户档位**（`ThinkingDegenerationGuard.kt:30-33`）。

**真缺口（唯一）：计划沉底。**

- `todo_write` 设计上**不落库**（`TodoTools.kt:35-41`：「最新一次调用的参数就是当前清单」，随消息历史持久化）；
- 而 `latestTodoPlan` 的**全部两个读取点**是规则引擎（`GenerationHandler.kt:371`）与聊天页 UI（`ChatPage.kt:417`）
  ——**没有第三个**；
- 即：**用户看得见计划，模型看不见**。计划写完后只以「很久以前的一次工具调用」留在上下文里，位置随轮数衰减、
  被压缩时按「只取正文」规则**整段消失**；
- 这与规则 3（`plan_missing`）正好构成半截：规则只会催「你该写计划」，**写完就没人再提**。

**反证（为什么不做 bigeye 的 `thought_chain`）**：它只有 154 行，本质是「每任务一条只追加 jsonl + 热层 5 条注入」，
其存在理由是**任务跨会话、上下文装不下整个 DAG**；本仓的「任务」就是一轮对话，轨迹本来就在 history 里。
让模型自己写「思维步」（`append_thought_step`）在本仓是与 history 冗余的 **token 税**。
真正需要外部化的是**计划**（它是有状态的、且已经存在），不是思维日志。

#### E. 记忆清单层判据度量（2026-10-16，真机 50 条库）

**问题**：清单层（L3）该按什么判据选？现行是 `sortKey` 升序（`selectMemoriesForIndex`，理由写的是「分数会漂移 ⇒
前缀抖动」，而**该理由随尾部注入已作废**——见 `design-memory-injection-tiering.md` §3.2）。

**方法与样本**：主助手 `0950e2dc` 的 **50 条 / 55,152 字符 / 58,569 cl100k token**；查询 = 每个活跃会话的
「最后一条真实用户消息」（即生产 `memoryQueryOf` 的取值），共 **4 条**。`read_count` 全 0、`description` 非空 **0/50**、
`is_anchor` **0/50**、`importance` 仅 {0.55, 0.6（默认）, 0.78} 三档。

**E1 现状**（全文 6000 + 清单 2000 token）：全文 5–7 条、清单 20 条、**不可见 23–25 条**、**库覆盖 50–54%**。

**E2 预算才是主导项**（以最新会话为查询）：

| 注入预算 | 全文 | 清单 | **不可见** |
|---|---|---|---|
| 4000 | 5 | 9 | 36 |
| 6000 | 3 | 14 | 33 |
| **8000（现生产）** | 7 | 20 | **23** |
| 12000 | 5 | 32 | 13 |
| 16000 | 7 | 42 | **1** |
| 24000 | 11 | 39 | **0** |

全库 58.6K token ⇒「全都在场」要每轮付 24K+，**不可行**。

**E3 判据对比（本节要回答的问题）**：

| 清单层判据 | 平均可见 | 不可见者均 imp | 清单内相关条/会话 |
|---|---|---|---|
| `sortkey_asc`（现行） | **26.0** | 0.600 | 1.0 |
| `sortkey_desc` | 26.5 | 0.600 | 3.0 |
| `relevance` | 25.5 | 0.600 | 4.5 |
| `score` | 25.8 | 0.598 | 1.0 |
| `importance` | 25.8 | 0.598 | 1.0 |

**覆盖度在五种判据下几乎不变（25.5–26.5 / 50）** —— 改判据只决定「看见哪 26 条」，**不改变看见多少**。
唯一会动的是「清单里与本轮相关的条数」，而逐查询拆开后它是**一条 12-token 查询撑起来的**：
两条 4-token 查询（「继续」「先到这里」）上判据差 ≤1；37-token 那条两者都是 0（被 `QUERY_TOKEN_CAP=24` 稀释）。
**~~真实用法里的查询常常是没有主题内容的续话碎片~~ ⇒ 这既削弱了「清单层换判据」的理由，也说明刚落地的
`relevance` 维度（设计上只抬不压）在真实用法下经常空转，但无害。**

> **⚠️ 2026-10-16 更正（用户指出，本条的因果被推翻）**：上面那句**撤回**。
> 用户在装机核验时说明：**那 4 个「最近会话」基本是在测试功能，记忆库还没有真实使用记录的积累**。
> 于是「查询是续话碎片」不是**真实用法**的性质，而是**测试期**的性质——把测试语料当成
> 「用户平时怎么说话」是**样本代表性错误**，我犯的就是这个。
>
> **仍然成立**的（不依赖查询文本）：①覆盖度由预算决定、与五种判据几乎无关（25.5–26.5 / 50）；
> ②现行 `sortKey` 升序的方向缺陷（最新记忆最先掉出）；③`description` 全空 → 清单行退化成内容截断；
> ④`read` 自 V2 上线后 0 次。
>
> **随之失效/降级的**：①「清单层换判据的理由被削弱」——**不再成立**，理由本来就只是「旧理由（前缀抖动）
> 已作废」，与查询长短无关；②「`relevance` 维度在真实用法下经常空转」——**无证据**，
> 应当说「**样本不足以判断**」；③E3 里 `relevance` 判据「被一条 12-token 查询撑起来」这一观察
> 仍然是**这批样本内的事实**，但不能外推成「真实查询都是这样」。
>
> **教训（值得进 handoff）**：度量之前先问一句「**这批数据是在什么状态下产生的**」。
> 一台正在被开发/测试的设备，它的会话语料衡量的是**开发者的测试习惯**，不是产品的用法分布。
> 正确做法是把结论写成「**样本内**成立」并标明数据产生条件，而不是直接写成「真实用法里常常…」。

**E4 现行判据的真实缺陷在「方向」**：升序取 ⇒ 清单层固定展示**最老的 20 条**，不可见集是「除全文层外较新的全部」
—— 库中最新一条（2026-09-23，`sort=84`）**不可见**，而 6 月的记忆在展示。不可见者平均 importance **正好 0.600（默认值）**。

**E5 读取路径实测（含一次自我更正）**：先只看 `read_count`（50/50 全 0）我写下「模型从未读过」——**这条被推翻**。
查历史：`memory_tool` 共 **237 次调用 / 72 个会话**，`edit` 126、`create` 97、`delete` 8、**`read` 4**。
而那 4 次全在 **2026-06/07**，且**全部失败**：

```
{"error":"...unknown action: read, must be one of [create, edit, ...]"}
```

即**模型在没有这个动作时就自发想读记忆体**（潜伏需求真实存在）；V2 上线（10-09）后到今天的 4 个会话里 **0 次**。
样本太小，不足以说「清单层没用」，也不足以说「有用」。

**E6 度量结论**

1. **判据不是杠杆**：覆盖度由预算决定、与顺序几乎无关 ⇒ 换判据买不到可见性；
2. 现行判据该改的是**方向**（升序 ⇒ 最新记忆最先掉出去），但只值 +0.5 条覆盖度；
3. 主导项是**预算**（16K ⇒ 只剩 1 条不可见），而全库 24K+ 不可常付 ⇒ 正解是**按需取回**；
4. 而「按需取回」恰是模型 6 月就自发尝试、当时被拒的那条路径——**形状对、入口弱**：现在的入口是 20 行纯文本清单，
   且 50 条记忆的 `description` **全为空** ⇒ 清单行退化成 content 前 80 字（`MEMORY_INDEX_FALLBACK_CHARS`）。

**E7 顺带发现（本仓自身，不在 bigeye 范围内；本节未动代码，仅供排期）**

- **锚点不免疫 200 条淘汰**：`enforceMaxCount` 只按 `memoryScoreBucket` 排序取淘汰者，**不过滤 `isAnchor`**
  （`MemoryRepository.kt:267-279`）⇒ 用户显式固化的记忆在满 200 条时**可能被静默删掉**；
- **注入预算是硬上限，不是「不会消失」的保证**：这是既有设计的已知代价（清单层就是为此而生），记账即可，别当回归；
- `MAX_MEMORY_COUNT` 的**常量注释已过期**（写「删除最旧记录」，而 `enforceMaxCount` kdoc 写明「用分数（而非旧的
  『最旧』）」——`MemoryRepository.kt:18-19` vs `:262-266`）；
- `MemoryEntity` **没有任何索引**（Room 导出 schema 38 确认），而「记忆多」是已知 ANR 面
  （`design-memory-consolidation.md:412-450` 明确记为**未结案**）。

#### F. 建议与不做清单

| 优先级 | 项 | 成本 | 理由 |
|---|---|---|---|
| **P1** | `memory_tool` 加**检索动作**（内容+主题匹配，FTS 或 LIKE） | ~半天 | 直接让不可见的 23 条**可按需取回**；E6 指出这才是杠杆；模型 6 月已自发要过这个能力 |
| **P1** | **计划常驻尾部块**（复用 `latestTodoPlan` + `PromptAssembly` 尾部槽） | ~半天 | D 的唯一真缺口；闭环规则 3 |
| P2 | 整理建议**落库 + 二次确认应用** | ~1 天 | 「删了没有账」（B 的额外记账） |
| P2 | 存量历史整理（允许整理 `memoryConsolidationSince` 之前/全部积压） | 待评估 | 现有水位只覆盖写入之后的轮次 |
| P3 | 锚点免疫 200 条淘汰 | ~10 分钟 | E7 的真缺陷，一行过滤 |
| P3 | 整理批次成本记账 | ~1h | §11.1 D④，唯一未被 ①②③ 覆盖的一条 |
| P3 | 清单层改判据方向（新→老） | ~10 分钟 | 只值 +0.5 覆盖度，但修正一个**已失效的理由** |
| — | 反思回路 / 记忆网络本体（图·向量）/ DAG·思维链工具 / 自动 importance pin | — | **不做**，理由见 B/C/D |

#### G. 诚实边界

- 上述「收益」除 E 的数字外**均为推断**；`plan_sticky` 与「检索动作」的收益**尚未实测**。
- **E 的数据产生条件（2026-10-16 补记，用户指出）**：那台设备**正在被开发与测试**，4 个「最近会话」
  基本是功能测试，**记忆库还没有真实使用记录的积累** ⇒ E 的数字是「**样本内**成立」，
  既不能代表真实用法分布，也不能反过来推出「真实查询很短」。
  样本量：**4 条查询 / 1 个助手 / 4 个会话**（V2 上线至今）⇒ 任何关于「用户平时怎么提问」的结论都不该由此得出。
- E 的 token 用 `cl100k`；生产用 classpath 自带的 tokenizer（应为同族，**未逐字节比对**）。
- `updated_at` 多为 2026-09-15 批量写入 ⇒ **age 在本库里区分度低**，E4 的「新/老」以 `sortKey` 为准而非时间。
- 本节的 bigeye 行为均来自**静态阅读**，未运行其程序。

### 11.5 增量评估：10-18 → 10-24（v1.5 / v1.6，2026-10-26）

5 个提交（`5bf21b7` 工具产出/见图片/侧栏、`3c71a2e` 切任务落点+fe-manifest/cost、`734f831` 热点雷达、`32a774a` 上下文裁剪+凭据引导、`7464a19` 本地 TTS）。**总评：无代码吸收项，判据级记账 2 条**。

**① tool_output 落盘/翻页 3 条实证判据（`5bf21b7` `tools/tool_output.py`）→ 补进 `design-tool-output-budget.md`**（fork C4/D3 句柄概念已有，这是落地层的失败模式证据）：
- 读取器自身返回须低于落盘阈值，防「二次落盘套娃」（tool_output.py:21-25，3200<4000 留 800 余量）；
- 游标按「实际完整交付行数」推进而非请求 limit（:119-126）；
- 超长单行至少交付开头（:44-49）。

**② loop_detector 自指死循环修复（`5bf21b7` `loop_detector.py:6-16,66-79`）——将来做运行时循环提醒的前置护栏**：提醒被模型当作用户指令去「调查检测器」→ 自指关键词静默 + 每话题 MAX_INJECTS=2 硬熔断 + 「先落地才许再注入」。fork 现为静态规则提醒无注入面，立项时再取。

**已有等价**：`32a774a` 裁剪本体（token 预算内按对话边界切+钉住最近 6 个整理摘要——修「摘要下一轮被自己裁掉」，fork 检查点合成模型不删消息无此根因）；`_ORGANIZE_MAX_ITEMS=200` 整理区间闸（与 multi-pass 分块无 token 上限同题已修）；system 静/动拆分（上游 #1542/#1630 同族已收录，bigeye 仅多两个量化细节：单字节变化命中率 99.6%→26.6%、动态段只追加一次防误判插话）；`3c71a2e` /api/cost 口径（fork usage 锚点已落地）；`7464a19` TTS（前端朗读 API+自动播放，与 fork 同形态，引擎层全 Windows 专属，Android 系统 TTS 即零模型本地）。

**不适用**：`5bf21b7` see_image（磁盘图自看，聊天客户端无工作区磁盘图场景）、shell_router（Windows Git Bash/cmd）、write_ledger（多进程文件锁，Android 单进程+Room）；`3c71a2e` fe-manifest/localStorage 键前缀（桌面打包面）；`734f831` 热点雷达/跨天差分（bigeye 特有产品面）、msg_vectors 词面绕向量门槛（§11.1 已有结论）；`7464a19` 消息反馈落库/付费引擎优先（产品选择）。
